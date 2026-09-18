package com.abn3li.telemusic.ui.library

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Library/YouTube top bar switcher - the pill toggle from the reference design (Ivorisnoob/Koda's
 * MusicVideoToggle), adapted here: same "liquid" thumb (a fast-spring leading edge racing ahead of
 * a slower trailing edge, stretching the pill mid-slide instead of just translating it), restyled
 * to this app's own dark/teal palette instead of MaterialTheme.colorScheme (this codebase uses
 * plain hardcoded Color values throughout, not Material3 dynamic color), and with
 * MaterialTheme.motionScheme swapped for fixed spring() specs - that API is Material3 Expressive
 * (1.4+), newer than this project's BOM.
 *
 * Unlike Koda's toggle (one screen whose content swaps in place, so the SAME toggle instance
 * survives the mode switch), Library and YouTube are two separate nav destinations here - tapping
 * the inactive segment navigates away entirely, so there's no need to hoist this state above a
 * screen swap. Each screen just renders its own copy already snapped to the correct side.
 */
@Stable
private class LibrarySourceToggleState(initialShowYoutube: Boolean) {
    val fastEdge = Animatable(if (initialShowYoutube) 1f else 0f)
    val slowEdge = Animatable(if (initialShowYoutube) 1f else 0f)
}

@Composable
private fun rememberLibrarySourceToggleState(showYoutube: Boolean): LibrarySourceToggleState {
    val state = remember { LibrarySourceToggleState(showYoutube) }
    LaunchedEffect(showYoutube) {
        val target = if (showYoutube) 1f else 0f
        launch { state.fastEdge.animateTo(target, spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessHigh)) }
        launch { state.slowEdge.animateTo(target, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)) }
    }
    return state
}

/**
 * @param showYoutube which segment reads as "selected" - true on the YouTube screen, false on
 * Library. There's no shared boolean state to flip in place: [onSelectLibrary]/[onSelectYoutube]
 * navigate to the other screen instead.
 */
@Composable
fun LibrarySourceToggle(
    showYoutube: Boolean,
    onSelectLibrary: () -> Unit,
    onSelectYoutube: () -> Unit,
    modifier: Modifier = Modifier,
    trackHeight: Dp = 38.dp,
    segmentWidth: Dp = 34.dp,
    segmentHeight: Dp = 30.dp,
    trackColor: Color = adaptivePanelFill(),
    activeColor: Color = AccentGreen
) {
    val state = rememberLibrarySourceToggleState(showYoutube)

    Surface(
        modifier = modifier.height(trackHeight),
        shape = CircleShape,
        color = trackColor
    ) {
        Box(modifier = Modifier.padding(4.dp)) {
            // Overshoot clamped so the stretched thumb squashes against the track ends instead
            // of poking outside them.
            val start = minOf(state.fastEdge.value, state.slowEdge.value).coerceAtLeast(0f)
            val end = maxOf(state.fastEdge.value, state.slowEdge.value).coerceAtMost(1f)
            Box(
                modifier = Modifier
                    .offset(x = segmentWidth * start)
                    .width(segmentWidth * (1f + end - start))
                    .height(segmentHeight)
                    .clip(CircleShape)
                    .background(activeColor)
            )
            Row {
                ToggleSegment(
                    icon = Icons.Rounded.MusicNote,
                    contentDescription = "Library",
                    selected = !showYoutube,
                    segmentWidth = segmentWidth,
                    segmentHeight = segmentHeight,
                    onClick = { if (showYoutube) onSelectLibrary() }
                )
                ToggleSegment(
                    icon = Icons.Rounded.VideoLibrary,
                    contentDescription = "YouTube",
                    selected = showYoutube,
                    segmentWidth = segmentWidth,
                    segmentHeight = segmentHeight,
                    onClick = { if (!showYoutube) onSelectYoutube() }
                )
            }
        }
    }
}

@Composable
private fun ToggleSegment(
    icon: ImageVector,
    contentDescription: String,
    selected: Boolean,
    segmentWidth: Dp,
    segmentHeight: Dp,
    onClick: () -> Unit
) {
    val tint by animateColorAsState(
        targetValue = if (selected) Color.White else TextSecondary,
        animationSpec = tween(durationMillis = 200),
        label = "sourceToggleSegmentTint"
    )
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.8f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "sourceToggleSegmentScale"
    )

    Box(
        modifier = Modifier
            .size(width = segmentWidth, height = segmentHeight)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier
                .size(18.dp)
                .scale(scale)
        )
    }
}
