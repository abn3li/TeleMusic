package com.abn3li.telemusic.ui.nowplaying

import com.abn3li.telemusic.ui.library.CalmSpinner
import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.material3.LocalTextStyle
import com.abn3li.telemusic.data.settings.PlayerEffects
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlin.math.abs

private val LyricEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private const val CASCADE_DURATION_MS = 620f
private const val CASCADE_STEP_MS = 42f
private const val CASCADE_MAX_STEPS = 12
private const val RESUME_FOLLOW_DELAY_MS = 1600L
private const val INTRO_COUNTDOWN_MIN_MS = 3000L

/**
 * The Lyrics page body: synced lyrics with the Apple-Music-style follow animation, or the
 * plain/empty/loading states. [onUserInteraction] keeps the player controls visible.
 */
@Composable
internal fun LyricsPage(
    state: NowPlayingUiState,
    viewModel: NowPlayingViewModel,
    effects: PlayerEffects,
    onOpenManualSearch: () -> Unit,
    onUserInteraction: () -> Unit,
    modifier: Modifier = Modifier
) {
    val song = state.song
    Box(modifier.fillMaxSize()) {
        when {
            state.isFetchingLyrics -> Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CalmSpinner(color = Color.White.copy(alpha = 0.8f), strokeWidth = 2.5.dp)
                Spacer(Modifier.height(14.dp))
                Text("Searching lyrics…", color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp)
            }

            state.lyricLines.isNotEmpty() -> SyncedLyrics(
                lines = state.lyricLines,
                viewModel = viewModel,
                effects = effects,
                onUserInteraction = onUserInteraction
            )

            !song?.lyricsPlain.isNullOrBlank() -> Column(
                Modifier
                    .fillMaxSize()
                    .lyricsLayer(effects.lyricsGlow)
                    .verticalScroll(rememberScrollState())
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onUserInteraction)
                    .padding(horizontal = 28.dp, vertical = 36.dp)
            ) {
                Text(
                    text = song.lyricsPlain,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 34.sp
                )
                Spacer(Modifier.height(120.dp))
            }

            else -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("No lyrics found", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Try again, or search with a different title and artist.",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(
                        onClick = { viewModel.fetchLyricsOnDemand() },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Color.White.copy(alpha = 0.14f),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Retry")
                    }
                    Button(
                        onClick = onOpenManualSearch,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Search")
                    }
                }
            }
        }
    }
}

/**
 * One follow step: every line is drawn [delta] px lower than its new resting place and settles
 * to 0, each line starting [CASCADE_STEP_MS] after the one above it - the staggered "wave".
 */
private class CascadeStep(val delta: Float, val origin: Int, val startNanos: Long) {
    fun offsetAt(index: Int, nowNanos: Long): Float {
        val steps = (index - origin + 1).coerceIn(0, CASCADE_MAX_STEPS)
        val elapsedMs = (nowNanos - startNanos) / 1_000_000f - steps * CASCADE_STEP_MS
        val progress = (elapsedMs / CASCADE_DURATION_MS).coerceIn(0f, 1f)
        return delta * (1f - LyricEasing.transform(progress))
    }

    fun isFinished(nowNanos: Long): Boolean =
        (nowNanos - startNanos) / 1_000_000f > CASCADE_DURATION_MS + CASCADE_MAX_STEPS * CASCADE_STEP_MS
}

@Stable
private class LyricCascade {
    val steps = mutableStateListOf<CascadeStep>()
    var frameNanos by mutableLongStateOf(0L)

    fun offsetFor(index: Int): Float {
        var total = 0f
        for (step in steps) total += step.offsetAt(index, frameNanos)
        return total
    }
}

