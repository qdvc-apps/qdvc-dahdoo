package qdvc.dahdoo.android.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat
import qdvc.dahdoo.android.app.model.ThemeMode

@Composable
fun resolveDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.AUTOMATIC -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/** The colour of the button while it is recording: unmistakably "on air". */
val RecordingRed = Color(0xFFE5484D)

// Warm cream and coral for daytime; cocoa and soft coral for night feeds.
// The light background must match res/values/colors.xml launch_background and
// the dark one res/values-night/colors.xml, or the launch window flashes.
private val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFFF26D5B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD3),
    onPrimaryContainer = Color(0xFF3B0A04),
    secondary = Color(0xFF3E9C8F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDEDE7),
    onSecondaryContainer = Color(0xFF00201C),
    background = Color(0xFFFFF8F2),
    onBackground = Color(0xFF3B2A26),
    surface = Color(0xFFFFF8F2),
    onSurface = Color(0xFF3B2A26),
    surfaceVariant = Color(0xFFFBE9DF),
    onSurfaceVariant = Color(0xFF7A625B),
    surfaceContainer = Color(0xFFFBEFE8),
    surfaceContainerHigh = Color(0xFFF7E6DD),
    outline = Color(0xFFD9BFB4),
    outlineVariant = Color(0xFFEBD6CC),
    error = Color(0xFFC62828),
    onError = Color.White,
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFFF9A8B),
    onPrimary = Color(0xFF4A120A),
    primaryContainer = Color(0xFF6B2A20),
    onPrimaryContainer = Color(0xFFFFDAD3),
    secondary = Color(0xFF8ED3C7),
    onSecondary = Color(0xFF00382F),
    secondaryContainer = Color(0xFF1F4F47),
    onSecondaryContainer = Color(0xFFCDEDE7),
    background = Color(0xFF1E1715),
    onBackground = Color(0xFFF6E6E0),
    surface = Color(0xFF1E1715),
    onSurface = Color(0xFFF6E6E0),
    surfaceVariant = Color(0xFF3A2C28),
    onSurfaceVariant = Color(0xFFCDB4AC),
    surfaceContainer = Color(0xFF2A201D),
    surfaceContainerHigh = Color(0xFF342925),
    outline = Color(0xFF5E4A44),
    outlineVariant = Color(0xFF473733),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF3B0A04),
)

@Composable
fun DahdooTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // Top bars and the bottom bar all use `surface`, so one colour matches
            // both system bars (spec A6/B4). Targeting SDK 34 these setters still
            // apply; see Cursed Knowledge before raising targetSdk to 35.
            val window = (view.context as Activity).window
            val bar = colors.surface.toArgb()
            @Suppress("DEPRECATION")
            window.statusBarColor = bar
            @Suppress("DEPRECATION")
            window.navigationBarColor = bar
            val controller = WindowInsetsControllerCompat(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }
    MaterialTheme(colorScheme = colors) {
        // Plain Box/Column content outside any Surface would otherwise draw text
        // in black, even in dark mode (Cursed Knowledge: android-ui).
        CompositionLocalProvider(LocalContentColor provides colors.onSurface, content = content)
    }
}
