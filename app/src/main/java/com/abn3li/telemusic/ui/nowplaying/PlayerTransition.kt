package com.abn3li.telemusic.ui.nowplaying

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.toSize

/**
 * The player's open/close movement, shared by everything that moves with it: the player itself
 * (it grows out of the mini player and shrinks back into it), the page behind it (it steps back
 * and dims) and the dock (the tabs drop away). [expansion] is 0 closed, 1 open.
 */
@Stable
class PlayerTransition {
    // Bounded: a hard flick settles at the end instead of running past it.
    val expansion: Animatable<Float, AnimationVector1D> = Animatable(0f).apply { updateBounds(0f, 1f) }

    /** Where the mini player sits, in root pixels; the player grows from here. Empty when there
     * is none (then the player slides up from the bottom edge instead). Read only while drawing,
     * so it is a plain field. */
    var miniBounds: Rect = Rect.Zero

    /** The open player's cover height in pixels (it fills the width from the top of the screen). */
    var coverHeightPx by mutableFloatStateOf(0f)

    /** The title + artist and the play button, in the mini player and in the open player (root
     * pixels, as they are at rest): the copies that fly between them start and end here. */
    var miniText by mutableStateOf(Rect.Zero)
    var miniPlay by mutableStateOf(Rect.Zero)
    var playerText by mutableStateOf(Rect.Zero)
    var playerPlay by mutableStateOf(Rect.Zero)

    /** While true the real title/artist (and play button) in both players are hidden: their
     * flying copies stand in for them. Set by the player. */
    var textFlying: () -> Boolean = { false }
    var playFlying: () -> Boolean = { false }

    /** The player's own frame, inside its parking and movement: the open player fills the screen
     * from the top left, so a part's place in this frame is its place on screen when open. */
    var playerFrame: LayoutCoordinates? = null

    /** How far down the closed player is parked (it is laid out there, off screen). */
    var closedShiftPx: Float = 0f

    /** A finger dragging the mini player up: pixels moved (down positive), then the release
     * speed in pixels per second. Set by the player. */
    var dragBy: (Float) -> Unit = {}
    var dragEnd: (Float) -> Unit = {}
}

/** The movement, for the open player's parts that report where they rest. */
val LocalPlayerTransition = staticCompositionLocalOf<PlayerTransition?> { null }

/** Records where this open-player part rests on screen once the player is open. Measured inside
 * the player's own frame, so it is right however the closed player is parked or shifted, and
 * only at rest, never mid-movement. */
fun Modifier.playerRestingSpot(transition: PlayerTransition?, store: PlayerTransition.(Rect) -> Unit): Modifier =
    if (transition == null) this else onGloballyPositioned { coordinates ->
        val e = transition.expansion.value
        if (e > 0.001f && e < 0.999f) return@onGloballyPositioned
        val frame = transition.playerFrame?.takeIf { it.isAttached }
        val rect = if (frame != null) {
            Rect(frame.localPositionOf(coordinates, Offset.Zero), coordinates.size.toSize())
        } else {
            val inRoot = Rect(coordinates.positionInRoot(), coordinates.size.toSize())
            if (e >= 0.999f) inRoot else inRoot.translate(0f, -transition.closedShiftPx)
        }
        transition.store(rect)
    }
