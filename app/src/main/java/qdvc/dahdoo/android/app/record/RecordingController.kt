package qdvc.dahdoo.android.app.record

import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import qdvc.dahdoo.android.app.model.FinishedRecording
import qdvc.dahdoo.android.app.util.Names
import java.io.File
import java.time.OffsetDateTime
import kotlin.math.sqrt

sealed interface RecorderState {
    data object Idle : RecorderState

    /** Tapped; the recorder is being prepared. Lasts a fraction of a second. */
    data object Starting : RecorderState

    data class Recording(
        val baseName: String,
        /** `SystemClock.elapsedRealtime()` when capture began; immune to clock changes. */
        val startedAtElapsed: Long,
        /** 0 means no limit. */
        val maxDurationMs: Long,
    ) : RecorderState
}

/**
 * Owns the one [MediaRecorder]. Lives for the whole process (not the Activity),
 * so a recording survives rotation, and the foreground service only has to keep
 * the process alive rather than own the recorder.
 *
 * Every MediaRecorder call happens on one dedicated thread: prepare() and start()
 * take tens to hundreds of milliseconds, which would otherwise drop frames from
 * the button's animation at exactly the moment the user is watching it, and
 * MediaRecorder is not documented as thread-safe. Its listeners are delivered on
 * the thread that created it, which is this one.
 *
 * Recording goes to app-private storage, never straight to the user's folder: a
 * local file opens instantly, needs no SAF round trip, and works before a folder
 * has been chosen. The repository copies it into the folder afterwards.
 */
class RecordingController(
    context: Context,
    private val pendingDir: () -> File,
    /** True if a base name is already used by a recording the app knows about. */
    private val isNameTaken: (String) -> Boolean,
) {
    private val app = context.applicationContext
    private val thread = HandlerThread("dahdoo-recorder").apply { start() }
    private val handler = Handler(thread.looper)

    private val _state = MutableStateFlow<RecorderState>(RecorderState.Idle)
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    /** 0..1, roughly perceptual loudness, for the button's halo. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    var onFinished: ((FinishedRecording) -> Unit)? = null
    var onMessage: ((String) -> Unit)? = null

    private class Active(
        val recorder: MediaRecorder,
        val baseName: String,
        val file: File,
        val startedAt: OffsetDateTime,
        val startedAtElapsed: Long,
        val maxDurationMs: Long,
    )

    // Touched only on [thread].
    private var active: Active? = null

    @Volatile
    private var activeBase: String? = null

    fun isActiveBase(base: String): Boolean = activeBase == base

    fun start(maxMinutes: Int) {
        if (_state.value != RecorderState.Idle) return
        _state.value = RecorderState.Starting
        handler.post { startOnThread(maxMinutes) }
    }

    fun stop() {
        handler.post { stopOnThread(autoStopped = false) }
    }

    private fun startOnThread(maxMinutes: Int) {
        if (active != null) return
        val startedAt = OffsetDateTime.now()
        val dir = pendingDir().apply { mkdirs() }
        val base = Names.baseNameFor(startedAt.toLocalDateTime()) { candidate ->
            File(dir, Names.audioName(candidate)).exists() || isNameTaken(candidate)
        }
        val file = File(dir, Names.audioName(base))
        val maxMs = if (maxMinutes > 0) maxMinutes * 60_000L else 0L

        val recorder = newRecorder()
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            // AAC in an MPEG-4 container (.m4a): plays everywhere, small, and shares
            // cleanly to messaging apps. Trade-off: the index is written at stop(),
            // so a recording cut off by the process dying may not play.
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioChannels(1)
            recorder.setAudioSamplingRate(SAMPLE_RATE)
            recorder.setAudioEncodingBitRate(BIT_RATE)
            if (maxMs > 0) recorder.setMaxDuration(maxMs.toInt())
            recorder.setOutputFile(file.absolutePath)
            recorder.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    stopOnThread(autoStopped = true)
                }
            }
            recorder.setOnErrorListener { _, _, _ -> stopOnThread(autoStopped = true) }
            recorder.prepare()
            recorder.start()
        } catch (e: Exception) {
            runCatching { recorder.release() }
            file.delete()
            _state.value = RecorderState.Idle
            onMessage?.invoke("Couldn't start recording. Is another app using the microphone?")
            return
        }

        val now = SystemClock.elapsedRealtime()
        active = Active(recorder, base, file, startedAt, now, maxMs)
        activeBase = base
        _state.value = RecorderState.Recording(base, now, maxMs)
        startService()
        handler.postDelayed(levelPoll, LEVEL_POLL_MS)
    }

    private fun stopOnThread(autoStopped: Boolean) {
        val a = active ?: return
        active = null
        handler.removeCallbacks(levelPoll)
        val elapsed = SystemClock.elapsedRealtime() - a.startedAtElapsed

        var usable = true
        try {
            a.recorder.stop()
        } catch (e: RuntimeException) {
            // stop() throws if no audio was captured (a tap-tap too quick to hold
            // any data). After an automatic stop at the time limit, the platform may
            // already have finalised the file, so keep it if it has content.
            usable = autoStopped && a.file.length() > 0
        }
        runCatching { a.recorder.release() }
        activeBase = null
        _level.value = 0f
        _state.value = RecorderState.Idle
        stopService()

        if (!usable) {
            a.file.delete()
            onMessage?.invoke("That was too short to save.")
            return
        }
        val duration = if (a.maxDurationMs > 0) elapsed.coerceAtMost(a.maxDurationMs) else elapsed
        if (autoStopped && a.maxDurationMs > 0 && elapsed >= a.maxDurationMs) {
            onMessage?.invoke("Stopped at the time limit and saved.")
        }
        onFinished?.invoke(FinishedRecording(a.baseName, a.file, a.startedAt, duration))
    }

    private val levelPoll = object : Runnable {
        override fun run() {
            val a = active ?: return
            val amp = try {
                a.recorder.maxAmplitude
            } catch (e: Exception) {
                0
            }
            // sqrt makes quiet babbles visible without loud ones pinning the halo.
            _level.value = sqrt((amp / MAX_AMPLITUDE).coerceIn(0f, 1f))
            handler.postDelayed(this, LEVEL_POLL_MS)
        }
    }

    private fun startService() {
        try {
            ContextCompat.startForegroundService(app, Intent(app, RecorderService::class.java))
        } catch (e: Exception) {
            // E.g. ForegroundServiceStartNotAllowedException if the app was somehow
            // not in the foreground. Recording carries on while the app is visible.
        }
    }

    private fun stopService() {
        runCatching { app.stopService(Intent(app, RecorderService::class.java)) }
    }

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(app) else MediaRecorder()

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val BIT_RATE = 128_000
        const val LEVEL_POLL_MS = 60L
        const val MAX_AMPLITUDE = 32767f
    }
}
