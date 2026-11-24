package qdvc.dahdoo.android.app.ui.record

import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import qdvc.dahdoo.android.app.data.FolderState
import qdvc.dahdoo.android.app.model.AppSettings
import qdvc.dahdoo.android.app.model.Recording
import qdvc.dahdoo.android.app.record.RecorderState
import qdvc.dahdoo.android.app.ui.components.AppTopBar
import qdvc.dahdoo.android.app.ui.components.Banner
import qdvc.dahdoo.android.app.ui.components.rememberHaptics
import qdvc.dahdoo.android.app.ui.components.topOnlyInsets
import qdvc.dahdoo.android.app.ui.theme.RecordingRed
import qdvc.dahdoo.android.app.util.Ages
import qdvc.dahdoo.android.app.util.Durations

/**
 * Slot 1, the home surface, and the reason the app exists: one huge button. It
 * draws from state that is available before anything has been read from disk,
 * so it is usable on the very first frame. Banners about the folder or the
 * microphone are advisory and never stand between the user and the button.
 */
@Composable
fun RecordScreen(
    state: RecorderState,
    level: Float,
    folder: FolderState,
    micBlocked: Boolean,
    lastSaved: Recording?,
    settings: AppSettings,
    onToggle: () -> Unit,
    onOpenSettings: () -> Unit,
    onChooseFolder: () -> Unit,
    onOpenMicSettings: () -> Unit,
    onOpenLastSaved: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = topOnlyInsets,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(
                title = "Dahdoo",
                actions = {
                    // Opening Settings changes what is shown, so it taps (spec A9).
                    val haptics = rememberHaptics()
                    IconButton(onClick = { haptics.tap(); onOpenSettings() }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                micBlocked -> Banner(
                    icon = Icons.Filled.MicOff,
                    text = "Microphone access is turned off for Dahdoo.",
                    action = "Open settings",
                    onAction = onOpenMicSettings,
                )
                folder is FolderState.NotChosen -> Banner(
                    icon = Icons.Filled.Folder,
                    text = "Choose a folder to keep your recordings in. You can record now; they'll move there.",
                    action = "Choose",
                    onAction = onChooseFolder,
                )
                folder is FolderState.Chosen && folder.status == FolderState.Status.UNAVAILABLE -> Banner(
                    icon = Icons.Filled.Folder,
                    text = "Your folder can't be reached. New recordings are safe on this phone for now.",
                    action = "Choose",
                    onAction = onChooseFolder,
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                BigRecordButton(state = state, level = level, onToggle = onToggle)
            }

            StatusLine(state)
            Spacer(Modifier.height(16.dp))

            if (lastSaved != null && state !is RecorderState.Recording) {
                LastSavedCard(lastSaved, settings, onOpenLastSaved)
            } else {
                // Keep the button from jumping when the card appears.
                Spacer(Modifier.height(LAST_SAVED_HEIGHT))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private val LAST_SAVED_HEIGHT = 72.dp

@Composable
private fun BigRecordButton(state: RecorderState, level: Float, onToggle: () -> Unit) {
    val haptics = rememberHaptics()
    val recording = state is RecorderState.Recording
    val idleBreath = rememberInfiniteTransition(label = "breath")
    val breath by idleBreath.animateFloat(
        initialValue = 1f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Reverse),
        label = "breath-scale",
    )
    val halo by animateFloatAsState(
        targetValue = if (recording) level else 0f,
        animationSpec = tween(90),
        label = "halo",
    )

    BoxWithConstraints(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
        // ~58% of the screen's width, like Shazam's button, but never so tall it
        // crowds the status line on a short landscape screen.
        val diameter = minOf(maxWidth * 0.58f, maxHeight * 0.72f)
        val color = if (recording) RecordingRed else MaterialTheme.colorScheme.primary

        Box(
            modifier = Modifier
                .size(diameter)
                .scale(1f + 0.38f * halo)
                .background(color.copy(alpha = 0.18f), CircleShape)
        )
        Box(
            modifier = Modifier
                .size(diameter)
                .scale(1f + 0.18f * halo)
                .background(color.copy(alpha = 0.22f), CircleShape)
        )
        Surface(
            onClick = {
                if (state !is RecorderState.Starting) {
                    haptics.confirm()
                    onToggle()
                }
            },
            shape = CircleShape,
            color = color,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shadowElevation = 8.dp,
            modifier = Modifier
                .size(diameter)
                .scale(if (recording) 1f else breath)
                .semantics {
                    contentDescription = if (recording) "Stop recording" else "Start recording"
                },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (recording) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(diameter * 0.4f),
                )
            }
        }
    }
}

@Composable
private fun StatusLine(state: RecorderState) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state) {
        while (state is RecorderState.Recording) {
            now = SystemClock.elapsedRealtime()
            delay(200)
        }
    }
    val text = when (state) {
        is RecorderState.Recording -> {
            val elapsed = Durations.format(now - state.startedAtElapsed)
            if (state.maxDurationMs > 0) "$elapsed / ${Durations.format(state.maxDurationMs)}" else elapsed
        }
        RecorderState.Starting -> "Listening…"
        RecorderState.Idle -> "Tap to catch a babble"
    }
    Text(
        text,
        style = if (state is RecorderState.Recording) {
            MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum")
        } else {
            MaterialTheme.typography.titleMedium
        },
        color = if (state is RecorderState.Recording) RecordingRed else MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = if (state is RecorderState.Recording) FontWeight.SemiBold else FontWeight.Normal,
    )
}

@Composable
private fun LastSavedCard(recording: Recording, settings: AppSettings, onOpen: () -> Unit) {
    val haptics = rememberHaptics()
    val child = settings.child(recording.meta.childId)
    val age = child?.birthday?.let { Ages.describeShort(it, recording.meta.recordedAt.toLocalDate()) }
    val detail = listOfNotNull(
        Durations.format(recording.meta.durationMs),
        child?.name,
        age,
    ).joinToString(" · ")
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .fillMaxWidth()
            .height(LAST_SAVED_HEIGHT)
            .clickable { haptics.tap(); onOpen() },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            Column(Modifier.weight(1f)) {
                Text("Saved · $detail", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "Add who, what and what they said",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}
