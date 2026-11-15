package com.abn3li.telemusic.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.border

import com.abn3li.telemusic.ui.theme.SystemBarsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import com.abn3li.telemusic.ui.theme.LocalPalette
import com.abn3li.telemusic.ui.theme.paper
import com.abn3li.telemusic.ui.theme.ink
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.rounded.Abc
import com.abn3li.telemusic.ui.theme.MENU_DIM
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.abn3li.telemusic.ui.nowplaying.LocalMiniPlayerInset
import com.abn3li.telemusic.ui.navigation.FrostedBackdrop
import com.abn3li.telemusic.ui.navigation.pinnedGlass
import androidx.compose.ui.semantics.Role
import com.abn3li.telemusic.ui.navigation.LocalFrostedDockVisible
import com.abn3li.telemusic.ui.navigation.frostedBackdropSource
import com.abn3li.telemusic.ui.navigation.frostedSurface
import com.abn3li.telemusic.ui.navigation.rememberFrostedBackdrop
import kotlinx.coroutines.delay

internal val LibraryBarHeight = 54.dp
internal val SearchStickyHeight = 61.dp

@Composable
internal fun frostedHeaderTopInset(): Dp {
    DisposableEffect(Unit) {
        SystemBarsState.frostedHeaderPages++
        onDispose { SystemBarsState.frostedHeaderPages-- }
    }
    return WindowInsets.statusBars.union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Top).asPaddingValues().calculateTopPadding()
}

/** Keep controls below the system icons while letting the list and its blur reach behind them. */
internal fun Modifier.extendBehindStatusBar(inset: Dp): Modifier = layout { measurable, constraints ->
    // A page with no height limit (inside a scrolling parent) is laid out as it is: adding to
    // an unbounded height would overflow.
    if (!constraints.hasBoundedHeight) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val extra = inset.roundToPx()
    val placeable = measurable.measure(
        constraints.copy(minHeight = constraints.minHeight + extra, maxHeight = constraints.maxHeight + extra)
    )
    layout(placeable.width, constraints.maxHeight) { placeable.place(0, -extra) }
}

/**
 * iOS-style page: a big 35sp title scrolls with the list and fades out; once it's gone a compact
 * bar with a centred small title (and a hairline) fades in. [stickyContent] (the search field)
 * rides under the big title and then pins below the bar.
 */
@Composable
internal fun LargeTitleList(
    title: String,
    onBack: (() -> Unit)? = null,
    listState: LazyListState = rememberLazyListState(),
    titleTrailing: (@Composable () -> Unit)? = null,
    barActions: (@Composable RowScope.() -> Unit)? = null,
    stickyContent: (@Composable () -> Unit)? = null,
    // Taller when the sticky part holds more than the search field (the search page's filter pills).
    stickyHeight: Dp = SearchStickyHeight,
    overlay: (@Composable BoxScope.() -> Unit)? = null,
    // A Settings-style page of grouped cards: grey page, white cards in the light theme.
    grouped: Boolean = false,
    showTopBar: Boolean = true,
    // Stays at the top right in a glass circle while the page scrolls under it (the account).
    pinnedButton: (@Composable () -> Unit)? = null,
    onPinnedClick: () -> Unit = {},
    pinnedDescription: String? = null,
    content: LazyListScope.() -> Unit
) {
    val palette = LocalPalette.current
    if (grouped) {
        DisposableEffect(Unit) {
            SystemBarsState.groupedPages++
            onDispose { SystemBarsState.groupedPages-- }
        }
    }
    CompositionLocalProvider(LocalPalette provides if (grouped) palette.groupedPage() else palette) {
        LargeTitleListBody(title, onBack, listState, titleTrailing, barActions, stickyContent, stickyHeight, overlay, showTopBar, pinnedButton, onPinnedClick, pinnedDescription, content)
    }
}

