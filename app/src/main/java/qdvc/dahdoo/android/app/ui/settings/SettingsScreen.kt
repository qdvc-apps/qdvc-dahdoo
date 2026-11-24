package qdvc.dahdoo.android.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import qdvc.dahdoo.android.app.BuildConfig
import qdvc.dahdoo.android.app.data.FolderState
import qdvc.dahdoo.android.app.model.AppSettings
import qdvc.dahdoo.android.app.model.Child
import qdvc.dahdoo.android.app.model.SettingsPage
import qdvc.dahdoo.android.app.model.ThemeMode
import qdvc.dahdoo.android.app.ui.components.AppTopBar
import qdvc.dahdoo.android.app.ui.components.ChoiceRow
import qdvc.dahdoo.android.app.ui.components.NavRow
import qdvc.dahdoo.android.app.ui.components.SectionHeader
import qdvc.dahdoo.android.app.ui.components.SwitchRow
import qdvc.dahdoo.android.app.ui.components.hierarchySlide
import qdvc.dahdoo.android.app.ui.components.rememberHaptics
import qdvc.dahdoo.android.app.util.Ages
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class SettingsActions(
    val navigate: (SettingsPage, String?) -> Unit,
    val back: () -> Unit,
    val chooseFolder: () -> Unit,
    val retryCopy: () -> Unit,
    val saveChild: (id: String?, name: String, birthday: LocalDate?) -> Unit,
    val removeChild: (String) -> Unit,
    val setDefaultChild: (String?) -> Unit,
    val setThemeMode: (ThemeMode) -> Unit,
    val setKeepScreenOn: (Boolean) -> Unit,
    val setMaxMinutes: (Int) -> Unit,
    val allowNotifications: () -> Unit,
)

private val BIRTHDAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

/**
 * Full-screen route drawn over the tabs, so it owns its own bottom inset (no
 * topOnlyInsets). The arrow and system back share [SettingsActions.back].
 */
@Composable
fun SettingsScreen(
    page: SettingsPage,
    editingChildId: String?,
    settings: AppSettings,
    folder: FolderState,
    pendingCount: Int,
    notificationsBlocked: Boolean,
    actions: SettingsActions,
) {
    BackHandler(onBack = actions.back)
    val editing = settings.child(editingChildId)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(
                title = when (page) {
                    SettingsPage.ROOT -> "Settings"
                    SettingsPage.FOLDER -> "Folder"
                    SettingsPage.CHILDREN -> "Children"
                    SettingsPage.CHILD_EDIT -> if (editing == null) "Add a child" else "Edit child"
                    SettingsPage.APPEARANCE -> "Appearance"
                    SettingsPage.RECORDING -> "Recording"
                    SettingsPage.ABOUT -> "About"
                },
                onBack = actions.back,
            )
        },
    ) { padding ->
        AnimatedContent(
            targetState = page,
            transitionSpec = { hierarchySlide(deeper = targetState.depth > initialState.depth) },
            label = "settings-page",
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) { shown ->
            // Switch on the lambda's own argument, never on `page`, or the
            // outgoing and incoming frames render the same page.
            when (shown) {
                SettingsPage.ROOT -> RootPage(settings, folder, actions)
                SettingsPage.FOLDER -> FolderPage(folder, pendingCount, actions)
                SettingsPage.CHILDREN -> ChildrenPage(settings, actions)
                SettingsPage.CHILD_EDIT -> ChildEditPage(editing, actions)
                SettingsPage.APPEARANCE -> AppearancePage(settings, actions)
                SettingsPage.RECORDING -> RecordingPage(settings, notificationsBlocked, actions)
                SettingsPage.ABOUT -> AboutPage()
            }
        }
    }
}

