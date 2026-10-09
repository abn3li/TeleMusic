package com.abn3li.telemusic.ui.navigation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.abn3li.telemusic.data.local.displayArtwork
import com.abn3li.telemusic.ui.library.CalmSpinner
import com.abn3li.telemusic.ui.nowplaying.NowPlayingUiState
import com.abn3li.telemusic.ui.nowplaying.PlayerTransition
import com.abn3li.telemusic.ui.nowplaying.easeOutCubic
import com.abn3li.telemusic.ui.theme.LocalPalette
import kotlin.math.abs

internal val ClassicDockInset = 136.dp
internal val ClassicTabsInset = 72.dp
internal val ClassicMiniPlayerInset = 58.dp

/** The tabs share one capsule; the transport stays in its own row above it. */
@Composable
internal fun ClassicDock(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    backdrop: FrostedBackdrop,
    state: NowPlayingUiState,
    bottomInset: Dp,
    onExpand: () -> Unit,
    onExpandDrag: (Float) -> Unit,
    onExpandDragEnd: (Float) -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    transition: PlayerTransition,
    showTabs: Boolean,
    modifier: Modifier = Modifier
) {
    if (!showTabs && state.song == null) return

    // The player and the tabs share one frosted material: the page is blurred once for both,
    // except while they move with the player (see FrostedCapsules.paused).
    val density = LocalDensity.current
    val sink = with(density) { 90.dp.toPx() }
    // How each part moves with the player - the content (below) and its glass (drawn by the
    // group, see FrostedCapsules.moves) follow the same numbers.
    val playerMove = {
        val progress = transition.expansion.value
        val from = transition.origin?.player?.top ?: transition.dockPlayer?.top ?: 0f
        // Whole again just before the player has closed: the closing slows right down at its
        // end, and a capsule still fading in through those frames then snapped to solid (a flash).
        CapsuleMove(dy = -from * progress, scale = 1f, alpha = 1f - transition.stage(0.02f, 0.12f))
    }
    val tabsMove = {
        val gone = easeOutCubic(transition.stage(0.06f, 0.6f))
        CapsuleMove(dy = sink * gone, scale = 1f - 0.04f * gone, alpha = 1f - gone)
    }
    val capsules = remember(transition) {
        FrostedCapsules().apply {
            moving = { transition.expansion.value > 0.001f }
            moves[0] = playerMove
            moves[1] = tabsMove
        }
    }
    Column(modifier.fillMaxWidth()
        .padding(start = 16.dp, end = 16.dp, bottom = maxOf(bottomInset, 15.dp) + 2.dp)
        .clickable(remember { MutableInteractionSource() }, indication = null) {}
        .frostedCapsuleGroup(backdrop, capsules)) {
        if (state.song != null) {
            ClassicMiniPlayer(state, onExpand, onExpandDrag, onExpandDragEnd, onPlayPause, onNext, onPrevious, transition,
                Modifier.fillMaxWidth().height(56.dp)
                    .onGloballyPositioned { transition.dockPlayer = it.boundsInRoot() }
                    .graphicsLayer {
                        // Rides up with the card's top edge and gives way to it quickly, so the
                        // artwork and title flying out of it are seen.
                        val move = playerMove()
                        translationY = move.dy
                        alpha = move.alpha
                    }
                    .frostedCapsule(capsules, 0))
            if (showTabs) Spacer(Modifier.height(8.dp))
        }
        if (showTabs) ClassicTabs(currentRoute, onNavigate, capsules, Modifier.graphicsLayer {
            // The tabs sink away over the first half of the opening and rise back as it closes.
            val move = tabsMove()
            translationY = move.dy
            scaleX = move.scale
            scaleY = move.scale
            alpha = move.alpha
        })
    }
}

