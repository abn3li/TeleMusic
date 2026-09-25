package com.abn3li.telemusic.ui.nowplaying

import com.abn3li.telemusic.ui.library.CalmSpinner
import com.abn3li.telemusic.data.browse.FULL_ARTWORK_SIZE
import com.abn3li.telemusic.data.browse.googleArtworkAtSize
import com.abn3li.telemusic.ui.library.AppAlert
import com.abn3li.telemusic.ui.library.AlertAction
import com.abn3li.telemusic.ui.library.AlertTextField
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.abn3li.telemusic.data.local.SongEntity
import com.abn3li.telemusic.data.local.displayArtwork
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.data.settings.PlayerEffects
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

/** Open/close motion of the whole player sheet - quick, no overshoot. */
private val SHEET_TRANSITION_SPEC = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 550f)
/** The artwork's move between the big cover and the small header on the Lyrics/Queue pages. */
private val ARTWORK_MORPH_SPEC = spring<Float>(dampingRatio = 0.86f, stiffness = 360f)
private val EaseOutQuart = CubicBezierEasing(0.25f, 1f, 0.5f, 1f)
private const val CONTROLS_AUTO_HIDE_MS = 2500L

private fun lerpFloat(start: Float, stop: Float, fraction: Float) = start + (stop - start) * fraction

/**
 * Persistent player overlay, mounted once at the navigation root. The mini player and the full
 * player are both always composed; opening/closing is a graphicsLayer transform driven by one
 * Animatable, so expanding never pays a first-composition cost mid-animation.
 */
@Composable
fun PlayerSheetOverlay(
    viewModel: NowPlayingViewModel,
    bottomOffset: Dp = 84.dp,
    onOpenArtist: (String) -> Unit = {},
    onOpenAlbum: (String) -> Unit = {},
    modifier: Modifier = Modifier
) = BoxWithConstraints(modifier.fillMaxSize()) {
    val stableState by viewModel.stableUiState.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    val expansionFraction = remember { Animatable(0f) }
    var isExpanded by remember { mutableStateOf(false) }

    val density = LocalDensity.current
    val parentHeightPx = constraints.maxHeight.toFloat()
    // The mini player's own rectangle - the card grows out of exactly this shape and shrinks
    // back into it (see MiniPlayer: 12dp side margins, 56dp tall, 16dp corners).
    val miniHeightPx = with(density) { MiniPlayerBarHeight.toPx() }
    val miniSidePx = with(density) { MiniPlayerSideMargin.toPx() }
    val miniCornerPx = with(density) { MiniPlayerCorner.toPx() }
    val miniTopPx = parentHeightPx - with(density) { bottomOffset.toPx() } - miniHeightPx
    val cardShadowPx = with(density) { 18.dp.toPx() }

    fun expand() {
        isExpanded = true
        coroutineScope.launch { expansionFraction.animateTo(1f, SHEET_TRANSITION_SPEC) }
    }
    fun collapse() {
        isExpanded = false
        coroutineScope.launch { expansionFraction.animateTo(0f, SHEET_TRANSITION_SPEC) }
    }

    key(isExpanded) {
        BackHandler(enabled = isExpanded) { collapse() }
    }

    NowPlayingContent(
        state = stableState,
        viewModel = viewModel,
        expansionFraction = expansionFraction,
        screenHeightPx = parentHeightPx,
        isExpanded = isExpanded,
        onCollapse = { collapse() },
        onOpenArtist = { artist -> collapse(); onOpenArtist(artist) },
        onOpenAlbum = { album -> collapse(); onOpenAlbum(album) },
        modifier = Modifier
            .fillMaxSize()
            .offset {
                // Parked off-screen while collapsed so it never intercepts touches meant for the
                // screen underneath. Applied before graphicsLayer so the layer itself moves -
                // otherwise the layer's outline shadow stayed behind, drawing a faint ghost of the
                // mini player's rectangle above the nav bar even with no song loaded.
                if (expansionFraction.value <= 0.001f) IntOffset(0, parentHeightPx.roundToInt() + 100) else IntOffset.Zero
            }
            .graphicsLayer {
                // The whole card is one layer: it starts as the mini player's rectangle and grows
                // to fill the screen. Content is laid out full size and simply revealed by the
                // growing clip, riding up with the card's top edge.
                val progress = expansionFraction.value
                val side = lerpFloat(miniSidePx, 0f, progress)
                val cardHeight = lerpFloat(miniHeightPx, size.height, progress)
                translationY = lerpFloat(miniTopPx, 0f, progress)
                shape = RevealCardShape(side, cardHeight, lerpFloat(miniCornerPx, 0f, progress))
                clip = true
                shadowElevation = if (progress > 0.001f && progress < 0.999f) cardShadowPx else 0f
            }
    )

    // Drawn above the card and carried up with its top edge, fading out as the card opens - so
    // opening reads as the mini player itself turning into the full player.
    MiniPlayer(
        state = stableState,
        onPlayPause = { viewModel.togglePlayPause() },
        onNext = { viewModel.nextSong() },
        onPrevious = { viewModel.previousSong() },
        onClick = { expand() },
        onExpandDrag = { delta ->
            coroutineScope.launch {
                expansionFraction.snapTo((expansionFraction.value - delta / parentHeightPx).coerceIn(0f, 1f))
            }
        },
        onExpandDragEnd = { velocity ->
            if (velocity < -600f || expansionFraction.value > 0.25f) expand() else collapse()
        },
        bottomOffset = bottomOffset,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .offset {
                // Out of the way once mostly open, so its invisible bounds can't catch taps
                // meant for the player's own header.
                if (expansionFraction.value >= 0.5f) IntOffset(0, parentHeightPx.roundToInt() + 100) else IntOffset.Zero
            }
            .graphicsLayer {
                val progress = expansionFraction.value
                translationY = -miniTopPx * progress
                alpha = (1f - progress * 3f).coerceIn(0f, 1f)
            }
    )
}