@Composable
private fun RootPage(settings: AppSettings, folder: FolderState, actions: SettingsActions) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        NavRow(Icons.Filled.Folder, "Folder", folderSummary(folder)) { actions.navigate(SettingsPage.FOLDER, null) }
        NavRow(
            Icons.Filled.ChildCare,
            "Children",
            when (settings.children.size) {
                0 -> "None added yet"
                1 -> settings.children.first().name.ifBlank { "1 child" }
                else -> settings.children.joinToString(", ") { it.name.ifBlank { "Unnamed" } }
            },
        ) { actions.navigate(SettingsPage.CHILDREN, null) }
        NavRow(
            Icons.Filled.Palette,
            "Appearance",
            when (settings.themeMode) {
                ThemeMode.AUTOMATIC -> "Automatic"
                ThemeMode.LIGHT -> "Light"
                ThemeMode.DARK -> "Dark"
            },
        ) { actions.navigate(SettingsPage.APPEARANCE, null) }
        NavRow(Icons.Filled.Mic, "Recording", maxLabel(settings.maxRecordingMinutes)) {
            actions.navigate(SettingsPage.RECORDING, null)
        }
        NavRow(Icons.Filled.Info, "About", "Version ${BuildConfig.VERSION_NAME}") {
            actions.navigate(SettingsPage.ABOUT, null)
        }
    }
}

private fun folderSummary(folder: FolderState): String = when (folder) {
    FolderState.NotChosen -> "Not chosen yet"
    is FolderState.Chosen -> when (folder.status) {
        FolderState.Status.OK -> folder.name ?: "Chosen"
        FolderState.Status.CHECKING -> (folder.name ?: "Chosen") + " · checking…"
        FolderState.Status.UNAVAILABLE -> (folder.name ?: "Chosen") + " · can't be reached"
    }
}

private fun maxLabel(minutes: Int): String =
    if (minutes <= 0) "No time limit" else "Stops after $minutes min"