@Composable
private fun LargeTitleListBody(
    title: String,
    onBack: (() -> Unit)?,
    listState: LazyListState,
    titleTrailing: (@Composable () -> Unit)?,
    barActions: (@Composable RowScope.() -> Unit)?,
    stickyContent: (@Composable () -> Unit)?,
    stickyHeight: Dp,
    overlay: (@Composable BoxScope.() -> Unit)?,
    showTopBar: Boolean,
    pinnedButton: (@Composable () -> Unit)?,
    onPinnedClick: () -> Unit,
    pinnedDescription: String?,
    content: LazyListScope.() -> Unit
) {
    var barHeightPx by remember { mutableIntStateOf(0) }
    val backdrop = rememberFrostedBackdrop()
    val captureForDock = LocalFrostedDockVisible.current
    val statusBar = frostedHeaderTopInset()
    val titleAlpha by remember(listState) {
        derivedStateOf {
            val first = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }
            if (first == null || first.size == 0) 0f else (1f + first.offset.toFloat() / first.size * 1.8f).coerceIn(0f, 1f)
        }
    }
    val showSmallTitle by remember(listState) {
        derivedStateOf { titleAlpha <= 0f && listState.layoutInfo.visibleItemsInfo.isNotEmpty() }
    }
    val stickyOffset by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            info.visibleItemsInfo.firstOrNull { it.index == 1 }
                ?.let { (it.offset - info.viewportStartOffset).coerceAtLeast(barHeightPx) }
                ?: if (listState.firstVisibleItemIndex > 1) barHeightPx else info.viewportEndOffset
        }
    }
    val bottomInset = LocalMiniPlayerInset.current + 24.dp

    Box(Modifier.fillMaxSize().extendBehindStatusBar(statusBar).background(paper)) {
        val barsLayer = remember { PlayingBarsLayer() }
        CompositionLocalProvider(LocalPlayingBarsLayer provides barsLayer) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().playingBarsLayer(barsLayer).libraryReorderGestures(LocalLibraryEditing.current, listState).then(
                if ((captureForDock || showSmallTitle) && !SystemBarsState.playerOpen) Modifier.frostedBackdropSource(backdrop, paper)
                else Modifier
            ),
            contentPadding = PaddingValues(top = statusBar + if (showTopBar) LibraryBarHeight else 0.dp)
        ) {
            item("large_title") {
                if (LocalLibraryEditing.current?.active != true) {
                    LargeTitle(title, if (pinnedButton != null) { { Spacer(Modifier.size(PinnedButtonSize)) } } else titleTrailing, { titleAlpha })
                }
            }
            if (stickyContent != null) item("sticky_space") { Spacer(Modifier.height(stickyHeight)) }
            content()
            item("bottom_inset") { Spacer(Modifier.height(bottomInset)) }
        }
        }
        if (stickyContent != null) {
            Box(
                Modifier
                    .offset { IntOffset(0, stickyOffset) }
                    .fillMaxWidth()
                    .height(stickyHeight)
                    .then(
                        if (showSmallTitle) Modifier.frostedSurface(backdrop, shape = RectangleShape, drawBorder = false)
                        else Modifier.background(paper)
                    )
            ) { stickyContent() }
        }
        if (overlay != null) {
            Box(Modifier.fillMaxSize().padding(top = statusBar)) { overlay() }
        }
        LibraryTitleBar(
            title = title,
            onBack = onBack,
            showSmallTitle = showSmallTitle,
            showDivider = stickyContent == null,
            actions = barActions,
            backdrop = backdrop,
            statusBar = statusBar,
            showToolbar = showTopBar,
            modifier = Modifier.onSizeChanged { barHeightPx = it.height }
        )
        if (pinnedButton != null && LocalLibraryEditing.current?.active != true) {
            // Centred on the large title's line at rest (8dp above it, 40sp line).
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = statusBar + 6.dp, end = 14.dp)
                    .size(PinnedButtonSize)
                    .frostedSurface(backdrop, shape = CircleShape, glass = pinnedGlass(LocalPalette.current.isLight))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClickLabel = pinnedDescription,
                        onClick = onPinnedClick
                    ),
                contentAlignment = Alignment.Center
            ) { pinnedButton() }
        }
    }
}

private val PinnedButtonSize = 44.dp

