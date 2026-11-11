package com.abn3li.telemusic.ui.nowplaying

import com.abn3li.telemusic.ui.theme.SoftIconButton as IconButton

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.layout.layout
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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

/** Opening and closing from a tap or Back: one even movement that starts gently and lands without
 * a slow last stretch, so every staged part (see the constants below) gets real time on screen. */
private val SHEET_OPEN_SPEC = tween<Float>(340, easing = CubicBezierEasing(0.3f, 0f, 0.15f, 1f))
private val SHEET_CLOSE_SPEC = tween<Float>(300, easing = CubicBezierEasing(0.4f, 0f, 0.3f, 1f))
/** Settling after a finger lets go mid-way: carries on from the finger's own speed, no overshoot. */
private val SHEET_RELEASE_SPEC = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 550f)
/** The artwork's move between the big cover and the small header on the Lyrics/Queue pages. */
private val ARTWORK_MORPH_SPEC = spring<Float>(dampingRatio = 0.86f, stiffness = 360f)
private val EaseOutQuart = CubicBezierEasing(0.25f, 1f, 0.5f, 1f)
private const val CONTROLS_AUTO_HIDE_MS = 2500L

private fun lerpFloat(start: Float, stop: Float, fraction: Float) = start + (stop - start) * fraction

/**
 * A title that scrolls once when it's too long, dissolving at both ends instead of being cut. The
 * text starts [fade] inside the scrolling strip, which reaches that far out to the left, so a
 * resting title sits where it always did and is whole; only text that has scrolled past its
 * starting edge, or runs off the far one, fades. Waits before moving so the start can be read.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.softEdgedMarquee(fade: Dp = 16.dp): Modifier = this
    .layout { measurable, constraints ->
        val reach = fade.roundToPx()
        val widest = if (constraints.hasBoundedWidth) constraints.maxWidth + reach else constraints.maxWidth
        val placeable = measurable.measure(constraints.copy(maxWidth = widest))
        layout((placeable.width - reach).coerceAtLeast(0), placeable.height) { placeable.placeRelative(-reach, 0) }
    }
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithCache {
        val edge = (fade.toPx() / size.width).coerceIn(0f, 0.5f)
        val mask = Brush.horizontalGradient(
            0f to Color.Transparent, edge to Color.Black, 1f - edge to Color.Black, 1f to Color.Transparent
        )
        onDrawWithContent {
            drawContent()
            drawRect(mask, blendMode = BlendMode.DstIn)
        }
    }
    .basicMarquee(iterations = 1, initialDelayMillis = 1800)
    .padding(horizontal = fade)

// How much of the cover shows down its height: solid, then an S-curve to nothing. Straight lines
// between a few stops left a visible edge where the fade stopped, so it eases out to flat instead.
private const val COVER_FADE_START = 0.75f
private val CoverFadeCurve: List<Pair<Float, Float>> = listOf(0f to 1f) + List(13) { i ->
    val t = i / 12f
    (COVER_FADE_START + (1f - COVER_FADE_START) * t) to 1f - t * t * t * (t * (t * 6f - 15f) + 10f)
}

/**
 * Persistent player overlay, mounted once at the navigation root. The mini player and the full
 * player are both always composed; opening/closing is a graphicsLayer transform driven by one
 * Animatable, so expanding never pays a first-composition cost mid-animation.
 */
