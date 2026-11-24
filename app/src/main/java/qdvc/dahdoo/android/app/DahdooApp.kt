package qdvc.dahdoo.android.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import qdvc.dahdoo.android.app.data.FolderState
import qdvc.dahdoo.android.app.data.Messages
import qdvc.dahdoo.android.app.data.RecordingRepository
import qdvc.dahdoo.android.app.data.SettingsRepository
import qdvc.dahdoo.android.app.record.RecordingController
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Deliberately does nothing in onCreate(): every millisecond here delays the
 * first frame and, with it, the record button. The container is built lazily on
 * first use, and its background work starts only once the UI is up.
 */
class DahdooApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

/** Process-wide singletons. Outlives Activities, so recordings survive rotation. */
class AppContainer(app: Application) {
    /** IO-bound background work that must outlive any one screen. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val messages = Messages()
    val settings = SettingsRepository(app, scope, messages)

    private val pendingDir = File(app.filesDir, RecordingRepository.PENDING_DIR)

    // The recorder and repository refer to each other only through lambdas, so
    // neither needs the other at construction time.
    val recorder: RecordingController = RecordingController(
        context = app,
        pendingDir = { pendingDir },
        isNameTaken = { base -> recordings.hasBase(base) },
    )

    val recordings: RecordingRepository = RecordingRepository(
        context = app,
        scope = scope,
        settingsRepo = settings,
        messages = messages,
        isActiveRecording = { base -> recorder.isActiveBase(base) },
    )

    init {
        recorder.onFinished = { finished -> recordings.addFinished(finished) }
        recorder.onMessage = { text -> messages.post(text) }
    }

    private val started = AtomicBoolean(false)
    private val syncing = AtomicBoolean(false)

    /** Kicks off the background load once; safe to call repeatedly. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            recordings.loadLocal()
            sync()
        }
    }

    /** Re-checks the folder if it was unreachable (e.g. an SD card reinserted). */
    fun retryIfUnavailable() {
        val f = settings.folder.value as? FolderState.Chosen ?: return
        if (f.status != FolderState.Status.UNAVAILABLE) return
        scope.launch { sync() }
    }

    private suspend fun sync() {
        if (!syncing.compareAndSet(false, true)) return
        try {
            settings.syncWithFolder()
            recordings.refresh()
        } finally {
            syncing.set(false)
        }
    }
}