/** Grid counterpart of [LargeTitleList] (the Albums page): two columns, 18dp gutters. */
@Composable
internal fun LargeTitleGrid(
    title: String,
    onBack: (() -> Unit)?,
    gridState: LazyGridState = rememberLazyGridState(),
    stickyContent: (@Composable () -> Unit)? = null,
    content: LazyGridScope.() -> Unit
) {
    var barHeightPx by remember { mutableIntStateOf(0) }
    val backdrop = rememberFrostedBackdrop()
    val captureForDock = LocalFrostedDockVisible.current
    val statusBar = frostedHeaderTopInset()
    val titleAlpha by remember(gridState) {
        derivedStateOf {
            val first = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }
            if (first == null) 0f else (1f + first.offset.y.toFloat() / first.size.height * 1.8f).coerceIn(0f, 1f)
        }
    }
    val showSmallTitle by remember(gridState) {
        derivedStateOf { titleAlpha <= 0f && gridState.layoutInfo.visibleItemsInfo.isNotEmpty() }
    }
    val stickyOffset by remember(gridState) {
        derivedStateOf {
            val info = gridState.layoutInfo
            info.visibleItemsInfo.firstOrNull { it.index == 1 }
                ?.let { (it.offset.y - info.viewportStartOffset).coerceAtLeast(barHeightPx) }
                ?: if (gridState.firstVisibleItemIndex > 1) barHeightPx else info.viewportEndOffset
        }
    }
    val bottomInset = LocalMiniPlayerInset.current + 24.dp

    Box(Modifier.fillMaxSize().extendBehindStatusBar(statusBar).background(paper)) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().then(
                if ((captureForDock || showSmallTitle) && !SystemBarsState.playerOpen) Modifier.frostedBackdropSource(backdrop, paper)
                else Modifier
            ),
            horizontalArrangement = Arrangement.spacedBy(15.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = LibraryBarHeight + statusBar)
        ) {
            item("large_title", span = { GridItemSpan(2) }) { LargeTitle(title, null, { titleAlpha }, grid = true) }
            if (stickyContent != null) {
                item("sticky_space", span = { GridItemSpan(2) }) { Spacer(Modifier.height(SearchStickyHeight - 20.dp)) }
            }
            content()
            item("bottom_inset", span = { GridItemSpan(2) }) { Spacer(Modifier.height(bottomInset)) }
        }
        if (stickyContent != null) {
            Box(
                Modifier
                    .offset { IntOffset(0, stickyOffset) }
                    .fillMaxWidth()
                    .height(SearchStickyHeight)
                    .then(
                        if (showSmallTitle) Modifier.frostedSurface(backdrop, shape = RectangleShape, drawBorder = false)
                        else Modifier.background(paper)
                    )
            ) { stickyContent() }
        }
        LibraryTitleBar(
            title = title,
            onBack = onBack,
            showSmallTitle = showSmallTitle,
            showDivider = stickyContent == null,
            actions = null,
            backdrop = backdrop,
            statusBar = statusBar,
            modifier = Modifier.onSizeChanged { barHeightPx = it.height }
        )
    }
}

@Composable
private fun LargeTitle(title: String, trailing: (@Composable () -> Unit)?, alpha: () -> Float, grid: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = if (grid) 0.dp else 20.dp)
            .padding(top = 8.dp, bottom = if (grid) 0.dp else 12.dp)
            .graphicsLayer { this.alpha = alpha() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            color = ink,
            fontSize = 35.sp,
            lineHeight = 40.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
    }
}

