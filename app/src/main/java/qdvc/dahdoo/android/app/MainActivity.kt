package qdvc.dahdoo.android.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import qdvc.dahdoo.android.app.data.AudioSource
import qdvc.dahdoo.android.app.model.SettingsPage
import qdvc.dahdoo.android.app.model.Tab
import qdvc.dahdoo.android.app.record.RecorderState
import qdvc.dahdoo.android.app.ui.components.BottomBar
import qdvc.dahdoo.android.app.ui.components.hierarchySlide
import qdvc.dahdoo.android.app.ui.components.lateralSwap
import qdvc.dahdoo.android.app.ui.record.RecordScreen
import qdvc.dahdoo.android.app.ui.recordings.RecordingDetailScreen
import qdvc.dahdoo.android.app.ui.recordings.RecordingsScreen
import qdvc.dahdoo.android.app.ui.settings.SettingsActions
import qdvc.dahdoo.android.app.ui.settings.SettingsScreen
import qdvc.dahdoo.android.app.ui.theme.DahdooTheme
import qdvc.dahdoo.android.app.ui.theme.resolveDarkTheme

/**
 * The single Activity. It hosts Compose, owns the system plumbing (edge-to-edge,
 * permissions, the folder picker, the "Record now" shortcut) and nothing else.
 *
 * Start-up path: onCreate -> setContent -> first frame with the record button.
 * The ViewModel's background load (folder sync, recordings list) starts in
 * parallel and fills in whatever it finds; nothing on the record screen waits
 * for it.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    /** Set when launched (or re-launched) from the "Record now" shortcut. */
    private var autoRecord by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only on a fresh launch: after rotation the intent is the same one, and
        // re-reading it would start a second recording.
        if (savedInstanceState == null && intent?.action == ACTION_RECORD) autoRecord = true
        // A listener rather than overriding onNewIntent, whose parameter
        // nullability differs between SDK and androidx versions.
        addOnNewIntentListener { newIntent ->
            if (newIntent.action == ACTION_RECORD) autoRecord = true
        }

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            DahdooTheme(darkTheme = resolveDarkTheme(settings.themeMode)) {
                AppRoot(
                    viewModel = viewModel,
                    autoRecord = autoRecord,
                    onAutoRecordHandled = { autoRecord = false },
                )
            }
        }
    }

    companion object {
        const val ACTION_RECORD = "qdvc.dahdoo.android.app.action.RECORD"
    }
}

private fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppRoot(
    viewModel: AppViewModel,
    autoRecord: Boolean,
    onAutoRecordHandled: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as Activity
    val scope = rememberCoroutineScope()

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val folder by viewModel.folder.collectAsStateWithLifecycle()
    val recordings by viewModel.recordings.collectAsStateWithLifecycle()
    val lastAdded by viewModel.lastAdded.collectAsStateWithLifecycle()
    val recorder by viewModel.recorderState.collectAsStateWithLifecycle()
    val level by viewModel.level.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val nav by viewModel.nav.collectAsStateWithLifecycle()
    val doingSuggestions by viewModel.doingSuggestions.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }

    // ---- microphone permission ----
    var micGranted by remember { mutableStateOf(context.hasPermission(Manifest.permission.RECORD_AUDIO)) }
    var micBlocked by remember { mutableStateOf(false) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
        if (granted) {
            micBlocked = false
            // The tap that asked was a request to record; honour it.
            viewModel.startRecording()
        } else {
            // No rationale after a denial means "don't ask again": only Settings can help.
            micBlocked = !activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        }
    }

    var notificationsBlocked by remember { mutableStateOf(false) }
    fun refreshNotificationPermission() {
        notificationsBlocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshNotificationPermission()
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        micGranted = context.hasPermission(Manifest.permission.RECORD_AUDIO)
        if (micGranted) micBlocked = false
        refreshNotificationPermission()
        viewModel.onResumed()
    }

    fun toggleRecording() {
        if (recorder is RecorderState.Recording || micGranted) {
            viewModel.toggleRecording()
        } else {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(autoRecord) {
        if (!autoRecord) return@LaunchedEffect
        onAutoRecordHandled()
        if (micGranted) viewModel.startRecording() else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Keep the screen awake mid-recording, if the user wants that.
    val view = LocalView.current
    val keepAwake = recorder is RecorderState.Recording && settings.keepScreenOn
    DisposableEffect(keepAwake) {
        view.keepScreenOn = keepAwake
        onDispose { view.keepScreenOn = false }
    }

    // ---- folder picker ----
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.chooseFolder(uri)
    }
    fun chooseFolder() = folderPicker.launch(null)

    fun openAppSettings() {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        )
    }

    fun share(base: String) {
        scope.launch {
            val source = viewModel.shareSource(base) as? AudioSource.Document ?: return@launch
            val send = Intent(Intent.ACTION_SEND)
                .setType("audio/mp4")
                .putExtra(Intent.EXTRA_STREAM, source.uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, "Share recording"))
        }
    }

    // Hoisted above the tab AnimatedContent, which discards the outgoing tab's
    // composition: remembered inside the list, the scroll position would reset on
    // every return (rememberSaveable doesn't survive leaving composition).
    val recordingsListState = rememberLazyListState()

    // Top-level back: Recordings root -> Record. Detail screens and Settings have
    // their own handlers, registered later and so consulted first.
    BackHandler(enabled = nav.tab == Tab.RECORDINGS && nav.openRecording == null && nav.settingsPage == null) {
        viewModel.backFromRecordingsRoot()
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            // The outer Scaffold owns no insets: each screen's TopAppBar owns the
            // top, the NavigationBar owns the bottom (spec B2).
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                // Hidden while typing so a field can sit right on the keyboard.
                if (!WindowInsets.isImeVisible) {
                    BottomBar(current = nav.tab, onSelect = viewModel::selectTab)
                }
            },
        ) { padding ->
            AnimatedContent(
                targetState = nav.tab,
                transitionSpec = { lateralSwap() },
                label = "tabs",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) { shownTab ->
                when (shownTab) {
                    Tab.RECORD -> RecordScreen(
                        state = recorder,
                        level = level,
                        folder = folder,
                        micBlocked = micBlocked,
                        lastSaved = lastAdded?.let { base -> recordings.firstOrNull { it.baseName == base } },
                        settings = settings,
                        onToggle = ::toggleRecording,
                        onOpenSettings = { viewModel.openSettings() },
                        onChooseFolder = ::chooseFolder,
                        onOpenMicSettings = ::openAppSettings,
                        onOpenLastSaved = { lastAdded?.let(viewModel::openRecording) },
                    )

                    Tab.RECORDINGS -> AnimatedContent(
                        targetState = nav.openRecording,
                        transitionSpec = {
                            hierarchySlide(deeper = targetState != null && initialState == null)
                        },
                        label = "recordings",
                    ) { openBase ->
                        if (openBase == null) {
                            RecordingsScreen(
                                recordings = recordings,
                                settings = settings,
                                playback = playback,
                                childFilter = nav.childFilter,
                                listState = recordingsListState,
                                onFilter = viewModel::setChildFilter,
                                onTogglePlay = viewModel::togglePlay,
                                onOpen = { viewModel.openRecording(it.baseName) },
                            )
                        } else {
                            RecordingDetailScreen(
                                recording = recordings.firstOrNull { it.baseName == openBase },
                                settings = settings,
                                playback = playback,
                                doingSuggestions = doingSuggestions,
                                onTogglePlay = viewModel::togglePlay,
                                onSeek = viewModel::seek,
                                onSave = viewModel::saveMeta,
                                onDelete = viewModel::deleteRecording,
                                onShare = { share(it.baseName) },
                                onClose = viewModel::closeRecording,
                            )
                        }
                    }
                }
            }
        }

        // Settings is a full-screen route above the tabs.
        val settingsPage = nav.settingsPage
        // Remembers the page being left so the exit animation has something to show.
        var lastPage by remember { mutableStateOf(SettingsPage.ROOT) }
        SideEffect { if (settingsPage != null) lastPage = settingsPage }
        AnimatedVisibility(
            visible = settingsPage != null,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
        ) {
            SettingsScreen(
                page = settingsPage ?: lastPage,
                editingChildId = nav.editingChildId,
                settings = settings,
                folder = folder,
                pendingCount = recordings.count { it.isPending },
                notificationsBlocked = notificationsBlocked,
                actions = SettingsActions(
                    navigate = viewModel::openSettings,
                    back = viewModel::settingsBack,
                    chooseFolder = ::chooseFolder,
                    retryCopy = viewModel::retryCopy,
                    saveChild = viewModel::saveChild,
                    removeChild = viewModel::removeChild,
                    setDefaultChild = viewModel::setDefaultChild,
                    setThemeMode = viewModel::setThemeMode,
                    setKeepScreenOn = viewModel::setKeepScreenOn,
                    setMaxMinutes = viewModel::setMaxMinutes,
                    allowNotifications = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                ),
            )
        }
    }
}
