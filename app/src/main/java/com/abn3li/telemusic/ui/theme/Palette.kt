package com.abn3li.telemusic.ui.theme

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The app's colours by role, in a dark and a light version (Settings > Appearance). Screens read
 * them through [LocalPalette] instead of naming white or black, so one switch recolours them.
 * Now Playing stays on [DarkPalette] in both themes - like Apple Music, the player always sits
 * on the cover's own colours with white text.
 */
@Immutable
data class AppPalette(
    val isLight: Boolean,
    /** Page background. */
    val background: Color,
    /** Main text and icons. Faded copies of it (`ink.copy(alpha = …)`) are the secondary text. */
    val ink: Color,
    /** Cards, grouped settings, search fields, shortcut tiles. */
    val field: Color,
    /** A step up from [field]: icon squares inside a card, the selected segment. */
    val raised: Color,
    /** Thin dividers between rows. */
    val hairline: Color,
    /** The mini player's card. */
    val miniPlayer: Color,
    /** Pop-up dialogs. */
    val alert: Color,
    /** Settings-style pages: the page behind grouped cards, and the cards on it (see
     * [groupedPage]). In the light theme a grey page with white cards, like iOS Settings. */
    val groupedBackground: Color,
    val groupedCard: Color,
) {
    /** This palette for a Settings-style page of grouped cards. */
    fun groupedPage(): AppPalette = copy(background = groupedBackground, field = groupedCard)
}

val DarkPalette = AppPalette(
    isLight = false,
    background = Color.Black,
    ink = Color.White,
    field = Color(0xFF1C1C1E),
    raised = Color(0xFF2C2C2E),
    hairline = Color.White.copy(alpha = 0.12f),
    miniPlayer = Color(0xFF202023),
    alert = Color(0xFF252527),
    groupedBackground = Color.Black,
    groupedCard = Color(0xFF1C1C1E),
)

val LightPalette = AppPalette(
    isLight = true,
    background = Color.White,
    ink = Color.Black,
    // A step darker than iOS's F2F2F7, which nearly vanishes on a white page.
    field = Color(0xFFEBEBF0),
    raised = Color(0xFFDDDDE2),
    hairline = Color.Black.copy(alpha = 0.12f),
    miniPlayer = Color(0xFFF2F2F5),
    alert = Color(0xFFF7F7F9),
    groupedBackground = Color(0xFFF2F2F7),
    groupedCard = Color.White,
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }

/** Main text/icon colour of the current theme. */
internal val ink: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.ink

/** Page background of the current theme. */
internal val paper: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.background

/** What's on screen, for the system bars' colours (read by MainActivity). The full player and
 * the welcome pages are always dark, so the bars around them go dark too, whatever the app's
 * theme; a Settings-style page has its grey page colour behind the status bar. */
object SystemBarsState {
    var playerOpen by mutableStateOf(false)
    var onboardingOpen by mutableStateOf(false)
    // Settings-style pages on screen (a page fading out and the next fading in can overlap).
    var groupedPages by mutableIntStateOf(0)
}
