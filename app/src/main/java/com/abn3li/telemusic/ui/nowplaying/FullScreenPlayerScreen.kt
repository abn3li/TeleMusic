package com.abn3li.telemusic.ui.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import java.util.concurrent.TimeUnit

/**
 * Everything [FullScreenPlayerScreen] needs to render - no ViewModel/repository types, so this
 * stays previewable/testable on its own. [currentPositionMs]/[durationMs] live in here (rather
 * than split into their own fast-ticking flow the way the rest of this app's real Now Playing
 * screen does - see NowPlayingScreen.kt's own SeekbarSection doc) because this composable is
 * meant to be a clean, reusable, spec-accurate piece; the real screen wires this up itself and
 * can still choose how often it rebuilds this state.
 */
data class NowPlayingCardState(
    val title: String,
    val artist: String,
    val albumArtUrl: String?,
    val sourceLabel: String,
    val currentPositionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
    val isBuffering: Boolean = false,
    val isFavorite: Boolean = false,
    val hasNext: Boolean = true,
    val hasPrevious: Boolean = true,
    val volume: Float = 0.45f
)

private fun formatDuration(ms: Long): String {
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(ms.coerceAtLeast(0))
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}

/**
 * Full-screen glassmorphic "Now Playing" card - blurred album-art backdrop, square artwork,
 * scrubber, transport row, volume row, and a footer of secondary actions. Stateless: every value
 * comes from [state], every action goes out through the callbacks. See NowPlayingCardState's own
 * doc for why position/duration live in the same state object here.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FullScreenPlayerScreen(
    state: NowPlayingCardState,
    onDismiss: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onFavoriteClick: () -> Unit,
    onOptionsClick: () -> Unit,
    onLyricsClick: () -> Unit,
    onQueueClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        // Modifier.blur() is a no-op below API 31 (RenderEffect isn't available there) - the
        // artwork just renders sharp instead of blurred, which is the "graceful fallback" asked
        // for: still a correct, readable screen, just without the blur.
        AsyncImage(
            model = state.albumArtUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .blur(60.dp)
        )

        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0.0f to Color.Black.copy(alpha = 0.35f),
                    0.45f to Color.Black.copy(alpha = 0.55f),
                    1.0f to Color.Black.copy(alpha = 0.95f)
                )
            )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 28.dp, vertical = 20.dp)
        ) {
            // Drag handle doubles as a tap-to-dismiss target - the mockup has no separate back
            // button, and swipe-to-dismiss (wired by the caller) covers the gesture case.
            Column(
                modifier = Modifier.fillMaxWidth().clickableNoRipple(onDismiss),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 40.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.35f))
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = state.sourceLabel,
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(Modifier.height(24.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = 0.05f)),
                contentAlignment = Alignment.Center
            ) {
                if (!state.albumArtUrl.isNullOrEmpty()) {
                    AsyncImage(
                        model = state.albumArtUrl,
                        contentDescription = "Album artwork",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(64.dp))
                }
                if (state.isBuffering) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.title,
                        color = Color.White,
                        fontSize = 23.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.basicMarquee()
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = state.artist.uppercase(),
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(10.dp))
                CircleIconButton(icon = if (state.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, onClick = onFavoriteClick, tint = if (state.isFavorite) Color(0xFFE85D75) else Color.White)
                Spacer(Modifier.width(8.dp))
                CircleIconButton(icon = Icons.Default.MoreHoriz, onClick = onOptionsClick)
            }

            Spacer(Modifier.height(14.dp))

            // isDragging gates which value the slider shows: while the user is actively
            // dragging, a live-ticking state.currentPositionMs must NOT override their thumb
            // position mid-gesture (remember(state.currentPositionMs) would reset on every tick
            // and fight the drag) - only once they release does the real position resume
            // driving the display, right after the seek this release triggers lands.
            var isDragging by remember { mutableStateOf(false) }
            var dragPositionMs by remember { mutableFloatStateOf(state.currentPositionMs.toFloat()) }
            val displayedPositionMs = if (isDragging) dragPositionMs else state.currentPositionMs.toFloat()
            Slider(
                value = displayedPositionMs,
                onValueChange = { isDragging = true; dragPositionMs = it },
                onValueChangeFinished = { isDragging = false; onSeek(dragPositionMs.toLong()) },
                valueRange = 0f..state.durationMs.coerceAtLeast(1L).toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.22f)
                ),
                modifier = Modifier.fillMaxWidth().height(20.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(formatDuration(displayedPositionMs.toLong()), color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                val remainingMs = (state.durationMs - displayedPositionMs.toLong()).coerceAtLeast(0)
                Text("-${formatDuration(remainingMs)}", color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TransportIcon(Icons.Default.SkipPrevious, onSkipPrevious, size = 42.dp, enabled = state.hasPrevious)
                TransportIcon(
                    icon = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    onClick = onPlayPause,
                    size = 54.dp
                )
                TransportIcon(Icons.Default.SkipNext, onSkipNext, size = 42.dp, enabled = state.hasNext)
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.VolumeMute, contentDescription = null, tint = Color.White.copy(alpha = 0.55f), modifier = Modifier.size(18.dp))
                Slider(
                    value = state.volume,
                    onValueChange = onVolumeChange,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.Transparent,
                        activeTrackColor = Color.White.copy(alpha = 0.85f),
                        inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                    ),
                    modifier = Modifier.weight(1f).height(16.dp)
                )
                Icon(Icons.Default.VolumeUp, contentDescription = null, tint = Color.White.copy(alpha = 0.55f), modifier = Modifier.size(18.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onLyricsClick) {
                    Icon(Icons.Default.ChatBubbleOutline, contentDescription = "Lyrics", tint = Color.White.copy(alpha = 0.65f))
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color.White.copy(alpha = 0.15f))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.Headphones, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Box(Modifier.width(1.dp).height(14.dp).background(Color.White.copy(alpha = 0.25f)))
                        Icon(Icons.Default.Person, contentDescription = null, tint = Color.White.copy(alpha = 0.65f), modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("This phone", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }

                IconButton(onClick = onQueueClick) {
                    Icon(Icons.Default.QueueMusic, contentDescription = "Queue", tint = Color.White.copy(alpha = 0.65f))
                }
            }
        }
    }
}

@Composable
private fun CircleIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, tint: Color = Color.White) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.15f))
            .clickableNoRipple(onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun TransportIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, size: androidx.compose.ui.unit.Dp, enabled: Boolean = true) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = Color.White,
        modifier = Modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.35f)
            .clickableNoRipple(onClick)
    )
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this.composed {
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick
    )
}

/**
 * Interactive demo with local mock state - not @Preview-annotated (this project has no
 * androidx.compose.ui:ui-tooling-preview dependency, so Android Studio's Preview pane can't be
 * used here anyway), but still a real, callable, self-contained composable for manually
 * exercising every visual state without wiring up the real ViewModel.
 */
@Composable
fun FullScreenPlayerScreenDemo() {
    var isPlaying by remember { mutableStateOf(true) }
    var positionMs by remember { mutableFloatStateOf(1_000f) }
    var volume by remember { mutableFloatStateOf(0.45f) }
    var isFavorite by remember { mutableStateOf(false) }

    FullScreenPlayerScreen(
        state = NowPlayingCardState(
            title = "Youth",
            artist = "Daughter",
            albumArtUrl = null,
            sourceLabel = "Playing from Local Music",
            currentPositionMs = positionMs.toLong(),
            durationMs = 251_000L,
            isPlaying = isPlaying,
            isFavorite = isFavorite,
            volume = volume
        ),
        onDismiss = {},
        onPlayPause = { isPlaying = !isPlaying },
        onSeek = { positionMs = it.toFloat() },
        onSkipNext = {},
        onSkipPrevious = {},
        onVolumeChange = { volume = it },
        onFavoriteClick = { isFavorite = !isFavorite },
        onOptionsClick = {},
        onLyricsClick = {},
        onQueueClick = {}
    )
}
