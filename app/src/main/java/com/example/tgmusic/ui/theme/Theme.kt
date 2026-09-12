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

// 1. Auxio Classic
private val AuxioDarkColorScheme = darkColorScheme(
    primary = Color(0xFF8B9DFF),
    onPrimary = Color(0xFF001358),
    primaryContainer = Color(0xFF283990),
    onPrimaryContainer = Color(0xFFDCDEFF),
    secondary = Color(0xFFC3C5DD),
    onSecondary = Color(0xFF2C2F42),
    secondaryContainer = Color(0xFF43455A),
    onSecondaryContainer = Color(0xFFE0E1F9),
    background = Color(0xFF121318),
    onBackground = Color(0xFFE3E1E9),
    surface = Color(0xFF121318),
    onSurface = Color(0xFFE3E1E9),
    surfaceVariant = Color(0xFF1F212B),
    onSurfaceVariant = Color(0xFFC6C5D0)
)

private val AuxioLightColorScheme = lightColorScheme(
    primary = Color(0xFF4155B5),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCDEFF),
    onPrimaryContainer = Color(0xFF00105C),
    secondary = Color(0xFF5A5D72),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0E1F9),
    onSecondaryContainer = Color(0xFF171A2C),
    background = Color(0xFFFEF8FF),
    onBackground = Color(0xFF1A1B21),
    surface = Color(0xFFFEF8FF),
    onSurface = Color(0xFF1A1B21),
    surfaceVariant = Color(0xFFE2E1EC),
    onSurfaceVariant = Color(0xFF45464F)
)

// 2. Sakura Pink 🌸 (Cute Soft Pink / Pastel Magenta)
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

// 3. Matcha Mint 🍵 (Cute Sage & Fresh Mint)
private val MatchaDarkColorScheme = darkColorScheme(
    primary = Color(0xFF9FD4AD),
    onPrimary = Color(0xFF07381A),
    primaryContainer = Color(0xFF224F32),
    onPrimaryContainer = Color(0xFFBAF0C8),
    secondary = Color(0xFFB7CCBB),
    onSecondary = Color(0xFF233428),
    secondaryContainer = Color(0xFF394B3D),
    onSecondaryContainer = Color(0xFFD3E8D6),
    background = Color(0xFF0E1510),
    onBackground = Color(0xFFE0E4DF),
    surface = Color(0xFF0E1510),
    onSurface = Color(0xFFE0E4DF),
    surfaceVariant = Color(0xFF1C271E),
    onSurfaceVariant = Color(0xFFC0C9C0)
)

private val MatchaLightColorScheme = lightColorScheme(
    primary = Color(0xFF386A48),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFBAF0C8),
    onPrimaryContainer = Color(0xFF00210E),
    secondary = Color(0xFF506354),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD3E8D6),
    onSecondaryContainer = Color(0xFF0E1F13),
    background = Color(0xFFF6FBF5),
    onBackground = Color(0xFF171D18),
    surface = Color(0xFFF6FBF5),
    onSurface = Color(0xFF171D18),
    surfaceVariant = Color(0xFFDDE5DC),
    onSurfaceVariant = Color(0xFF414942)
)

// 4. Sunset Peach 🍑 (Cute Warm Coral & Soft Cream)
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

// 5. Cloud Violet ☁️ (Cute Soft Lilac & Lavender)
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

// 6. AMOLED Neon ⚡ (Electric Cyan & Neon Magenta)
private val NeonDarkColorScheme = darkColorScheme(
    primary = Color(0xFF00E5FF),
    onPrimary = Color(0xFF00363F),
    primaryContainer = Color(0xFF004D5A),
    onPrimaryContainer = Color(0xFF80F4FF),
    secondary = Color(0xFFFF4081),
    onSecondary = Color(0xFF5E0028),
    secondaryContainer = Color(0xFF88003C),
    onSecondaryContainer = Color(0xFFFFB2C9),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE0F7FA),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFE0F7FA),
    surfaceVariant = Color(0xFF121A1C),
    onSurfaceVariant = Color(0xFFB0BEC5)
)

private val NeonLightColorScheme = lightColorScheme(
    primary = Color(0xFF00838F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB2EBF2),
    onPrimaryContainer = Color(0xFF002025),
    secondary = Color(0xFFC2185B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFD9E2),
    onSecondaryContainer = Color(0xFF3E001D),
    background = Color(0xFFF4FBFB),
    onBackground = Color(0xFF001F24),
    surface = Color(0xFFF4FBFB),
    onSurface = Color(0xFF001F24),
    surfaceVariant = Color(0xFFE0F2F1),
    onSurfaceVariant = Color(0xFF405A5D)
)

private fun getColorScheme(choice: AppColorScheme, darkTheme: Boolean): ColorScheme {
    return when (choice) {
        AppColorScheme.AUXIO -> if (darkTheme) AuxioDarkColorScheme else AuxioLightColorScheme
        AppColorScheme.SAKURA_PINK -> if (darkTheme) SakuraDarkColorScheme else SakuraLightColorScheme
        AppColorScheme.MATCHA_MINT -> if (darkTheme) MatchaDarkColorScheme else MatchaLightColorScheme
        AppColorScheme.SUNSET_PEACH -> if (darkTheme) PeachDarkColorScheme else PeachLightColorScheme
        AppColorScheme.CLOUD_VIOLET -> if (darkTheme) VioletDarkColorScheme else VioletLightColorScheme
        AppColorScheme.AMOLED_NEON -> if (darkTheme) NeonDarkColorScheme else NeonLightColorScheme
    }
}

@Composable
fun TgMusicTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    colorSchemeChoice: AppColorScheme = AppColorScheme.AUXIO,
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