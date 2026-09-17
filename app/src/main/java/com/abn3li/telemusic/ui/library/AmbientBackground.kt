package com.abn3li.telemusic.ui.library

/**
 * Ambient-blur background + frosted-glass surface, shared by Library/detail/Settings screens.
 *
 * `Modifier.blur()` only blurs what's INSIDE that composable, not whatever is drawn behind it
 * (that would need real backdrop blur, e.g. the Haze library - not available here). The blobs
 * below blur themselves, which is exactly what's wanted (they ARE the thing being blurred); the
 * "frosted glass" surfaces on top are just a translucent fill + thin border, which reads as glass
 * without needing an actual backdrop-blur pass.
 *
 * Unlike ArtworkMeshBackdrop.kt's cached-texture approach (deliberately avoiding a live
 * Modifier.blur() there for per-frame GPU cost reasons), these blobs ARE a live blur - static
 * position/size so there's no per-frame relayout, but the compositor still re-does the blur pass
 * on every frame this screen is on screen. Traded off explicitly here per the pasted reference.
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.data.settings.AmbientColorSet
import com.abn3li.telemusic.data.settings.AppearanceStyle

internal object AmbientPalette {
    val Base = Color(0xFF0A0A0D)

    val GlassFill = Color.White.copy(alpha = 0.07f)
    val GlassFillSubtle = Color.White.copy(alpha = 0.04f)
    val GlassBorder = Color.White.copy(alpha = 0.10f)
}

/** The 5 blob colors for each selectable "Ambient colors" set (see the Settings screen's row
 * and AmbientColorSet's own doc) - always 5 entries, in the same TopStart/TopEnd/Center/
 * BottomStart/BottomEnd order [AmbientBlurBackground] draws them in. */
internal fun blobColorsFor(set: AmbientColorSet): List<Color> = when (set) {
    AmbientColorSet.SUNSET -> listOf(
        Color(0xFF6B3FA0), Color(0xFFC9793F), Color(0xFF1F7A5C), Color(0xFF9C3A4F), Color(0xFF2D4F8F)
    )
    AmbientColorSet.OCEAN -> listOf(
        Color(0xFF1B3A6B), Color(0xFF1F7A8C), Color(0xFF14524A), Color(0xFF2E2A6B), Color(0xFF34506B)
    )
    AmbientColorSet.FOREST -> listOf(
        Color(0xFF1F5C3F), Color(0xFF5C6B2F), Color(0xFF8C6A1F), Color(0xFF2F5C2F), Color(0xFF1F4A4A)
    )
    AmbientColorSet.BERRY -> listOf(
        Color(0xFF7A2A5C), Color(0xFF9C3A6B), Color(0xFF5C2A7A), Color(0xFF7A1F3A), Color(0xFF4A2A8C)
    )
}

/** The live value of the Settings screen's "Ambient colors" choice - see
 * MusicRepository.observeAmbientColorSet/setAmbientColorSet. */
@Composable
fun rememberAmbientColorSet(): AmbientColorSet {
    val app = LocalContext.current.applicationContext as TgMusicApp
    val set by app.musicRepository.observeAmbientColorSet().collectAsState()
    return set
}

/**
 * Draws the ambient blurred-glow background (soft colored blobs, blurred, darkened with a scrim
 * so foreground text/cards stay legible), then lays [content] on top of it. Root background for
 * every Library/detail/Settings screen, replacing the earlier flat BgColor fill.
 */
