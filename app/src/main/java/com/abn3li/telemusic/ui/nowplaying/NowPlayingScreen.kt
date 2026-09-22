package com.abn3li.telemusic.ui.nowplaying

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.abn3li.telemusic.data.local.SongEntity
import com.abn3li.telemusic.data.local.displayArtwork
import com.abn3li.telemusic.playback.RepeatMode
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The sheet's own open/close motion - expand-from-mini-player and swipe-down-to-mini-player
 * both animate expansionFraction with this, so the two directions feel identical. Tuned to
 * match Spotify's now-playing sheet: quick and snappy with no visible bounce/overshoot at the
 * end, rather than the noticeably slower, softer settle StiffnessMediumLow (400) gave either
 * direction on its own. Nudged down from 800 - snappy still, just not quite as instant.
 */
private val SHEET_TRANSITION_SPEC = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 550f)

/**
 * Persistent player overlay, mounted once at the app's navigation root (never inside a
 * NavHost destination). Both the mini player and the full-screen "now playing" content are
 * ALWAYS composed - opening/closing is purely a graphicsLayer transform driven by one shared
 * Animatable, never a composable being created/destroyed. Routing the "open now playing" tap
 * through Navigation Compose meant every tap paid a full first-composition cost (new ViewModel,
 * new state collection, artwork color extraction) at the same time as the slide-in animation was
 * running, which is what made that transition - and the swipe back down - feel like a stutter
 * instead of one continuous motion.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlayerSheetOverlay(
    viewModel: NowPlayingViewModel,
    bottomOffset: Dp = 84.dp,
    modifier: Modifier = Modifier
) {
    val stableState by viewModel.stableUiState.collectAsState()
    val playbackProgress by viewModel.playbackProgress.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    val expansionFraction = remember { Animatable(0f) }
    var isExpanded by remember { mutableStateOf(false) }

    val configuration = LocalConfiguration.current
    val screenHeightPx = with(LocalDensity.current) { configuration.screenHeightDp.dp.toPx() }

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

    Box(modifier.fillMaxSize()) {
        MiniPlayer(
            state = stableState,
            playbackProgress = playbackProgress,
            onPlayPause = { viewModel.togglePlayPause() },
            onNext = { viewModel.nextSong() },
            onClick = { expand() },
            bottomOffset = bottomOffset,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .graphicsLayer {
                    // Fade out over the first half of the expansion so it's fully gone before
                    // the full player becomes visible - read here in the draw phase only.
                    alpha = (1f - expansionFraction.value * 2f).coerceIn(0f, 1f)
                }
        )

        NowPlayingContent(
            state = stableState,
            viewModel = viewModel,
            expansionFraction = expansionFraction,
            screenHeightPx = screenHeightPx,
            isExpanded = isExpanded,
            onCollapse = { collapse() },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Every transform for the whole open/close motion - fade, slide, and the
                    // rounded top corners - lives in this ONE graphicsLayer now. It used to be
                    // split across this one and a second, nested graphicsLayer inside
                    // NowPlayingContent's own container Box, both driven off the same
                    // expansionFraction: two composited layers being independently
                    // transformed every single animation frame instead of one.
                    val fraction = expansionFraction.value
                    val collapseProgress = (1f - fraction).coerceIn(0f, 1f)
                    // No alpha fade any more - the card (and everything else in this layer)
                    // stayed fully opaque while its own drawn area was on screen, then simply
                    // slid out of the viewport via translationY below; fading it on top of that
                    // was what made the card look increasingly see-through while being dragged,
                    // not sliding cleanly. MiniPlayer still fades itself IN independently as
                    // this layer slides down to reveal it - that reveal was never dependent on
                    // this layer's own alpha.
                    translationY = collapseProgress * screenHeightPx
                    // No scale ("squish") here on purpose: this is a fillMaxSize layer scaled
                    // around its center, so anything less than 1.0 scale leaves a gap at the
                    // true screen edges where the Library screen underneath (including its
                    // filter tab row) was still visible for part of the animation - that's the
                    // "lag" spotted right around where the tabs sit. Sliding alone doesn't have
                    // that problem: it's a fillMaxSize layer moving as a whole, so there's never
                    // a gap at its own edges, only the ordinary reveal of whatever is behind it
                    // as it moves out of the way - which is the point of a slide-to-dismiss.
                    // clip stays on for the entire animation instead of only while
                    // collapseProgress > 0 - toggling it off right at the final settle frame
                    // (the "last gap" as the sheet reaches the top) forced the layer to
                    // reconfigure its clipping right at that exact frame, which is what
                    // showed up as a stutter precisely at the end of every expand. A 0dp
                    // RoundedCornerShape at full expansion looks identical to no clip at all,
                    // so nothing changes visually - only the discrete on/off toggle is gone.
                    clip = true
                    val cornerRadius = 32.dp * collapseProgress
                    shape = RoundedCornerShape(topStart = cornerRadius, topEnd = cornerRadius)
                }
                .offset {
                    // Snapped off-screen while collapsed so it never intercepts touches meant
                    // for whatever NavHost content is showing underneath. Just past the bottom
                    // edge rather than absurdly far away (screenHeightPx is already enough to
                    // clear it) - pushing it that much farther away risked the OS treating the
                    // layer as fully discarded rather than merely off-screen, forcing a more
                    // expensive cold re-render (including the background blur) on every expand.
                    if (expansionFraction.value <= 0.001f) {
                        IntOffset(0, screenHeightPx.roundToInt() + 100)
                    } else {
                        IntOffset.Zero
                    }
                }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun NowPlayingContent(
    state: NowPlayingUiState,
    viewModel: NowPlayingViewModel,
    expansionFraction: Animatable<Float, *>,
    screenHeightPx: Float,
    isExpanded: Boolean,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var showLyricsView by remember { mutableStateOf(false) }
    var showQueueView by remember { mutableStateOf(false) }
    var showSongInfoDialog by remember { mutableStateOf(false) }
    var showManualLyricsDialog by remember { mutableStateOf(false) }

    // Without this, system back while Lyrics/Queue is open skipped straight past this whole
    // screen to whatever's behind the player sheet (the Library screen) - the OUTER
    // BackHandler(enabled=isExpanded) up in NowPlayingScreen only knows about the sheet being
    // expanded or not, it has no idea Lyrics/Queue is showing inside it. key()-wrapped for the
    // same reason as that outer one - forces re-registration (reclaiming front-of-stack
    // priority) the instant either flag flips true, instead of only toggling an already-
    // registered callback's enabled flag.
    key(showLyricsView, showQueueView) {
        BackHandler(enabled = showLyricsView || showQueueView) {
            if (showQueueView) showQueueView = false else showLyricsView = false
        }
    }

    // The button click at line ~800 only fires fetchLyricsOnDemand() for the OPEN action
    // itself - it never re-fires when the song underneath changes (Next/Previous/swipe) while
    // the lyrics view is already open, so skipping to a song with no cached lyrics just showed
    // the "No lyrics found" fallback instead of searching for it. This re-runs the same
    // no-cached-lyrics check every time the song id changes while showLyricsView is true.
    LaunchedEffect(state.song?.telegramMessageId, showLyricsView) {
        if (showLyricsView && state.song?.lyricsPlain.isNullOrBlank() && state.song?.lyricsSynced.isNullOrBlank()) {
            viewModel.fetchLyricsOnDemand()
        }
    }

    // Horizontal swipe-to-skip. The artwork stays in ONE place the whole time instead of
    // sliding multiple cards past each other (what a HorizontalPager here did before) - it
    // just nudges toward the drag direction as a hint, then triggers next()/previous() on
    // release past the threshold, exactly like tapping the skip button. Compositing/scaling
    // several full album-art cards simultaneously is real per-frame GPU cost purely from
    // existing on screen, regardless of how cheap each individual transform is made; one
    // static card with a small damped offset has essentially none - which is the actual
    // reason this never lags, not any particular animation trick. A full-bleed image sliding
    // instead would open a strip of bare backdrop down one edge of the screen.
    val swipeThresholdPx = with(LocalDensity.current) { 72.dp.toPx() }
    var swipeOffset by remember { mutableFloatStateOf(0f) }
    val swipeSettle by animateFloatAsState(
        targetValue = swipeOffset,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "swipeSettle"
    )

    // Signature Apple-Music-style touch: the sleeve shrinks back while paused, settles back
    // to full size on resume. Read only inside graphicsLayer below, never at composable scope.
    val artScale by animateFloatAsState(
        targetValue = if (state.isPlaying) 1f else 0.9f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "artScale"
    )

    // Auto-scroll-to-active-line and the isActive highlight both live in LyricsView.kt now -
    // that composable collects playbackProgress on its own, same reasoning as SeekbarSection's
    // own doc for why this screen can't just read state.activeLyricIndex here any more.

    val backgroundColor = MaterialTheme.colorScheme.background

    val artworkRequest = remember(state.song?.displayArtwork, context) {
        ImageRequest.Builder(context)
            .data(state.song?.displayArtwork)
            .crossfade(200)
            .allowHardware(true)
            .memoryCacheKey(state.song?.displayArtwork)
            .diskCacheKey(state.song?.displayArtwork)
            .build()
    }

    // The horizontal drag gesture's callbacks below live inside a pointerInput(Unit) block,
    // which launches its coroutine ONCE and never restarts it - so without this, onDragEnd
    // would keep checking hasNext/hasPrevious from whatever `state` was current the very
    // first time this composable ran, not the latest one.
    val latestState by rememberUpdatedState(state)

    Box(modifier = modifier) {
        if (showQueueView) {
            // Queue is its own full-screen view too (QueueView.kt), same pattern as LyricsView -
            // a reorderable "Up Next" list has nothing to do with the artwork/seekbar/transport
            // layout below, so it gets the whole screen rather than living inside a slot of it.
            QueueView(
                state = state,
                viewModel = viewModel,
                onClose = { showQueueView = false },
                onOpenLyrics = { showQueueView = false; showLyricsView = true },
                modifier = Modifier.fillMaxSize()
            )
        } else if (showLyricsView) {
            // Lyrics is its own full-screen view now (LyricsView.kt) - it used to render
            // inside the artwork card's own box, sharing the screen with the metadata/seekbar/
            // transport rows below it, which fought the artwork's horizontal swipe-to-skip
            // gesture region and read as visually unrelated to a synced-lyrics list anyway.
            LyricsView(
                state = state,
                viewModel = viewModel,
                onClose = { showLyricsView = false },
                onOpenManualSearch = { showManualLyricsDialog = true },
                onOpenQueue = { showLyricsView = false; showQueueView = true },
                modifier = Modifier.fillMaxSize()
            )
        } else {
        // Now Playing GPU Hardware Layer Container Sheet. Dragging this directly manipulates
        // the SAME expansionFraction the parent PlayerSheetOverlay uses for translationY/alpha,
        // so the drag, the squish/corner-radius feedback below, and the mini-player reveal are
        // all one continuous motion off one value - never a separate offset to keep in sync.
        Box(
            Modifier
                .fillMaxSize()
                // Opaque backing BEFORE the decorative blur/gradient layers below (which are
                // intentionally semi-transparent) so whatever NavHost content is behind this
                // always-composed overlay never bleeds through - previously this didn't matter
                // because Navigation Compose only ever drew one destination at a time. All
                // scale/fade/slide/corner-radius transforms for open/close live in the
                // parent's single graphicsLayer now (see PlayerSheetOverlay) - this Box no
                // longer needs its own.
                .background(backgroundColor)
                // Catch-all: Modifier.background() only draws, it never consumes touch input.
                // Any tap landing on a gap not covered by a button/gesture handler below
                // (padding, spacers, the space around the title/artist text) fell straight
                // through to whatever NavHost content is rendered underneath this
                // always-composed overlay - tapping "through" the expanded player onto the
                // Library screen behind it. A no-op clickable here absorbs every otherwise-
                // unhandled tap while the sheet is up; buttons and gesture areas inside still
                // get first refusal at handling their own taps/drags as before.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
        ) {
            // Real blurred album art backdrop (per the glassmorphic redesign spec) instead of
            // the mesh-of-averaged-colors ArtworkMeshBackdrop used to draw here. `state` passed
            // into this composable is stableUiState (see PlayerSheetOverlay), which does NOT
            // update on every 300ms position tick - only on a real song change - so this blur
            // isn't recomposed/rebuilt that often either way. Its ongoing cost is being
            // COMPOSITED every frame this screen is visible (same tradeoff already accepted for
            // the Library screen's ambient-blur background), not recomposition churn.
            // Modifier.blur() is a no-op below API 31 (no RenderEffect there) - the art just
            // renders sharp instead of blurred on those devices, which is the graceful fallback.
            AsyncImage(
                model = artworkRequest,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(60.dp)
            )

            // Ambient Dark Gradient Mask - keeps the app's own Material text/icon colors
            // legible over the blurred art regardless of how bright it is.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.35f),
                                Color.Black.copy(alpha = 0.55f),
                                Color.Black.copy(alpha = 0.92f)
                            )
                        )
                    )
            )

            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                // Drag-to-dismiss is scoped to just the top bar + artwork area, never the
                // playback controls below (a drag gesture and a button's clickable modifier
                // fighting over the same touch region is what caused an accidental pause
                // there before). Lyrics is its own separate full-screen view now (see
                // LyricsView.kt/showLyricsView's branch above) - this Column, and this drag
                // gesture, only ever run while showing the normal player, so the gesture no
                // longer needs to special-case a lyrics list living inside it.
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            // Whole-gesture average speed (total distance / total duration) -
                            // simpler and more reliable than VelocityTracker's recent-samples
                            // fit for this purpose, which under-reports on short, fast flicks
                            // that only produce a couple of drag events before release.
                            var gestureStartMs = 0L
                            var totalDragPx = 0f
                            detectVerticalDragGestures(
                                onDragStart = {
                                    gestureStartMs = System.currentTimeMillis()
                                    totalDragPx = 0f
                                },
                                onDragEnd = {
                                    val elapsedMs = (System.currentTimeMillis() - gestureStartMs).coerceAtLeast(1L)
                                    val avgVelocity = totalDragPx / (elapsedMs / 1000f)
                                    coroutineScope.launch {
                                        if (expansionFraction.value < 0.6f || avgVelocity > 500f) {
                                            onCollapse()
                                        } else {
                                            expansionFraction.animateTo(1f, SHEET_TRANSITION_SPEC)
                                        }
                                    }
                                },
                                onDragCancel = {
                                    coroutineScope.launch { expansionFraction.animateTo(1f, SHEET_TRANSITION_SPEC) }
                                },
                                onVerticalDrag = { change, dragAmount ->
                                    change.consume()
                                    totalDragPx += dragAmount
                                    val deltaFraction = -(dragAmount / screenHeightPx)
                                    coroutineScope.launch {
                                        expansionFraction.snapTo((expansionFraction.value + deltaFraction).coerceIn(0f, 1f))
                                    }
                                }
                            )
                        }
                ) {
                // Drag handle + source label, replacing the old TopAppBar - matches the
                // glassmorphic mockup (no traditional app bar). The whole thing is tappable to
                // collapse, same "tap the handle to dismiss" affordance the mockup's own drag
                // handle implies; the back arrow/Info button that used to live here moved into
                // the metadata row below (a MoreHoriz options button) so this row stays purely
                // the handle + context label.
                val sourceLabel = remember(state.song?.telegramMessageId) {
                    val song = state.song
                    when {
                        song == null -> "Not Playing"
                        song.youtubeVideoId != null -> "Playing from YouTube"
                        song.isLocalImport -> "Playing from Local Music"
                        else -> "Playing from Telegram"
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 4.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onCollapse
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 40.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = 0.35f))
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = sourceLabel,
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Main Display Area - just the artwork card now. Lyrics used to render here
                // too (swapped in via an if/else on showLyricsView) - it's its own full-screen
                // LyricsView now (see the branch at the top of this function), so this box only
                // ever shows the artwork.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                        // Single static artwork card - see the note on swipeOffset/swipeSettle
                        // above for why this deliberately isn't a multi-card carousel anymore.
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = Color.White.copy(alpha = 0.05f),
                                // No shadow here on purpose - it visibly followed the card
                                // around during swipe-down-to-dismiss (the whole sheet
                                // translating drags the shadow's own offset along with it),
                                // which read as distracting rather than adding depth. Border
                                // dropped too (per the glassmorphic redesign) - a translucent
                                // fill alone over the blurred backdrop already reads as glass.
                                // 0.78f, not full width - this sits inside a weight(1f) region
                                // that also has to leave room for the metadata row, seekbar,
                                // transport row, volume row, and footer below it; going full
                                // width read as the artwork swallowing the whole screen instead
                                // of sharing it with everything else.
                                // The swipe-to-skip gesture is attached HERE, on the card's own
                                // bounds, not on the enclosing fillMaxSize() Box above - that box
                                // is taller than this 1:1 card (it shares its weight(1f) region
                                // with letterboxing above/below), and attaching the gesture to it
                                // meant tapping/dragging that blank space - nowhere near the
                                // visible card - could still register as a swipe and skip tracks.
                                modifier = Modifier
                                    .fillMaxWidth(0.78f)
                                    .aspectRatio(1f)
                                    .pointerInput(Unit) {
                                        var total = 0f
                                        detectHorizontalDragGestures(
                                            onDragStart = { total = 0f },
                                            onDragCancel = { swipeOffset = 0f },
                                            onDragEnd = {
                                                when {
                                                    total <= -swipeThresholdPx -> if (latestState.hasNext) viewModel.nextSong()
                                                    total >= swipeThresholdPx -> if (latestState.hasPrevious) viewModel.previousSong()
                                                }
                                                swipeOffset = 0f
                                            },
                                            onHorizontalDrag = { change, delta ->
                                                change.consume()
                                                total += delta
                                                // Damped: a hint the finger can feel, not a
                                                // drag-to-position slider.
                                                swipeOffset = total * 0.35f
                                            }
                                        )
                                    }
                                    .graphicsLayer {
                                        translationX = swipeSettle
                                        // Pause-shrink: the card settles to
                                        // 90% size while paused and springs back on resume.
                                        scaleX = artScale
                                        scaleY = artScale
                                    }
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    // Fallback placeholder icon underneath
                                    Icon(
                                        imageVector = Icons.Default.MusicNote,
                                        contentDescription = null,
                                        modifier = Modifier.size(80.dp),
                                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                    )

                                    // Artwork Image rendered seamlessly on top
                                    if (!state.song?.displayArtwork.isNullOrEmpty()) {
                                        AsyncImage(
                                            model = artworkRequest,
                                            contentDescription = "Album Artwork",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }

                                    // Buffering overlay - small floating indicator only, no
                                    // shading over the artwork itself.
                                    if (state.loadingSongId != null || state.isBuffering) {
                                        Surface(
                                            shape = CircleShape,
                                            color = Color.Black.copy(alpha = 0.45f),
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .padding(12.dp)
                                                .size(36.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(20.dp),
                                                    strokeWidth = 2.dp,
                                                    color = Color.White
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // Directional hint - fades in with drag distance to show which
                            // way a release would skip. No sliding card, just a glyph; this
                            // is the ONLY per-frame visual cost of the whole gesture.
                            val swipeHintProgress = (abs(swipeSettle) / swipeThresholdPx).coerceIn(0f, 1f)
                            if (swipeHintProgress > 0.01f) {
                                val showNext = swipeSettle < 0f
                                val enabled = if (showNext) state.hasNext else state.hasPrevious
                                Icon(
                                    imageVector = if (showNext) Icons.Default.FastForward else Icons.Default.FastRewind,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                        alpha = swipeHintProgress * if (enabled) 0.85f else 0.3f
                                    ),
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 4.dp)
                                        .size(28.dp)
                                )
                            }
                        }
                }
                } // close drag-gesture-scoped Column (top bar + pager/lyrics)

                Spacer(Modifier.height(16.dp))

                // Song Info Header (Title, Artist, Like, Download, & Lyrics Buttons grouped together)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.song?.title ?: "Unknown Title",
                            fontSize = 23.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = if (isExpanded) Modifier.basicMarquee(iterations = 2) else Modifier
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = (state.song?.artist ?: "Unknown Artist").uppercase(),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Favorite/Download used to be their own separate circle buttons next to
                    // this one - combined into this single options menu instead, so the row
                    // reads as one action button rather than three competing for the same
                    // small strip of space next to a potentially-marqueeing title.
                    val isFav = state.song?.isFavorite == true
                    val isDownloaded = state.song?.isExplicitDownload == true
                    // A YouTube "Play" stream also has isLocalImport=true (see SongEntity's own
                    // doc) but no localFilePath - that combination still shows Download, so it
                    // can be saved for real instead of just streamed once (see
                    // NowPlayingViewModel.downloadEphemeralSong's own doc). Only a REAL local
                    // import (already fully on-device) hides it - nothing to download.
                    val isRealLocalImport = state.song?.isLocalImport == true && state.song?.localFilePath != null
                    var optionsMenuExpanded by remember { mutableStateOf(false) }

                    Box {
                        if (state.isDownloading) {
                            Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                            }
                        } else {
                            PlayerCircleGlyph(
                                icon = Icons.Default.MoreHoriz,
                                contentDescription = "Song options",
                                onClick = { optionsMenuExpanded = true }
                            )
                        }
                        DropdownMenu(expanded = optionsMenuExpanded, onDismissRequest = { optionsMenuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text(if (isFav) "Remove from Favorites" else "Add to Favorites") },
                                leadingIcon = { Icon(if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, contentDescription = null) },
                                onClick = { optionsMenuExpanded = false; viewModel.toggleFavorite() }
                            )
                            if (!isRealLocalImport) {
                                DropdownMenuItem(
                                    text = { Text(if (isDownloaded) "Downloaded" else "Download song") },
                                    leadingIcon = { Icon(if (isDownloaded) Icons.Default.CloudDone else Icons.Default.CloudDownload, contentDescription = null) },
                                    enabled = !isDownloaded,
                                    onClick = { optionsMenuExpanded = false; viewModel.downloadCurrentSong() }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Song info") },
                                leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                                onClick = { optionsMenuExpanded = false; showSongInfoDialog = true }
                            )
                        }
                    }
                }

                state.errorMessage?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp)
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Wrapped in its own composable that
                // collects playbackProgress itself - see SeekbarSection's doc.
                SeekbarSection(
                    viewModel = viewModel,
                    song = state.song,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )

                Spacer(Modifier.height(8.dp))

                // Material 3 Expressive Playback Control Row (Shuffle, Prev, Play/Pause, Next, Repeat)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp, top = 8.dp, start = 16.dp, end = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Shuffle button - filled disc only when
                    // active, transparent otherwise, rather than just a tint change.
                    PlayerToggleGlyph(
                        icon = Icons.Default.Shuffle,
                        contentDescription = "Toggle Shuffle",
                        highlighted = state.isShuffleEnabled,
                        onClick = { viewModel.toggleShuffle() }
                    )

                    // Skip Previous - a plain circular touch
                    // target with no filled background, disabled state fades the icon's alpha
                    // rather than greying the whole button out.
                    PlayerTransportGlyph(
                        icon = Icons.Default.SkipPrevious,
                        contentDescription = "Previous Track",
                        size = 30.dp,
                        enabled = state.hasPrevious,
                        onClick = { viewModel.previousSong() }
                    )

                    // Play / Pause - Prominent Filled Icon Button. Corner radius morphs between a
                    // rounder "squircle" while paused and a fuller circle while playing (matching
                    // the same touch other polished players give this exact button) instead of a
                    // fixed CircleShape - a small detail, but it's the one button on this screen
                    // you look at and tap the most.
                    val playPauseCornerRadius by animateDpAsState(
                        targetValue = if (state.isPlaying) 36.dp else 24.dp,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                        label = "playPauseCornerRadius"
                    )
                    FilledIconButton(
                        onClick = { viewModel.togglePlayPause() },
                        shape = RoundedCornerShape(playPauseCornerRadius),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier.size(72.dp)
                    ) {
                        if (state.isBuffering) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(32.dp),
                                strokeWidth = 3.dp
                            )
                        } else {
                            // Play/pause morph: a Crossfade so the transport button's icon
                            // dissolves between states instead of popping instantly.
                            Crossfade(
                                targetState = state.isPlaying,
                                animationSpec = tween(durationMillis = 180),
                                label = "playPauseMorph"
                            ) { playing ->
                                Icon(
                                    imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (playing) "Pause" else "Play",
                                    modifier = Modifier.size(38.dp)
                                )
                            }
                        }
                    }

                    // Skip Next
                    PlayerTransportGlyph(
                        icon = Icons.Default.SkipNext,
                        contentDescription = "Next Track",
                        size = 30.dp,
                        enabled = state.hasNext,
                        onClick = { viewModel.nextSong() }
                    )

                    // Repeat Button
                    val isRepeatActive = state.repeatMode != RepeatMode.OFF
                    PlayerToggleGlyph(
                        icon = if (state.repeatMode == RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        contentDescription = "Toggle Repeat Mode",
                        highlighted = isRepeatActive,
                        onClick = { viewModel.toggleRepeat() }
                    )
                }

                // Real device volume (STREAM_MUSIC), not a fake/decorative slider - per the
                // glassmorphic redesign spec's volume row.
                DeviceVolumeRow(modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))

                // Footer: lyrics toggle (left), a purely decorative "this phone" glass pill
                // (center - this app has no cast/multi-device output, so it's visual only,
                // matching the mockup's own non-functional device icons), queue (right).
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DockToggleGlyph(
                        icon = Icons.Default.ChatBubbleOutline,
                        contentDescription = "Toggle Lyrics",
                        active = showLyricsView,
                        onClick = { showLyricsView = !showLyricsView }
                    )

                    // Same isFavorite/isExplicitDownload/isLocalImport reads and
                    // toggleFavorite()/downloadCurrentSong() actions the "..." options menu above
                    // uses - kept as a second local read rather than hoisting isFav/isDownloaded/
                    // isRealLocalImport out of that Row's scope, since both are cheap derived
                    // reads off the same `state.song`.
                    val isSongFavorite = state.song?.isFavorite == true
                    val isSongDownloaded = state.song?.isExplicitDownload == true
                    val isSongRealLocalImport = state.song?.isLocalImport == true && state.song?.localFilePath != null
                    DownloadSavePill(
                        isDownloaded = isSongDownloaded || isSongRealLocalImport,
                        isSaved = isSongFavorite,
                        isDownloading = state.isDownloading,
                        downloadEnabled = !isSongDownloaded && !isSongRealLocalImport,
                        onDownloadClick = { viewModel.downloadCurrentSong() },
                        onSaveClick = { viewModel.toggleFavorite() }
                    )

                    DockToggleGlyph(
                        icon = Icons.Default.QueueMusic,
                        contentDescription = "Queue",
                        onClick = { showQueueView = true }
                    )
                }
            }
        }
        }

        // Manual Custom Lyrics Search Dialog
        if (showManualLyricsDialog && state.song != null) {
            var customTitle by remember { mutableStateOf(state.song.title) }
            var customArtist by remember { mutableStateOf(state.song.artist) }

            AlertDialog(
                onDismissRequest = { showManualLyricsDialog = false },
                title = { Text("Search Lyrics Manually") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Type the exact song title and artist name to search lyrics across LRCLIB and lyrics.ovh:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = customTitle,
                            onValueChange = { customTitle = it },
                            label = { Text("Song Title") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = customArtist,
                            onValueChange = { customArtist = it },
                            label = { Text("Artist Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showManualLyricsDialog = false
                            viewModel.fetchLyricsCustom(customTitle, customArtist)
                        }
                    ) {
                        Text("Search Lyrics")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showManualLyricsDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Pop-up Overlay (100% Exclusively Colored from Song's Album Artwork Palette)
        AnimatedVisibility(
            visible = showSongInfoDialog && state.song != null,
            enter = fadeIn(animationSpec = tween(220)) + scaleIn(animationSpec = tween(220), initialScale = 0.88f),
            exit = fadeOut(animationSpec = tween(180)) + scaleOut(animationSpec = tween(180), targetScale = 0.88f)
        ) {
            val song = state.song!!
            val fileOnDisk = song.localFilePath?.let { File(it) }
            val sizeInBytes = fileOnDisk?.takeIf { it.exists() }?.length() ?: 0L
            val sizeMB = if (sizeInBytes > 0) String.format(Locale.US, "%.2f MB", sizeInBytes / (1024.0 * 1024.0)) else "Streaming"

            val (containerFormat, bitrateText) = detectAudioFormat(song.localFilePath, song.durationSeconds)

            val storageStatus = when {
                // isLocalImport is also true for an in-memory YouTube "Play" stream (see
                // SongEntity's own doc) - only a real local import has a localFilePath.
                song.isLocalImport && song.localFilePath != null -> "Imported from Device"
                song.isExplicitDownload -> "Downloaded (Offline)"
                song.localFilePath != null -> "Cached on Storage"
                song.isLocalImport -> "Streaming from YouTube"
                else -> "Streaming via TDLib"
            }

            // Fixed theme colors, not the song's own album art palette - see GlassInfoRow's doc
            // for why that was the actual source of the "colors/titles sometimes disappear" bug.
            val cardBgColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f)
            val accentColor = MaterialTheme.colorScheme.primary

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // No background at all - fully transparent. Only the popup card itself
                    // should read as blurred/frosted (its own internal blurred artwork overlay
                    // already does that); the rest of the screen stays exactly as it was, with
                    // nothing darkening or blurring it. This Box still exists purely to catch
                    // an outside tap and dismiss the popup.
                    //
                    // indication = null: plain clickable() draws Material's default ripple on
                    // press, which was the only thing left drawing anything over the now-fully-
                    // transparent background - a white flash every time the area outside the
                    // card was tapped to dismiss it.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { showSongInfoDialog = false },
                contentAlignment = Alignment.Center
            ) {
                // Card Surface - fixed theme colors, see cardBgColor's own doc above
                Surface(
                    onClick = {}, // Prevent tap through
                    shape = RoundedCornerShape(28.dp),
                    color = cardBgColor,
                    // Zero, not just no shadowElevation: Material3's tonalElevation blends a
                    // surfaceTint overlay onto the surface color, and at 20.dp (near the top of
                    // the scale) that overlay alone reads as a dark cast over the whole card,
                    // which is indistinguishable from a shadow at a glance. shadowElevation was
                    // already zeroed for the actual drop-shadow ring; this removes the other
                    // thing that was darkening the popup.
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier
                        .fillMaxWidth(0.86f)
                        .wrapContentHeight()
                        .clip(RoundedCornerShape(28.dp))
                ) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        // Blurred album artwork overlay inside card
                        if (!song.displayArtwork.isNullOrEmpty()) {
                            AsyncImage(
                                model = song.displayArtwork,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .matchParentSize()
                                    .blur(30.dp)
                                    // The Surface's own clip only masked THIS image's plain
                                    // rectangular bounds to its rounded shape in theory - in
                                    // practice the blur's own square edges were still visibly
                                    // bleeding past the rounded corners (worst at the bottom
                                    // and left, matching the screenshot), reading as a shadow
                                    // ring around the popup. Clipping the blur's own output
                                    // directly, rather than relying on the ancestor's clip to
                                    // catch it, is what actually confines it.
                                    .clip(RoundedCornerShape(28.dp))
                                    .alpha(0.20f)
                            )
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Header badge - white pill, black icon/text, matching the app's
                            // one fixed "main action" style everywhere else.
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = accentColor,
                                modifier = Modifier.padding(bottom = 16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Info,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        "AUDIO DETAILS",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                            }

                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                GlassInfoRow(Icons.Default.MusicNote, "Title", song.title)
                                GlassInfoRow(Icons.Default.Person, "Artist", song.artist)
                                GlassInfoRow(Icons.Default.Album, "Album", song.album?.ifBlank { "Unknown Album" } ?: "Unknown Album")
                                GlassInfoRow(Icons.Default.AudioFile, "Audio Codec", containerFormat)
                                GlassInfoRow(Icons.Default.GraphicEq, "Bitrate", bitrateText)
                                GlassInfoRow(Icons.Default.Timer, "Duration", formatMs(song.durationSeconds * 1000L))
                                GlassInfoRow(Icons.Default.Storage, "File Size", sizeMB)
                                GlassInfoRow(Icons.Default.Storage, "Storage", storageStatus)
                            }

                            Spacer(Modifier.height(20.dp))

                            FilledTonalButton(
                                onClick = { showSongInfoDialog = false },
                                shape = RoundedCornerShape(16.dp),
                                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text("Done", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Wraps the seekbar in its own composable that collects [NowPlayingViewModel.playbackProgress]
 * itself, rather than reading currentPositionMs/durationMs off the [NowPlayingUiState] the rest
 * of the screen uses. That state (see [NowPlayingViewModel.stableUiState]) deliberately no
 * longer updates every 300ms - only this narrow scope does now, isolating the recomposition
 * cost of following playback to just the slider instead of the entire Now Playing screen.
 */
