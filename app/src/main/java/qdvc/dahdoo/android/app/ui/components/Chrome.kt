package qdvc.dahdoo.android.app.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import qdvc.dahdoo.android.app.model.Tab

/**
 * Screens nested inside the outer Scaffold sit above the bottom bar, which owns
 * the bottom inset. Each inset edge has exactly one owner (spec B2), so nested
 * Scaffolds take the top only. A Scaffold that does not use this is announcing
 * that it is full-screen (Settings).
 */
val topOnlyInsets: WindowInsets
    @Composable get() = WindowInsets.systemBars.only(WindowInsetsSides.Top)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String,
    /** Must be the same lambda the system back button runs, so they never diverge. */
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    val haptics = rememberHaptics()
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            if (onBack != null) {
                // Only the arrow buzzes; system back has its own feedback.
                IconButton(onClick = { haptics.tap(); onBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

/** [onSelect] returns whether anything changed, which decides the haptic. */
@Composable
fun BottomBar(current: Tab, onSelect: (Tab) -> Boolean) {
    val haptics = rememberHaptics()
    // surface + no tonal elevation, so the bar matches the top bar and one colour
    // matches both system bars.
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        Tab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == current,
                onClick = { if (onSelect(tab)) haptics.tap() },
                icon = {
                    Icon(
                        imageVector = when (tab) {
                            Tab.RECORD -> Icons.Filled.Mic
                            Tab.RECORDINGS -> Icons.Filled.GraphicEq
                        },
                        contentDescription = null,
                    )
                },
                label = {
                    Text(
                        when (tab) {
                            Tab.RECORD -> "Record"
                            Tab.RECORDINGS -> "Recordings"
                        }
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                    indicatorColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}
