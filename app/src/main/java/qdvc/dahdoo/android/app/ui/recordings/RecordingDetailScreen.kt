package qdvc.dahdoo.android.app.ui.recordings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import qdvc.dahdoo.android.app.model.AppSettings
import qdvc.dahdoo.android.app.model.Child
import qdvc.dahdoo.android.app.model.Recording
import qdvc.dahdoo.android.app.model.RecordingMeta
import qdvc.dahdoo.android.app.playback.PlaybackState
import qdvc.dahdoo.android.app.ui.components.AppTopBar
import qdvc.dahdoo.android.app.ui.components.EmptyState
import qdvc.dahdoo.android.app.ui.components.SectionHeader
import qdvc.dahdoo.android.app.ui.components.rememberHaptics
import qdvc.dahdoo.android.app.ui.components.topOnlyInsets
import qdvc.dahdoo.android.app.util.Ages
import qdvc.dahdoo.android.app.util.Durations
import qdvc.dahdoo.android.app.util.Names
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
private val TITLE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

/**
 * Where catalogue details are filled in, whenever the parent has a moment --
 * never as a step between recording and saving. This is a form over the
 * sidecar's fields (spec A3, "form editing"); unknown sidecar keys survive a
 * save because the codec overlays rather than rewrites.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecordingDetailScreen(
    recording: Recording?,
    settings: AppSettings,
    playback: PlaybackState,
    doingSuggestions: List<String>,
    onTogglePlay: (Recording) -> Unit,
    onSeek: (Long) -> Unit,
    onSave: (Recording, RecordingMeta) -> Unit,
    onDelete: (Recording) -> Unit,
    onShare: (Recording) -> Unit,
    onClose: () -> Unit,
) {
    val haptics = rememberHaptics()

    if (recording == null) {
        // Deleted, or gone from the folder: say so rather than show another one.
        BackHandler(onBack = onClose)
        Scaffold(
            contentWindowInsets = topOnlyInsets,
            topBar = { AppTopBar(title = "Recording", onBack = onClose) },
        ) { padding ->
            EmptyState(
                title = "This recording isn't here any more",
                message = "It may have been deleted or moved out of your folder.",
                modifier = Modifier.padding(padding),
            )
        }
        return
    }

    val saved = recording.meta
    var draft by remember(recording.baseName) { mutableStateOf(saved) }
    var lastSeen by remember(recording.baseName) { mutableStateOf(saved) }
    // If the saved details change underneath us (a refresh picked up an edit made
    // elsewhere) and the user hasn't started editing, follow them.
    LaunchedEffect(saved) {
        if (draft == lastSeen) draft = saved
        lastSeen = saved
    }
    val dirty = draft != saved

    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    // One handler for the arrow and system back (spec A2).
    val goBack: () -> Unit = { if (dirty) confirmDiscard = true else onClose() }
    BackHandler(onBack = goBack)

    val title = TITLE_FORMAT.format(saved.recordedAt.toLocalDateTime())

    Scaffold(
        contentWindowInsets = topOnlyInsets,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(
                title = title,
                onBack = goBack,
                actions = {
                    if (dirty) {
                        TextButton(onClick = { haptics.confirm(); onSave(recording, draft) }) { Text("Save") }
                    }
                    if (!recording.isPending) {
                        IconButton(onClick = { haptics.tap(); onShare(recording) }) {
                            Icon(Icons.Filled.Share, contentDescription = "Share")
                        }
                    }
                    IconButton(onClick = { haptics.tap(); confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            PlayerCard(recording, playback, onTogglePlay, onSeek)

            val recordedAt = saved.recordedAt
            Text(
                "${DATE_FORMAT.format(recordedAt.toLocalDate())} at ${TIME_FORMAT.format(recordedAt.toLocalTime())}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            SectionHeader("Who")
            FlowRow(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = draft.childId == null,
                    onClick = { draft = draft.copy(childId = null) },
                    label = { Text("Not sure") },
                )
                settings.children.forEach { child ->
                    FilterChip(
                        selected = draft.childId == child.id,
                        onClick = { draft = draft.copy(childId = child.id) },
                        label = { Text(child.name.ifBlank { "Unnamed" }) },
                    )
                }
            }
            val child = settings.child(draft.childId)
            val ageText = ageLine(settings, draft.childId, child, recordedAt.toLocalDate())
            if (ageText != null) {
                Text(
                    ageText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
                )
            }

            SectionHeader("What were they doing?")
            OutlinedTextField(
                value = draft.doing,
                onValueChange = { draft = draft.copy(doing = it) },
                placeholder = { Text("e.g. bath time, reading together") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
            val suggestions = doingSuggestions.filter { !it.equals(draft.doing.trim(), ignoreCase = true) }
            if (suggestions.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    suggestions.forEach { s ->
                        SuggestionChip(onClick = { draft = draft.copy(doing = s) }, label = { Text(s) })
                    }
                }
            }

            SectionHeader("What did they seem to say?")
            OutlinedTextField(
                value = draft.saying,
                onValueChange = { draft = draft.copy(saying = it) },
                placeholder = { Text("e.g. dah doo, ba-ba") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )

            SectionHeader("Notes")
            OutlinedTextField(
                value = draft.notes,
                onValueChange = { draft = draft.copy(notes = it) },
                placeholder = { Text("Anything else worth remembering") },
                minLines = 3,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { haptics.tap(); draft = draft.copy(favourite = !draft.favourite) }) {
                    Icon(
                        if (draft.favourite) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(if (draft.favourite) "A favourite" else "Mark as a favourite")
            }

            Spacer(Modifier.height(12.dp))
            Text(
                buildString {
                    append("File: ").append(Names.audioName(recording.baseName))
                    if (recording.isPending) append("\nOn this phone for now; it will move to your folder when it can.")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("The details you've typed for this recording haven't been saved.") },
            confirmButton = {
                TextButton(onClick = {
                    haptics.reject()
                    confirmDiscard = false
                    onClose()
                }) { Text("Discard", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") }
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this recording?") },
            text = {
                Text(
                    if (recording.isPending) "The audio and its details will be removed from this phone. This can't be undone."
                    else "The audio file and its details will be deleted from your folder. This can't be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    haptics.reject()
                    confirmDelete = false
                    onDelete(recording)
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun PlayerCard(
    recording: Recording,
    playback: PlaybackState,
    onTogglePlay: (Recording) -> Unit,
    onSeek: (Long) -> Unit,
) {
    val haptics = rememberHaptics()
    val isCurrent = playback.baseName == recording.baseName
    val duration = if (isCurrent && playback.durationMs > 0) playback.durationMs else recording.meta.durationMs
    val position = if (isCurrent) playback.positionMs else 0L
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledIconButton(
                onClick = { haptics.tap(); onTogglePlay(recording) },
                modifier = Modifier.size(56.dp),
            ) {
                Icon(
                    if (isCurrent && playback.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isCurrent && playback.playing) "Pause" else "Play",
                    modifier = Modifier.size(32.dp),
                )
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Slider(
                    value = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                    onValueChange = { fraction -> if (isCurrent && duration > 0) onSeek((fraction * duration).toLong()) },
                    enabled = isCurrent && duration > 0,
                )
                Row {
                    Text(Durations.format(position), style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (duration > 0) Durations.format(duration) else "–:––",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

/** The line under the child chips: their age then, or what's missing to show it. */
private fun ageLine(settings: AppSettings, childId: String?, child: Child?, on: LocalDate): String? {
    if (settings.children.isEmpty()) return "Add your children in Settings to pick them here."
    if (childId != null && child == null) return "This child has been removed from Settings."
    if (child == null) return null
    val name = child.name.ifBlank { "this child" }
    val birthday = child.birthday ?: return "Add a birthday for $name in Settings to see their age here."
    if (on.isBefore(birthday)) return "Recorded before $name was born."
    return "${name.replaceFirstChar { it.uppercase() }} was ${Ages.describe(birthday, on)} old."
}
