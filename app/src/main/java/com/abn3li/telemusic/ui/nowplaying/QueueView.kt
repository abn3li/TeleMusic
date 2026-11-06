package com.abn3li.telemusic.ui.nowplaying

import com.abn3li.telemusic.ui.theme.SoftTextButton as TextButton

import android.widget.Toast
import androidx.compose.material.icons.rounded.Add
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.ui.download.YouTubeContextHost
import com.abn3li.telemusic.ui.download.youtubeTrackActions
import com.abn3li.telemusic.ui.library.CalmSpinner
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.abn3li.telemusic.data.local.SongEntity
import com.abn3li.telemusic.data.local.displayArtwork
import com.abn3li.telemusic.playback.RepeatMode
import com.abn3li.telemusic.ui.library.AppAccent
import kotlinx.coroutines.launch
import kotlin.math.abs


private val QueueRowHeight = 64.dp
private val DeleteRed = Color(0xFFD32F2F)
private val QueueEasing = CubicBezierEasing(0.25f, 1f, 0.5f, 1f)
private const val SWIPE_TRIGGER_FRACTION = 0.2f

private enum class QueueSection { NEXT_IN_QUEUE, UP_NEXT }

private enum class QueueTab(val label: String) { UP_NEXT("Up Next"), SUGGESTIONS("You Might Like") }

/**
 * The Queue page body: header with shuffle/repeat, then two tabs - the queue ("Next in Queue"
 * and "Up Next") and songs like the one playing. A song without a YouTube id has nothing to
 * base suggestions on, so it shows the queue alone.
 */
@Composable
internal fun QueuePage(
    state: NowPlayingUiState,
    viewModel: NowPlayingViewModel,
    onOpenPage: () -> Unit,
    modifier: Modifier = Modifier
) {
    val queueState by viewModel.queueState.collectAsState()
    val nextInQueue = queueState.nextInQueue
    val upNext = queueState.upNext
    val canSuggest = !state.song?.youtubeVideoId.isNullOrBlank()
    var tab by rememberSaveable { mutableStateOf(QueueTab.UP_NEXT) }
    val shownTab = if (canSuggest) tab else QueueTab.UP_NEXT

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 26.dp)
                .padding(top = 4.dp)
                .height(58.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Playing Next", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                val total = nextInQueue.size + upNext.size
                Text(
                    "$total ${if (total == 1) "song" else "songs"}",
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            QueueModeToggle(
                icon = Icons.Rounded.Shuffle,
                contentDescription = "Shuffle",
                active = state.isShuffleEnabled,
                onClick = { viewModel.toggleShuffle() }
            )
            Spacer(Modifier.width(10.dp))
            QueueModeToggle(
                icon = if (state.repeatMode == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                contentDescription = "Repeat",
                active = state.repeatMode != RepeatMode.OFF,
                onClick = { viewModel.toggleRepeat() }
            )
        }

        if (canSuggest) QueueTabs(shownTab, onSelect = { tab = it })
        if (shownTab == QueueTab.SUGGESTIONS) {
            SuggestionsList(state, viewModel, onOpenPage)
            return@Column
        }

        if (nextInQueue.isEmpty() && upNext.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(bottom = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.45f),
                    modifier = Modifier.size(64.dp)
                )
                Text(
                    "Nothing up next",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )
                Text(
                    "Use \"Play next\" on any song to line it up here.",
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 14.sp
                )
            }
            return@Column
        }

        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().fadedEdges()
        ) {
            item(key = "queue_top") { Spacer(Modifier.height(8.dp)) }
            if (nextInQueue.isNotEmpty()) {
                item(key = "header_next") {
                    QueueSectionHeader("Next in Queue", onClear = { viewModel.clearNextInQueue() })
                }
                queueSection(
                    section = QueueSection.NEXT_IN_QUEUE,
                    songs = nextInQueue,
                    onPlay = viewModel::jumpToNextInQueue,
                    onMove = viewModel::moveNextInQueue,
                    onRemove = viewModel::removeNextInQueue,
                    onMoveToNextInQueue = null
                )
            }
            if (upNext.isNotEmpty()) {
                item(key = "header_up") { QueueSectionHeader("Up Next") }
                queueSection(
                    section = QueueSection.UP_NEXT,
                    songs = upNext,
                    onPlay = viewModel::jumpToUpNext,
                    onMove = viewModel::moveUpNext,
                    onRemove = viewModel::removeUpNext,
                    onMoveToNextInQueue = viewModel::moveUpNextToNextInQueue
                )
            }
            item(key = "queue_bottom") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** The lists' soft top and bottom edges, so rows fade out instead of being cut. */
private fun Modifier.fadedEdges(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                0.05f to Color.Black,
                0.93f to Color.Black,
                1f to Color.Transparent
            ),
            blendMode = BlendMode.DstIn
        )
    }