@Composable
fun AmbientBlurBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val colors = blobColorsFor(rememberAmbientColorSet())
    Box(modifier = modifier.fillMaxSize().background(AmbientPalette.Base)) {
        Box(modifier = Modifier.fillMaxSize().blur(70.dp)) {
            GlowBlob(colors[0], 320.dp, Alignment.TopStart, x = (-30).dp, y = (-40).dp)
            GlowBlob(colors[1], 300.dp, Alignment.TopEnd, x = 60.dp, y = 10.dp)
            GlowBlob(colors[2], 340.dp, Alignment.Center, x = 40.dp, y = 100.dp)
            GlowBlob(colors[3], 300.dp, Alignment.BottomStart, x = (-60).dp, y = 80.dp)
            GlowBlob(colors[4], 260.dp, Alignment.BottomEnd, x = 40.dp, y = (-60).dp)
        }

        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0.0f to Color(0xFF060609).copy(alpha = 0.55f),
                    0.35f to Color(0xFF060609).copy(alpha = 0.72f),
                    0.70f to Color(0xFF060609).copy(alpha = 0.88f),
                    1.0f to Color(0xFF060609).copy(alpha = 0.95f)
                )
            )
        )

        content()
    }
}

@Composable
private fun BoxScope.GlowBlob(color: Color, size: Dp, alignment: Alignment, x: Dp, y: Dp) {
    Box(
        modifier = Modifier
            .align(alignment)
            .offset(x = x, y = y)
            .size(size)
            .clip(CircleShape)
            .background(color)
    )
}

/** Frosted-glass surface used for cards/rows/sheets on top of [AmbientBlurBackground]. */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    fill: Color = AmbientPalette.GlassFill,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier
            .clip(shape)
            .background(fill)
            .border(1.dp, AmbientPalette.GlassBorder, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        content = content
    )
}

/** The live value of the Settings screen's "Appearance" choice (CLASSIC vs AMBIENT_BLUR) - see
 * MusicRepository.observeAppearanceStyle/setAppearanceStyle. Reads [LocalAppearanceStyle] (a
 * CompositionLocal AdaptiveScreenBackground provides once per screen) instead of subscribing to
 * the repository's StateFlow itself - adaptivePanelFill/adaptiveRow below are called from inside
 * lists (once per playlist row), and each independently calling collectAsState() on the same
 * flow meant N rows opened N separate collectors on every recomposition instead of sharing the
 * one screen-level subscription. Falls back to reading the flow directly only where no
 * AdaptiveScreenBackground is above it yet (defensive default, shouldn't normally happen).
 */
val LocalAppearanceStyle = compositionLocalOf { AppearanceStyle.CLASSIC }

@Composable
fun rememberAppearanceStyle(): AppearanceStyle = LocalAppearanceStyle.current

/**
 * Root background for Library/detail/Settings screens - the ambient blur when AMBIENT_BLUR is
 * selected, or a plain flat fill (the original "classic" look) otherwise. Callers no longer
 * choose a background directly; they call this so both styles stay switchable from one setting.
 * The ONE place in a screen that actually subscribes to observeAppearanceStyle() - everything
 * below it reads the CompositionLocal this provides instead (see LocalAppearanceStyle's own doc).
 */
@Composable
fun AdaptiveScreenBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val app = LocalContext.current.applicationContext as TgMusicApp
    val style by app.musicRepository.observeAppearanceStyle().collectAsState()
    CompositionLocalProvider(LocalAppearanceStyle provides style) {
        if (style == AppearanceStyle.AMBIENT_BLUR) {
            AmbientBlurBackground(modifier, content)
        } else {
            Box(modifier = modifier.fillMaxSize().background(BgColor), content = content)
        }
    }
}

/** The big rounded content panel's fill - translucent glass under AMBIENT_BLUR, the classic
 * solid CardColor otherwise. */
@Composable
fun adaptivePanelFill(): Color =
    if (rememberAppearanceStyle() == AppearanceStyle.AMBIENT_BLUR) AmbientPalette.GlassFillSubtle else CardColor

/** A playlist/settings row's own fill + optional border - glass+border under AMBIENT_BLUR, the
 * classic solid DividerColor (no border) otherwise. */
@Composable
fun Modifier.adaptiveRow(shape: Shape): Modifier =
    if (rememberAppearanceStyle() == AppearanceStyle.AMBIENT_BLUR) {
        this.background(AmbientPalette.GlassFill, shape).border(0.5.dp, AmbientPalette.GlassBorder, shape)
    } else {
        this.background(DividerColor, shape)
    }
