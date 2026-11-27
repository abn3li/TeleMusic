package com.abn3li.telemusic.ui.navigation

import com.abn3li.telemusic.ui.theme.appFontFamily
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.util.lerp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
import com.abn3li.telemusic.ui.nowplaying.CARD_BLEND
import com.abn3li.telemusic.ui.nowplaying.DOCK_OVER_CARD
import com.abn3li.telemusic.ui.theme.LocalPalette
import kotlin.math.abs

internal val ClassicDockInset = 136.dp
internal val ClassicTabsInset = 72.dp
internal val ClassicMiniPlayerInset = 58.dp

/**
 * Whether the dock is folded: scrolling a page down folds the tabs into one round button and
 * brings the player down beside it; scrolling back up opens them again. Only flips a flag when
 * the direction has gone on for [threshold] pixels, so it costs nothing while nothing scrolls.
 */
internal class DockCollapse(private val threshold: Float) : NestedScrollConnection {
    var collapsed by mutableStateOf(false)
    private var travel = 0f

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        // Pulling past the top (available) counts as going up, so the tabs come back there too.
        val dy = consumed.y + available.y
        if (dy == 0f) return Offset.Zero
        if ((dy < 0f) != (travel < 0f)) travel = 0f
        travel = (travel + dy).coerceIn(-threshold * 2f, threshold * 2f)
        if (travel <= -threshold && !collapsed) collapsed = true
        else if (travel >= threshold && collapsed) collapsed = false
        return Offset.Zero
    }
}

private val TabRoutes = listOf(Routes.HOME, Routes.DISCOVER, Routes.LIBRARY)
private val DockGap = 8.dp
private val MiniHeight = 56.dp
/** Records where this mini-player part rests, in root pixels: only while the player is closed
 * and the capsule isn't pressed in (both move it a little). */
private fun Modifier.miniRestingSpot(transition: PlayerTransition?, press: () -> Float, store: PlayerTransition.(Rect) -> Unit): Modifier =
    if (transition == null) this else onGloballyPositioned { coordinates ->
        if (transition.expansion.value > 0.001f || press() > 0.001f) return@onGloballyPositioned
        transition.store(Rect(coordinates.positionInRoot(), coordinates.size.toSize()))
    }

// How far the tabs drop while the player opens over them.
private val TabsDrop = 40.dp

/**
 * The player above the tabs, with Search in its own circle beside them. Folded ([collapse]),
 * the tabs shrink to one circle on the left, Search stays on the right and the player slides
 * down between them, all in one row.
 */
