package com.abn3li.telemusic.ui.theme

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFFE64366)
private val Card = Color(0xFF1C1C1E)

// The app's one look: black backgrounds, dark grey cards, a single pink accent. No light mode
// and no theme picker.
private val AppColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3D1623),
    onPrimaryContainer = Color.White,
    secondary = Accent,
    onSecondary = Color.White,
    secondaryContainer = Card,
    onSecondaryContainer = Color.White,
    tertiary = Accent,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Card,
    onSurfaceVariant = Color(0xFF8E8D93),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Card,
    surfaceContainer = Card,
    surfaceContainerHigh = Card,
    surfaceContainerHighest = Color(0xFF2C2C2E),
    outline = Color(0xFF3A3A3C),
    outlineVariant = Color(0xFF2C2C2E),
    error = Color(0xFFFF453A),
    onError = Color.White
)

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun TgMusicTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AppColorScheme, shapes = AppShapes) {
        // No Android 12+ stretch overscroll: Samsung's renderer can segfault in
        // StretchEffect::getShader when a stretched list changes size mid-stretch (seen when
        // switching Sync filters), killing the whole process.
        CompositionLocalProvider(LocalOverscrollConfiguration provides null, content = content)
    }
}
