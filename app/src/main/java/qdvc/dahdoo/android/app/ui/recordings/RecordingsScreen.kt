package qdvc.dahdoo.android.app.ui.recordings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import qdvc.dahdoo.android.app.model.AppSettings
import qdvc.dahdoo.android.app.model.Recording
import qdvc.dahdoo.android.app.playback.PlaybackState
import qdvc.dahdoo.android.app.ui.components.AppTopBar
import qdvc.dahdoo.android.app.ui.components.EmptyState
import qdvc.dahdoo.android.app.ui.components.Pill
import qdvc.dahdoo.android.app.ui.components.rememberHaptics
import qdvc.dahdoo.android.app.ui.components.topOnlyInsets
import qdvc.dahdoo.android.app.util.Ages
import qdvc.dahdoo.android.app.util.Durations
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Sentinel filter meaning "recordings with no child assigned". */
const val FILTER_UNASSIGNED = "\u0000unassigned"

@Composable
fun RecordingsScreen(
    recordings: List<Recording>,
    settings: AppSettings,
    playback: PlaybackState,
    childFilter: String?,
    listState: LazyListState,
    onFilter: (String?) -> Unit,
    onTogglePlay: (Recording) -> Unit,
    onOpen: (Recording) -> Unit,
) {
    Scaffold(
        contentWindowInsets = topOnlyInsets,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppTopBar(title = "Recordings") },
    ) { padding ->
        if (recordings.isEmpty()) {
            EmptyState(
                title = "No babbles yet",
                message = "Recordings you make appear here, newest first. Tap the big button on the Record tab to catch one.",
                modifier = Modifier.padding(padding),
            )
        } else {
            RecordingsList(recordings, settings, playback, childFilter, listState, onFilter, onTogglePlay, onOpen, padding)
        }
    }
}

@Composable
private fun RecordingsList(
    recordings: List<Recording>,
    settings: AppSettings,
    playback: PlaybackState,
    childFilter: String?,
    listState: LazyListState,
    onFilter: (String?) -> Unit,
    onTogglePlay: (Recording) -> Unit,
    onOpen: (Recording) -> Unit,
    padding: PaddingValues,
) {
    val shown = when (childFilter) {
        null -> recordings
        FILTER_UNASSIGNED -> recordings.filter { settings.child(it.meta.childId) == null }
        else -> recordings.filter { it.meta.childId == childFilter }
    }
    val byDay = shown.groupBy { it.meta.recordedAt.toLocalDate() }

    // The filter row sits above the list rather than inside it, so it stays
    // put while the list scrolls.
    Column(Modifier.fillMaxSize().padding(padding)) {
        if (settings.children.isNotEmpty()) {
            FilterRow(settings, childFilter, onFilter)
        }
        if (shown.isEmpty()) {
            EmptyState(
                title = "Nothing here",
                message = "No recordings match this filter yet.",
            )
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = 16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                byDay.forEach { (day, dayItems) ->
                    item(key = "day-$day") { DayHeader(day) }
                    items(dayItems, key = { it.baseName }) { rec ->
                        RecordingRow(
                            recording = rec,
                            settings = settings,
                            playback = playback,
                            onTogglePlay = { onTogglePlay(rec) },
                            onOpen = { onOpen(rec) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterRow(settings: AppSettings, selected: String?, onFilter: (String?) -> Unit) {
    val haptics = rememberHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        fun choose(value: String?) {
            if (value != selected) haptics.tap()
            onFilter(value)
        }
        FilterChip(selected = selected == null, onClick = { choose(null) }, label = { Text("Everyone") })
        settings.children.forEach { child ->
            FilterChip(
                selected = selected == child.id,
                onClick = { choose(child.id) },
                label = { Text(child.name.ifBlank { "Unnamed" }) },
            )
        }
        FilterChip(
            selected = selected == FILTER_UNASSIGNED,
            onClick = { choose(FILTER_UNASSIGNED) },
            label = { Text("Not assigned") },
        )
    }
}

private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

@Composable
private fun DayHeader(day: LocalDate) {
    val today = LocalDate.now()
    val label = when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> DAY_FORMAT.format(day)
    }
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 4.dp),
    )
}

@Composable
private fun RecordingRow(
    recording: Recording,
    settings: AppSettings,
    playback: PlaybackState,
    onTogglePlay: () -> Unit,
    onOpen: () -> Unit,
) {
    val haptics = rememberHaptics()
    val meta = recording.meta
    val isCurrent = playback.baseName == recording.baseName
    val child = settings.child(meta.childId)
    val age = child?.birthday?.let { Ages.describeShort(it, meta.recordedAt.toLocalDate()) }
    val untitled = meta.saying.isBlank()
    val title = when {
        !untitled -> "“${meta.saying.trim()}”"
        meta.doing.isNotBlank() -> meta.doing.trim()
        else -> "Untitled babble"
    }
    val subtitle = listOfNotNull(
        TIME_FORMAT.format(meta.recordedAt.toLocalTime()),
        if (meta.durationMs > 0) Durations.format(meta.durationMs) else null,
        child?.name?.let { name -> if (age != null) "$name, $age" else name },
        if (!untitled && meta.doing.isNotBlank()) meta.doing.trim() else null,
    ).joinToString(" · ")

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { haptics.tap(); onOpen() }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(onClick = { haptics.tap(); onTogglePlay() }) {
                Icon(
                    if (isCurrent && playback.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isCurrent && playback.playing) "Pause" else "Play",
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontStyle = if (untitled && meta.doing.isBlank()) FontStyle.Italic else FontStyle.Normal,
                    color = if (untitled && meta.doing.isBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isCurrent && playback.durationMs > 0) {
                    LinearProgressIndicator(
                        progress = { (playback.positionMs.toFloat() / playback.durationMs).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                    )
                }
            }
            if (recording.isPending) {
                Spacer(Modifier.width(8.dp))
                Pill("On phone")
            }
            if (meta.favourite) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Filled.Star,
                    contentDescription = "Favourite",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(start = 64.dp),
        )
    }
}