@Composable
private fun LibraryTitleBar(
    title: String,
    onBack: (() -> Unit)?,
    showSmallTitle: Boolean,
    showDivider: Boolean,
    actions: (@Composable RowScope.() -> Unit)?,
    backdrop: FrostedBackdrop,
    statusBar: Dp,
    showToolbar: Boolean = true,
    modifier: Modifier = Modifier
) {
    val editing = LocalLibraryEditing.current
    // Sample only the list, so the bar never blurs its own title or buttons into the background.
    // Before the large title scrolls away, keep the original page-colour header.
    val background = if (showSmallTitle || editing?.active == true) {
        Modifier.frostedSurface(backdrop, shape = RectangleShape, drawBorder = false)
    } else Modifier.background(paper)
    val toolbarHeight = if (showToolbar) LibraryBarHeight else 0.dp
    Box(modifier.fillMaxWidth().height(toolbarHeight + statusBar).then(background).padding(top = statusBar)) {
        // Home keeps the system status area readable without covering the feed with a toolbar.
        if (!showToolbar) return@Box
        if (editing?.active == true) {
            LibraryEditingTopBar(editing)
            return@Box
        }
        if (onBack != null) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBackIos,
                contentDescription = "Back",
                tint = AppAccent,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 14.dp)
                    .size(22.dp)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onBack)
            )
        }
        AnimatedVisibility(
            showSmallTitle,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(120)),
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 70.dp)
        ) {
            Text(title, color = ink, fontSize = 18.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (actions != null) {
            Row(
                Modifier.align(Alignment.CenterEnd).padding(end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = actions
            )
        }
        AnimatedVisibility(
            showSmallTitle && showDivider,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(120)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Spacer(Modifier.fillMaxWidth().height(1.dp).background(ink.copy(alpha = 0.12f)))
        }
    }
}

/** The small grey circle "⋯" button used in title bars. */
@Composable
internal fun TitleBarCircleButton(
    icon: ImageVector = Icons.Rounded.MoreHoriz,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(Color.DarkGray.copy(alpha = 0.35f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription, tint = AppAccent, modifier = Modifier.size(20.dp))
    }
}

@Composable
internal fun LibrarySearchField(
    text: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onSearch: () -> Unit = {}
) {
    val keyboard = LocalSoftwareKeyboardController.current
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .padding(top = 5.dp, bottom = 12.dp)
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(LibraryFieldColor)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f)) {
            if (text.isEmpty()) {
                Text(placeholder, color = ink.copy(alpha = 0.6f), fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BasicTextField(
                value = text,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = ink, fontSize = 17.sp),
                cursorBrush = SolidColor(AppAccent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(); keyboard?.hide() }),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (text.isNotEmpty()) {
            Icon(
                Icons.Rounded.Cancel,
                contentDescription = "Clear",
                tint = ink.copy(alpha = 0.45f),
                modifier = Modifier
                    .size(20.dp)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onValueChange("") }
            )
        }
    }
}

/** Thin inset separator between rows. */
@Composable
internal fun LibraryDivider(start: Dp, end: Dp = 0.dp) {
    Spacer(
        Modifier
            .fillMaxWidth()
            .padding(start = start, end = end)
            .height(0.5.dp)
            .background(LibraryHairline)
    )
}

@Composable
internal fun ChevronIcon(modifier: Modifier = Modifier) {
    Icon(
        Icons.AutoMirrored.Rounded.ArrowForwardIos,
        contentDescription = null,
        tint = ink.copy(alpha = 0.3f),
        modifier = modifier.size(14.dp)
    )
}

/** Main Library page link: pink icon, big label, chevron. */
@Composable
internal fun LibraryLinkRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = AppAccent, modifier = Modifier.padding(start = 18.dp, end = 12.dp).size(30.dp))
        Text(label, color = ink, fontSize = 20.sp, modifier = Modifier.weight(1f))
        ChevronIcon(Modifier.padding(end = 21.dp))
    }
}

/** The Play / Shuffle pair under a list's search field. */
@Composable
internal fun PlayShuffleButtons(
    enabled: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(top = 12.dp, bottom = 15.dp)) {
        TopActionButton(Icons.Rounded.PlayArrow, "Play", enabled, onPlay, Modifier.weight(1f))
        Spacer(Modifier.width(15.dp))
        TopActionButton(Icons.Rounded.Shuffle, "Shuffle", enabled, onShuffle, Modifier.weight(1f))
    }
}