@Composable
private fun ClassicTabs(currentRoute: String?, onNavigate: (String) -> Unit, capsules: FrostedCapsules, modifier: Modifier = Modifier) {
    val routes = remember { listOf(Routes.HOME, Routes.DISCOVER, Routes.SEARCH, Routes.LIBRARY) }
    val selectedIndex = routes.indexOf(currentRoute)
    val indicator = animateFloatAsState(selectedIndex.coerceAtLeast(0).toFloat(),
        spring(dampingRatio = 0.72f, stiffness = 320f), label = "tabIndicator")
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current
    val ink = LocalPalette.current.ink
    var rowSize by remember { mutableStateOf(IntSize.Zero) }
    Box(modifier.fillMaxWidth().frostedCapsule(capsules, 1).padding(6.dp)) {
        val gap = 6.dp
        val rowWidth = with(density) { rowSize.width.toDp() }
        val rowHeight = with(density) { rowSize.height.toDp() }
        val tabWidth = ((rowWidth - gap * (routes.size - 1)) / routes.size).coerceAtLeast(0.dp)
        // Keep the highlight around the glyph and label on wider screens too.
        val highlightWidth = ((rowWidth - gap * 3) / 4).coerceIn(0.dp, tabWidth)
        val stride = with(density) { (tabWidth + gap).toPx() }
        val highlightInset = with(density) { ((tabWidth - highlightWidth) / 2).toPx() }
        if (selectedIndex >= 0 && rowSize.width > 0) {
            Box(Modifier.width(highlightWidth).height(rowHeight).graphicsLayer {
                translationX = (indicator.value * stride + highlightInset) * if (rtl) -1f else 1f
                val lag = abs(selectedIndex - indicator.value).coerceIn(0f, 1f)
                scaleX = 1f + lag * 0.16f
                scaleY = 1f - lag * 0.08f
            }.clip(RoundedCornerShape(percent = 50)).background(ink.copy(alpha = 0.14f)))
        }
        // Measure the content instead of fixing the bar's height; larger text can still fit.
        Row(Modifier.fillMaxWidth().onSizeChanged { rowSize = it },
            horizontalArrangement = Arrangement.spacedBy(gap)) {
            ClassicTab(NavIcons.Home, "Home", selectedIndex == 0, { onNavigate(Routes.HOME) }, Modifier.weight(1f))
            ClassicTab(NavIcons.Discover, "Discover", selectedIndex == 1, { onNavigate(Routes.DISCOVER) }, Modifier.weight(1f))
            ClassicTab(NavIcons.Search, "Search", selectedIndex == 2, { onNavigate(Routes.SEARCH) }, Modifier.weight(1f))
            ClassicTab(NavIcons.Library, "Library", selectedIndex == 3, { onNavigate(Routes.LIBRARY) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ClassicTab(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press = animateFloatAsState(if (pressed) 0.93f else 1f,
        spring(dampingRatio = 1f, stiffness = 500f), label = "tabPress")
    val glyphScale = animateFloatAsState(if (selected) 1.08f else 1f,
        spring(dampingRatio = 0.72f, stiffness = 320f), label = "tabGlyph")
    val haptic = LocalHapticFeedback.current
    val palette = LocalPalette.current
    val tint = if (selected) palette.ink else if (palette.isLight)
        androidx.compose.ui.graphics.Color(0xFF6E6E73) else androidx.compose.ui.graphics.Color(0xFF8E8E93)
    Column(modifier.semantics { this.selected = selected }
        .clip(RoundedCornerShape(percent = 50))
        .clickable(interaction, indication = null, role = Role.Tab) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        }.graphicsLayer { scaleX = press.value; scaleY = press.value }.padding(vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(25.dp).graphicsLayer {
            scaleX = glyphScale.value; scaleY = glyphScale.value
        })
        Spacer(Modifier.height(2.dp))
        Text(label, color = tint, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp, lineHeight = 13.sp, letterSpacing = 0.sp, maxLines = 1)
    }
}

@Composable
private fun ClassicMiniPlayer(
    state: NowPlayingUiState, onExpand: () -> Unit,
    onExpandDrag: (Float) -> Unit, onExpandDragEnd: (Float) -> Unit,
    onPlayPause: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit,
    transition: PlayerTransition, modifier: Modifier
) {
    val song = state.song ?: return
    val ink = LocalPalette.current.ink
    val haptics = LocalHapticFeedback.current
    val latestNext by rememberUpdatedState(onNext)
    val latestPrevious by rememberUpdatedState(onPrevious)
    val latestState by rememberUpdatedState(state)
    val swipeTravel = remember { floatArrayOf(0f) }
    val drag = rememberDraggableState { delta -> swipeTravel[0] += delta }
    val density = LocalDensity.current
    val threshold = with(density) { 44.dp.toPx() }
    Row(
        modifier.draggable(rememberDraggableState(onExpandDrag), Orientation.Vertical,
            onDragStopped = { onExpandDragEnd(it) })
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onExpand)
            .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(40.dp)
            .onGloballyPositioned { transition.dockArtwork = it.boundsInRoot() }
            // Hidden while its copy in the player flies; back (over the closing's slow last
            // frames) before the player has finished closing, so it doesn't pop in at the end.
            .graphicsLayer { alpha = if (transition.origin?.artwork != null) 1f - transition.stage(0.01f, 0.04f) else 1f }
            .clip(RoundedCornerShape(8.dp)).background(ink.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.MusicNote, null, tint = ink.copy(alpha = 0.4f), modifier = Modifier.size(20.dp))
            AsyncImage(song.displayArtwork, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f).padding(start = 10.dp, end = 4.dp)
            .onGloballyPositioned { transition.dockTitle = it.boundsInRoot() }
            .graphicsLayer { alpha = if (transition.origin?.title != null) 1f - transition.stage(0.01f, 0.04f) else 1f }
            .draggable(drag, Orientation.Horizontal, onDragStopped = { velocity ->
                val travel = swipeTravel[0]
                if (abs(travel) >= threshold || abs(velocity) >= 1400f) {
                    val forward = if (abs(velocity) >= 1400f) velocity < 0f else travel < 0f
                    if ((forward && latestState.hasNext) || (!forward && latestState.hasPrevious)) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        if (forward) latestNext() else latestPrevious()
                    }
                }
                swipeTravel[0] = 0f
            })) {
            Text(song.title, color = ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.SansSerif, letterSpacing = 0.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist.orEmpty(), color = ink.copy(alpha = 0.6f), fontSize = 11.sp,
                fontFamily = FontFamily.SansSerif, letterSpacing = 0.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(40.dp).clickable(remember { MutableInteractionSource() }, indication = null) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onPlayPause()
        }, contentAlignment = Alignment.Center) {
            // Pause remains usable during buffering, including a quality upgrade.
            if (state.isBuffering || state.loadingSongId == song.telegramMessageId) {
                CalmSpinner(color = ink, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            } else Icon(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (state.isPlaying) "Pause" else "Play", tint = ink, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(40.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = state.hasNext,
                onClick = onNext), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.SkipNext, "Next", tint = ink.copy(alpha = if (state.hasNext) 1f else 0.35f),
                modifier = Modifier.size(32.dp))
        }
    }
}
