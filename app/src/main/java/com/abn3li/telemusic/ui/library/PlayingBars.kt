package com.abn3li.telemusic.ui.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.toSize
import com.abn3li.telemusic.ui.theme.SystemBarsState
import kotlin.math.sin

// One step every 33 ms: 30 fps. Each step repaints the whole screen (measured: +13% of a core at
// 30 fps, +20% at 60), and bars this small still look smooth at 30.
private const val STEP_NANOS = 33_000_000L
private val SPEEDS = floatArrayOf(7.1f, 9.3f, 6.2f)
private val PHASES = floatArrayOf(0f, 1.9f, 3.7f)
private val SCRIM = Color.Black.copy(alpha = 0.45f)

/**
 * Where a page draws its playing song's moving bars: a small layer above its list, outside the
 * content the frosted header and dock blur (frostedBackdropSource). Drawn inside the list, every
 * bar step changed that content and the glass was blurred again each frame - most of the cost.
 */
internal class PlayingBarsLayer {
    internal class Spot(val bounds: Rect, val color: Color)
    internal val spots = mutableStateMapOf<Any, Spot>()
    internal val seconds = mutableFloatStateOf(0f)
    internal var origin = Offset.Zero
}

internal val LocalPlayingBarsLayer = staticCompositionLocalOf<PlayingBarsLayer?> { null }

/** Put left of the page's frostedBackdropSource: the bars get a layer of their own above the content. */
internal fun Modifier.playingBarsLayer(layer: PlayingBarsLayer): Modifier =
    onGloballyPositioned { layer.origin = it.positionInRoot() }
        .graphicsLayer()
        .drawWithContent {
            drawContent()
            val t = layer.seconds.floatValue
            for (spot in layer.spots.values) {
                translate(spot.bounds.left - layer.origin.x, spot.bounds.top - layer.origin.y) {
                    drawPlayingBars(t, spot.color, spot.bounds.size)
                }
            }
        }
        .graphicsLayer()

/**
 * The playing song's mark in a row: [scrim] shades the artwork under the bars (null: bars alone,
 * in place of a track number). The bars move only while [playing], while the row is composed (on
 * screen) and while the full player isn't covering the list; paused, they rest where they stopped.
 */
@Composable
internal fun BoxScope.PlayingMark(playing: Boolean, color: Color, scrim: Shape?) {
    val layer = LocalPlayingBarsLayer.current
    val shaded = if (scrim != null) Modifier.matchParentSize().clip(scrim).background(SCRIM) else Modifier.matchParentSize()
    if (layer == null) {
        val seconds = remember { mutableFloatStateOf(0f) }
        BarsTicker(playing, seconds)
        Canvas(shaded) { drawPlayingBars(seconds.floatValue, color, size) }
        return
    }
    val key = remember { Any() }
    DisposableEffect(layer) { onDispose { layer.spots.remove(key) } }
    Box(shaded.onGloballyPositioned {
        val bounds = Rect(it.positionInRoot(), it.size.toSize())
        if (layer.spots[key]?.bounds != bounds) layer.spots[key] = PlayingBarsLayer.Spot(bounds, color)
    })
    BarsTicker(playing, layer.seconds)
}

@Composable
private fun BarsTicker(playing: Boolean, seconds: MutableFloatState) {
    if (playing && !SystemBarsState.playerOpen) LaunchedEffect(seconds) {
        val resumeFrom = seconds.floatValue
        var origin = -1L
        var last = 0L
        while (true) withFrameNanos { now ->
            if (origin < 0) origin = now
            if (now - last >= STEP_NANOS) {
                last = now
                seconds.floatValue = resumeFrom + (now - origin) / 1_000_000_000f
            }
        }
    }
}

/** Three bars growing from the middle of [area], at time [t] seconds. */
private fun DrawScope.drawPlayingBars(t: Float, color: Color, area: Size) {
    val barWidth = area.minDimension * 0.1f
    val gap = barWidth * 0.9f
    val maxHalf = area.minDimension * 0.22f
    var x = (area.width - 3 * barWidth - 2 * gap) / 2
    val middle = area.height / 2
    for (i in 0..2) {
        val level = 0.3f + 0.7f * (0.5f + 0.5f * sin(t * SPEEDS[i] + PHASES[i]))
        val half = maxOf(maxHalf * level, barWidth / 2)
        drawRoundRect(color, Offset(x, middle - half), Size(barWidth, half * 2), CornerRadius(barWidth / 2))
        x += barWidth + gap
    }
}
