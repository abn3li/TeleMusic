package com.abn3li.telemusic.ui.nowplaying

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.abn3li.telemusic.data.local.SongEntity

/** Approximate rendered height of the floating MiniPlayer, incl. its own vertical margins. */
val MiniPlayerHeight: Dp = 78.dp

/**
 * How much extra bottom space a scrollable list should reserve (as content padding, not a
 * layout-shrinking inset) so its last item isn't hidden under the floating MiniPlayer - which
 * floats on top of content rather than pushing it up, see NavGraph's own doc on why. Provided
 * once at the navigation root from the same playback state MiniPlayer itself reads, and
 * defaults to 0 for any screen composed outside that provider (e.g. previews).
 */
val LocalMiniPlayerInset = compositionLocalOf { 0.dp }

/**
 * Pure presentational component - all state comes from the shared NowPlayingViewModel owned
 * by PlayerSheetOverlay, so there's exactly one poller/one source of truth for playback state
 * instead of this maintaining its own separate copy.
 */
@Composable
fun MiniPlayer(
    state: NowPlayingUiState,
    playbackProgress: PlaybackProgress,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = state.song != null,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
        modifier = modifier
    ) {
        val song = state.song ?: return@AnimatedVisibility

        val artworkColor = rememberArtworkColor(song.albumArtUrl)

        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(20.dp),
            color = artworkColor ?: MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Column {
                MiniPlayerContent(state, song, onPlayPause, onNext)
                MiniPlayerProgressBar(progress = playbackProgress)
            }
        }
    }
}

@Composable
private fun MiniPlayerProgressBar(progress: PlaybackProgress) {
    if (progress.durationMs > 0L) {
        val targetProgress = (progress.currentPositionMs.toFloat() / progress.durationMs.toFloat()).coerceIn(0f, 1f)
        val progressFloat by animateFloatAsState(
            targetValue = targetProgress,
            animationSpec = tween(durationMillis = 300, easing = LinearEasing),
            label = "miniPlayerProgress"
        )
        LinearProgressIndicator(
            progress = { progressFloat },
            modifier = Modifier
                .fillMaxWidth()
                .height(2.5.dp),
            color = Color.White,
            trackColor = Color.White.copy(alpha = 0.2f)
        )
    }
}

@Composable
private fun MiniPlayerContent(
    state: NowPlayingUiState,
    song: SongEntity,
    onPlayPause: () -> Unit,
    onNext: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Cute rounded artwork thumbnail
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.White.copy(alpha = 0.12f),
                modifier = Modifier.size(46.dp)
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
                            modifier = Modifier.size(22.dp),
                            tint = Color.White
                        )
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            // Title & Artist
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Play/Pause Button - a spinner in its place while THIS song is still loading
            // (loadingSongId, see NowPlayingViewModel.loadCurrentQueuePosition's own doc) OR
            // actively buffering (isBuffering, wired straight to ExoPlayer's own
            // Player.STATE_BUFFERING) - not just whenever anything is loading. loadingSongId
            // alone only covers the initial stream resolve; isBuffering is what actually lights
            // up during a real playback stall/retry (a still-downloading Telegram file, a slow
            // connection, ...), which used to show nothing here at all and just looked frozen.
            // Same treatment NowPlayingScreen's own artwork spinner already gets.
            if (state.loadingSongId == song.telegramMessageId || state.isBuffering) {
                Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                }
            } else {
                IconButton(
                    onClick = onPlayPause,
                    modifier = Modifier.size(40.dp)
                ) {
                    // Same play/pause dissolve NowPlayingContent's own big button gets, instead
                    // of the icon just popping instantly - this is the button you glance at most
                    // often since the mini player is visible almost the whole time you're using
                    // the app.
                    Crossfade(targetState = state.isPlaying, animationSpec = tween(durationMillis = 150), label = "miniPlayPauseMorph") { playing ->
                        Icon(
                            imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playing) "Pause" else "Play",
                            tint = Color.White
                        )
                    }
                }
            }

            // Next Button
            IconButton(
                onClick = onNext,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Default.SkipNext,
                    contentDescription = "Next",
                    tint = Color.White
                )
            }
        }
    }
}