@Composable
fun PlayerSheetOverlay(
    viewModel: NowPlayingViewModel,
    // The movement, shared with the page behind and the dock (see PlayerTransition).
    transition: PlayerTransition,
    onOpenArtist: (String) -> Unit = {},
    onOpenAlbum: (String) -> Unit = {},
    // The mini player (the dock): state, open, its modifier.
    miniPlayerContent: @Composable (NowPlayingUiState, () -> Unit, Modifier) -> Unit,
    modifier: Modifier = Modifier
) = BoxWithConstraints(modifier.fillMaxSize()) {
    val stableState by viewModel.stableUiState.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    val expansionFraction = transition.expansion
    var isExpanded by remember { mutableStateOf(false) }

    val parentHeightPx = constraints.maxHeight.toFloat()
    // Rounded at the top while it moves; square once it fills the screen.
    val sheetShape = remember { RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp) }

    // [releaseVelocity] (of the expansion, per second) when a finger let go: the movement then
    // carries on at that speed instead of starting again from rest.
    fun expand(releaseVelocity: Float? = null) {
        isExpanded = true
        coroutineScope.launch {
            if (releaseVelocity == null) expansionFraction.animateTo(1f, SHEET_OPEN_SPEC)
            else expansionFraction.animateTo(1f, SHEET_RELEASE_SPEC, releaseVelocity)
        }
    }
    fun collapse(releaseVelocity: Float? = null) {
        isExpanded = false
        coroutineScope.launch {
            if (releaseVelocity == null) expansionFraction.animateTo(0f, SHEET_CLOSE_SPEC)
            else expansionFraction.animateTo(0f, SHEET_RELEASE_SPEC, releaseVelocity)
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
        onDragRelease = { open, velocity -> if (open) expand(velocity) else collapse(velocity) },
        onOpenArtist = { artist -> collapse(); onOpenArtist(artist) },
        onOpenAlbum = { album -> collapse(); onOpenAlbum(album) },
        modifier = Modifier
            // Above the dock: the rising player covers it.
            .zIndex(1f)
            .fillMaxSize()
            .offset {
                // Parked off-screen while collapsed so it never intercepts touches meant for the
                // screen underneath.
                if (expansionFraction.value <= 0.001f) IntOffset(0, parentHeightPx.roundToInt() + 100) else IntOffset.Zero
            }
            .graphicsLayer {
                // The whole player is one layer that slides up from the bottom edge of the screen,
                // over the page and the dock, and back down again. Its content is laid out full
                // size and simply rides with it, so every frame shows a finished player.
                val progress = expansionFraction.value
                translationY = size.height * (1f - progress)
                val moving = progress > 0.001f && progress < 0.999f
                shape = if (moving) sheetShape else RectangleShape
                clip = moving
            }
    )
    }

    miniPlayerContent(stableState, { expand() }, Modifier.align(Alignment.BottomCenter))
}