/** Rounded-rect clip covering [height] px from the top, inset [side] px on both sides. */
private class RevealCardShape(
    private val side: Float,
    private val height: Float,
    private val radius: Float
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(
            RoundRect(
                left = side,
                top = 0f,
                right = size.width - side,
                bottom = height.coerceAtMost(size.height),
                cornerRadius = CornerRadius(radius)
            )
        )
}

@Composable
private fun NowPlayingContent(
    state: NowPlayingUiState,
    viewModel: NowPlayingViewModel,
    expansionFraction: Animatable<Float, *>,
    screenHeightPx: Float,
    isExpanded: Boolean,
    onCollapse: () -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val effects by (context.applicationContext as TgMusicApp).settingsStore.playerEffects.collectAsState()

    val playerVisible by remember { derivedStateOf { expansionFraction.value > 0.001f } }
    var page by rememberSaveable { mutableStateOf(PlayerPage.ARTWORK) }
    var showControls by remember { mutableStateOf(true) }
    var lastInteractionMs by remember { mutableLongStateOf(0L) }
    var showSongInfoDialog by remember { mutableStateOf(false) }
    var showManualLyricsDialog by remember { mutableStateOf(false) }
    var overflowSong by remember { mutableStateOf<SongEntity?>(null) }

    val onInteraction: () -> Unit = {
        lastInteractionMs = SystemClock.uptimeMillis()
        showControls = true
    }

    key(page, isExpanded) {
        BackHandler(enabled = isExpanded && page != PlayerPage.ARTWORK) { page = PlayerPage.ARTWORK }
    }

    // Back to the cover once the sheet has fully closed (not mid-slide, which would morph the
    // artwork while it's still visible).
    LaunchedEffect(isExpanded) {
        if (isExpanded) return@LaunchedEffect
        snapshotFlow { expansionFraction.value }.first { it <= 0.001f }
        page = PlayerPage.ARTWORK
        showControls = true
    }

    // On the Lyrics page the controls step aside after a few seconds without a touch, as long as
    // music is playing - a tap anywhere on the lyrics brings them back.
    LaunchedEffect(page, lastInteractionMs, state.isPlaying, isExpanded) {
        if (page == PlayerPage.LYRICS && state.isPlaying && isExpanded) {
            delay(CONTROLS_AUTO_HIDE_MS)
            showControls = false
        } else {
            showControls = true
        }
    }

    // Opening Lyrics (or skipping while it's open) searches once for a song that has none cached.
    LaunchedEffect(state.song?.telegramMessageId, page) {
        val song = state.song ?: return@LaunchedEffect
        if (page == PlayerPage.LYRICS && song.lyricsPlain.isNullOrBlank() && song.lyricsSynced.isNullOrBlank()) {
            viewModel.fetchLyricsOnDemand()
        }
    }

    // Vertical drag-to-dismiss, attached only to the grabber, the header and the cover - never the
    // lyrics/queue lists (those scroll) or the controls (a drag there fighting a button tap is what
    // caused accidental pauses before).
    val dismissDrag = Modifier.pointerInput(Unit) {
        var gestureStartMs = 0L
        var totalDragPx = 0f
        detectVerticalDragGestures(
            onDragStart = {
                gestureStartMs = SystemClock.uptimeMillis()
                totalDragPx = 0f
            },
            onDragEnd = {
                val elapsedMs = (SystemClock.uptimeMillis() - gestureStartMs).coerceAtLeast(1L)
                val averageVelocity = totalDragPx / (elapsedMs / 1000f)
                coroutineScope.launch {
                    if (expansionFraction.value < 0.6f || averageVelocity > 500f) onCollapse()
                    else expansionFraction.animateTo(1f, SHEET_TRANSITION_SPEC)
                }
            },
            onDragCancel = { coroutineScope.launch { expansionFraction.animateTo(1f, SHEET_TRANSITION_SPEC) } },
            onVerticalDrag = { change, dragAmount ->
                change.consume()
                totalDragPx += dragAmount
                coroutineScope.launch {
                    expansionFraction.snapTo((expansionFraction.value - dragAmount / screenHeightPx).coerceIn(0f, 1f))
                }
            }
        )
    }

    Box(modifier) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                // Absorbs taps on empty space so they never fall through to the screen behind.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
        ) {
            FloatingArtworkBackground(
                artwork = state.song?.displayArtwork,
                animate = isExpanded && state.isPlaying && effects.animatedBackground,
                dim = if (page == PlayerPage.LYRICS) 0.2f else 0.36f
            )

            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                        .then(dismissDrag)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onCollapse),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .size(width = 36.dp, height = 5.dp)
                            .clip(RoundedCornerShape(2.5.dp))
                            .background(Color.White.copy(alpha = 0.35f))
                    )
                }

                PlayerPageArea(
                    state = state,
                    viewModel = viewModel,
                    page = page,
                    effects = effects,
                    isExpanded = isExpanded,
                    dismissDrag = dismissDrag,
                    overflowOpen = overflowSong != null,
                    onPageChange = { page = it },
                    onInteraction = onInteraction,
                    onOpenOverflow = { overflowSong = state.song },
                    onOpenManualSearch = { showManualLyricsDialog = true },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )

                AnimatedVisibility(
                    visible = showControls,
                    enter = fadeIn(tween(260)) + expandVertically(tween(320, easing = EaseOutQuart), expandFrom = Alignment.Top),
                    exit = fadeOut(tween(200)) + shrinkVertically(tween(320, easing = EaseOutQuart), shrinkTowards = Alignment.Top)
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 26.dp)
                            .padding(top = 6.dp, bottom = 12.dp)
                    ) {
                        PlayerScrubber(viewModel = viewModel, active = playerVisible, onInteraction = onInteraction)
                        TransportRow(
                            state = state,
                            onPrevious = { onInteraction(); viewModel.previousSong() },
                            onPlayPause = { onInteraction(); viewModel.togglePlayPause() },
                            onNext = { onInteraction(); viewModel.nextSong() },
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                        VolumeRow(onInteraction = onInteraction)
                        Spacer(Modifier.height(10.dp))
                        PlayerBottomRow(
                            page = page,
                            onLyrics = {
                                onInteraction()
                                page = if (page == PlayerPage.LYRICS) PlayerPage.ARTWORK else PlayerPage.LYRICS
                            },
                            onQueue = {
                                onInteraction()
                                page = if (page == PlayerPage.QUEUE) PlayerPage.ARTWORK else PlayerPage.QUEUE
                            }
                        )
                    }
                }
            }
        }

        overflowSong?.let { song ->
            NowPlayingOverflowSheet(
                song = song,
                isDownloading = state.isDownloading,
                onDismiss = { overflowSong = null },
                onDownload = { (context.applicationContext as TgMusicApp).downloadGate.run { viewModel.downloadCurrentSong() } },
                onSongInfo = { showSongInfoDialog = true },
                onSearchLyrics = { showManualLyricsDialog = true },
                onOpenArtist = onOpenArtist,
                onOpenAlbum = onOpenAlbum
            )
        }

        // Manual Custom Lyrics Search Dialog
        if (showManualLyricsDialog && state.song != null) {
            var customTitle by remember { mutableStateOf(state.song.title) }
            var customArtist by remember { mutableStateOf(state.song.artist) }

            AppAlert(
                title = "Search Lyrics",
                message = "Type the exact song title and artist to search LRCLIB and lyrics.ovh.",
                onDismiss = { showManualLyricsDialog = false },
                actions = listOf(
                    AlertAction("Cancel") { showManualLyricsDialog = false },
                    AlertAction("Search", bold = true, enabled = customTitle.isNotBlank()) {
                        showManualLyricsDialog = false
                        viewModel.fetchLyricsCustom(customTitle, customArtist)
                    }
                )
            ) {
                AlertTextField(customTitle, { customTitle = it }, "Song title")
                Spacer(Modifier.height(8.dp))
                AlertTextField(customArtist, { customArtist = it }, "Artist")
            }
        }

        state.song?.takeIf { showSongInfoDialog }?.let { song ->
            SongInfoSheet(song = song, onDismiss = { showSongInfoDialog = false })
        }
    }
}

