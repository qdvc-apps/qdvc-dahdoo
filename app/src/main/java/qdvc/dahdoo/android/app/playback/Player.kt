package qdvc.dahdoo.android.app.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import qdvc.dahdoo.android.app.data.AudioSource

data class PlaybackState(
    val baseName: String? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
)

/**
 * One MediaPlayer for the whole app, driven from the main thread. Only one
 * recording plays at a time; starting another stops the first.
 */
class Player(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private val audioManager = app.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause()
        }
        .build()

    private var player: MediaPlayer? = null
    private var prepared = false
    private var ticker: Job? = null

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    var onError: ((String) -> Unit)? = null

    /** Play/pause for [baseName]; resolves its audio only when starting fresh. */
    fun toggle(baseName: String, resolve: suspend () -> AudioSource?) {
        val current = _state.value
        val p = player
        if (p != null && current.baseName == baseName) {
            if (current.playing) pause() else resume()
            return
        }
        scope.launch {
            val source = resolve()
            if (source == null) {
                onError?.invoke("That recording can't be found.")
                return@launch
            }
            play(baseName, source)
        }
    }

    fun seekTo(positionMs: Long) {
        val p = player ?: return
        runCatching { p.seekTo(positionMs.toInt()) }
        _state.value = _state.value.copy(positionMs = positionMs)
    }

    fun stop() {
        ticker?.cancel()
        player?.let { runCatching { it.release() } }
        player = null
        prepared = false
        audioManager?.abandonAudioFocusRequest(focusRequest)
        _state.value = PlaybackState()
    }

    private fun play(baseName: String, source: AudioSource) {
        stop()
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(attributes)
            when (source) {
                is AudioSource.LocalFile -> p.setDataSource(source.file.absolutePath)
                is AudioSource.Document -> p.setDataSource(app, source.uri)
            }
            p.setOnPreparedListener { mp ->
                if (player !== mp) return@setOnPreparedListener
                prepared = true
                audioManager?.requestAudioFocus(focusRequest)
                mp.start()
                _state.value = PlaybackState(baseName, true, 0L, mp.duration.toLong().coerceAtLeast(0L))
                startTicker()
            }
            p.setOnCompletionListener { mp -> if (player === mp) stop() }
            p.setOnErrorListener { mp, _, _ ->
                if (player === mp) {
                    stop()
                    onError?.invoke("That recording couldn't be played.")
                }
                true
            }
            player = p
            _state.value = PlaybackState(baseName, playing = false)
            p.prepareAsync()
        } catch (e: Exception) {
            runCatching { p.release() }
            player = null
            _state.value = PlaybackState()
            onError?.invoke("That recording couldn't be played.")
        }
    }

    private fun pause() {
        val p = player ?: return
        runCatching { if (p.isPlaying) p.pause() }
        ticker?.cancel()
        _state.value = _state.value.copy(playing = false)
    }

    private fun resume() {
        val p = player ?: return
        if (!prepared) return // Still preparing; it will start by itself.
        audioManager?.requestAudioFocus(focusRequest)
        runCatching { p.start() }
        _state.value = _state.value.copy(playing = true)
        startTicker()
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val p = player ?: break
                val pos = runCatching { p.currentPosition.toLong() }.getOrDefault(0L)
                _state.value = _state.value.copy(positionMs = pos)
                delay(TICK_MS)
            }
        }
    }

    private companion object {
        const val TICK_MS = 200L
    }
}