@Composable
private fun NowPlayingContent(
    state: NowPlayingUiState,
    viewModel: NowPlayingViewModel,
    expansionFraction: Animatable<Float, *>,
    screenHeightPx: Float,
    isExpanded: Boolean,
    onCollapse: () -> Unit,
    // A drag on the player let go: settle open or closed, from that speed (expansion per second).
    onDragRelease: (open: Boolean, velocity: Float) -> Unit,
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
    var showQuality by remember { mutableStateOf(false) }
    var overflowSong by remember { mutableStateOf<SongEntity?>(null) }

    val visualsActive = playerVisible
    // The moving background rests while a clip plays: two moving layers would cost twice.
    var clipShowing by remember { mutableStateOf(false) }

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
                onDragRelease(
                    !(expansionFraction.value < 0.6f || averageVelocity > 500f),
                    -averageVelocity / screenHeightPx
                )
            },
            onDragCancel = { onDragRelease(true, 0f) },
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
                animate = isExpanded && visualsActive && state.isPlaying && effects.animatedBackground && !clipShowing,
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
                    visualsActive = visualsActive,
                    dismissDrag = dismissDrag,
                    overflowOpen = overflowSong != null,
                    onPageChange = { page = it },
                    onInteraction = onInteraction,
                    onOpenOverflow = { overflowSong = state.song },
                    onOpenManualSearch = { showManualLyricsDialog = true },
                    onOpenLyricsSource = { showLyricsSourceDialog = true },
                    onCollapse = onCollapse,
                    onClipShowing = { clipShowing = it },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )

                AnimatedVisibility(
                    visible = showControls,
                    enter = fadeIn(tween(260)) + expandVertically(tween(320, easing = EaseOutQuart), expandFrom = Alignment.Top),
                    exit = fadeOut(tween(200)) + shrinkVertically(tween(320, easing = EaseOutQuart), shrinkTowards = Alignment.Top)
                ) {
                    // The controls take their new height at once, so the page above them is laid
                    // out a single time; the seek bar then glides from where it was to where it
                    // now belongs as a layer move.
                    val seekBarSlide = remember { Animatable(0f) }
                    val controlsHeight = remember { intArrayOf(0) }
                    val slideScope = rememberCoroutineScope()
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .onSizeChanged { size ->
                                val before = controlsHeight[0]
                                controlsHeight[0] = size.height
                                if (before != 0 && before != size.height) {
                                    // Undispatched: the bar is already offset when this frame draws.
                                    slideScope.launch(start = CoroutineStart.UNDISPATCHED) {
                                        seekBarSlide.snapTo(seekBarSlide.value + (size.height - before))
                                        seekBarSlide.animateTo(0f, tween(320, easing = EaseOutQuart))
                                    }
                                }
                            }
                            .padding(horizontal = 26.dp)
                            .padding(top = 6.dp)
                    ) {
                        // Queue and Lyrics swap the full controls for one compact row, so their
                        // lists get the room the volume, times and second row take on the cover.
                        val compact = page != PlayerPage.ARTWORK
                        val onLyrics = {
                            onInteraction()
                            page = if (page == PlayerPage.LYRICS) PlayerPage.ARTWORK else PlayerPage.LYRICS
                        }
                        val onQueue = {
                            onInteraction()
                            page = if (page == PlayerPage.QUEUE) PlayerPage.ARTWORK else PlayerPage.QUEUE
                        }
                        val onPrevious = { onInteraction(); viewModel.previousSong() }
                        val onPlayPause = { onInteraction(); viewModel.togglePlayPause() }
                        val onNext = { onInteraction(); viewModel.nextSong() }
                        PlayerScrubber(viewModel = viewModel, active = visualsActive, onInteraction = onInteraction,
                            qualityStatus = {
                                state.song?.let { song ->
                                    QualityUpgradeButton(song, active = isExpanded && visualsActive,
                                        onClick = { onInteraction(); showQuality = true })
                                }
                            },
                            modifier = Modifier.graphicsLayer { translationY = seekBarSlide.value },
                            compact = compact)
                        AnimatedContent(
                            targetState = compact,
                            transitionSpec = {
                                // The outgoing buttons fade where they stand and are gone before
                                // the incoming ones show, so the two sets never sit on each other.
                                (fadeIn(tween(200, delayMillis = 110)) togetherWith fadeOut(tween(120)))
                                    .using(SizeTransform(clip = false) { _, _ -> snap() })
                            },
                            // Both sets hang from the bottom edge, which is the one that stays put.
                            contentAlignment = Alignment.BottomCenter,
                            label = "playerControls"
                        ) { isCompact ->
                            if (isCompact) {
                                CompactControlsRow(
                                    state = state,
                                    visualsActive = visualsActive,
                                    page = page,
                                    onLyrics = onLyrics,
                                    onPrevious = onPrevious,
                                    onPlayPause = onPlayPause,
                                    onNext = onNext,
                                    onQueue = onQueue,
                                    modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
                                )
                            } else {
                                Column(Modifier.padding(bottom = 36.dp)) {
                                    TransportRow(
                                        state = state,
                                        visualsActive = visualsActive,
                                        onPrevious = onPrevious,
                                        onPlayPause = onPlayPause,
                                        onNext = onNext,
                                        modifier = Modifier.padding(vertical = 6.dp)
                                    )
                                    VolumeRow(onInteraction = onInteraction)
                                    Spacer(Modifier.height(8.dp))
                                    PlayerBottomRow(
                                        page = page,
                                        onLyrics = onLyrics,
                                        onQueue = onQueue,
                                        // 26 dp of content margin + 8 dp here puts the three actions on the same
                                        // columns as Previous / Play / Next above them.
                                        modifier = Modifier.padding(horizontal = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // The ••• menu's dim, drawn here over the whole player - up through the status bar, which
        // the menu's own window doesn't reach (its dim stopped short, leaving a bright strip).
        val menuDim by animateFloatAsState(if (overflowSong != null) 0.32f else 0f, tween(250), label = "menuDim")
        if (menuDim > 0f) Box(Modifier.fillMaxSize().drawBehind { drawRect(Color.Black, alpha = menuDim) })

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
    visualsActive: Boolean,
    dismissDrag: Modifier,
    overflowOpen: Boolean,
    onPageChange: (PlayerPage) -> Unit,
    onInteraction: () -> Unit,
    onOpenOverflow: () -> Unit,
    onOpenManualSearch: () -> Unit,
    onOpenLyricsSource: () -> Unit,
    // Closes the player before a suggestion's menu opens an artist or album page.
    onCollapse: () -> Unit,
    onClipShowing: (Boolean) -> Unit,
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

    // The title and everything under it share one side margin.
    val horizontalPadding = 26.dp
    // The cover fills the screen's width from its very top (behind the status bar and the
    // grabber, [coverLift] above this area) and has faded out into the player's colours by the
    // title, which sits just above the controls like Apple Music. It runs right down to the
    // title so no empty band separates them: a square cover is shown up to 1.22 times taller than
    // wide, on a very tall screen the band comes back rather than trimming the sides further.
    val coverLift = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 30.dp
    // Lyrics and Queue make this area taller. The cover and title keep the place they had on the
    // cover page while they fade, instead of following the new height down the screen.
    val coverPageHeight = remember { arrayOf(maxHeight) }
    if (page == PlayerPage.ARTWORK) coverPageHeight[0] = maxHeight
    val titleTop = (coverPageHeight[0] - 96.dp).coerceAtLeast(8.dp)
    val coverHeight = minOf(maxWidth * 1.22f, coverLift + titleTop).coerceAtLeast(160.dp)
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

    // Lyrics / Queue body, below the small header. Going back to the cover makes this area
    // shorter at once; the body keeps the height it had while it fades, so its list neither
    // shifts nor loses its lower part on the way out.
    val listPageHeight = remember { arrayOf(maxHeight) }
    if (page != PlayerPage.ARTWORK) listPageHeight[0] = maxHeight
    val bodyHeight = if (page == PlayerPage.ARTWORK) maxOf(listPageHeight[0], maxHeight) else maxHeight
    Box(
        Modifier
            .fillMaxWidth()
            .wrapContentHeight(Alignment.Top, unbounded = true)
            .height(bodyHeight)
            .padding(top = smallTop + smallSide + 8.dp)
            .graphicsLayer { alpha = morph.value }
    ) {
        Crossfade(targetState = page, animationSpec = tween(280), label = "playerPageBody") { shown ->
            when (shown) {
                PlayerPage.LYRICS -> LyricsPage(
                    state = state,
                    viewModel = viewModel,
                    effects = effects,
                    active = isExpanded && visualsActive,
                    onOpenManualSearch = onOpenManualSearch,
                    onOpenLyricsSource = onOpenLyricsSource,
                    onUserInteraction = onInteraction
                )
                PlayerPage.QUEUE -> QueuePage(state = state, viewModel = viewModel, onOpenPage = onCollapse)
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
                    modifier = if (isExpanded && visualsActive) Modifier.softEdgedMarquee() else Modifier
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
            // Past the side margin by the space inside the button, so the ••• itself ends where the
            // seek bar does.
            Box(Modifier.offset(x = 9.dp)) {
                PlayerActionButtons(
                    isFavorite = song?.isFavorite == true,
                    overflowOpen = overflowOpen,
                    onFavorite = { viewModel.toggleFavorite() },
                    onOverflow = onOpenOverflow,
                    plain = true
                )
            }
        }
    }

    // The line being sung, between the title and the seek bar (saved synced lyrics only - it
    // never searches on its own). Tapping it opens Lyrics.
    if (showCoverTitle && state.lyricLines.isNotEmpty()) {
        CurrentLyricLine(
            lines = state.lyricLines,
            viewModel = viewModel,
            active = isExpanded && visualsActive && page == PlayerPage.ARTWORK,
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
    val animatedCover = rememberAnimatedCover(
        song = song,
        wanted = effects.animatedCovers && isExpanded && visualsActive && onCover,
        allowMobileData = effects.animatedCoversOnMobileData
    )
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
                            colorStops = Array(CoverFadeCurve.size) { i ->
                                val (position, shade) = CoverFadeCurve[i]
                                position to Color.Black.copy(alpha = shade)
                            },
                            startY = 0f, endY = size.height
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
            AnimatedCoverLayer(
                cover = animatedCover,
                playing = state.isPlaying,
                onShowing = onClipShowing,
                modifier = Modifier.fillMaxSize()
            )
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
    val sleepTimer = (LocalContext.current.applicationContext as TgMusicApp).sleepTimer
    val timerOn = sleepTimer.state.collectAsState().value != com.abn3li.telemusic.playback.SleepTimer.State.Off
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (plain) 14.dp else 12.dp)) {
        if (timerOn) CircleActionButton(
            icon = Icons.Rounded.Bedtime,
            contentDescription = "Sleep timer on",
            inverted = false,
            plain = plain,
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onOverflow()
            }
        )
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