@Composable
internal fun ClassicDock(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    backdrop: FrostedBackdrop,
    state: NowPlayingUiState,
    bottomInset: Dp,
    onExpand: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    transition: PlayerTransition,
    showTabs: Boolean,
    collapse: DockCollapse,
    modifier: Modifier = Modifier,
    // The page under [backdrop], sampled beside it while it slides.
    backdropBelow: FrostedBackdrop? = null
) {
    if (!showTabs && state.song == null) return
    val hasMini = state.song != null
    val folded = collapse.collapsed && hasMini && showTabs
    LaunchedEffect(folded) { com.abn3li.telemusic.crash.CrashTrail.set("Dock", if (folded) "folded" else "open") }
    val fold = animateFloatAsState(if (folded) 1f else 0f,
        spring(dampingRatio = 0.86f, stiffness = 380f), label = "dockFold")

    // The player and the tabs share one frosted material: the page is blurred once for both,
    // except while they move with the player (see FrostedCapsules.paused).
    // The player grows out of the mini player: the card fades in over its capsule, which stays
    // solid underneath until the card is (CARD_BLEND) - two fading layers let the page show
    // through. The tabs drop a little and are gone just as fast. Once covered nothing here draws.
    val dropPx = with(LocalDensity.current) { TabsDrop.toPx() }
    // The mini player sits over the card near its own size and fades in over it as it shrinks.
    val miniMove = {
        val e = transition.expansion.value
        CapsuleMove(dy = 0f, scale = 1f,
            alpha = 1f - ((e - CARD_BLEND) / (DOCK_OVER_CARD - CARD_BLEND)).coerceIn(0f, 1f))
    }
    val tabsMove = {
        val e = transition.expansion.value
        CapsuleMove(dy = e * dropPx, scale = 1f, alpha = (1f - e / CARD_BLEND).coerceIn(0f, 1f))
    }
    val capsules = remember(transition) {
        FrostedCapsules().apply {
            moving = { transition.expansion.value > 0.001f }
            moves[0] = miniMove
            moves[1] = tabsMove
            moves[2] = tabsMove
        }
    }
    // The tabs' row keeps its open width while the capsule shrinks around it (set while measuring).
    val tabsRowWidth = remember { IntArray(1) }
    Layout(
        content = {
            if (hasMini) {
                ClassicMiniPlayer(state, onExpand, onPlayPause, onNext, onPrevious, compact = folded,
                    onDragUp = transition.dragBy, onDragUpEnd = transition.dragEnd, transition = transition,
                    modifier = Modifier.layoutId("mini")
                        // Where the player grows from (and shrinks back to). The movement never
                        // moves this capsule, so it is kept current even mid-way - the page under
                        // it can change while the player closes.
                        .onGloballyPositioned { transition.miniBounds = it.boundsInRoot() }
                        .graphicsLayer { alpha = miniMove().alpha }
                        .frostedCapsule(capsules, 0))
            }
            if (showTabs) {
                ClassicTabs(currentRoute, onNavigate, fold::value, folded, { collapse.collapsed = false },
                    { tabsRowWidth[0] },
                    Modifier.layoutId("tabs").graphicsLayer { tabsMove().let { alpha = it.alpha; translationY = it.dy } }
                        .frostedCapsule(capsules, 1))
                ClassicSearchButton(currentRoute == Routes.SEARCH, { onNavigate(Routes.SEARCH) },
                    Modifier.layoutId("search").graphicsLayer { tabsMove().let { alpha = it.alpha; translationY = it.dy } }
                        .frostedCapsule(capsules, 2))
            }
        },
        modifier = modifier.fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = maxOf(bottomInset, 15.dp) + 2.dp)
            // Taps between the capsules stay in the dock. Folded, the empty space above the row
            // belongs to the page again.
            // A gesture, not a clickable, so TalkBack doesn't stop on the dock as an unnamed button.
            .then(if (folded) Modifier else Modifier.pointerInput(Unit) { detectTapGestures { } })
            .frostedCapsuleGroup(backdrop, capsules, backdropBelow)
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val gap = DockGap.roundToPx()
        val miniH = MiniHeight.roundToPx()
        val mini = measurables.firstOrNull { it.layoutId == "mini" }
        val tabs = measurables.firstOrNull { it.layoutId == "tabs" }
        val search = measurables.firstOrNull { it.layoutId == "search" }
        val t = if (mini != null && tabs != null) fold.value else 0f
        // The tabs' height: padding, glyph and one label line (in sp, so larger text still fits).
        val tabsH = if (tabs != null) 57.dp.roundToPx() + 13.sp.roundToPx() else 0
        val height = when {
            mini != null && tabs != null -> miniH + gap + tabsH
            tabs != null -> tabsH
            else -> miniH
        }
        val rowH = lerp(tabsH, miniH, t)
        val openTabsW = width - tabsH - gap
        tabsRowWidth[0] = openTabsW
        val tabsW = lerp(openTabsW, miniH, t)
        val miniX = lerp(0, miniH + gap, t)
        val miniW = lerp(width, width - 2 * (miniH + gap), t)
        val miniY = if (tabs != null) lerp(0, height - miniH, t) else 0
        val miniP = mini?.measure(Constraints.fixed(miniW, miniH))
        val tabsP = tabs?.measure(Constraints.fixed(tabsW, rowH))
        val searchP = search?.measure(Constraints.fixed(rowH, rowH))
        layout(width, height) {
            miniP?.placeRelative(miniX, miniY)
            tabsP?.placeRelative(0, height - rowH)
            searchP?.placeRelative(width - rowH, height - rowH)
        }
    }
}

