package com.abn3li.telemusic.ui.nowplaying

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode as AnimRepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.abn3li.telemusic.data.local.SongEntity
import com.abn3li.telemusic.playback.RepeatMode

/**
 * Full-screen "Playing Next" queue view - its own glassmorphic screen matching NowPlayingScreen/
 * LyricsView's own look. Shows the currently playing track plus everything queued after it
 * (NowPlayingViewModel.upcomingQueue), with real drag-to-reorder, tap-to-jump, and a "Clear
 * Queue" action that drops everything after the current song.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QueueView(
    state: NowPlayingUiState,
    viewModel: NowPlayingViewModel,
    onClose: () -> Unit,
    onOpenLyrics: () -> Unit,
    modifier: Modifier = Modifier
) {
    val song = state.song
    val upcoming by viewModel.upcomingQueue.collectAsState()
    val progress by viewModel.playbackProgress.collectAsState()

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        AsyncImage(
            model = song?.albumArtUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().blur(55.dp)
        )
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0.0f to Color.Black.copy(alpha = 0.35f),
                    0.45f to Color.Black.copy(alpha = 0.65f),
                    1.0f to Color.Black.copy(alpha = 0.95f)
                )
            )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                // Catch-all so a tap on empty space (e.g. the gap between the transport row and
                // the queue icon in the dock below) doesn't fall through this always-composed
                // overlay to whatever is behind it (the mini player), which is what made tapping
                // blank dock space toggle play/pause - same reasoning as NowPlayingContent/
                // LyricsView's own catch-alls.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Playing Next", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircleIconButton(icon = Icons.Default.DeleteSweep, onClick = { viewModel.clearUpcomingQueue() })
                    CircleIconButton(icon = Icons.Default.KeyboardArrowDown, onClick = onClose)
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val trackWord = if (upcoming.size == 1) "track" else "tracks"
                Text(
                    text = "QUEUE (${upcoming.size} $trackWord)",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionPill(
                        icon = Icons.Default.Shuffle,
                        label = "Shuffle",
                        active = state.isShuffleEnabled,
                        onClick = { viewModel.toggleShuffle() }
                    )
                    ActionPill(
                        icon = if (state.repeatMode == RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        label = if (state.repeatMode == RepeatMode.ONE) "Repeat One" else "Repeat",
                        active = state.repeatMode != RepeatMode.OFF,
                        onClick = { viewModel.toggleRepeat() }
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Queue list: "Now Playing" (current song, non-draggable) + "Up Next" (reorderable).
            val density = LocalDensity.current
            val rowHeightPx = with(density) { 68.dp.toPx() }
            var draggedIndex by remember { mutableStateOf(-1) }
            var dragOffsetPx by remember { mutableStateOf(0f) }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                item {
                    Text(
                        "NOW PLAYING",
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(bottom = 4.dp, top = 6.dp)
                    )
                }
                item {
                    if (song != null) {
                        CurrentTrackCard(song = song, isPlaying = state.isPlaying)
                    }
                }
                if (upcoming.isNotEmpty()) {
                    item {
                        Text(
                            "UP NEXT",
                            color = Color.White.copy(alpha = 0.45f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(bottom = 4.dp, top = 10.dp)
                        )
                    }
                }
                items(upcoming.size, key = { upcoming[it].telegramMessageId }) { index ->
                    val isDragged = draggedIndex == index
                    // The drag lambdas below close over `index`, which is only correct AT THE
                    // MOMENT this item recomposes - it goes stale the instant moveQueueItem()
                    // reorders the list mid-drag. rememberUpdatedState keeps a live reference so
                    // a still-running gesture always reads the CURRENT index instead of the one
                    // captured when the drag started.
                    val latestIndex = rememberUpdatedState(index)
                    // true only while the finger is actually down - stays isDragged=true a bit
                    // longer than this, through the settle animation below, so the row hands off
                    // to animateItemPlacement() only once it's visually back at offset 0 instead
                    // of jump-cutting there.
                    var isDragging by remember { mutableStateOf(false) }
                    // Snaps 1:1 with the finger while isDragging (SnapSpec - no lag chasing a
                    // fast swipe), then eases back to 0 on release. Directly assigning
                    // dragOffsetPx to 0f on release (the original version) cut the row straight
                    // from mid-drag position to its resting slot in a single frame - a hard
                    // jump-cut. A spring() fixed that jump but read as slow/mushy instead - a
                    // fixed-duration tween settles in a short, predictable beat with no physics
                    // wobble either way.
                    val settledOffset by animateFloatAsState(
                        targetValue = if (isDragging) dragOffsetPx else 0f,
                        animationSpec = if (isDragging) snap() else tween(
                            durationMillis = 220,
                            easing = FastOutSlowInEasing
                        ),
                        label = "queueRowDragSettle",
                        // animateFloatAsState fires finishedListener once on EVERY row's first
                        // composition too (its very first animateTo, target==0==current, still
                        // "completes"), not just after a real drag settles - every row in the
                        // list shares this one draggedIndex var, so without the index check here
                        // a row that happens to compose for the first time while a DIFFERENT row
                        // is mid-drag (e.g. the list reflows/scrolls mid-gesture) would reset the
                        // shared draggedIndex and visually cut that other row's drag short. Only
                        // clear it if this row is actually the one that owned it.
                        finishedListener = { if (!isDragging && draggedIndex == latestIndex.value) draggedIndex = -1 }
                    )
                    UpcomingTrackRow(
                        song = upcoming[index],
                        // No animateItemPlacement() while this row is the one being dragged (or
                        // still settling back into place) - it would fight the manual
                        // translationY offset below with its own position animation. Every OTHER
                        // row still gets it, so it slides smoothly into its new slot instead of
                        // snapping there instantly.
                        modifier = (if (isDragged) Modifier else Modifier.animateItemPlacement(
                            animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
                        )).graphicsLayer { translationY = if (isDragged) settledOffset else 0f },
                        onClick = { viewModel.jumpToQueueItem(index) },
                        // Keyed on the song's own id, not `index` - keying on index meant this
                        // pointerInput's whole coroutine (and the gesture it was mid-way through
                        // tracking) got cancelled and restarted every single time moveQueueItem()
                        // changed this row's index, which is what showed up as a glitch/stutter
                        // while dragging. The song's id never changes across a reorder, so the
                        // SAME detectDragGestures call now runs uninterrupted for the whole drag.
                        dragHandleModifier = Modifier.pointerInput(upcoming[index].telegramMessageId) {
                            detectDragGestures(
                                onDragStart = {
                                    draggedIndex = latestIndex.value
                                    dragOffsetPx = 0f
                                    isDragging = true
                                },
                                onDragEnd = { isDragging = false },
                                onDragCancel = { isDragging = false },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragOffsetPx += dragAmount.y
                                    val moveBy = (dragOffsetPx / rowHeightPx).toInt()
                                    // upcoming.lastIndex is -1 if the queue got cleared out from
                                    // under an in-progress drag (e.g. "Clear Queue" or the last
                                    // song finishing while dragging) - coerceIn(0, -1) throws
                                    // (empty range), so this has to bail out first.
                                    if (moveBy != 0 && upcoming.isNotEmpty()) {
                                        val current = latestIndex.value
                                        val newIndex = (current + moveBy).coerceIn(0, upcoming.lastIndex)
                                        if (newIndex != current) {
                                            viewModel.moveQueueItem(current, newIndex)
                                            draggedIndex = newIndex
                                            dragOffsetPx -= moveBy * rowHeightPx
                                        }
                                    }
                                }
                            )
                        }
                    )
                }
            }

            // Floating glassmorphic bottom dock - scrubber + transport, matching NowPlaying/Lyrics.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 16.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.White.copy(alpha = 0.12f))
                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(24.dp))
                    .padding(horizontal = 18.dp, vertical = 14.dp)
            ) {
                var isDragging by remember { mutableStateOf(false) }
                var dragPositionMs by remember { mutableFloatStateOf(progress.currentPositionMs.toFloat()) }
                val displayedPositionMs = if (isDragging) dragPositionMs else progress.currentPositionMs.toFloat()

                Slider(
                    value = displayedPositionMs,
                    onValueChange = { isDragging = true; dragPositionMs = it },
                    onValueChangeFinished = { isDragging = false; viewModel.seekTo(dragPositionMs.toLong()) },
                    valueRange = 0f..progress.durationMs.coerceAtLeast(1L).toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                    ),
                    modifier = Modifier.fillMaxWidth().height(14.dp)
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatMs(displayedPositionMs.toLong()), color = Color.White.copy(alpha = 0.45f), fontSize = 10.sp, fontWeight = FontWeight.Medium)
                    val remainingMs = (progress.durationMs - displayedPositionMs.toLong()).coerceAtLeast(0)
                    Text("-${formatMs(remainingMs)}", color = Color.White.copy(alpha = 0.45f), fontSize = 10.sp, fontWeight = FontWeight.Medium)
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DockToggleGlyph(
                        icon = Icons.Default.ChatBubbleOutline,
                        contentDescription = "Lyrics",
                        onClick = onOpenLyrics
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                        val prevInteraction = remember { MutableInteractionSource() }
                        val prevScale = rememberPressScale(prevInteraction)
                        val prevAlpha by animateFloatAsState(if (state.hasPrevious) 1f else 0.35f, label = "prevAlpha")
                        Icon(
                            Icons.Default.SkipPrevious,
                            contentDescription = "Previous",
                            tint = Color.White,
                            modifier = Modifier
                                .size(28.dp)
                                .graphicsLayer { scaleX = prevScale; scaleY = prevScale }
                                .alpha(prevAlpha)
                                .clickable(interactionSource = prevInteraction, indication = null, onClick = { viewModel.previousSong() })
                        )
                        val playPauseInteraction = remember { MutableInteractionSource() }
                        val playPauseScale = rememberPressScale(playPauseInteraction)
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = Color.White,
                            modifier = Modifier
                                .size(36.dp)
                                .graphicsLayer { scaleX = playPauseScale; scaleY = playPauseScale }
                                .clickable(interactionSource = playPauseInteraction, indication = null, onClick = { viewModel.togglePlayPause() })
                        )
                        val nextInteraction = remember { MutableInteractionSource() }
                        val nextScale = rememberPressScale(nextInteraction)
                        val nextAlpha by animateFloatAsState(if (state.hasNext) 1f else 0.35f, label = "nextAlpha")
                        Icon(
                            Icons.Default.SkipNext,
                            contentDescription = "Next",
                            tint = Color.White,
                            modifier = Modifier
                                .size(28.dp)
                                .graphicsLayer { scaleX = nextScale; scaleY = nextScale }
                                .alpha(nextAlpha)
                                .clickable(interactionSource = nextInteraction, indication = null, onClick = { viewModel.nextSong() })
                        )
                    }

                    DockToggleGlyph(
                        icon = Icons.Default.QueueMusic,
                        contentDescription = "Back to Now Playing",
                        active = true,
                        onClick = onClose
                    )
                }
            }
        }
    }
}

@Composable
private fun CircleIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressScale = rememberPressScale(interactionSource)
    Box(
        modifier = Modifier
            .size(36.dp)
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.15f))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun ActionPill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String?, active: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressScale = rememberPressScale(interactionSource)
    val backgroundColor by animateColorAsState(
        targetValue = Color.White.copy(alpha = if (active) 0.28f else 0.12f),
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "actionPillBackground"
    )
    val contentColor by animateColorAsState(
        targetValue = if (active) Color.White else Color.White.copy(alpha = 0.7f),
        animationSpec = tween(durationMillis = 220),
        label = "actionPillContent"
    )
    Row(
        modifier = Modifier
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .clip(RoundedCornerShape(14.dp))
            .background(backgroundColor)
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(icon, contentDescription = label, tint = contentColor, modifier = Modifier.size(16.dp))
        if (label != null) {
            Text(label, color = contentColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun CurrentTrackCard(song: SongEntity, isPlaying: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.16f))
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TrackArt(song)
        Column(modifier = Modifier.weight(1f)) {
            Text(song.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (song.album.isNullOrBlank()) song.artist else "${song.artist} • ${song.album}",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        EqualizerIndicator(animating = isPlaying)
    }
}

@Composable
private fun UpcomingTrackRow(
    song: SongEntity,
    onClick: () -> Unit,
    dragHandleModifier: Modifier,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.04f), RoundedCornerShape(16.dp))
            // Real ripple here (not indication = null like the icon-only buttons elsewhere in
            // this screen) - tapping a row to jump to that song is a primary action on this
            // screen and needs its own visible feedback, unlike a small icon button.
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TrackArt(song)
        Column(modifier = Modifier.weight(1f)) {
            Text(song.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (song.album.isNullOrBlank()) song.artist else "${song.artist} • ${song.album}",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            Icons.Default.DragHandle,
            contentDescription = "Reorder",
            tint = Color.White.copy(alpha = 0.35f),
            modifier = dragHandleModifier.size(20.dp).padding(4.dp)
        )
    }
}

@Composable
private fun TrackArt(song: SongEntity) {
    Box(modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.08f))) {
        if (!song.albumArtUrl.isNullOrEmpty()) {
            AsyncImage(model = song.albumArtUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Three bars animating up/down while [animating] - the "now playing" equalizer glyph. */
@Composable
private fun EqualizerIndicator(animating: Boolean) {
    Row(modifier = Modifier.height(14.dp), horizontalArrangement = Arrangement.spacedBy(2.5.dp), verticalAlignment = Alignment.Bottom) {
        repeat(3) { i ->
            val transition = rememberInfiniteTransition(label = "eqBar$i")
            val heightFraction by if (animating) {
                transition.animateFloat(
                    initialValue = 0.25f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 500 + i * 150, easing = LinearEasing),
                        repeatMode = AnimRepeatMode.Reverse
                    ),
                    label = "eqBarValue$i"
                )
            } else {
                remember { mutableFloatStateOf(0.3f) }
            }
            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .height(14.dp)
                    .graphicsLayer {
                        scaleY = heightFraction
                        transformOrigin = TransformOrigin(0.5f, 1.0f)
                    }
                    .background(Color.White, RoundedCornerShape(1.dp))
            )
        }
    }
}
