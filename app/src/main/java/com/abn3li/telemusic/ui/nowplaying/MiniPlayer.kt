package com.abn3li.telemusic.ui.nowplaying

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.abn3li.telemusic.data.local.SongEntity
import com.abn3li.telemusic.ui.library.AccentGreen

/** Rendered height of the compact floating capsule MiniPlayer, incl. vertical margins. */
val MiniPlayerHeight: Dp = 62.dp

/**
 * Bottom padding for scrollable lists so content clears the floating MiniPlayer.
 */
val LocalMiniPlayerInset = compositionLocalOf { 0.dp }

/**
 * Compact Capsule Mini-Player (height: ~48px, fully rounded pill / border-radius 9999px).
 */
@Composable
fun MiniPlayer(
    state: NowPlayingUiState,
    playbackProgress: PlaybackProgress,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onClick: () -> Unit,
    bottomOffset: Dp = 84.dp,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = state.song != null,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
        modifier = modifier.padding(bottom = bottomOffset)
    ) {
        val song = state.song ?: return@AnimatedVisibility
        val artworkColor = rememberArtworkColor(song.albumArtUrl)

        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = artworkColor ?: MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
            shadowElevation = 10.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .height(48.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 6.dp, end = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Circular 36px album art badge
                    Surface(
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                    ) {
                        if (!song.albumArtUrl.isNullOrEmpty()) {
                            AsyncImage(
                                model = song.albumArtUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.MusicNote,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = Color.White
                                )
                            }
                        }
                    }

                    Spacer(Modifier.width(10.dp))

                    // Title & Artist
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = song.title,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(1.dp))
                        Text(
                            text = song.artist,
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.65f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    // Controls
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (state.loadingSongId == song.telegramMessageId || state.isBuffering) {
                            Box(
                                modifier = Modifier.size(30.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = onPlayPause
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Crossfade(
                                    targetState = state.isPlaying,
                                    animationSpec = tween(durationMillis = 150),
                                    label = "miniPlayPause"
                                ) { playing ->
                                    Icon(
                                        imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = if (playing) "Pause" else "Play",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = onNext
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.SkipNext,
                                contentDescription = "Next",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // Embedded 2px progress bar at bottom edge
                MiniPlayerProgressBar(
                    progress = playbackProgress,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 20.dp)
                )
            }
        }
    }
}

@Composable
private fun MiniPlayerProgressBar(
    progress: PlaybackProgress,
    modifier: Modifier = Modifier
) {
    if (progress.durationMs > 0L) {
        val targetProgress = (progress.currentPositionMs.toFloat() / progress.durationMs.toFloat()).coerceIn(0f, 1f)
        val progressFloat by animateFloatAsState(
            targetValue = targetProgress,
            animationSpec = tween(durationMillis = 300, easing = LinearEasing),
            label = "miniPlayerProgress"
        )
        LinearProgressIndicator(
            progress = { progressFloat },
            modifier = modifier
                .fillMaxWidth()
                .height(2.dp),
            color = AccentGreen,
            trackColor = Color.White.copy(alpha = 0.12f)
        )
    }
}