@Composable
private fun FolderPage(folder: FolderState, pendingCount: Int, actions: SettingsActions) {
    val haptics = rememberHaptics()
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            "Everything Dahdoo keeps (your recordings, their details, your children and these " +
                "settings) lives in one folder you choose, as ordinary files. Point a backup or " +
                "sync app at it to mirror it to a network drive.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        Text("Current folder", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(folderSummary(folder), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(12.dp))
        Button(onClick = { haptics.tap(); actions.chooseFolder() }) {
            Text(if (folder is FolderState.NotChosen) "Choose folder" else "Choose a different folder")
        }
        if (pendingCount > 0) {
            Spacer(Modifier.height(20.dp))
            Text(
                if (pendingCount == 1) "1 recording is waiting on this phone to be copied into the folder."
                else "$pendingCount recordings are waiting on this phone to be copied into the folder.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = { haptics.tap(); actions.retryCopy() }, modifier = Modifier.padding(top = 8.dp)) {
                Text("Copy now")
            }
        }
        Spacer(Modifier.height(20.dp))
        Text("What's inside", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(
            "dahdoo-settings.json — children and preferences\n" +
                "recordings/2026-11-23_14-05-33.m4a — the audio\n" +
                "recordings/2026-11-23_14-05-33.json — its details\n\n" +
                "Choosing a folder that already holds Dahdoo files (a backup, or another phone's " +
                "folder) picks them up. Choosing a different folder doesn't move or delete anything " +
                "in the old one.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChildrenPage(settings: AppSettings, actions: SettingsActions) {
    val today = LocalDate.now()
    Column(Modifier.verticalScroll(rememberScrollState())) {
        settings.children.forEach { child ->
            val subtitle = child.birthday?.let {
                "Born ${BIRTHDAY_FORMAT.format(it)} · ${Ages.describe(it, today)}"
            } ?: "No birthday set"
            NavRow(Icons.Filled.ChildCare, child.name.ifBlank { "Unnamed" }, subtitle) {
                actions.navigate(SettingsPage.CHILD_EDIT, child.id)
            }
        }
        NavRow(Icons.Filled.Add, "Add a child", null) { actions.navigate(SettingsPage.CHILD_EDIT, null) }

        if (settings.children.isNotEmpty()) {
            SectionHeader("New recordings are assigned to")
            ChoiceRow("Nobody (choose later)", settings.defaultChildId == null) { actions.setDefaultChild(null) }
            settings.children.forEach { child ->
                ChoiceRow(child.name.ifBlank { "Unnamed" }, settings.defaultChildId == child.id) {
                    actions.setDefaultChild(child.id)
                }
            }
            Text(
                "Handy with one child, or when one is babbling most. You can always change it on each recording.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChildEditPage(child: Child?, actions: SettingsActions) {
    val haptics = rememberHaptics()
    var name by remember(child?.id) { mutableStateOf(child?.name ?: "") }
    var birthday by remember(child?.id) { mutableStateOf(child?.birthday) }
    var pickDate by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    Column(
        Modifier
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Text("Birthday", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                birthday?.let { BIRTHDAY_FORMAT.format(it) } ?: "Not set",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { pickDate = true }) { Text(if (birthday == null) "Choose" else "Change") }
            if (birthday != null) TextButton(onClick = { birthday = null }) { Text("Clear") }
        }
        Text(
            "Used only to show how old they were in each recording.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = name.isNotBlank(),
                onClick = {
                    haptics.confirm()
                    actions.saveChild(child?.id, name.trim(), birthday)
                },
            ) { Text("Save") }
            if (child != null) {
                OutlinedButton(onClick = { haptics.tap(); confirmRemove = true }) { Text("Remove") }
            }
        }
    }

    if (pickDate) {
        // The picker speaks UTC midnight; converting through the local zone would
        // shift the chosen day for anyone west of Greenwich.
        val state = rememberDatePickerState(
            initialSelectedDateMillis = birthday?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        birthday = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = state)
        }
    }

    if (confirmRemove && child != null) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove ${child.name.ifBlank { "this child" }}?") },
            text = {
                Text(
                    "They'll be taken off the list. Their recordings stay where they are, and " +
                        "will show as not assigned until you pick someone else for them."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    haptics.confirm()
                    confirmRemove = false
                    actions.removeChild(child.id)
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AppearancePage(settings: AppSettings, actions: SettingsActions) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        ChoiceRow("Automatic", settings.themeMode == ThemeMode.AUTOMATIC, "Follow the phone") {
            actions.setThemeMode(ThemeMode.AUTOMATIC)
        }
        ChoiceRow("Light", settings.themeMode == ThemeMode.LIGHT) { actions.setThemeMode(ThemeMode.LIGHT) }
        ChoiceRow("Dark", settings.themeMode == ThemeMode.DARK, "Kind to eyes during night feeds") {
            actions.setThemeMode(ThemeMode.DARK)
        }
    }
}

@Composable
private fun RecordingPage(settings: AppSettings, notificationsBlocked: Boolean, actions: SettingsActions) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        SwitchRow(
            "Keep the screen on while recording",
            "So it doesn't lock halfway through a babble",
            settings.keepScreenOn,
            actions.setKeepScreenOn,
        )
        SectionHeader("Stop automatically after")
        AppSettings.MAX_MINUTES_CHOICES.forEach { minutes ->
            ChoiceRow(
                if (minutes == 0) "No time limit" else "$minutes minutes",
                settings.maxRecordingMinutes == minutes,
                if (minutes == 0) "A forgotten recording keeps going until you stop it" else null,
            ) { actions.setMaxMinutes(minutes) }
        }
        if (notificationsBlocked) {
            SectionHeader("Notification")
            NavRow(
                null,
                "Show a notification while recording",
                "Off. Allow it to get a Stop button in the notification shade.",
                actions.allowNotifications,
            )
        }
    }
}

@Composable
private fun AboutPage() {
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Dahdoo", style = MaterialTheme.typography.headlineSmall)
        Text("Version ${BuildConfig.VERSION_NAME} · part of the QDVC suite", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Text(
            "Catch your little one's babbles the moment they happen: open the app, tap the big " +
                "button, tap again. Each recording is saved straight away; add who, what and " +
                "what they said whenever you have a moment.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        Text("Privacy", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(
            "Dahdoo has no network permission at all: it cannot reach the internet or your local " +
                "network, and nothing is ever uploaded. Android's own cloud backup is switched off " +
                "for it too. Your recordings live only in the folder you choose.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
