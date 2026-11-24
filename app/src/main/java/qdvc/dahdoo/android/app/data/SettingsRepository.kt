package qdvc.dahdoo.android.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import qdvc.dahdoo.android.app.model.AppSettings
import qdvc.dahdoo.android.app.util.Names

/** Where the user's folder stands. */
sealed interface FolderState {
    data object NotChosen : FolderState

    data class Chosen(val uri: Uri, val name: String?, val status: Status) : FolderState

    enum class Status { CHECKING, OK, UNAVAILABLE }
}

/**
 * Owns the settings and the pointer to the user's folder.
 *
 * The folder's `dahdoo-settings.json` is the system of record. A copy is kept in
 * app-private SharedPreferences purely so the first frame can be drawn (and
 * themed) without waiting on SAF IPC; the folder is reconciled in the
 * background. The folder URI itself necessarily lives only in private storage.
 *
 * Edits apply at once and are written straight to the folder. If that write
 * fails -- or there is no folder yet -- the edit is kept locally, marked dirty,
 * the user is told, and it is written on the next successful sync. A failed
 * write is never silent and never loses the user's change.
 */
class SettingsRepository(
    context: Context,
    private val scope: CoroutineScope,
    private val messages: Messages,
) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver

    // Reading SharedPreferences is a small synchronous file read on first access.
    // It is the only storage touched before the first frame.
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(
        SettingsCodec.decode(prefs.getString(KEY_SETTINGS, null)) ?: AppSettings()
    )
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _folder = MutableStateFlow(initialFolderState())
    val folder: StateFlow<FolderState> = _folder.asStateFlow()

    private val writeLock = Mutex()

    private fun initialFolderState(): FolderState {
        val uri = prefs.getString(KEY_FOLDER_URI, null)?.let(Uri::parse) ?: return FolderState.NotChosen
        return FolderState.Chosen(uri, prefs.getString(KEY_FOLDER_NAME, null), FolderState.Status.CHECKING)
    }

    /** A store for the folder if it is chosen and was reachable at last check. */
    fun store(): FolderStore? {
        val f = _folder.value as? FolderState.Chosen ?: return null
        if (f.status == FolderState.Status.UNAVAILABLE) return null
        return FolderStore(resolver, f.uri)
    }

    fun markUnavailable() {
        val f = _folder.value as? FolderState.Chosen ?: return
        _folder.value = f.copy(status = FolderState.Status.UNAVAILABLE)
    }

    /**
     * Background reconcile at launch (and after choosing a folder): checks the
     * folder is still reachable, then either pushes unsynced local edits to it or
     * adopts what it holds. Never blocks the UI.
     */
    suspend fun syncWithFolder(adopting: Boolean = false) = writeLock.withLock {
        val chosen = _folder.value as? FolderState.Chosen ?: return@withLock
        val store = FolderStore(resolver, chosen.uri)
        _folder.value = chosen.copy(status = FolderState.Status.CHECKING)
        try {
            val name = store.displayName() ?: chosen.name
            val existing = store.find(store.rootDocId, Names.SETTINGS_FILE)
            val folderText = existing?.let { store.readText(it.docId) }
            val folderSettings = SettingsCodec.decode(folderText)
            val local = _settings.value
            val dirty = prefs.getBoolean(KEY_DIRTY, false)

            val result = when {
                // Nothing (readable) there yet: the folder gets our settings.
                folderSettings == null -> local
                // A folder just chosen that already holds settings (a restored
                // backup, a second phone): adopt them, keeping any children only
                // this phone knows about.
                adopting -> mergeChildren(primary = folderSettings, secondary = local)
                // Edits made while the folder was unreachable win, but children
                // only the folder knows about are kept.
                dirty -> mergeChildren(primary = local, secondary = folderSettings)
                else -> folderSettings
            }
            val needsWrite = folderSettings == null || dirty || result != folderSettings
            if (needsWrite) {
                val text = SettingsCodec.encode(result, folderText)
                if (existing != null) store.overwriteText(existing.docId, text)
                else store.createText(store.rootDocId, Names.SETTINGS_FILE, text)
            }
            if (store.find(store.rootDocId, Names.README_FILE) == null) {
                runCatching { store.createText(store.rootDocId, Names.README_FILE, FOLDER_README) }
            }
            _settings.value = result
            cacheLocally(result, dirty = false)
            prefs.edit().putString(KEY_FOLDER_NAME, name).apply()
            _folder.value = chosen.copy(name = name, status = FolderState.Status.OK)
        } catch (e: Exception) {
            _folder.value = chosen.copy(status = FolderState.Status.UNAVAILABLE)
        }
    }

    /**
     * Points the app at a newly granted folder. If it already holds Dahdoo
     * settings (a restored backup, or a second phone), they are adopted, with any
     * children only this phone knows about merged in.
     */
    suspend fun chooseFolder(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            resolver.takePersistableUriPermission(uri, flags)
        } catch (e: SecurityException) {
            messages.post("That folder can't be kept. Please choose another.")
            return
        }
        val previous = (_folder.value as? FolderState.Chosen)?.uri
        if (previous != null && previous != uri) {
            runCatching { resolver.releasePersistableUriPermission(previous, flags) }
        }
        prefs.edit()
            .putString(KEY_FOLDER_URI, uri.toString())
            .remove(KEY_FOLDER_NAME)
            .apply()
        _folder.value = FolderState.Chosen(uri, null, FolderState.Status.CHECKING)
        syncWithFolder(adopting = true)
        if ((_folder.value as? FolderState.Chosen)?.status != FolderState.Status.OK) {
            messages.post("Couldn't open that folder. Please choose another.")
        }
    }

    /** Applies an edit immediately, then writes it to the folder in the background. */
    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        if (next == _settings.value) return
        _settings.value = next
        cacheLocally(next, dirty = true)
        scope.launch { writeToFolder() }
    }

    private suspend fun writeToFolder() = writeLock.withLock {
        val store = store() ?: return@withLock // Kept dirty; written on next sync.
        val current = _settings.value
        try {
            val existing = store.find(store.rootDocId, Names.SETTINGS_FILE)
            val previous = existing?.let { runCatching { store.readText(it.docId) }.getOrNull() }
            val text = SettingsCodec.encode(current, previous)
            if (existing != null) store.overwriteText(existing.docId, text)
            else store.createText(store.rootDocId, Names.SETTINGS_FILE, text)
            // Only clear the flag if nothing changed while we were writing.
            if (_settings.value == current) prefs.edit().putBoolean(KEY_DIRTY, false).apply()
        } catch (e: Exception) {
            messages.post("Couldn't save settings to your folder. They're kept on this phone and will be saved there later.")
        }
    }

    private fun cacheLocally(settings: AppSettings, dirty: Boolean) {
        prefs.edit()
            .putString(KEY_SETTINGS, SettingsCodec.encode(settings))
            .putBoolean(KEY_DIRTY, dirty)
            .apply()
    }

    private fun mergeChildren(primary: AppSettings, secondary: AppSettings): AppSettings {
        val known = primary.children.map { it.id }.toSet()
        val extra = secondary.children.filter { it.id !in known }
        return if (extra.isEmpty()) primary else primary.copy(children = primary.children + extra)
    }

    private companion object {
        const val PREFS = "local"
        const val KEY_SETTINGS = "settings_json"
        const val KEY_DIRTY = "settings_dirty"
        const val KEY_FOLDER_URI = "folder_uri"
        const val KEY_FOLDER_NAME = "folder_name"

        val FOLDER_README = """
            This folder belongs to Dahdoo, a baby-babble recorder (part of the QDVC suite).

            dahdoo-settings.json   Your children (names, birthdays) and app preferences.
            recordings/            One .m4a audio file per recording, plus a .json file of
                                   the same name holding its catalogue details (which child,
                                   what they were doing, what they seemed to be saying).

            File names are the date and time of recording and never change. Everything is
            plain files, so it is safe to mirror this folder to a backup drive. To restore,
            point Dahdoo at a copy of this folder.
        """.trimIndent() + "\n"
    }
}
