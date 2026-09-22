package com.abn3li.telemusic.ui.nowplaying

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Airplay
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.abn3li.telemusic.data.local.displayArtwork
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Space a floating MiniPlayer takes at the bottom of a screen without the nav bar, incl. margin. */
val MiniPlayerHeight: Dp = 68.dp

/** Bottom padding for scrollable lists so content clears the floating MiniPlayer. */
val LocalMiniPlayerInset = compositionLocalOf { 0.dp }

/** "Play next" for a library song id, provided at the nav root; null where there's no player. */
val LocalPlayNext = staticCompositionLocalOf<((Long) -> Unit)?> { null }

internal val MiniPlayerBarHeight: Dp = 56.dp
internal val MiniPlayerSideMargin: Dp = 12.dp
internal val MiniPlayerCorner: Dp = 16.dp

private val MiniPlayerColor = Color(0xF2202023)
private val SwipeEasing = CubicBezierEasing(0.25f, 1f, 0.5f, 1f)
private const val SKIP_DISTANCE_FRACTION = 0.18f
private const val SKIP_VELOCITY = 1400f

/**
 * Compact player bar. Tap or drag it up to open the full player; swipe the title sideways to
 * skip (left = next, right = previous).
 */
@Composable
fun MiniPlayer(
    state: NowPlayingUiState,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onClick: () -> Unit,
    onExpandDrag: (Float) -> Unit,
    onExpandDragEnd: (Float) -> Unit,
    bottomOffset: Dp = 84.dp,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = state.song != null,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier.padding(bottom = bottomOffset)
    ) {
        val song = state.song ?: return@AnimatedVisibility
        val context = LocalContext.current
        val haptics = LocalHapticFeedback.current
        val scope = rememberCoroutineScope()
        val latestState by rememberUpdatedState(state)

        var titleWidth by remember { mutableIntStateOf(0) }
        val titleOffset = remember { Animatable(0f) }
        var pendingDirection by remember { mutableIntStateOf(0) }

        // After a swipe-skip the new title slides in from the opposite side.
        LaunchedEffect(song.telegramMessageId) {
            if (pendingDirection == 0) return@LaunchedEffect
            titleOffset.snapTo(-pendingDirection * titleWidth.toFloat())
            titleOffset.animateTo(0f, tween(260, easing = SwipeEasing))
            pendingDirection = 0
        }

        val verticalDrag = rememberDraggableState { delta -> onExpandDrag(delta) }
        val horizontalDrag = rememberDraggableState { delta ->
            if (pendingDirection != 0) return@rememberDraggableState
            val limit = titleWidth * 0.35f
            scope.launch { titleOffset.snapTo((titleOffset.value + delta).coerceIn(-limit, limit)) }
        }

        Surface(
            shape = RoundedCornerShape(MiniPlayerCorner),
            color = MiniPlayerColor,
            border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.08f)),
            shadowElevation = 10.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MiniPlayerSideMargin)
                .height(MiniPlayerBarHeight)
                .draggable(
                    state = verticalDrag,
                    orientation = Orientation.Vertical,
                    onDragStopped = { velocity -> onExpandDragEnd(velocity) }
                )
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
        ) {
            Row(
                Modifier.fillMaxSize().padding(start = 6.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(20.dp))
                    if (!song.displayArtwork.isNullOrEmpty()) {
                        AsyncImage(
                            model = song.displayArtwork,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(start = 12.dp, end = 6.dp)
                        .clipToBounds()
                        .onSizeChanged { titleWidth = it.width }
                        .draggable(
                            state = horizontalDrag,
                            orientation = Orientation.Horizontal,
                            onDragStopped = { velocity ->
                                val offset = titleOffset.value
                                val passed = abs(offset) > titleWidth * SKIP_DISTANCE_FRACTION || abs(velocity) > SKIP_VELOCITY
                                val direction = when {
                                    abs(velocity) > SKIP_VELOCITY -> if (velocity < 0f) -1 else 1
                                    offset < 0f -> -1
                                    else -> 1
                                }
                                val allowed = if (direction < 0) latestState.hasNext else latestState.hasPrevious
                                if (!passed || !allowed) {
                                    titleOffset.animateTo(0f, tween(160, easing = SwipeEasing))
                                    return@draggable
                                }
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                pendingDirection = direction
                                titleOffset.animateTo(direction * titleWidth.toFloat(), tween(120, easing = SwipeEasing))
                                if (direction < 0) onNext() else onPrevious()
                                // Repeat-one (or a slow load) can leave the same song showing;
                                // bring the title back rather than leaving it off-screen.
                                delay(1200)
                                if (pendingDirection != 0) {
                                    pendingDirection = 0
                                    titleOffset.animateTo(0f, tween(260, easing = SwipeEasing))
                                }
                            }
                        ),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = song.title,
                        color = Color.White,
                        fontSize = 15.5.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.graphicsLayer {
                            translationX = titleOffset.value
                            alpha = 1f - (abs(titleOffset.value) / titleWidth.coerceAtLeast(1)).coerceIn(0f, 0.6f)
                        }
                    )
                }

                val loading = state.loadingSongId == song.telegramMessageId || state.isBuffering
                Box(
                    Modifier
                        .size(40.dp)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onPlayPause()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (loading) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    } else {
                        AnimatedContent(
                            targetState = state.isPlaying,
                            transitionSpec = {
                                (scaleIn(initialScale = 0.3f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.3f) + fadeOut())
                            },
                            label = "miniPlayPause"
                        ) { playing ->
                            Icon(
                                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (playing) "Pause" else "Play",
                                tint = Color.White,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.width(4.dp))
                val outputName = rememberExternalOutputName()
                Box(
                    Modifier
                        .size(40.dp)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            openSystemOutputSwitcher(context)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (outputName != null) Icons.Rounded.Headphones else Icons.Rounded.Airplay,
                        contentDescription = "Audio output",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(if (outputName != null) 24.dp else 22.dp)
                    )
                }
            }
        }
    }
}