@Composable
private fun SeekbarSection(
    viewModel: NowPlayingViewModel,
    song: SongEntity?,
    modifier: Modifier = Modifier
) {
    val progress by viewModel.playbackProgress.collectAsState()

    // Minimalist thin slider (per the glassmorphic redesign spec) instead of the WaveformSeekBar
    // this screen used before - a plain Slider with a small thumb, not per-sample bars.
    // isDragging gates which value the slider shows: while the user is actively dragging, the
    // real ticking position (progress.currentPositionMs, updating every 300ms) must NOT override
    // their thumb position mid-gesture - only once they release does the real position resume
    // driving the display, right after the seek this same release triggers lands.
    var isDragging by remember { mutableStateOf(false) }
    var dragPositionMs by remember { mutableFloatStateOf(progress.currentPositionMs.toFloat()) }
    val displayedPositionMs = if (isDragging) dragPositionMs else progress.currentPositionMs.toFloat()
    Column(modifier = modifier) {
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
            modifier = Modifier.fillMaxWidth().height(20.dp)
        )

        // Three equal-weight slots (not Arrangement.SpaceBetween on raw children) so the center
        // badge stays put - SpaceBetween just puts the badge "between" the two time labels, and
        // those labels change width as their digit count changes (0:09 -> 0:10), which visibly
        // shifted the badge left/right every tick. Each slot now owns a fixed third of the row
        // and aligns its own content within it, so the badge's slot - and its centered position
        // inside that slot - never moves regardless of how wide the time text on either side is.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, top = 2.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatMs(displayedPositionMs.toLong()),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f),
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )

            // Codec/bitrate badge, matching the "MP3 - 192 kbps" style quality badge other media
            // players show here - reuses detectAudioFormat, the same real-header-sniffing +
            // file-size-derived-bitrate logic the Song Info popup already shows, instead of the
            // source label (Telegram/YouTube/Local) this slot showed before.
            val badgeText = remember(song?.telegramMessageId, song?.localFilePath) {
                if (song == null) "" else {
                    val (format, bitrate) = detectAudioFormat(song.localFilePath, song.durationSeconds)
                    val shortFormat = format.substringBefore(" ").substringBefore("(").trim()
                    if (bitrate.isBlank()) shortFormat else "$shortFormat · $bitrate"
                }
            }
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (badgeText.isNotEmpty()) {
                    Icon(
                        Icons.Default.Headphones,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = badgeText,
                        fontSize = 10.sp,
                        color = Color.White.copy(alpha = 0.6f),
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            val remainingMs = (progress.durationMs - displayedPositionMs.toLong()).coerceAtLeast(0)
            Text(
                text = "-${formatMs(remainingMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f),
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * Real device volume (STREAM_MUSIC) - not a fake/decorative slider. Stays in sync with the
 * hardware volume buttons (or another app changing the stream) via VOLUME_CHANGED_ACTION, not
 * just its own drags - without that, pressing the phone's volume rocker while this screen is
 * open would leave the slider showing a stale position until it was reopened.
 */
@Composable
private fun DeviceVolumeRow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volume by remember { mutableFloatStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) / maxVolume.toFloat()) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) == AudioManager.STREAM_MUSIC) {
                    volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) / maxVolume.toFloat()
                }
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION"), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.VolumeMute, contentDescription = null, tint = Color.White.copy(alpha = 0.55f), modifier = Modifier.size(18.dp))
        Slider(
            value = volume,
            onValueChange = { newVolume ->
                volume = newVolume
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (newVolume * maxVolume).roundToInt(), 0)
            },
            colors = SliderDefaults.colors(
                thumbColor = Color.Transparent,
                activeTrackColor = Color.White.copy(alpha = 0.85f),
                inactiveTrackColor = Color.White.copy(alpha = 0.2f)
            ),
            modifier = Modifier.weight(1f).height(16.dp)
        )
        Icon(Icons.Default.VolumeUp, contentDescription = null, tint = Color.White.copy(alpha = 0.55f), modifier = Modifier.size(18.dp))
    }
}

