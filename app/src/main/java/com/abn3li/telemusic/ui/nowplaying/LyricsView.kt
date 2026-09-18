package com.abn3li.telemusic.ui.nowplaying

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/**
 * Full-screen "Lyrics View" - its own glassmorphic screen (blurred backdrop, compact header,
 * scrollable synced lyrics, floating glass dock), not lyrics rendered inside the artwork card's
 * own box any more. NowPlayingContent swaps its ENTIRE body for this when showLyricsView is
 * true, rather than nesting it inside the artwork slot - the old nested version fought the
 * artwork's own horizontal swipe-to-skip gesture region and left the metadata/seekbar/transport
 * rows sitting oddly above a lyrics list that visually had nothing to do with them.
 */
@Composable
fun LyricsView(
    state: NowPlayingUiState,
    viewModel: NowPlayingViewModel,
    onClose: () -> Unit,
    onOpenManualSearch: () -> Unit,
    onOpenQueue: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val song = state.song
    val listState = rememberLazyListState()
    val progress by viewModel.playbackProgress.collectAsState()

    // Computed directly from state.lyricLines (the exact list rendered below) and
    // progress.currentPositionMs, rather than trusting progress.activeLyricIndex (an index
    // computed in the ViewModel against ITS OWN copy of the lyric list). Those two lists are
    // supposed to always be the same one, but going through a precomputed index at all meant any
    // drift between "the list the index was computed against" and "the list actually on screen"
    // (e.g. right as a song changes and both are mid-update) could silently highlight/scroll to
    // the wrong line. Both the auto-scroll below and the highlight in the list itself now derive
    // from this ONE locally-computed value, so they can never disagree with each other either.
    val activeIndex = remember(state.lyricLines, progress.currentPositionMs) {
        state.lyricLines.indexOfLast { it.timeMs <= progress.currentPositionMs }
    }

    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) {
            listState.animateScrollToItem(maxOf(0, activeIndex - 3))
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        // Modifier.blur() is a no-op below API 31 (no RenderEffect there) - the art renders
        // sharp instead of blurred on those devices, which is the graceful fallback.
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
                    0.5f to Color.Black.copy(alpha = 0.65f),
                    1.0f to Color.Black.copy(alpha = 0.95f)
                )
            )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                // Catch-all so a tap on empty space doesn't fall through to whatever's behind
                // this always-composed overlay - same reasoning as NowPlayingContent's own.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
        ) {
            Spacer(Modifier.height(6.dp))

            // Compact header: mini art + title/artist, dismiss chevron on the right.
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.08f))
                    ) {
                        if (!song?.albumArtUrl.isNullOrEmpty()) {
                            AsyncImage(model = song.albumArtUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                    Column {
                        Text(
                            text = song?.title ?: "Unknown Title",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = (song?.artist ?: "Unknown Artist").uppercase(),
                            color = Color.White.copy(alpha = 0.55f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.8.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.15f))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Back to player", tint = Color.White)
                }
            }

            // Lyrics list / loading / empty states.
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.isFetchingLyrics -> {
                        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            CircularProgressIndicator(color = Color.White)
                            Spacer(Modifier.height(12.dp))
                            Text("Searching lyrics across LRCLIB & lyrics.ovh...", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    state.lyricLines.isNotEmpty() -> {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                // Top/bottom fade mask - lines dissolve into the backdrop as they
                                // enter/leave the viewport instead of hard-clipping at the edges.
                                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                                .drawWithContent {
                                    drawContent()
                                    drawRect(
                                        brush = Brush.verticalGradient(
                                            colors = listOf(Color.Transparent, Color.Black, Color.Black, Color.Transparent),
                                            startY = 0f,
                                            endY = size.height
                                        ),
                                        blendMode = androidx.compose.ui.graphics.BlendMode.DstIn
                                    )
                                },
                            contentPadding = PaddingValues(vertical = 32.dp)
                        ) {
                            itemsIndexed(state.lyricLines) { index, line ->
                                LyricLineRow(
                                    text = line.text,
                                    isActive = index == activeIndex,
                                    onClick = { viewModel.seekTo(line.timeMs) }
                                )
                            }
                        }
                    }
                    !song?.lyricsPlain.isNullOrBlank() -> {
                        LazyColumn(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                            item {
                                Text(
                                    text = song.lyricsPlain!!,
                                    color = Color.White.copy(alpha = 0.85f),
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 24.dp)
                                )
                            }
                        }
                    }
                    else -> {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text("No lyrics found automatically", color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Search a custom song name/artist to fetch lyrics from LRCLIB & other sources:",
                                color = Color.White.copy(alpha = 0.6f),
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(16.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(onClick = { viewModel.fetchLyricsOnDemand() }) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Auto-Retry")
                                }
                                Button(onClick = onOpenManualSearch) {
                                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Search Custom Name")
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Floating glassmorphic bottom dock - scrubber + time, then lyrics-toggle/transport/queue.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
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
                        icon = Icons.Default.ChatBubble,
                        contentDescription = "Hide lyrics",
                        active = true,
                        onClick = onClose
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
                        contentDescription = "Queue",
                        onClick = onOpenQueue
                    )
                }
            }
        }
    }
}

@Composable
private fun LyricLineRow(text: String, isActive: Boolean, onClick: () -> Unit) {
    val scale by animateFloatAsState(targetValue = if (isActive) 1.04f else 1f, label = "lyricLineScale")
    Text(
        text = text,
        color = if (isActive) Color.White else Color.White.copy(alpha = 0.32f),
        fontSize = 25.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 33.sp,
        letterSpacing = (-0.4).sp,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(vertical = 10.dp)
    )
}
