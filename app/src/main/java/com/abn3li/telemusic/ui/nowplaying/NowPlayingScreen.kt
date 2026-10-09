package com.abn3li.telemusic.ui.nowplaying

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.zIndex
import com.abn3li.telemusic.ui.theme.SystemBarsState
import androidx.compose.runtime.SideEffect
import com.abn3li.telemusic.ui.theme.DarkPlayerTheme
import com.abn3li.telemusic.data.local.listArtwork
import com.abn3li.telemusic.data.browse.fullSizeArtwork
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
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.geometry.Rect
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
    miniPlayerBounds: (() -> Rect?)? = null,
    // The mini player (the dock): state, open, drag-to-open, drag released, its modifier.
    miniPlayerContent: @Composable (NowPlayingUiState, () -> Unit, (Float) -> Unit, (Float) -> Unit, Modifier) -> Unit,
    modifier: Modifier = Modifier
) = BoxWithConstraints(modifier.fillMaxSize()) {
    val stableState by viewModel.stableUiState.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    val expansionFraction = remember { Animatable(0f) }
    var isExpanded by remember { mutableStateOf(false) }
    var transitionOrigin by remember { mutableStateOf<Rect?>(null) }

    val density = LocalDensity.current
    val parentHeightPx = constraints.maxHeight.toFloat()
    // Where the card starts before the dock's player has been measured (it grows out of and
    // shrinks back into the dock's own player once it has - see miniPlayerBounds).
    val miniHeightPx = with(density) { MiniPlayerBarHeight.toPx() }
    val miniSidePx = with(density) { MiniPlayerSideMargin.toPx() }
    val miniCornerPx = with(density) { MiniPlayerCorner.toPx() }
    val miniTopPx = parentHeightPx - with(density) { bottomOffset.toPx() } - miniHeightPx
    val cardShadowPx = with(density) { 18.dp.toPx() }

    fun expand() {
        if (!isExpanded && transitionOrigin == null) transitionOrigin = miniPlayerBounds?.invoke()
        isExpanded = true
        coroutineScope.launch { expansionFraction.animateTo(1f, SHEET_TRANSITION_SPEC) }
    }
    fun collapse() {
        isExpanded = false
        coroutineScope.launch {
            expansionFraction.animateTo(0f, SHEET_TRANSITION_SPEC)
            transitionOrigin = null
        }
    }

    LaunchedEffect(isExpanded) {
        if (!isExpanded) {
            snapshotFlow { expansionFraction.value }.first { it <= 0.001f }
            transitionOrigin = null
        }
    }

    key(isExpanded) {
        BackHandler(enabled = isExpanded) { collapse() }
    }
    // The player is dark in both themes: the system bars follow it while it's open.
    SideEffect { SystemBarsState.playerOpen = isExpanded }

    DarkPlayerTheme {
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
                val origin = transitionOrigin ?: miniPlayerBounds?.invoke()
                val originHeight = origin?.height ?: miniHeightPx
                // The dock's player is a capsule; before it has been measured, the default shape.
                val originCorner = if (origin != null) originHeight / 2f else miniCornerPx
                val side = lerpFloat(origin?.left ?: miniSidePx, 0f, progress)
                val right = lerpFloat(origin?.let { size.width - it.right } ?: miniSidePx, 0f, progress)
                val cardHeight = lerpFloat(originHeight, size.height, progress)
                translationY = lerpFloat(origin?.top ?: miniTopPx, 0f, progress)
                shape = RevealCardShape(side, cardHeight, lerpFloat(originCorner, 0f, progress), right)
                clip = true
                shadowElevation = if (progress > 0.001f && progress < 0.999f) cardShadowPx else 0f
            }
    )
    }

    // The mini player rides with the card's top edge and fades into the resting player page.
    val miniModifier = Modifier
        .align(Alignment.BottomCenter)
        .offset {
            if (expansionFraction.value >= 0.5f) IntOffset(0, parentHeightPx.roundToInt() + 100) else IntOffset.Zero
        }
        .graphicsLayer {
            val progress = expansionFraction.value
            val origin = transitionOrigin ?: miniPlayerBounds?.invoke()
            translationY = -(origin?.top ?: miniTopPx) * progress
            alpha = (1f - progress * 3f).coerceIn(0f, 1f)
        }
    val onExpandDrag: (Float) -> Unit = { delta ->
        if (expansionFraction.value <= 0.001f) transitionOrigin = miniPlayerBounds?.invoke()
        coroutineScope.launch {
            expansionFraction.snapTo((expansionFraction.value - delta / parentHeightPx).coerceIn(0f, 1f))
        }
    }
    val onExpandDragEnd: (Float) -> Unit = { velocity ->
        if (velocity < -600f || expansionFraction.value > 0.25f) expand() else collapse()
    }
    miniPlayerContent(stableState, { expand() }, onExpandDrag, onExpandDragEnd, miniModifier)
}