/**
 * A small translucent disc whose fill brightens when [active], with the icon itself Crossfading
 * between states rather than popping. Used here for the favorite/download/lyrics-toggle row.
 * Themed off this app's own Material colors so it reads correctly in both light and dark themes.
 */
@Composable
private fun PlayerCircleGlyph(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    active: Boolean = false
) {
    val discAlpha by animateFloatAsState(
        targetValue = if (active) 0.20f else 0.10f,
        label = "glyphDisc"
    )
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = discAlpha))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Crossfade(targetState = icon, animationSpec = tween(durationMillis = 180), label = "circleGlyph") { glyph ->
            Icon(
                imageVector = glyph,
                contentDescription = contentDescription,
                tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(19.dp)
            )
        }
    }
}

/**
 * A plain circular touch target with no filled background - a disabled state fades the icon's
 * own alpha rather than greying out a whole button shape, which is what Material's
 * IconButton/FilledTonalIconButton did before.
 */
@Composable
private fun PlayerTransportGlyph(
    icon: ImageVector,
    contentDescription: String,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    val iconAlpha by animateFloatAsState(targetValue = if (enabled) 1f else 0.35f, label = "transportAlpha")
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    // Same spring press-scale as DockToggleGlyph (the lyrics/queue dock icons) - skip/previous
    // had a disabled-state fade already but nothing at all for the tap itself.
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh),
        label = "transportPressScale"
    )
    Box(
        modifier = Modifier
            .size(size + 24.dp)
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = iconAlpha),
            modifier = Modifier.size(size)
        )
    }
}

