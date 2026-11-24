package qdvc.dahdoo.android.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import qdvc.dahdoo.android.app.data.AudioSource
import qdvc.dahdoo.android.app.model.Child
import qdvc.dahdoo.android.app.model.Nav
import qdvc.dahdoo.android.app.model.Recording
import qdvc.dahdoo.android.app.model.RecordingMeta
import qdvc.dahdoo.android.app.model.SettingsPage
import qdvc.dahdoo.android.app.model.Tab
import qdvc.dahdoo.android.app.model.ThemeMode
import qdvc.dahdoo.android.app.playback.Player
import qdvc.dahdoo.android.app.record.RecorderState
import qdvc.dahdoo.android.app.util.Suggestions
import java.time.LocalDate
import java.util.UUID

/**
 * All UI state, as StateFlows, plus the callbacks that change it (spec B1).
 * Process-wide state (recorder, repositories) lives in [AppContainer]; this
 * class adds navigation and playback and exposes it all to the screens.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as DahdooApp).container

    val settings = c.settings.settings
    val folder = c.settings.folder
    val recordings = c.recordings.recordings
    val lastAdded = c.recordings.lastAdded
    val recorderState = c.recorder.state
    val level = c.recorder.level
    val messages = c.messages.flow

    private val player = Player(app, viewModelScope).also { p -> p.onError = { c.messages.post(it) } }
    val playback = player.state

    private val _nav = MutableStateFlow(Nav())
    val nav: StateFlow<Nav> = _nav.asStateFlow()

    /** Most-used answers to "what were they doing?", for one-tap entry. */
    val doingSuggestions: StateFlow<List<String>> = recordings
        .map { list -> Suggestions.frequent(list.map { it.meta.doing }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // Background only: the first frame never waits for this.
        c.start()
    }

    fun onResumed() = c.retryIfUnavailable()

    // ---- recording ----

    fun toggleRecording() {
        when (recorderState.value) {
            RecorderState.Idle -> startRecording()
            is RecorderState.Recording -> c.recorder.stop()
            RecorderState.Starting -> Unit
        }
    }

    fun startRecording() {
        if (recorderState.value != RecorderState.Idle) return
        player.stop() // Don't record our own playback.
        c.recorder.start(settings.value.maxRecordingMinutes)
    }

    // ---- navigation ----

    /** Returns whether anything changed (decides the tap haptic). */
    fun selectTab(tab: Tab): Boolean {
        val n = _nav.value
        if (n.tab == tab) {
            // Re-tapping a tab returns it to its root.
            if (tab == Tab.RECORDINGS && n.openRecording != null) {
                _nav.value = n.copy(openRecording = null)
                return true
            }
            return false
        }
        _nav.value = n.copy(tab = tab)
        return true
    }

    fun openRecording(base: String) {
        _nav.value = _nav.value.copy(tab = Tab.RECORDINGS, openRecording = base)
    }

    fun closeRecording() {
        _nav.value = _nav.value.copy(openRecording = null)
    }

    fun setChildFilter(id: String?) {
        _nav.value = _nav.value.copy(childFilter = id)
    }

    /** System back at the top level: Recordings returns to Record; Record exits. */
    fun backFromRecordingsRoot(): Boolean {
        val n = _nav.value
        if (n.tab == Tab.RECORDINGS && n.openRecording == null) {
            _nav.value = n.copy(tab = Tab.RECORD)
            return true
        }
        return false
    }

    fun openSettings(page: SettingsPage = SettingsPage.ROOT, childId: String? = null) {
        _nav.value = _nav.value.copy(settingsPage = page, editingChildId = childId)
    }

    fun settingsBack() {
        val n = _nav.value
        _nav.value = when (n.settingsPage) {
            null -> n
            SettingsPage.ROOT -> n.copy(settingsPage = null)
            SettingsPage.CHILD_EDIT -> n.copy(settingsPage = SettingsPage.CHILDREN, editingChildId = null)
            else -> n.copy(settingsPage = SettingsPage.ROOT)
        }
    }

    // ---- playback ----

    fun togglePlay(recording: Recording) {
        if (recorderState.value != RecorderState.Idle) return
        player.toggle(recording.baseName) { c.recordings.audioSource(recording.baseName) }
    }

    fun seek(positionMs: Long) = player.seekTo(positionMs)

    suspend fun shareSource(base: String): AudioSource? = c.recordings.audioSource(base)

    // ---- catalogue ----

    fun saveMeta(recording: Recording, meta: RecordingMeta) {
        viewModelScope.launch { c.recordings.updateMeta(recording.baseName, meta) }
    }

    fun deleteRecording(recording: Recording) {
        if (playback.value.baseName == recording.baseName) player.stop()
        viewModelScope.launch {
            if (c.recordings.delete(recording.baseName)) closeRecording()
        }
    }

    // ---- folder ----

    fun chooseFolder(uri: Uri) {
        c.scope.launch {
            c.settings.chooseFolder(uri)
            c.recordings.refresh()
        }
    }

    fun retryCopy() {
        c.scope.launch {
            c.settings.syncWithFolder()
            c.recordings.refresh()
        }
    }

    // ---- settings ----

    fun saveChild(id: String?, name: String, birthday: LocalDate?) {
        c.settings.update { s ->
            if (id == null) {
                val child = Child(newChildId(), name, birthday)
                // The first child becomes the default, so a one-child family never
                // has to assign anything by hand.
                s.copy(
                    children = s.children + child,
                    defaultChildId = s.defaultChildId ?: if (s.children.isEmpty()) child.id else null,
                )
            } else {
                s.copy(children = s.children.map { if (it.id == id) it.copy(name = name, birthday = birthday) else it })
            }
        }
        settingsBack()
    }

    fun removeChild(id: String) {
        c.settings.update { s ->
            s.copy(
                children = s.children.filterNot { it.id == id },
                defaultChildId = s.defaultChildId?.takeIf { it != id },
            )
        }
        if (_nav.value.childFilter == id) setChildFilter(null)
        settingsBack()
    }

    fun setDefaultChild(id: String?) = c.settings.update { it.copy(defaultChildId = id) }
    fun setThemeMode(mode: ThemeMode) = c.settings.update { it.copy(themeMode = mode) }
    fun setKeepScreenOn(on: Boolean) = c.settings.update { it.copy(keepScreenOn = on) }
    fun setMaxMinutes(minutes: Int) = c.settings.update { it.copy(maxRecordingMinutes = minutes) }

    override fun onCleared() {
        player.stop()
    }

    private fun newChildId(): String = "c-" + UUID.randomUUID().toString().take(8)
}
