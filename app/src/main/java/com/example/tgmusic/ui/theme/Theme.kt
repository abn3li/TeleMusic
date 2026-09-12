package com.example.tgmusic.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.tgmusic.data.settings.AppThemeMode

// One fixed, monochrome look for the whole app - no accent color, no per-user color scheme
// picker. Primary/secondary (used by filled buttons, the selected tab chip, Play/Pause, etc.)
// are white-on-black in dark mode so every "main action" reads as a solid white pill with black
// text/icon, and the inverse (black-on-white) in light mode for the same contrast logic mirrored.
private val MonochromeDarkColorScheme = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    primaryContainer = Color.White,
    onPrimaryContainer = Color.Black,
    secondary = Color.White,
    onSecondary = Color.Black,
    secondaryContainer = Color.White,
    onSecondaryContainer = Color.Black,
    background = Color(0xFF121212),
    onBackground = Color.White,
    surface = Color(0xFF121212),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1C1C1E),
    onSurfaceVariant = Color(0xFFB3B3B3),
    outline = Color(0xFF3A3A3C)
)

private val MonochromeLightColorScheme = lightColorScheme(
    primary = Color.Black,
    onPrimary = Color.White,
    primaryContainer = Color.Black,
    onPrimaryContainer = Color.White,
    secondary = Color.Black,
    onSecondary = Color.White,
    secondaryContainer = Color.Black,
    onSecondaryContainer = Color.White,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFF2F2F7),
    onSurfaceVariant = Color(0xFF6C6C70),
    outline = Color(0xFFD1D1D6)
)

@Composable
fun TgMusicTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        AppThemeMode.SYSTEM -> systemDark
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK, AppThemeMode.AMOLED -> true
    }
    val isAmoled = themeMode == AppThemeMode.AMOLED

    val baseColorScheme = if (isDark) MonochromeDarkColorScheme else MonochromeLightColorScheme
    val finalColorScheme = if (isAmoled) {
        baseColorScheme.copy(background = Color.Black, surface = Color.Black)
    } else {
        baseColorScheme
    }

    MaterialTheme(
        colorScheme = finalColorScheme,
        shapes = AppShapes,
        content = content
    )
}