/** Shuffle/repeat toggle row, filled disc only when on. */
@Composable
private fun PlayerToggleGlyph(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    highlighted: Boolean = false
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "toggleGlyphBackground"
    )
    val tint by animateColorAsState(
        targetValue = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
        animationSpec = tween(durationMillis = 220),
        label = "toggleGlyphTint"
    )
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh),
        label = "toggleGlyphPressScale"
    )
    Box(
        modifier = Modifier
            .size(44.dp)
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .clip(CircleShape)
            .background(backgroundColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(24.dp)
        )
    }
}

/**
 * The lyrics-toggle/queue icon buttons in the player dock's footer row (this file, LyricsView.kt,
 * QueueView.kt) used to be bare IconButtons with a static tint - the only feedback tapping them
 * gave was the platform ripple, and toggled state (lyrics on/off) just snapped instantly. This
 * gives them the same kind of press-scale + fading active pill + tint crossfade that reference
 * players like Metrolist/InnerTune give their dock controls: a spring-based press-down scale for
 * tactile feedback, a soft white disc that fades in behind the icon while [active], and the icon
 * tint itself crossfading instead of hard-cutting between its on/off alpha. `internal` (not
 * `private`) so LyricsView.kt/QueueView.kt can call it too.
 */
@Composable
internal fun DockToggleGlyph(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    active: Boolean = false,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.82f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh),
        label = "dockGlyphPress"
    )
    val backgroundAlpha by animateFloatAsState(
        targetValue = if (active) 0.22f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "dockGlyphBackground"
    )
    val tint by animateColorAsState(
        targetValue = if (active) Color.White else Color.White.copy(alpha = 0.65f),
        animationSpec = tween(durationMillis = 220),
        label = "dockGlyphTint"
    )
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = backgroundAlpha))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/**
 * The footer's center pill - Download and Favorite, sharing the exact same actions as the "..."
 * options menu above (viewModel.toggleFavorite()/downloadCurrentSong()) instead of duplicating
 * that logic. Replaces the old decorative "This phone" device pill, which had no click handlers
 * at all - just a headphones/person glyph pair that never did anything.
 *
 * No `.blur()` on the pill itself: the reference design asks for a CSS `backdrop-filter` (blurs
 * what's BEHIND the pill, letting the artwork/backdrop show through softened). Compose's
 * `Modifier.blur()` only blurs what's INSIDE the composable it's applied to - on a Row wrapping
 * the icons themselves, that would blur the icons into illegibility, not the backdrop. Real
 * backdrop blur needs the Haze library, which (per AmbientBackground.kt's own doc) isn't a
 * dependency here - so this pill stays a plain translucent fill, same as the DockToggleGlyph
 * buttons and the pill it replaces.
 */