@Composable
private fun SyncedLyrics(
    lines: List<LyricLine>,
    viewModel: NowPlayingViewModel,
    effects: PlayerEffects,
    onUserInteraction: () -> Unit
) {
    val progressState = viewModel.playbackProgress.collectAsState()
    val activeIndex by remember(lines) {
        derivedStateOf {
            val position = progressState.value.currentPositionMs
            lines.indexOfLast { it.timeMs <= position }
        }
    }
    val listState = rememberLazyListState()
    val cascade = remember(lines) { LyricCascade() }
    var autoFollow by remember { mutableStateOf(true) }
    val latestOnInteraction by rememberUpdatedState(onUserInteraction)
    val haptics = LocalHapticFeedback.current

    // A drag pauses auto-follow; it resumes once the fling has settled plus a short grace period.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collectLatest { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    autoFollow = false
                    latestOnInteraction()
                }
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    snapshotFlow { listState.isScrollInProgress }.first { !it }
                    delay(RESUME_FOLLOW_DELAY_MS)
                    autoFollow = true
                }
            }
        }
    }

    // Drives cascade frames only while a step is still settling, then stops.
    LaunchedEffect(cascade) {
        snapshotFlow { cascade.steps.size }.collectLatest { count ->
            if (count == 0) return@collectLatest
            while (cascade.steps.isNotEmpty()) {
                withFrameNanos { now ->
                    cascade.frameNanos = now
                    cascade.steps.removeAll { it.isFinished(now) }
                }
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val anchorPx = with(density) { (maxHeight * 0.14f).toPx() }
        val topSpacer = maxHeight * 0.14f

        LaunchedEffect(activeIndex, autoFollow) {
            if (!autoFollow || activeIndex < 0) return@LaunchedEffect
            val itemIndex = activeIndex + 1
            val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == itemIndex }
            if (target == null) {
                cascade.steps.clear()
                listState.animateScrollToItem(itemIndex, -anchorPx.toInt())
                return@LaunchedEffect
            }
            val delta = target.offset - anchorPx
            if (abs(delta) < 1f) return@LaunchedEffect
            listState.scrollBy(delta)
            cascade.steps.add(CascadeStep(delta, activeIndex, System.nanoTime().also { cascade.frameNanos = it }))
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .lyricsLayer(effects.lyricsGlow)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onUserInteraction)
        ) {
            item(key = "lyrics_intro") {
                Box(Modifier.fillMaxWidth().height(topSpacer), contentAlignment = Alignment.BottomStart) {
                    val firstTime = lines.first().timeMs
                    if (activeIndex < 0 && firstTime >= INTRO_COUNTDOWN_MIN_MS) {
                        CountdownDots(
                            progressState = progressState,
                            startMs = 0L,
                            endMs = firstTime,
                            modifier = Modifier.padding(start = 28.dp, bottom = 8.dp)
                        )
                    }
                }
            }
            itemsIndexed(lines, key = { index, line -> "${index}_${line.timeMs}" }) { index, line ->
                val isActive = index == activeIndex
                val distance = if (activeIndex < 0) index + 1 else abs(index - activeIndex)
                Box(Modifier.graphicsLayer { translationY = cascade.offsetFor(index) }) {
                    if (line.text.isBlank()) {
                        if (isActive) {
                            val endMs = lines.getOrNull(index + 1)?.timeMs ?: (line.timeMs + 5000L)
                            CountdownDots(
                                progressState = progressState,
                                startMs = line.timeMs,
                                endMs = endMs,
                                modifier = Modifier.padding(start = 28.dp, top = 14.dp, bottom = 14.dp)
                            )
                        } else {
                            Spacer(Modifier.height(6.dp))
                        }
                    } else {
                        LyricLineText(
                            text = line.text,
                            isActive = isActive,
                            distance = distance,
                            justPassed = activeIndex >= 0 && index == activeIndex - 1,
                            blurEnabled = autoFollow && effects.lyricsBlur,
                            glow = effects.lyricsGlow,
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                latestOnInteraction()
                                autoFollow = true
                                viewModel.seekTo(line.timeMs)
                            }
                        )
                    }
                }
            }
            item(key = "lyrics_tail") { Spacer(Modifier.height(maxHeight * 0.8f)) }
        }
    }
}

