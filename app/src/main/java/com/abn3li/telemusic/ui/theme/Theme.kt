package com.abn3li.telemusic.ui.theme

import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFFE64366)
private val Card = Color(0xFF1C1C1E)

// Dark: black backgrounds, dark grey cards, a single pink accent. The light scheme below is the
// same app on white (Settings > Appearance); see Palette.kt for the colours screens use.
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

private val LightColorScheme = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE3EA),
    onPrimaryContainer = Color.Black,
    secondary = Accent,
    onSecondary = Color.White,
    secondaryContainer = LightPalette.field,
    onSecondaryContainer = Color.Black,
    tertiary = Accent,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = LightPalette.field,
    onSurfaceVariant = Color(0xFF8E8D93),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = LightPalette.field,
    surfaceContainer = LightPalette.field,
    surfaceContainerHigh = LightPalette.field,
    surfaceContainerHighest = LightPalette.raised,
    outline = Color(0xFFC6C6C8),
    outlineVariant = Color(0xFFE5E5EA),
    error = Color(0xFFFF3B30),
    onError = Color.White
)

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun TgMusicTheme(light: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (light) LightColorScheme else AppColorScheme, shapes = AppShapes) {
        // No Android 12+ stretch overscroll: Samsung's renderer can segfault in
        // StretchEffect::getShader when a stretched list changes size mid-stretch (seen when
        // switching Sync filters), killing the whole process.
        CompositionLocalProvider(
            LocalOverscrollConfiguration provides null,
            LocalPalette provides if (light) LightPalette else DarkPalette,
            content = content
        )
    }
}

/** The full Now Playing player and everything opened from it: always dark, whichever theme the
 * rest of the app uses - it sits on the cover's own colours with white text, as Apple Music's does. */
@Composable
fun DarkPlayerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AppColorScheme, shapes = AppShapes) {
        CompositionLocalProvider(LocalPalette provides DarkPalette, content = content)
    }
}