@Composable
internal fun DownloadSavePill(
    isDownloaded: Boolean,
    isSaved: Boolean,
    onDownloadClick: () -> Unit,
    onSaveClick: () -> Unit,
    isDownloading: Boolean = false,
    downloadEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.11f))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (isDownloading) {
            Box(modifier = Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
            }
        } else {
            PillActionGlyph(
                icon = Icons.Default.FileDownload,
                contentDescription = if (isDownloaded) "Downloaded" else "Download song",
                active = isDownloaded,
                enabled = downloadEnabled,
                onClick = onDownloadClick
            )
        }

        Box(Modifier.width(1.dp).height(16.dp).background(Color.White.copy(alpha = 0.18f)))

        PillActionGlyph(
            icon = if (isSaved) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            contentDescription = if (isSaved) "Remove from Favorites" else "Add to Favorites",
            active = isSaved,
            onClick = onSaveClick
        )
    }
}

/** One icon inside [DownloadSavePill] - dim white by default, full white + press-scale when tapped. */
@Composable
private fun PillActionGlyph(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressScale = rememberPressScale(interactionSource)
    val tint by animateColorAsState(
        targetValue = if (active) Color.White else Color.White.copy(alpha = 0.7f),
        animationSpec = tween(durationMillis = 200),
        label = "pillGlyphTint"
    )
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = tint,
        modifier = Modifier
            .size(22.dp)
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
    )
}