@Composable
private fun ClassicTabs(
    currentRoute: String?, onNavigate: (String) -> Unit,
    fold: () -> Float, folded: Boolean, onUnfold: () -> Unit,
    rowWidth: () -> Int, modifier: Modifier = Modifier
) {
    val selectedIndex = TabRoutes.indexOf(currentRoute)
    val indicator = animateFloatAsState(selectedIndex.coerceAtLeast(0).toFloat(),
        spring(dampingRatio = 0.72f, stiffness = 320f), label = "tabIndicator")
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current
    val palette = LocalPalette.current
    val ink = palette.ink
    // Folded on Search, the round button still shows the tab you came from.
    var lastTab by rememberSaveable { mutableStateOf(Routes.HOME) }
    LaunchedEffect(selectedIndex) { if (selectedIndex >= 0) lastTab = TabRoutes[selectedIndex] }
    var rowSize by remember { mutableStateOf(IntSize.Zero) }
    Box(modifier) {
        Box(Modifier
            // Laid out at the open width and only clipped while the capsule shrinks, so the
            // labels never squeeze; they fade out instead.
            .layout { measurable, constraints ->
                val w = maxOf(rowWidth(), constraints.minWidth)
                val placeable = measurable.measure(Constraints(minWidth = w, maxWidth = w))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place(if (rtl) constraints.maxWidth - w else 0, (constraints.maxHeight - placeable.height) / 2)
                }
            }
            .graphicsLayer { alpha = (1f - fold() * 2f).coerceIn(0f, 1f) }
            .padding(6.dp)) {
            val gap = 6.dp
            val rowW = with(density) { rowSize.width.toDp() }
            val rowHeight = with(density) { rowSize.height.toDp() }
            val tabWidth = ((rowW - gap * (TabRoutes.size - 1)) / TabRoutes.size).coerceAtLeast(0.dp)
            val stride = with(density) { (tabWidth + gap).toPx() }
            if (selectedIndex >= 0 && rowSize.width > 0) {
                Box(Modifier.width(tabWidth).height(rowHeight).graphicsLayer {
                    translationX = indicator.value * stride * if (rtl) -1f else 1f
                    val lag = abs(selectedIndex - indicator.value).coerceIn(0f, 1f)
                    scaleX = 1f + lag * 0.16f
                    scaleY = 1f - lag * 0.08f
                }.clip(RoundedCornerShape(percent = 50)).background(ink.copy(alpha = 0.14f)))
            }
            Row(Modifier.fillMaxWidth().onSizeChanged { rowSize = it },
                horizontalArrangement = Arrangement.spacedBy(gap)) {
                ClassicTab(NavIcons.Home, "Home", selectedIndex == 0, { onNavigate(Routes.HOME) }, Modifier.weight(1f))
                ClassicTab(NavIcons.Discover, "Discover", selectedIndex == 1, { onNavigate(Routes.DISCOVER) }, Modifier.weight(1f))
                ClassicTab(NavIcons.Library, "Library", selectedIndex == 2, { onNavigate(Routes.LIBRARY) }, Modifier.weight(1f))
            }
        }
        // Folded: one round button with the current tab's glyph; a tap opens the tabs again.
        val shown = if (selectedIndex >= 0) TabRoutes[selectedIndex] else lastTab
        val (icon, label) = when (shown) {
            Routes.DISCOVER -> NavIcons.Discover to "Discover"
            Routes.LIBRARY -> NavIcons.Library to "Library"
            else -> NavIcons.Home to "Home"
        }
        val haptic = LocalHapticFeedback.current
        Box(Modifier.matchParentSize()
            .graphicsLayer { alpha = (fold() * 2f - 1f).coerceIn(0f, 1f) }
            .then(if (folded) Modifier.clickable(remember { MutableInteractionSource() }, indication = null) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onUnfold()
            } else Modifier),
            contentAlignment = Alignment.Center) {
            Icon(icon, if (folded) "Show tabs, $label" else null,
                tint = if (selectedIndex >= 0) ink else inactiveTint(palette.isLight), modifier = Modifier.size(24.dp))
        }
    }
}

private fun inactiveTint(light: Boolean) = if (light) Color(0xFF6E6E73) else Color(0xFF8E8E93)

/** Search in its own glass circle, beside the tabs. */
@Composable
private fun ClassicSearchButton(selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press = animateFloatAsState(if (pressed) 0.9f else 1f,
        spring(dampingRatio = 1f, stiffness = 500f), label = "searchPress")
    val haptic = LocalHapticFeedback.current
    val palette = LocalPalette.current
    Box(modifier.semantics { this.selected = selected }
        .clickable(interaction, indication = null, role = Role.Tab) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick()
        }, contentAlignment = Alignment.Center) {
        if (selected) Box(Modifier.matchParentSize().padding(6.dp).clip(CircleShape).background(palette.ink.copy(alpha = 0.14f)))
        Icon(NavIcons.Search, "Search", tint = if (selected) palette.ink else inactiveTint(palette.isLight),
            modifier = Modifier.size(25.dp).graphicsLayer { scaleX = press.value; scaleY = press.value })
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
    val tint = if (selected) palette.ink else inactiveTint(palette.isLight)
    Column(modifier.semantics { this.selected = selected }
        .clip(RoundedCornerShape(percent = 50))
        .clickable(interaction, indication = null, role = Role.Tab) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        }.graphicsLayer { scaleX = press.value; scaleY = press.value }.padding(vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        // The label below names the tab; the glyph isn't read again.
        Icon(icon, null, tint = tint, modifier = Modifier.size(25.dp).graphicsLayer {
            scaleX = glyphScale.value; scaleY = glyphScale.value
        })
        Spacer(Modifier.height(2.dp))
        Text(label, color = tint, fontFamily = appFontFamily, fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp, lineHeight = 13.sp, letterSpacing = 0.sp, maxLines = 1)
    }
}