/**
 * Everything between the grabber and the controls. The artwork is a single element that morphs
 * between the big cover (Artwork page) and the small header thumbnail (Lyrics/Queue pages); its
 * position and size are pure graphicsLayer transforms, so the morph never re-lays-out anything.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlayerPageArea(
    state: NowPlayingUiState,
    viewModel: NowPlayingViewModel,
    page: PlayerPage,
    effects: PlayerEffects,
    isExpanded: Boolean,
    dismissDrag: Modifier,
    overflowOpen: Boolean,
    onPageChange: (PlayerPage) -> Unit,
    onInteraction: () -> Unit,
    onOpenOverflow: () -> Unit,
    onOpenManualSearch: () -> Unit,
    modifier: Modifier = Modifier
    // On the artwork page, a drag anywhere (not just on the cover/title) closes the player, like
    // Apple Music. Lyrics and queue keep only the header, since their lists scroll.
) = BoxWithConstraints(modifier.then(if (page == PlayerPage.ARTWORK) dismissDrag else Modifier)) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val song = state.song

    val morph = animateFloatAsState(
        targetValue = if (page == PlayerPage.ARTWORK) 0f else 1f,
        animationSpec = ARTWORK_MORPH_SPEC,
        label = "artworkMorph"
    )
    val showCoverTitle by remember { derivedStateOf { morph.value < 0.99f } }
    val showHeaderTitle by remember { derivedStateOf { morph.value > 0.01f } }

    // Playing: the cover sits large; paused: it settles back smaller (Apple Music's signature).
    val pauseScale = animateFloatAsState(
        targetValue = if (state.isPlaying) 1f else 0.8f,
        animationSpec = if (state.isPlaying) spring(dampingRatio = 1f, stiffness = 300f) else tween(350, easing = EaseOutQuart),
        label = "coverPauseScale"
    )

    val horizontalPadding = 28.dp
    val coverTitleHeight = 92.dp
    val bigSide = minOf(maxWidth - horizontalPadding * 2, maxHeight - coverTitleHeight - 16.dp).coerceAtLeast(120.dp)
    val bigLeft = (maxWidth - bigSide) / 2
    val bigTop = ((maxHeight - bigSide - coverTitleHeight) / 2).coerceAtLeast(8.dp)
    val smallSide = 62.dp
    val smallLeft = 26.dp
    val smallTop = 10.dp

    val swipeThresholdPx = with(LocalDensity.current) { 72.dp.toPx() }
    var swipeOffset by remember { mutableFloatStateOf(0f) }
    val swipeSettle = animateFloatAsState(
        targetValue = swipeOffset,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "coverSwipe"
    )
    val latestState by rememberUpdatedState(state)

    // YouTube Music art is fetched at 1200 px here (the big cover); everywhere else keeps the
    // saved 544 px link. Local files and other hosts pass through unchanged.
    val fullArtwork = remember(song?.displayArtwork) { googleArtworkAtSize(song?.displayArtwork, FULL_ARTWORK_SIZE) }
    val artworkRequest = remember(fullArtwork, context) {
        ImageRequest.Builder(context)
            .data(fullArtwork)
            .crossfade(200)
            .memoryCacheKey(fullArtwork)
            .diskCacheKey(fullArtwork)
            .build()
    }

    // Lyrics / Queue body, below the small header.
    Box(
        Modifier
            .fillMaxSize()
            .padding(top = smallTop + smallSide + 8.dp)
            .graphicsLayer { alpha = morph.value }
    ) {
        Crossfade(targetState = page, animationSpec = tween(280), label = "playerPageBody") { shown ->
            when (shown) {
                PlayerPage.LYRICS -> LyricsPage(
                    state = state,
                    viewModel = viewModel,
                    effects = effects,
                    onOpenManualSearch = onOpenManualSearch,
                    onUserInteraction = onInteraction
                )
                PlayerPage.QUEUE -> QueuePage(state = state, viewModel = viewModel)
                PlayerPage.ARTWORK -> Spacer(Modifier.fillMaxSize())
            }
        }
    }

    if (showCoverTitle) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = bigTop + bigSide + 18.dp)
                .padding(horizontal = horizontalPadding)
                .graphicsLayer { alpha = (1f - morph.value * 2f).coerceIn(0f, 1f) }
                .then(dismissDrag),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f).padding(end = 14.dp)) {
                Text(
                    text = song?.title ?: "Not Playing",
                    color = Color.White,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // A long title scrolls once, then rests: the scroll redraws the whole screen
                    // every frame (~45% CPU while it runs), so it shouldn't repeat.
                    modifier = if (isExpanded) Modifier.basicMarquee(iterations = 1) else Modifier
                )
                Text(
                    text = song?.artist ?: "",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                state.errorMessage?.let { error ->
                    Text(
                        text = error,
                        color = Color(0xFFFF8A80),
                        fontSize = 12.sp,
                        maxLines = 2,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            PlayerActionButtons(
                isFavorite = song?.isFavorite == true,
                overflowOpen = overflowOpen,
                onFavorite = { viewModel.toggleFavorite() },
                onOverflow = onOpenOverflow
            )
        }
    }

    if (showHeaderTitle) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = smallTop, start = smallLeft + smallSide + 14.dp, end = 26.dp)
                .height(smallSide)
                .graphicsLayer { alpha = ((morph.value - 0.5f) * 2f).coerceIn(0f, 1f) }
                .then(dismissDrag),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    text = song?.title ?: "Not Playing",
                    color = Color.White,
                    fontSize = 16.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = song?.artist ?: "",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 14.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            PlayerActionButtons(
                isFavorite = song?.isFavorite == true,
                overflowOpen = overflowOpen,
                onFavorite = { viewModel.toggleFavorite() },
                onOverflow = onOpenOverflow
            )
        }
    }

    val onCover = page == PlayerPage.ARTWORK
    Box(
        Modifier
            .offset(x = bigLeft, y = bigTop)
            .size(bigSide)
            .graphicsLayer {
                val t = morph.value
                val bigSidePx = bigSide.toPx()
                val coverScale = pauseScale.value
                val headerScale = smallSide.toPx() / bigSidePx
                val scale = lerpFloat(coverScale, headerScale, t)
                val coverX = (1f - coverScale) * bigSidePx / 2f + swipeSettle.value * (1f - t)
                val coverY = (1f - coverScale) * bigSidePx / 2f
                val headerX = (smallLeft - bigLeft).toPx()
                val headerY = (smallTop - bigTop).toPx()
                transformOrigin = TransformOrigin(0f, 0f)
                translationX = lerpFloat(coverX, headerX, t)
                translationY = lerpFloat(coverY, headerY, t)
                scaleX = scale
                scaleY = scale
                val cornerPx = lerpFloat(12.dp.toPx(), 7.dp.toPx(), t) / scale
                shape = RoundedCornerShape(cornerPx)
                clip = true
                shadowElevation = lerpFloat(22.dp.toPx(), 4.dp.toPx(), t)
            }
            .pointerInput(onCover) {
                if (onCover) {
                    var total = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { total = 0f },
                        onDragCancel = { swipeOffset = 0f },
                        onDragEnd = {
                            when {
                                total <= -swipeThresholdPx && latestState.hasNext -> {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    viewModel.nextSong()
                                }
                                total >= swipeThresholdPx && latestState.hasPrevious -> {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    viewModel.previousSong()
                                }
                            }
                            swipeOffset = 0f
                        },
                        onHorizontalDrag = { change, delta ->
                            change.consume()
                            total += delta
                            swipeOffset = total * 0.35f
                        }
                    )
                } else {
                    detectTapGestures { onPageChange(PlayerPage.ARTWORK) }
                }
            }
            .then(if (onCover) dismissDrag else Modifier)
            .background(Color(0xFF2A2A2E)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Rounded.MusicNote,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.35f),
            modifier = Modifier.fillMaxSize(0.3f)
        )
        if (!song?.displayArtwork.isNullOrEmpty()) {
            AsyncImage(
                model = artworkRequest,
                contentDescription = "Album artwork",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        if (state.loadingSongId != null || state.isBuffering) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
                    .size(36.dp)
                    .graphicsLayer { alpha = 1f - morph.value }
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                CalmSpinner(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
            }
        }
    }
}

@Composable
private fun PlayerActionButtons(
    isFavorite: Boolean,
    overflowOpen: Boolean,
    onFavorite: () -> Unit,
    onOverflow: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircleActionButton(
            icon = if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
            contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
            inverted = false,
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onFavorite()
            }
        )
        CircleActionButton(
            icon = Icons.Rounded.MoreHoriz,
            contentDescription = "More options",
            inverted = overflowOpen,
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onOverflow()
            }
        )
    }
}

@Composable
private fun CircleActionButton(
    icon: ImageVector,
    contentDescription: String,
    inverted: Boolean,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction)
    val background by animateColorAsState(
        if (inverted) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.14f),
        tween(300),
        label = "circleActionBg"
    )
    val tint by animateColorAsState(if (inverted) Color.Black else Color.White, tween(300), label = "circleActionTint")
    Box(
        Modifier
            .size(32.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(background)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/** Spring press-down scale shared by the player's buttons. */
@Composable
internal fun rememberPressScale(interactionSource: InteractionSource): Float {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh),
        label = "sharedPressScale"
    )
    return scale
}


/** Size on disk for the Song Info popup - handles both a real filesystem path and a referenced
 * local import's own content:// URI. Zero (shown as "Streaming") if the path is missing/unreadable rather than
 * a real size. */
internal fun localFileSizeBytes(context: Context, path: String): Long = try {
    if (path.startsWith("content://")) {
        context.contentResolver.openAssetFileDescriptor(Uri.parse(path), "r")?.use { it.length } ?: 0L
    } else {
        File(path).takeIf { it.exists() }?.length() ?: 0L
    }
} catch (_: Exception) {
    0L
}

internal fun formatMs(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}