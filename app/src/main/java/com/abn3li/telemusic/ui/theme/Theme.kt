package com.abn3li.telemusic.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// One fixed, monochrome dark look for the whole app - no light mode, no per-user theme picker.
// Primary/secondary (used by filled buttons, the selected tab chip, Play/Pause, etc.) are
// white-on-black so every "main action" reads as a solid white pill with black text/icon.
private val MonochromeDarkColorScheme = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    primaryContainer = Color.White,
    onPrimaryContainer = Color.Black,
    secondary = Color.White,
    onSecondary = Color.Black,
    secondaryContainer = Color.White,
    onSecondaryContainer = Color.Black,
    background = Color(0xFF000000),
    onBackground = Color.White,
    surface = Color(0xFF000000),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1C1C1E),
    onSurfaceVariant = Color(0xFFB3B3B3),
    outline = Color(0xFF3A3A3C)
)

@Composable
fun TgMusicTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MonochromeDarkColorScheme,
        shapes = AppShapes,
        content = content
    )
}