@Composable
private fun ClassicMiniPlayer(
    state: NowPlayingUiState, onExpand: () -> Unit,
    onPlayPause: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit,
    // Folded between the two round buttons: Next gives its room to the title.
    compact: Boolean = false,
    // A finger pulling the player up out of it: pixels moved (down positive), then the release
    // speed in pixels per second.
    onDragUp: (Float) -> Unit = {},
    onDragUpEnd: (Float) -> Unit = {},
    // Where its title and play button rest, for the copies that fly into the player.
    transition: PlayerTransition? = null,
    modifier: Modifier
) {
    val song = state.song ?: return
    val latestDragUp by rememberUpdatedState(onDragUp)
    val latestDragUpEnd by rememberUpdatedState(onDragUpEnd)
    val ink = LocalPalette.current.ink
    val haptics = LocalHapticFeedback.current
    val latestNext by rememberUpdatedState(onNext)
    val latestPrevious by rememberUpdatedState(onPrevious)
    val latestState by rememberUpdatedState(state)
    val swipeTravel = remember { floatArrayOf(0f) }
    val drag = rememberDraggableState { delta -> swipeTravel[0] += delta }
    val density = LocalDensity.current
    val threshold = with(density) { 44.dp.toPx() }
    // A touch lights the glass and presses its content in, with a tick on the tap itself, so the
    // player answers the finger before it starts opening.
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press = animateFloatAsState(if (pressed) 1f else 0f,
        if (pressed) spring(dampingRatio = 1f, stiffness = 1400f) else spring(dampingRatio = 0.6f, stiffness = 380f),
        label = "miniPress")
    Row(
        modifier
            .drawBehind { if (press.value > 0.001f) drawRect(ink.copy(alpha = 0.12f * press.value)) }
            // Up pulls the player out of the mini player; sideways (on the title) changes song.
            .pointerInput(Unit) {
                val tracker = VelocityTracker()
                detectVerticalDragGestures(
                    onDragStart = { tracker.resetTracking() },
                    onDragEnd = { latestDragUpEnd(tracker.calculateVelocity().y) },
                    onDragCancel = { latestDragUpEnd(0f) },
                    onVerticalDrag = { change, dy ->
                        change.consume()
                        tracker.addPosition(change.uptimeMillis, change.position)
                        latestDragUp(dy)
                    }
                )
            }
            .clickable(interaction, indication = null) {
                com.abn3li.telemusic.crash.CrashTrail.note("Mini player tapped")
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onExpand()
            }
            .graphicsLayer { val s = 1f - 0.035f * press.value; scaleX = s; scaleY = s }
            .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(40.dp)
            .clip(RoundedCornerShape(8.dp)).background(ink.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.MusicNote, null, tint = ink.copy(alpha = 0.4f), modifier = Modifier.size(20.dp))
            AsyncImage(song.displayArtwork, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f).padding(start = 10.dp, end = 4.dp)
            .draggable(drag, Orientation.Horizontal, onDragStopped = { velocity ->
                val travel = swipeTravel[0]
                if (abs(travel) >= threshold || abs(velocity) >= 1400f) {
                    val forward = if (abs(velocity) >= 1400f) velocity < 0f else travel < 0f
                    com.abn3li.telemusic.crash.CrashTrail.note(if (forward) "Mini player swiped to next" else "Mini player swiped to previous")
                    if ((forward && latestState.hasNext) || (!forward && latestState.hasPrevious)) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        if (forward) latestNext() else latestPrevious()
                    }
                }
                swipeTravel[0] = 0f
            })
            .miniRestingSpot(transition, press::value) { miniText = it }
            .graphicsLayer { alpha = if (transition?.textFlying?.invoke() == true) 0f else 1f }) {
            Text(song.title, color = ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                fontFamily = appFontFamily, letterSpacing = 0.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist.orEmpty(), color = ink.copy(alpha = 0.6f), fontSize = 11.sp,
                fontFamily = appFontFamily, letterSpacing = 0.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(40.dp)
            .miniRestingSpot(transition, press::value) { miniPlay = it }
            .graphicsLayer { alpha = if (transition?.playFlying?.invoke() == true) 0f else 1f }
            .clickable(remember { MutableInteractionSource() }, indication = null) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onPlayPause()
            }, contentAlignment = Alignment.Center) {
            // Pause remains usable during buffering, including a quality upgrade.
            if (state.isBuffering || state.loadingSongId == song.telegramMessageId) {
                CalmSpinner(color = ink, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            } else Icon(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (state.isPlaying) "Pause" else "Play", tint = ink, modifier = Modifier.size(32.dp))
        }
        if (!compact) Spacer(Modifier.width(8.dp))
        if (!compact) Box(Modifier.size(40.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = state.hasNext,
                onClick = onNext), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.SkipNext, "Next", tint = ink.copy(alpha = if (state.hasNext) 1f else 0.35f),
                modifier = Modifier.size(32.dp))
        }
    }
}