@Composable
private fun LyricLineText(
    text: String,
    isActive: Boolean,
    distance: Int,
    justPassed: Boolean,
    blurEnabled: Boolean,
    glow: Boolean,
    onClick: () -> Unit
) {
    // Soft halo on the line being sung: eases in after the line lights up, leaves quickly so the
    // old and new halos never overlap mid-scroll.
    val haloAlpha by animateFloatAsState(
        targetValue = if (glow && isActive) 0.6f else 0f,
        animationSpec = if (isActive) tween(500, delayMillis = 150, easing = LyricEasing) else tween(220, easing = LyricEasing),
        label = "lyricHalo"
    )
    val alpha by animateFloatAsState(
        targetValue = if (isActive) 1f else 0.3f,
        animationSpec = tween(durationMillis = 380, delayMillis = if (isActive) 120 else 60, easing = LyricEasing),
        label = "lyricAlpha"
    )
    val scale by animateFloatAsState(
        targetValue = if (isActive) 1f else 0.96f,
        animationSpec = tween(durationMillis = 420, delayMillis = if (isActive) 100 else 40, easing = LyricEasing),
        label = "lyricScale"
    )
    val blurTarget = if (!blurEnabled || isActive || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) 0.dp
    else (distance * 1.6f).coerceAtMost(6f).dp
    // Snapped, never animated: an animated radius visibly smears in/out around the line on every
    // change. The line just sung waits until it has scrolled away before blurring.
    val blurRadius by animateDpAsState(blurTarget, snap(delayMillis = if (justPassed) 260 else 0), label = "lyricBlur")

    Text(
        text = text,
        color = Color.White,
        fontSize = 30.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 38.sp,
        letterSpacing = (-0.3).sp,
        style = if (haloAlpha > 0.01f) {
            LocalTextStyle.current.copy(shadow = Shadow(Color.White.copy(alpha = haloAlpha), Offset.Zero, blurRadius = 36f))
        } else {
            LocalTextStyle.current
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 11.dp)
            .graphicsLayer {
                // ModulateAlpha fades each draw call directly instead of rendering into an
                // offscreen buffer the size of the line - that buffer clipped the halo into a
                // visible rectangle while the line faded in/out.
                compositingStrategy = CompositingStrategy.ModulateAlpha
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
            .then(if (blurRadius > 0.2.dp) Modifier.blur(blurRadius, BlurredEdgeTreatment.Unbounded) else Modifier)
    )
}

/** Three dots that fill one after another across an instrumental gap, gently breathing. */
@Composable
private fun CountdownDots(
    progressState: State<PlaybackProgress>,
    startMs: Long,
    endMs: Long,
    modifier: Modifier = Modifier
) {
    val span = (endMs - startMs).coerceAtLeast(1L)
    val target = ((progressState.value.currentPositionMs - startMs).toFloat() / span).coerceIn(0f, 1f)
    val fill by animateFloatAsState(target, tween(300, easing = LinearEasing), label = "countdownFill")
    val breathing = rememberInfiniteTransition(label = "countdownBreath")
    val breath by breathing.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LyricEasing), RepeatMode.Reverse),
        label = "countdownBreathScale"
    )
    Row(
        modifier.graphicsLayer {
            scaleX = breath
            scaleY = breath
            transformOrigin = TransformOrigin(0f, 0.5f)
        },
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        repeat(3) { dot ->
            val dotFill = (fill * 3f - dot).coerceIn(0f, 1f)
            Box(
                Modifier
                    .size(11.dp)
                    .graphicsLayer { alpha = 0.25f + 0.75f * dotFill }
                    .background(Color.White, CircleShape)
            )
        }
    }
}

/**
 * The lyrics sit in their own cached offscreen layer (edges faded). With [glow], that layer is
 * composited onto the artwork with an additive (Plus) blend so the text brightens the colours
 * under it - per frame this is just the cached layer drawn through one blend pass; the lyrics
 * themselves aren't re-rendered while only the background moves.
 */
private fun Modifier.lyricsLayer(glow: Boolean): Modifier = this
    .then(
        if (glow) Modifier.drawWithCache {
            val plus = Paint().apply { blendMode = BlendMode.Plus }
            val bounds = Rect(Offset.Zero, size)
            onDrawWithContent {
                drawContext.canvas.saveLayer(bounds, plus)
                drawContent()
                drawContext.canvas.restore()
            }
        } else Modifier
    )
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                0.07f to Color.Black,
                0.82f to Color.Black,
                1f to Color.Transparent
            ),
            blendMode = BlendMode.DstIn
        )
    }