/**
 * Same spring press-scale DockToggleGlyph/PlayerToggleGlyph/PlayerTransportGlyph all use, factored
 * out so LyricsView.kt/QueueView.kt's own plain `Icon(...).clickable(...)` transport rows (which
 * only ever had a hard on/off `.alpha()` for enabled/disabled and nothing at all for the tap
 * itself) can get the same tactile feedback without duplicating the boilerplate. `internal` so
 * those files can call it.
 */
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

/**
 * Inspects file headers to detect the exact audio codec/container format (MP3, M4A/AAC, FLAC, OGG, WAV).
 */
private fun detectAudioFormat(filePath: String?, durationSec: Int): Pair<String, String> {
    if (filePath == null) return "Audio Track" to "320 kbps"
    val file = File(filePath)
    val duration = durationSec.coerceAtLeast(1)

    if (!file.exists() || file.length() < 12) {
        val ext = file.extension.lowercase(Locale.US)
        val fmt = when (ext) {
            "mp3" -> "MP3"
            "m4a", "aac", "mp4" -> "M4A / AAC"
            "flac" -> "FLAC (Lossless)"
            "ogg", "opus" -> "OGG / Opus"
            "wav" -> "WAV"
            "dsf", "dff" -> "DSD / DSF"
            else -> ext.uppercase(Locale.US).ifBlank { "Audio Track" }
        }
        return fmt to "320 kbps"
    }

    try {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(16)
            raf.readFully(header)

            val fileBytes = file.length()
            val bitrateKbps = (fileBytes * 8 / duration / 1000)

            // 0. DSD / DSF Container ("DSD " at byte 0..3)
            if (header[0] == 0x44.toByte() && header[1] == 0x53.toByte() && header[2] == 0x44.toByte() && header[3] == 0x20.toByte()) {
                return "DSD / DSF" to "Hi-Res DSD"
            }

            // 1. MP3 ("ID3" at byte 0..2 or 0xFF 0xFB)
            if ((header[0] == 0x49.toByte() && header[1] == 0x44.toByte() && header[2] == 0x33.toByte()) ||
                (header[0] == 0xFF.toByte() && (header[1].toInt() and 0xE0) == 0xE0)) {
                return "MP3" to "$bitrateKbps kbps"
            }

            // 2. M4A / AAC ("ftyp" at byte 4..7)
            if (header[4] == 0x66.toByte() && header[5] == 0x74.toByte() && header[6] == 0x79.toByte() && header[7] == 0x70.toByte()) {
                return "M4A / AAC" to "$bitrateKbps kbps"
            }

            // 3. FLAC ("fLaC" at byte 0..3)
            if (header[0] == 0x66.toByte() && header[1] == 0x4C.toByte() && header[2] == 0x61.toByte() && header[3] == 0x43.toByte()) {
                return "FLAC (Lossless)" to "Hi-Res Lossless"
            }

            // 4. OGG / Opus ("OggS" at byte 0..3)
            if (header[0] == 0x4F.toByte() && header[1] == 0x67.toByte() && header[2] == 0x68.toByte() && header[3] == 0x53.toByte()) {
                return "OGG / Opus" to "$bitrateKbps kbps"
            }

            // 5. WAV ("RIFF" at byte 0..3)
            if (header[0] == 0x52.toByte() && header[1] == 0x56.toByte() && header[2] == 0x46.toByte() && header[3] == 0x46.toByte()) {
                return "WAV (Uncompressed)" to "Uncompressed Audio"
            }
        }
    } catch (_: Exception) {
    }

    val ext = file.extension.uppercase(Locale.US).ifBlank { "Audio Track" }
    return ext to "320 kbps"
}

@Composable
private fun GlassInfoRow(icon: ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Fixed white badge / black icon (matches the rest of the app's "main action" style -
        // the selected tab chip, filled buttons, etc.) - this used to be tinted from the song's
        // own album art palette, which is exactly why it (and the value text below, which
        // inherited its color from the same artwork-derived card background) sometimes read as
        // invisible: Palette extraction can fail, run late, or land on a color Material3 can't
        // derive a safe contrasting content color from for an arbitrary/unbounded background.
        // Fixed theme colors always have a well-defined contrast pair, so that whole class of
        // bug is gone by construction now.
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(34.dp)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

internal fun formatMs(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}