/** Rounded-rect clip covering [height] px from the top, inset [side] px on both sides. */
private class RevealCardShape(
    private val side: Float,
    private val height: Float,
    private val radius: Float,
    private val right: Float = side
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(
            RoundRect(
                left = side,
                top = 0f,
                right = size.width - right,
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
    var showLyricsSourceDialog by remember { mutableStateOf(false) }
    var showRelated by remember { mutableStateOf(false) }
    var showQuality by remember { mutableStateOf(false) }
    var overflowSong by remember { mutableStateOf<SongEntity?>(null) }

    LaunchedEffect(state.song?.youtubeVideoId) {
        if (state.song?.youtubeVideoId.isNullOrBlank()) showRelated = false
    }
    LaunchedEffect(state.song?.telegramMessageId) { showQuality = false }

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
        showRelated = false
        showQuality = false
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

    // The open player (any page - the cover page shows the line being sung) looks once for a song
    // that has no lyrics yet: the saved ones on the phone first, then the internet. Never with
    // the player closed or the app in the background, so songs passing by unseen cost nothing.
    LaunchedEffect(state.song?.telegramMessageId, isExpanded) {
        val song = state.song ?: return@LaunchedEffect
        if (isExpanded && song.lyricsPlain.isNullOrBlank() && song.lyricsSynced.isNullOrBlank()) {
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
                        .zIndex(1f)
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
                            .background(Color.White.copy(alpha = 0.6f))
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
                    onOpenLyricsSource = { showLyricsSourceDialog = true },
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
                            .padding(top = 6.dp, bottom = if (!state.song?.youtubeVideoId.isNullOrBlank()) 8.dp else 36.dp)
                    ) {
                        PlayerScrubber(viewModel = viewModel, active = playerVisible, onInteraction = onInteraction,
                            qualityStatus = {
                                state.song?.let { song ->
                                    QualityUpgradeButton(song, active = isExpanded,
                                        onClick = { onInteraction(); showQuality = true })
                                }
                            })
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
                        if (!state.song?.youtubeVideoId.isNullOrBlank()) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                IconButton(
                                    onClick = { onInteraction(); showRelated = true },
                                    modifier = Modifier.pointerInput(Unit) {
                                        val openDistance = 24.dp.toPx()
                                        var dragDistance = 0f
                                        // Keep small finger movements as taps; open only after an upward swipe.
                                        detectVerticalDragGestures(
                                            onDragStart = { dragDistance = 0f },
                                            onDragEnd = {
                                                if (dragDistance <= -openDistance) {
                                                    onInteraction()
                                                    showRelated = true
                                                }
                                            },
                                            onDragCancel = { dragDistance = 0f },
                                            onVerticalDrag = { change, dragAmount ->
                                                change.consume()
                                                dragDistance += dragAmount
                                            }
                                        )
                                    }
                                ) {
                                    Icon(
                                        androidx.compose.material.icons.Icons.Rounded.KeyboardArrowUp,
                                        contentDescription = "Related songs",
                                        tint = Color.White.copy(alpha = 0.7f),
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showRelated && isExpanded && !state.song?.youtubeVideoId.isNullOrBlank()) {
            RelatedSongsSheet(state, viewModel, onOpenPage = onCollapse, onDismiss = { showRelated = false })
        }
        state.song?.takeIf { showQuality && isExpanded }?.let { song ->
            QualityUpgradeSheet(song, onDismiss = { showQuality = false; onInteraction() })
        }

        overflowSong?.let { song ->
            NowPlayingOverflowSheet(
                song = song,
                isDownloading = state.isDownloading,
                onDismiss = { overflowSong = null },
                onDownload = { (context.applicationContext as TgMusicApp).downloadGate.run { viewModel.downloadCurrentSong() } },
                onSongInfo = { showSongInfoDialog = true },
                onSearchLyrics = { showManualLyricsDialog = true },
                onLyricsSource = { showLyricsSourceDialog = true },
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
                message = "Type the exact song title and artist to search every lyrics source.",
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

        if (showLyricsSourceDialog && state.song != null) {
            LyricsSourceDialog(viewModel = viewModel, song = state.song, onDismiss = { showLyricsSourceDialog = false })
        }

        state.song?.takeIf { showSongInfoDialog }?.let { song ->
            SongInfoSheet(song = song, onDismiss = { showSongInfoDialog = false })
        }
    }
}

/**
 * Everything between the grabber and the controls. The Artwork page shows the full-width cover;
 * Lyrics and Queue fade it out for a small thumbnail beside the title. Only layer alphas change
 * on the switch, so nothing is laid out or drawn again.
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
    onOpenLyricsSource: () -> Unit,
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

    val horizontalPadding = 28.dp
    // The cover fills the screen's width from its very top (behind the status bar and the
    // grabber, [coverLift] above this area) and has faded out into the player's colours by the
    // title, which sits just above the controls like Apple Music. A square cover is shown at
    // most a tenth taller than wide, so little of its sides is trimmed.
    val coverLift = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 30.dp
    val titleTop = (maxHeight - 96.dp).coerceAtLeast(8.dp)
    val coverHeight = minOf(maxWidth * 1.1f, coverLift + titleTop).coerceAtLeast(160.dp)
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

    // The big cover is fetched at 1200 px where the host allows it (YouTube Music, iTunes, Deezer,
    // YouTube video frames - see fullSizeArtwork); everywhere else keeps the saved link.
    val fullArtwork = remember(song?.displayArtwork) { fullSizeArtwork(song?.displayArtwork) }
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
                    onOpenLyricsSource = onOpenLyricsSource,
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
                .zIndex(1f)
                .padding(top = titleTop)
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = song?.artist ?: "",
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 18.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
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
                onOverflow = onOpenOverflow,
                plain = true
            )
        }
    }

    // The line being sung, between the title and the seek bar (saved synced lyrics only - it
    // never searches on its own). Tapping it opens Lyrics.
    if (showCoverTitle && state.lyricLines.isNotEmpty()) {
        CurrentLyricLine(
            lines = state.lyricLines,
            viewModel = viewModel,
            active = isExpanded && page == PlayerPage.ARTWORK,
            isPlaying = state.isPlaying,
            onClick = { onInteraction(); onPageChange(PlayerPage.LYRICS) },
            modifier = Modifier
                .zIndex(1f)
                // Right on top of the seek bar, lined up with its left and right ends (26dp).
                .padding(top = titleTop + 70.dp)
                .padding(horizontal = 26.dp)
                .fillMaxWidth()
                .height(26.dp)
                .graphicsLayer { alpha = (1f - morph.value * 2f).coerceIn(0f, 1f) }
        )
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
    val hasArtwork = !song?.displayArtwork.isNullOrEmpty()
    // The large size missing (an old video has no 1280 px frame) or offline and not saved: the
    // saved link, then the small list thumbnail - each beats a blank.
    var coverFailures by remember(fullArtwork) { mutableIntStateOf(0) }
    val coverModel: Any? = when (coverFailures) {
        0 -> artworkRequest
        1 -> song?.displayArtwork
        else -> song?.listArtwork
    }

    // The full-width cover, drawn first so the title sits on top of its fade. Lyrics and Queue
    // fade it out. Moving and fading it are layer changes only - the faded picture itself is
    // drawn again only when the artwork changes.
    if (showCoverTitle) {
        Box(
            Modifier
                .offset(y = -coverLift)
                .fillMaxWidth()
                .height(coverHeight)
                .graphicsLayer {
                    alpha = (1f - morph.value * 1.6f).coerceIn(0f, 1f)
                    translationX = swipeSettle.value
                    // Offscreen, so the fade below can cut the picture's own alpha.
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.Black, 0.62f to Color.Black, 0.85f to Color.Black.copy(alpha = 0.35f), 1f to Color.Transparent
                        ),
                        blendMode = BlendMode.DstIn
                    )
                }
                .pointerInput(onCover) {
                    if (!onCover) return@pointerInput
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
            if (hasArtwork) {
                AsyncImage(
                    model = coverModel,
                    contentDescription = "Album artwork",
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize(),
                    onError = { coverFailures++ }
                )
            }
            // A soft shade under the status bar, so its clock and icons read on a bright cover.
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(coverLift + 24.dp)
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.32f), Color.Transparent)))
            )
        }
    }

    // Lyrics and Queue: the cover as a small thumbnail beside the title; tapping it goes back.
    if (showHeaderTitle) {
        Box(
            Modifier
                .offset(x = smallLeft, y = smallTop)
                .size(smallSide)
                .graphicsLayer {
                    val t = morph.value
                    alpha = ((t - 0.3f) / 0.7f).coerceIn(0f, 1f)
                    val scale = lerpFloat(0.85f, 1f, t)
                    scaleX = scale
                    scaleY = scale
                    shape = RoundedCornerShape(7.dp)
                    clip = true
                    shadowElevation = 4.dp.toPx()
                }
                .pointerInput(Unit) { detectTapGestures { onPageChange(PlayerPage.ARTWORK) } }
                .background(Color(0xFF2A2A2E)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.35f),
                modifier = Modifier.fillMaxSize(0.4f)
            )
            if (hasArtwork) {
                AsyncImage(
                    model = coverModel,
                    contentDescription = "Album artwork",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    onError = { coverFailures++ }
                )
            }
        }
    }
}

@Composable
private fun PlayerActionButtons(
    isFavorite: Boolean,
    overflowOpen: Boolean,
    onFavorite: () -> Unit,
    onOverflow: () -> Unit,
    // On the cover's fade: bare icons, no circles behind them.
    plain: Boolean = false
) {
    val haptics = LocalHapticFeedback.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (plain) 14.dp else 12.dp)) {
        CircleActionButton(
            icon = if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
            contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
            inverted = false,
            plain = plain,
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onFavorite()
            }
        )
        CircleActionButton(
            icon = Icons.Rounded.MoreHoriz,
            contentDescription = "More options",
            inverted = overflowOpen,
            plain = plain,
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
    plain: Boolean = false,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction)
    val background by animateColorAsState(
        when {
            inverted -> Color.White.copy(alpha = 0.92f)
            plain -> Color.Transparent
            else -> Color.White.copy(alpha = 0.14f)
        },
        tween(300),
        label = "circleActionBg"
    )
    val tint by animateColorAsState(if (inverted) Color.Black else Color.White, tween(300), label = "circleActionTint")
    Box(
        Modifier
            .size(if (plain) 36.dp else 32.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(background)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(if (plain && !inverted) 26.dp else 20.dp))
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