@Composable
private fun TopActionButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val haptics = LocalHapticFeedback.current
    // A pill, like the other buttons of the new style (Resume, Import playlist).
    val shape = RoundedCornerShape(50)
    // The card is painted straight onto the row (no clip/alpha layers) and "disabled" dims only the
    // label: with layers, the card's background sometimes wasn't redrawn when the songs arrived a
    // moment after the page opened, leaving just the pink label until the list was scrolled.
    val tint = if (enabled) AppAccent else AppAccent.copy(alpha = 0.45f)
    Row(
        modifier
            .height(44.dp)
            .background(LibraryFieldColor, shape)
            .clip(shape)
            .clickable(enabled = enabled) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, color = tint, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * The "⋯" pull-down menu: pops out of the top-right under the bar with a springy scale-in.
 * Place it inside the button's own Box so it anchors there.
 */
@Composable
internal fun LibraryFloatingMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    // Grow from the anchor's left edge instead of its right (for anchors on the left of the screen),
    // and an optional vertical offset in px (defaults to just under a title-bar button).
    alignStart: Boolean = false,
    offsetYPx: Int? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    var keep by remember { mutableStateOf(false) }
    var show by remember { mutableStateOf(false) }
    // Where the button's Box sits: kept in a plain holder (no recomposition), read on open.
    val anchor = remember { arrayOfNulls<LayoutCoordinates>(1) }
    Spacer(Modifier.onPlaced { anchor[0] = it })
    LaunchedEffect(expanded) {
        if (expanded) {
            keep = true
            delay(16)
            show = true
        } else if (keep) {
            show = false
            delay(260)
            keep = false
        }
    }
    if (!keep) return
    val density = LocalDensity.current
    val bounds = remember { anchor[0]?.parentLayoutCoordinates?.takeIf { it.isAttached }?.boundsInWindow() }
    val offsetY = offsetYPx ?: with(density) { 40.dp.roundToPx() }
    val origin = TransformOrigin(if (alignStart) 0.05f else 0.95f, 0f)
    // The page under the menu dims with it, system bars included (as the song menus do).
    val dim by animateFloatAsState(if (show) MENU_DIM else 0f, tween(if (show) 140 else 200), label = "pullDownDim")
    DisposableEffect(show) {
        SystemBarsState.menuOpen = show
        onDispose { SystemBarsState.menuOpen = false }
    }
    ContextMenuLayer(onDismissRequest = onDismiss) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .drawBehind { drawRect(Color.Black.copy(alpha = dim)) }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
        ) {
            val menuWidth = 224.dp
            val margin = 8.dp
            val left = with(density) {
                val b = bounds
                val x = when {
                    b == null -> maxWidth - menuWidth - margin
                    alignStart -> b.left.toDp()
                    else -> b.right.toDp() - menuWidth
                }
                x.coerceIn(margin, (maxWidth - menuWidth - margin).coerceAtLeast(margin))
            }
            val top = with(density) { ((bounds?.top ?: 0f) + offsetY).toDp() }
            val spec = spring<Float>(dampingRatio = 0.7f, stiffness = 340f)
            AnimatedVisibility(
                visible = show,
                enter = fadeIn(spec) + scaleIn(spec, initialScale = 0.618f, transformOrigin = origin),
                exit = fadeOut(spec) + scaleOut(spec, targetScale = 0.618f, transformOrigin = origin),
                modifier = Modifier.offset(x = left, y = top)
            ) {
                val nav = remember { MenuNav() }
                CompositionLocalProvider(LocalMenuNav provides nav) {
                    Column(
                        Modifier
                            .width(menuWidth)
                            .graphicsLayer {
                                shape = RoundedCornerShape(13.dp)
                                clip = true
                            }
                            .background(LocalPalette.current.alert)
                            .border(0.5.dp, ink.copy(alpha = 0.12f), RoundedCornerShape(13.dp))
                            // Taps between rows stay in the menu instead of closing it (a gesture,
                            // not a clickable, so TalkBack still reaches each row on its own).
                            .pointerInput(Unit) { detectTapGestures { } }
                            .animateContentSize(spring(dampingRatio = 0.86f, stiffness = 520f)),
                        content = content
                    )
                }
            }
        }
    }
}

/** Which page of the open pull-down shows: the main rows (null), or one submenu by its title. */
internal class MenuNav {
    var page by mutableStateOf<String?>(null)
}

internal val LocalMenuNav = staticCompositionLocalOf<MenuNav?> { null }
private val LocalMenuPage = staticCompositionLocalOf<String?> { null }

/** False for rows of the page that isn't showing. */
@Composable
private fun menuRowShown(): Boolean {
    val nav = LocalMenuNav.current ?: return true
    return nav.page == LocalMenuPage.current
}

