package com.abn3li.telemusic.ui.nowplaying

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.Stable

/**
 * The player's open/close movement, shared by everything that moves with it: the player itself
 * (it slides up from the bottom of the screen), the page behind it (it steps back and dims) and
 * the dock (it stops drawing once it is covered). [expansion] is 0 closed, 1 open.
 */
@Stable
class PlayerTransition {
    // Bounded: a hard flick settles at the end instead of running past it.
    val expansion: Animatable<Float, AnimationVector1D> = Animatable(0f).apply { updateBounds(0f, 1f) }
}
