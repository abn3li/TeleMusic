package com.abn3li.telemusic.ui.nowplaying

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect

/**
 * The player's open/close movement, shared by everything that moves with it: the player card,
 * the page behind it (it steps back and dims) and the dock (its player rides up into the card,
 * its tabs sink away). [expansion] is 0 closed, 1 open.
 */
@Stable
class PlayerTransition {
    val expansion: Animatable<Float, AnimationVector1D> = Animatable(0f)

    // Where the dock's player, its artwork and its title are on screen, as the dock last laid
    // them out (root px). Plain fields: only read when a movement starts.
    var dockPlayer: Rect? = null
    var dockArtwork: Rect? = null
    var dockTitle: Rect? = null

    /** Those places as they were when the player started opening: it opens out of them and
     * closes back into them, even if the dock moves meanwhile. Null while closed. */
    var origin by mutableStateOf<Origin?>(null)
        internal set

    class Origin(val player: Rect?, val artwork: Rect?, val title: Rect?)

    internal fun captureOrigin() {
        if (origin == null) origin = Origin(dockPlayer, dockArtwork, dockTitle)
    }

    internal fun clearOrigin() {
        origin = null
    }

    /** 0..1 within [from]..[to] of the movement - for parts that move in only part of it. */
    fun stage(from: Float, to: Float): Float =
        ((expansion.value - from) / (to - from)).coerceIn(0f, 1f)
}

/** Fast at first, settling gently: the shape of every staged part of the movement. */
internal fun easeOutCubic(t: Float): Float {
    val inv = 1f - t
    return 1f - inv * inv * inv
}