/** The Sort by row and its page of choices (see [LibrarySubmenu]). */
@Composable
internal fun LibrarySortMenu(current: String, gapBefore: Boolean = true, choices: @Composable () -> Unit) =
    LibrarySubmenu("Sort by", current, gapBefore, choices)

/**
 * One "Sort by" row showing the current choice ([current]); tapping it swaps the menu to
 * [choices] under a back row. A pick there closes the menu as usual.
 */
@Composable
internal fun LibrarySubmenu(title: String, current: String, gapBefore: Boolean = true, choices: @Composable () -> Unit) {
    val nav = LocalMenuNav.current
    if (nav == null || nav.page == null) {
        if (gapBefore) LibraryMenuGroupGap()
        if (nav == null) return
        Row(
            Modifier.fillMaxWidth().height(40.dp).clickable { nav.page = title }.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = ink, fontSize = 15.sp)
            Text(current, color = ink.copy(alpha = 0.5f), fontSize = 13.5.sp, textAlign = TextAlign.End, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 10.dp, end = 6.dp))
            Icon(Icons.AutoMirrored.Rounded.ArrowForwardIos, null, tint = ink.copy(alpha = 0.45f), modifier = Modifier.size(12.dp))
        }
        return
    }
    if (nav.page != title) return
    // Back steps out of the choices first; a second Back closes the menu.
    BackHandler { nav.page = null }
    Row(
        Modifier.fillMaxWidth().height(40.dp).clickable { nav.page = null }.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBackIos, "Back", tint = ink.copy(alpha = 0.5f), modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(6.dp))
        Text(title, color = ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
    CompositionLocalProvider(LocalMenuPage provides title) {
        LibraryMenuGroupGap()
        choices()
    }
}

/**
 * A pull-down row. [checked] non-null makes it a choice, with the check before the label (the
 * row's own [icon] stays at the end). [detail] is gray text after the label (a count); a row
 * that can't be used now is dimmed and shows [detail] in place of its icon.
 */
@Composable
internal fun LibraryMenuItem(
    label: String,
    icon: ImageVector?,
    checked: Boolean? = null,
    destructive: Boolean = false,
    detail: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    if (!menuRowShown()) return
    val base = if (destructive) Color(0xFFFF453A) else ink
    val color = if (enabled) base else base.copy(alpha = 0.35f)
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = if (checked != null) 9.dp else 14.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (checked != null) {
            Box(Modifier.width(24.dp)) {
                if (checked) Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = color, modifier = Modifier.size(16.dp))
            }
        }
        Row(Modifier.weight(1f).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = color, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (detail != null && enabled) Text(detail, color = ink.copy(alpha = 0.45f), fontSize = 14.sp, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
        }
        if (!enabled && detail != null) Text(detail, color = color, fontSize = 12.sp, maxLines = 1)
        else if (icon != null) Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
    }
}

/** The small gray title over a group of choices. */
@Composable
internal fun LibraryMenuHeader(text: String) {
    if (!menuRowShown()) return
    Text(
        text,
        color = ink.copy(alpha = 0.5f),
        fontSize = 12.sp,
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 7.dp, bottom = 6.dp)
    )
    LibraryMenuDivider()
}

@Composable
internal fun LibraryMenuDivider() {
    if (!menuRowShown()) return
    Spacer(Modifier.fillMaxWidth().height(0.5.dp).background(ink.copy(alpha = 0.12f)))
}

@Composable
internal fun LibraryMenuGroupGap() {
    if (!menuRowShown()) return
    Spacer(Modifier.fillMaxWidth().height(6.dp).background(Color.Black.copy(alpha = if (LocalPalette.current.isLight) 0.06f else 0.4f)))
}

/** The A to Z strip switch; dimmed with [unusableText] while the order has no letters to jump by. */
@Composable
internal fun AlphabetIndexMenuItem(showIndex: Boolean, usable: Boolean, unusableText: String, onToggle: () -> Unit) {
    LibraryMenuItem(
        label = "Alphabet Index",
        icon = Icons.Rounded.Abc,
        checked = showIndex && usable,
        detail = if (usable) null else unusableText,
        enabled = usable,
        onClick = onToggle
    )
}
