package qdvc.dahdoo.android.app.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import qdvc.dahdoo.android.app.model.FinishedRecording
import qdvc.dahdoo.android.app.model.Recording
import qdvc.dahdoo.android.app.model.RecordingLocation
import qdvc.dahdoo.android.app.model.RecordingMeta
import qdvc.dahdoo.android.app.util.Names
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/** What the player should open. */
sealed interface AudioSource {
    data class LocalFile(val file: File) : AudioSource
    data class Document(val uri: Uri) : AudioSource
}

/**
 * The catalogue of recordings: what is in the user's folder, plus recordings
 * still waiting in app-private storage to be copied there.
 *
 * Follows the spec's "index as a search cache" pattern (B10, Pattern 1), minus
 * Room: the folder is the system of record, and a small JSON cache of what it
 * held last time is shown instantly at launch while a cheap reconcile runs in
 * the background (one directory listing; sidecar bodies re-read only when their
 * last-modified time changed).
 *
 * Saving never waits on the folder. A finished recording lands in
 * `filesDir/pending/` first (audio plus sidecar), appears in the list at once,
 * and is then copied into the folder. If there is no folder yet, or it is
 * unreachable, it stays pending -- safe on the phone -- and is retried at the
 * next launch or when a folder is chosen.
 */
class RecordingRepository(
    context: Context,
    private val scope: CoroutineScope,
    private val settingsRepo: SettingsRepository,
    private val messages: Messages,
    /** True for the base name the recorder is writing right now. */
    private val isActiveRecording: (String) -> Boolean,
) {
    private val app = context.applicationContext
    val pendingDir = File(app.filesDir, PENDING_DIR)
    private val cacheFile = File(app.filesDir, CACHE_FILE)

    private val _recordings = MutableStateFlow<List<Recording>>(emptyList())
    val recordings: StateFlow<List<Recording>> = _recordings.asStateFlow()

    /** Base name of the most recently finished recording, for the "Add details" card. */
    private val _lastAdded = MutableStateFlow<String?>(null)
    val lastAdded: StateFlow<String?> = _lastAdded.asStateFlow()

    private data class FolderEntry(val recording: Recording, val sidecarModified: Long)

    /** Document ids, in memory only: never persisted (they leak paths and go stale). */
    private data class FolderDocs(val audio: String, val sidecar: String?)

    private val lock = Mutex()

    // Guarded by [lock].
    private var loaded = false
    private var folderEntries: Map<String, FolderEntry> = emptyMap()
    private var pendingEntries: Map<String, Recording> = emptyMap()

    // Readable without the lock, so playback never waits behind a long copy.
    private val docIds = ConcurrentHashMap<String, FolderDocs>()

    fun hasBase(base: String): Boolean = _recordings.value.any { it.baseName == base }

    /** Shows the cached list and any pending recordings. Cheap; local files only. */
    suspend fun loadLocal() = withContext(Dispatchers.IO) {
        lock.withLock {
            if (loaded) return@withLock
            val fallback = OffsetDateTime.now()
            val cached = runCatching { if (cacheFile.exists()) cacheFile.readText() else null }.getOrNull()
            folderEntries = CacheCodec.decode(cached, fallback).associate { e ->
                e.baseName to FolderEntry(
                    Recording(e.baseName, e.meta, RecordingLocation.InFolder),
                    e.sidecarModified
                )
            }
            pendingEntries = scanPending()
            loaded = true
            publish()
        }
    }

    /** Reconciles with the folder, then copies anything pending into it. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        loadLocal()
        lock.withLock {
            refreshLocked()
            importPendingLocked()
        }
    }

    /** Called (on the recorder's thread) the moment a recording stops. */
    fun addFinished(finished: FinishedRecording) {
        scope.launch(Dispatchers.IO) {
            loadLocal()
            lock.withLock {
                val meta = RecordingMeta(
                    recordedAt = finished.startedAt,
                    durationMs = finished.durationMs,
                    childId = settingsRepo.settings.value.defaultChildId,
                )
                val audioName = Names.audioName(finished.baseName)
                try {
                    writeLocalAtomically(
                        File(pendingDir, Names.sidecarName(finished.baseName)),
                        RecordingCodec.encode(meta, audioName)
                    )
                } catch (e: Exception) {
                    // The audio is safe; without the sidecar the details fall back
                    // to what the file name says. Not worth interrupting anyone.
                }
                pendingEntries = pendingEntries + (finished.baseName to Recording(
                    finished.baseName, meta, RecordingLocation.Pending(finished.audioFile)
                ))
                _lastAdded.value = finished.baseName
                publish()
                importPendingLocked()
            }
        }
    }

    /** Retries copying pending recordings, e.g. after a folder is chosen. */
    suspend fun retryImport() = withContext(Dispatchers.IO) {
        lock.withLock { importPendingLocked() }
    }

    /**
     * Saves catalogue details. Optimistic: the list updates at once, and is rolled
     * back (with a message) if the write fails.
     */
    suspend fun updateMeta(base: String, meta: RecordingMeta): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            val pending = pendingEntries[base]
            val inFolder = folderEntries[base]
            when {
                pending != null -> {
                    pendingEntries = pendingEntries + (base to pending.copy(meta = meta))
                    publish()
                    try {
                        val file = File(pendingDir, Names.sidecarName(base))
                        val previous = if (file.exists()) runCatching { file.readText() }.getOrNull() else null
                        writeLocalAtomically(file, RecordingCodec.encode(meta, Names.audioName(base), previous))
                        true
                    } catch (e: Exception) {
                        pendingEntries = pendingEntries + (base to pending)
                        publish()
                        messages.post("Couldn't save those details.")
                        false
                    }
                }

                inFolder != null -> {
                    val updated = inFolder.recording.copy(meta = meta)
                    folderEntries = folderEntries + (base to inFolder.copy(recording = updated))
                    publish()
                    try {
                        val store = settingsRepo.store()
                            ?: throw IOException("your folder isn't available right now")
                        val ids = resolveDocsLocked(store, base)
                            ?: throw IOException("the recording is no longer in your folder")
                        val previous = ids.sidecar?.let { runCatching { store.readText(it) }.getOrNull() }
                        val text = RecordingCodec.encode(meta, Names.audioName(base), previous)
                        val sidecarId = if (ids.sidecar != null) {
                            store.overwriteText(ids.sidecar, text)
                            ids.sidecar
                        } else {
                            val dirId = store.ensureDir(store.rootDocId, Names.RECORDINGS_DIR)
                            store.createText(dirId, Names.sidecarName(base), text)
                        }
                        docIds[base] = ids.copy(sidecar = sidecarId)
                        val modified = store.info(sidecarId)?.lastModified ?: 0L
                        folderEntries = folderEntries + (base to FolderEntry(updated, modified))
                        saveCache()
                        true
                    } catch (e: Exception) {
                        folderEntries = folderEntries + (base to inFolder)
                        publish()
                        messages.post("Couldn't save those details: ${e.message ?: "unknown error"}.")
                        false
                    }
                }

                else -> false
            }
        }
    }

    /** Deletes the audio and its sidecar. The caller has already confirmed. */
    suspend fun delete(base: String): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            val pending = pendingEntries[base]
            if (pending != null) {
                (pending.location as? RecordingLocation.Pending)?.audioFile?.delete()
                File(pendingDir, Names.sidecarName(base)).delete()
                pendingEntries = pendingEntries - base
                if (_lastAdded.value == base) _lastAdded.value = null
                publish()
                return@withLock true
            }
            if (folderEntries[base] == null) return@withLock false
            try {
                val store = settingsRepo.store() ?: throw IOException("your folder isn't available right now")
                val ids = resolveDocsLocked(store, base)
                if (ids != null) {
                    store.delete(ids.audio)
                    ids.sidecar?.let { runCatching { store.delete(it) } }
                }
                docIds.remove(base)
                folderEntries = folderEntries - base
                if (_lastAdded.value == base) _lastAdded.value = null
                saveCache()
                publish()
                true
            } catch (e: Exception) {
                messages.post("Couldn't delete that recording: ${e.message ?: "unknown error"}.")
                false
            }
        }
    }

    suspend fun audioSource(base: String): AudioSource? = withContext(Dispatchers.IO) {
        val listed = _recordings.value.firstOrNull { it.baseName == base } ?: return@withContext null
        (listed.location as? RecordingLocation.Pending)?.let { return@withContext AudioSource.LocalFile(it.audioFile) }
        val store = settingsRepo.store() ?: return@withContext null
        val known = docIds[base]
        if (known != null) return@withContext AudioSource.Document(store.docUri(known.audio))
        lock.withLock {
            runCatching { resolveDocsLocked(store, base) }.getOrNull()
                ?.let { AudioSource.Document(store.docUri(it.audio)) }
        }
    }

    // ---- internals (call with [lock] held, on Dispatchers.IO) ----

    private fun refreshLocked() {
        val store = settingsRepo.store() ?: return
        try {
            val dir = store.find(store.rootDocId, Names.RECORDINGS_DIR)?.takeIf { it.isDir }
            val listing = if (dir == null) emptyList() else store.list(dir.docId)
            val audio = HashMap<String, DocInfo>()
            val sidecars = HashMap<String, DocInfo>()
            for (doc in listing) {
                if (doc.isDir) continue
                val audioBase = Names.baseOfAudio(doc.name)
                if (audioBase != null) {
                    audio[audioBase] = doc
                } else {
                    Names.baseOfSidecar(doc.name)?.let { sidecars[it] = doc }
                }
            }
            val next = HashMap<String, FolderEntry>(audio.size)
            val nextIds = HashMap<String, FolderDocs>(audio.size)
            for ((base, a) in audio) {
                val sc = sidecars[base]
                val cached = folderEntries[base]
                val fallback = fallbackTime(base, a.lastModified)
                val meta = when {
                    sc == null -> RecordingMeta(fallback, 0L)
                    cached != null && sc.lastModified != 0L && cached.sidecarModified == sc.lastModified ->
                        cached.recording.meta
                    else -> runCatching { RecordingCodec.decode(store.readText(sc.docId), fallback) }.getOrNull()
                        ?: cached?.recording?.meta
                        ?: RecordingMeta(fallback, 0L)
                }
                next[base] = FolderEntry(
                    Recording(base, meta, RecordingLocation.InFolder),
                    sc?.lastModified ?: NO_SIDECAR
                )
                nextIds[base] = FolderDocs(a.docId, sc?.docId)
            }
            folderEntries = next
            docIds.clear()
            docIds.putAll(nextIds)
            saveCache()
            publish()
        } catch (e: Exception) {
            // An unreadable folder is not an empty one: keep showing what we last
            // knew rather than wiping the list.
            settingsRepo.markUnavailable()
        }
    }

    private fun importPendingLocked() {
        if (pendingEntries.isEmpty()) return
        val chosen = settingsRepo.folder.value as? FolderState.Chosen ?: return
        if (chosen.status != FolderState.Status.OK) return
        val store = settingsRepo.store() ?: return
        val dirId = try {
            store.ensureDir(store.rootDocId, Names.RECORDINGS_DIR)
        } catch (e: Exception) {
            messages.post("Couldn't reach your folder. New recordings are safe on this phone for now.")
            return
        }
        var failed = 0
        for (rec in pendingEntries.values.toList()) {
            val loc = rec.location as? RecordingLocation.Pending ?: continue
            val base = rec.baseName
            try {
                val audioName = Names.audioName(base)
                val audioId = store.copyIn(dirId, audioName, loc.audioFile)
                val localSidecar = File(pendingDir, Names.sidecarName(base))
                val previous = if (localSidecar.exists()) runCatching { localSidecar.readText() }.getOrNull() else null
                val text = RecordingCodec.encode(rec.meta, audioName, previous)
                val existing = store.find(dirId, Names.sidecarName(base))
                val sidecarId = if (existing != null) {
                    store.overwriteText(existing.docId, text)
                    existing.docId
                } else {
                    store.createText(dirId, Names.sidecarName(base), text)
                }
                val modified = store.info(sidecarId)?.lastModified ?: 0L
                // Only now, with both files safely in the folder, drop the local copy.
                loc.audioFile.delete()
                localSidecar.delete()
                pendingEntries = pendingEntries - base
                folderEntries = folderEntries + (base to FolderEntry(
                    rec.copy(location = RecordingLocation.InFolder), modified
                ))
                docIds[base] = FolderDocs(audioId, sidecarId)
            } catch (e: Exception) {
                failed++
            }
        }
        saveCache()
        publish()
        if (failed > 0) {
            messages.post(
                if (failed == 1) "1 recording couldn't be copied to your folder yet. It's safe on this phone."
                else "$failed recordings couldn't be copied to your folder yet. They're safe on this phone."
            )
        }
    }

    private fun resolveDocsLocked(store: FolderStore, base: String): FolderDocs? {
        docIds[base]?.let { return it }
        val dir = store.find(store.rootDocId, Names.RECORDINGS_DIR)?.takeIf { it.isDir } ?: return null
        val listing = store.list(dir.docId)
        val audio = listing.firstOrNull { it.name == Names.audioName(base) } ?: return null
        val sidecar = listing.firstOrNull { it.name == Names.sidecarName(base) }
        return FolderDocs(audio.docId, sidecar?.docId).also { docIds[base] = it }
    }

    private fun scanPending(): Map<String, Recording> {
        pendingDir.mkdirs()
        val files = pendingDir.listFiles() ?: return emptyMap()
        val out = HashMap<String, Recording>()
        for (audio in files) {
            val base = Names.baseOfAudio(audio.name) ?: continue
            if (isActiveRecording(base)) continue
            if (audio.length() == 0L) {
                // A recorder that failed to start; nothing to keep.
                audio.delete()
                continue
            }
            val fallback = fallbackTime(base, audio.lastModified())
            val sidecar = File(pendingDir, Names.sidecarName(base))
            val meta = if (sidecar.exists()) {
                RecordingCodec.decode(runCatching { sidecar.readText() }.getOrNull(), fallback)
            } else {
                null
            }
            // No sidecar means the app was killed mid-recording: the sidecar is
            // written when a recording stops. Keep the audio and say so honestly.
            out[base] = Recording(
                base,
                meta ?: RecordingMeta(fallback, 0L, notes = INTERRUPTED_NOTE),
                RecordingLocation.Pending(audio)
            )
        }
        return out
    }

    private fun publish() {
        val all = LinkedHashMap<String, Recording>()
        pendingEntries.forEach { (k, v) -> all[k] = v }
        folderEntries.forEach { (k, v) -> all[k] = v.recording } // Folder copy wins.
        _recordings.value = all.values.sortedByDescending { it.meta.recordedAt.toInstant() }
    }

    private fun saveCache() {
        runCatching {
            writeLocalAtomically(
                cacheFile,
                CacheCodec.encode(folderEntries.map { (base, e) ->
                    CacheCodec.Entry(base, e.sidecarModified, e.recording.meta)
                })
            )
        }
    }

    private fun writeLocalAtomically(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("Could not replace ${target.name}")
        }
    }

    private fun fallbackTime(base: String, lastModifiedMillis: Long): OffsetDateTime {
        val zone = ZoneId.systemDefault()
        return Names.parseBaseName(base)?.atZone(zone)?.toOffsetDateTime()
            ?: Instant.ofEpochMilli(lastModifiedMillis).atZone(zone).toOffsetDateTime()
    }

    companion object {
        const val PENDING_DIR = "pending"
        private const val CACHE_FILE = "recordings-cache.json"
        private const val NO_SIDECAR = -1L
        const val INTERRUPTED_NOTE =
            "This recording was interrupted (the app was closed while recording), so the file may not play."
    }
}