/** Up Next / You Might Like, as one rounded switch under the header. */
@Composable
private fun QueueTabs(selected: QueueTab, onSelect: (QueueTab) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 26.dp)
            .padding(top = 6.dp, bottom = 4.dp)
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.1f))
            .padding(3.dp)
    ) {
        QueueTab.entries.forEach { tab ->
            val on = tab == selected
            val pill by animateColorAsState(if (on) Color.White.copy(alpha = 0.2f) else Color.Transparent, tween(200), label = "queueTab")
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(pill)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        if (!on) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(tab)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(tab.label, color = Color.White.copy(alpha = if (on) 1f else 0.6f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/**
 * Songs like the one playing. They load only while this tab is open, and follow the song: a new
 * song brings its own. Tap plays them, + puts one in Next in Queue, a long press has the rest.
 */
@Composable
private fun SuggestionsList(state: NowPlayingUiState, viewModel: NowPlayingViewModel, onOpenPage: () -> Unit) {
    val related by viewModel.relatedState.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(state.song?.youtubeVideoId) { viewModel.fetchRelated() }
    DisposableEffect(viewModel) { onDispose { viewModel.closeRelated() } }

    YouTubeContextHost {
        val current = related.videoId == state.song?.youtubeVideoId
        if (!current || related.loading) {
            Box(Modifier.fillMaxSize().padding(bottom = 40.dp), contentAlignment = Alignment.Center) { CalmSpinner() }
        } else if (related.error != null || related.tracks.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 26.dp).padding(bottom = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(related.error ?: "No suggestions for this song yet.", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
                if (related.error != null) {
                    TextButton(onClick = { viewModel.fetchRelated(force = true) }) { Text("Retry", color = Color.White) }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().fadedEdges()) {
                item(key = "based_on") {
                    Text(
                        "Based on ${state.song?.title.orEmpty()}",
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 26.dp).padding(top = 10.dp, bottom = 4.dp)
                    )
                }
                itemsIndexed(related.tracks, key = { _, track -> track.videoId }) { index, track ->
                    val play = { viewModel.playRelated(related.tracks, index) }
                    val download = { (context.applicationContext as TgMusicApp).downloadGate.run { viewModel.downloadRelated(track) } }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(QueueRowHeight)
                            .youtubeTrackActions(track, play, download, onOpenPage)
                            .padding(start = 26.dp, end = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            track.thumbnailUrl, null, contentScale = ContentScale.Crop,
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.08f))
                        )
                        Column(Modifier.weight(1f).padding(start = 14.dp, end = 10.dp)) {
                            Text(track.title, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(track.artist, color = Color.White.copy(alpha = 0.5f), fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Box(
                            Modifier
                                .size(42.dp)
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                    viewModel.queueRelated(track)
                                    Toast.makeText(context, "Added to Next in Queue", Toast.LENGTH_SHORT).show()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.Add, "Add ${track.title} to the queue", tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(24.dp))
                        }
                    }
                }
                item(key = "suggestions_bottom") { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.queueSection(
    section: QueueSection,
    songs: List<SongEntity>,
    onPlay: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMoveToNextInQueue: ((Int) -> Boolean)?
) {
    val keys = stableKeys(section, songs)
    itemsIndexed(songs, key = { index, _ -> keys[index] }) { index, song ->
        val latestIndex by rememberUpdatedState(index)
        val latestSize by rememberUpdatedState(songs.size)
        var dragging by remember { mutableStateOf(false) }
        var dragOffset by remember { mutableFloatStateOf(0f) }
        val rowHeightPx = with(LocalDensity.current) { QueueRowHeight.toPx() }
        val haptics = LocalHapticFeedback.current
        val shownOffset by animateFloatAsState(
            targetValue = if (dragging) dragOffset else 0f,
            animationSpec = if (dragging) snap() else tween(220, easing = FastOutSlowInEasing),
            label = "queueReorderOffset"
        )
        val placement = if (dragging || shownOffset != 0f) Modifier else Modifier.animateItemPlacement(
            tween(240, easing = FastOutSlowInEasing)
        )

        QueueRow(
            song = song,
            reorderEnabled = songs.size > 1,
            isDragging = dragging,
            modifier = placement
                .zIndex(if (dragging) 1f else 0f)
                .graphicsLayer { translationY = shownOffset },
            onClick = { onPlay(latestIndex) },
            onRemove = { onRemove(latestIndex) },
            onMoveToNextInQueue = onMoveToNextInQueue?.let { move -> { move(latestIndex) } },
            dragHandleModifier = Modifier.pointerInput(keys[index]) {
                detectDragGestures(
                    onDragStart = {
                        dragging = true
                        dragOffset = 0f
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                    onDrag = { change, amount ->
                        change.consume()
                        dragOffset += amount.y
                        val steps = (dragOffset / rowHeightPx).toInt()
                        if (steps != 0) {
                            val from = latestIndex
                            val to = (from + steps).coerceIn(0, latestSize - 1)
                            if (to != from) {
                                onMove(from, to)
                                dragOffset -= (to - from) * rowHeightPx
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }
                    }
                )
            }
        )
    }
}

// The same song can be queued more than once, so keys combine the id with its occurrence count.
private fun stableKeys(section: QueueSection, songs: List<SongEntity>): List<String> {
    val seen = HashMap<Long, Int>()
    return songs.map { song ->
        val occurrence = seen.merge(song.telegramMessageId, 1, Int::plus) ?: 1
        "${section.name}_${song.telegramMessageId}_$occurrence"
    }
}

@Composable
private fun QueueSectionHeader(title: String, onClear: (() -> Unit)? = null) {
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 26.dp)
            .padding(top = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (onClear != null) {
            Text(
                "Clear",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClear()
                }
            )
        }
    }
}

@Composable
private fun QueueModeToggle(icon: ImageVector, contentDescription: String, active: Boolean, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction)
    val background by animateColorAsState(
        if (active) Color.White.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.1f),
        tween(220),
        label = "queueToggleBg"
    )
    val tint by animateColorAsState(if (active) Color.Black else Color.White.copy(alpha = 0.75f), tween(220), label = "queueToggleTint")
    Box(
        Modifier
            .size(36.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .clickable(interactionSource = interaction, indication = null) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/**
 * One queue row. Swipe left past [SWIPE_TRIGGER_FRACTION] of the width to remove it; swipe
 * right (Up Next only) to move it into Next in Queue. The drag handle reorders.
 */
@Composable
private fun QueueRow(
    song: SongEntity,
    reorderEnabled: Boolean,
    isDragging: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onMoveToNextInQueue: (() -> Boolean)?,
    dragHandleModifier: Modifier,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var rowWidth by remember { mutableIntStateOf(0) }
    val swipe = remember { Animatable(0f) }
    val trigger = rowWidth * SWIPE_TRIGGER_FRACTION
    val canSwipeRight = onMoveToNextInQueue != null

    val swipeState = rememberDraggableState { delta ->
        if (rowWidth == 0) return@rememberDraggableState
        val before = swipe.value
        val after = (before + delta).coerceIn(-rowWidth.toFloat(), if (canSwipeRight) rowWidth.toFloat() else 0f)
        if ((abs(before) >= trigger) != (abs(after) >= trigger)) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        scope.launch { swipe.snapTo(after) }
    }

    val background by animateColorAsState(
        if (isDragging) Color.White.copy(alpha = 0.09f) else Color.Transparent,
        label = "queueRowDragBg"
    )

    Box(
        modifier
            .fillMaxWidth()
            .height(QueueRowHeight)
            .onSizeChanged { rowWidth = it.width }
            .clipToBounds()
    ) {
        val offset = swipe.value
        val revealWidth = with(LocalDensity.current) { abs(offset).toDp() }
        val progress = if (rowWidth > 0) (abs(offset) / rowWidth).coerceIn(0f, 1f) else 0f
        if (offset < 0f) {
            Box(
                Modifier.align(Alignment.CenterEnd).width(revealWidth).fillMaxHeight().background(DeleteRed),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(26.dp).graphicsLayer { scaleX = 0.82f + progress * 0.18f; scaleY = scaleX }
                )
            }
        } else if (offset > 0f) {
            Box(
                Modifier.align(Alignment.CenterStart).width(revealWidth).fillMaxHeight().background(AppAccent),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.PlaylistAdd,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(26.dp).graphicsLayer { scaleX = 0.82f + progress * 0.18f; scaleY = scaleX }
                )
            }
        }

        Row(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = swipe.value }
                .background(background, RoundedCornerShape(10.dp))
                .draggable(
                    state = swipeState,
                    orientation = Orientation.Horizontal,
                    onDragStopped = {
                        val current = swipe.value
                        when {
                            current <= -trigger && trigger > 0f -> {
                                // Removed first, so nothing here can leave a song half deleted. The
                                // rows below slide up on their own. The reset is for a repeated
                                // song: its next copy takes over this row and must start in place.
                                onRemove()
                                swipe.animateTo(-rowWidth.toFloat(), tween(120, easing = QueueEasing))
                                swipe.snapTo(0f)
                            }
                            current >= trigger && trigger > 0f && onMoveToNextInQueue != null -> {
                                if (onMoveToNextInQueue()) {
                                    Toast.makeText(context, "Added to Next in Queue", Toast.LENGTH_SHORT).show()
                                }
                                swipe.animateTo(0f, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow))
                            }
                            else -> swipe.animateTo(0f, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow))
                        }
                    }
                )
                .clickable(onClick = onClick)
                .padding(start = 26.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Color.White.copy(alpha = 0.35f), modifier = Modifier.size(22.dp))
                if (!song.displayArtwork.isNullOrEmpty()) {
                    AsyncImage(
                        model = song.displayArtwork,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            Column(Modifier.weight(1f).padding(start = 14.dp, end = 10.dp)) {
                Text(
                    song.title,
                    color = Color.White,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    song.artist,
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (reorderEnabled) {
                Box(
                    dragHandleModifier.size(42.dp).fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.DragHandle,
                        contentDescription = "Reorder",
                        tint = Color.White.copy(alpha = 0.34f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}
