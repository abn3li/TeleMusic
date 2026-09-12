package com.example.tgmusic.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.tgmusic.data.settings.AppColorScheme
import com.example.tgmusic.data.settings.AppThemeMode

// 1. Catppuccin (Mocha dark / Latte light) - https://catppuccin.com, Mauve + Blue accents
private val CatppuccinDarkColorScheme = darkColorScheme(
    primary = Color(0xFFCBA6F7),          // Mauve
    onPrimary = Color(0xFF1E1E2E),        // Base
    primaryContainer = Color(0xFF313244), // Surface0
    onPrimaryContainer = Color(0xFFCBA6F7),
    secondary = Color(0xFF89B4FA),        // Blue
    onSecondary = Color(0xFF1E1E2E),
    secondaryContainer = Color(0xFF313244),
    onSecondaryContainer = Color(0xFF89B4FA),
    background = Color(0xFF1E1E2E),       // Base
    onBackground = Color(0xFFCDD6F4),     // Text
    surface = Color(0xFF1E1E2E),
    onSurface = Color(0xFFCDD6F4),
    surfaceVariant = Color(0xFF313244),   // Surface0
    onSurfaceVariant = Color(0xFFA6ADC8)  // Subtext0
)

private val CatppuccinLightColorScheme = lightColorScheme(
    primary = Color(0xFF8839EF),          // Mauve
    onPrimary = Color(0xFFEFF1F5),        // Base
    primaryContainer = Color(0xFFE9DFFC),
    onPrimaryContainer = Color(0xFF3D0A6B),
    secondary = Color(0xFF1E66F5),        // Blue
    onSecondary = Color(0xFFEFF1F5),
    secondaryContainer = Color(0xFFD9E4FE),
    onSecondaryContainer = Color(0xFF0A2A6B),
    background = Color(0xFFEFF1F5),       // Base
    onBackground = Color(0xFF4C4F69),     // Text
    surface = Color(0xFFEFF1F5),
    onSurface = Color(0xFF4C4F69),
    surfaceVariant = Color(0xFFCCD0DA),   // Surface0
    onSurfaceVariant = Color(0xFF6C6F85)  // Subtext0
)

// 2. Pink (Soft Pastel Rose / Magenta)
private val SakuraDarkColorScheme = darkColorScheme(
    primary = Color(0xFFFFB2C9),
    onPrimary = Color(0xFF5E112A),
    primaryContainer = Color(0xFF7D2941),
    onPrimaryContainer = Color(0xFFFFD9E2),
    secondary = Color(0xFFE2BDC7),
    onSecondary = Color(0xFF422931),
    secondaryContainer = Color(0xFF5B3F48),
    onSecondaryContainer = Color(0xFFFFD9E2),
    background = Color(0xFF1D1014),
    onBackground = Color(0xFFF0DEE1),
    surface = Color(0xFF1D1014),
    onSurface = Color(0xFFF0DEE1),
    surfaceVariant = Color(0xFF332026),
    onSurfaceVariant = Color(0xFFD6C2C6)
)

private val SakuraLightColorScheme = lightColorScheme(
    primary = Color(0xFF984061),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD9E2),
    onPrimaryContainer = Color(0xFF3E001D),
    secondary = Color(0xFF755660),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFD9E2),
    onSecondaryContainer = Color(0xFF2B151D),
    background = Color(0xFFFFF8F8),
    onBackground = Color(0xFF22191C),
    surface = Color(0xFFFFF8F8),
    onSurface = Color(0xFF22191C),
    surfaceVariant = Color(0xFFF3DEDF),
    onSurfaceVariant = Color(0xFF524346)
)

// 3. Peach (Warm Coral & Soft Cream)
private val PeachDarkColorScheme = darkColorScheme(
    primary = Color(0xFFFFB59D),
    onPrimary = Color(0xFF561F0A),
    primaryContainer = Color(0xFF73341E),
    onPrimaryContainer = Color(0xFFFFDBCD),
    secondary = Color(0xFFE7BEB2),
    onSecondary = Color(0xFF442A22),
    secondaryContainer = Color(0xFF5D4037),
    onSecondaryContainer = Color(0xFFFFDBCD),
    background = Color(0xFF1C110D),
    onBackground = Color(0xFFF1DFD9),
    surface = Color(0xFF1C110D),
    onSurface = Color(0xFFF1DFD9),
    surfaceVariant = Color(0xFF31211C),
    onSurfaceVariant = Color(0xFFD8C2BB)
)

private val PeachLightColorScheme = lightColorScheme(
    primary = Color(0xFF904B32),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDBCD),
    onPrimaryContainer = Color(0xFF3A0B00),
    secondary = Color(0xFF77574E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDBCD),
    onSecondaryContainer = Color(0xFF2C160F),
    background = Color(0xFFFFF8F6),
    onBackground = Color(0xFF231A16),
    surface = Color(0xFFFFF8F6),
    onSurface = Color(0xFF231A16),
    surfaceVariant = Color(0xFFF5DED7),
    onSurfaceVariant = Color(0xFF53433E)
)

// 4. Violet (Soft Lilac & Lavender)
private val VioletDarkColorScheme = darkColorScheme(
    primary = Color(0xFFD4BBFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC),
    onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458),
    onSecondaryContainer = Color(0xFFE8DEF8),
    background = Color(0xFF141218),
    onBackground = Color(0xFFE6E1E9),
    surface = Color(0xFF141218),
    onSurface = Color(0xFFE6E1E9),
    surfaceVariant = Color(0xFF231F2A),
    onSurfaceVariant = Color(0xFFCAC4D0)
)

private val VioletLightColorScheme = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF625B71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8DEF8),
    onSecondaryContainer = Color(0xFF1D192B),
    background = Color(0xFFFEF7FF),
    onBackground = Color(0xFF1D1B20),
    surface = Color(0xFFFEF7FF),
    onSurface = Color(0xFF1D1B20),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F)
)

private fun getColorScheme(choice: AppColorScheme, darkTheme: Boolean): ColorScheme {
    return when (choice) {
        AppColorScheme.CATPPUCCIN -> if (darkTheme) CatppuccinDarkColorScheme else CatppuccinLightColorScheme
        AppColorScheme.PINK -> if (darkTheme) SakuraDarkColorScheme else SakuraLightColorScheme
        AppColorScheme.PEACH -> if (darkTheme) PeachDarkColorScheme else PeachLightColorScheme
        AppColorScheme.VIOLET -> if (darkTheme) VioletDarkColorScheme else VioletLightColorScheme
    }
}

@Composable
fun TgMusicTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    colorSchemeChoice: AppColorScheme = AppColorScheme.CATPPUCCIN,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        AppThemeMode.SYSTEM -> systemDark
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK, AppThemeMode.AMOLED -> true
    }
    val isAmoled = themeMode == AppThemeMode.AMOLED

    val baseColorScheme = getColorScheme(colorSchemeChoice, isDark)
    val finalColorScheme = if (isAmoled) {
        baseColorScheme.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceVariant = Color(0xFF121212),
            onBackground = Color(0xFFF0F0F0),
            onSurface = Color(0xFFF0F0F0)
        )
    } else {
        baseColorScheme
    }

    MaterialTheme(
        colorScheme = finalColorScheme,
        shapes = AppShapes,
        content = content
    )
}