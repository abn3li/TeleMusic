package com.abn3li.telemusic.ui.library

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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
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
import kotlinx.coroutines.delay

internal val LibraryBarHeight = 54.dp
internal val SearchStickyHeight = 61.dp

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
    overlay: (@Composable BoxScope.() -> Unit)? = null,
    content: LazyListScope.() -> Unit
) {
    var barHeightPx by remember { mutableIntStateOf(0) }
    val titleAlpha by remember(listState) {
        derivedStateOf {
            val first = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }
            if (first == null) 0f else (1f + first.offset.toFloat() / first.size * 1.8f).coerceIn(0f, 1f)
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

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = LibraryBarHeight)
        ) {
            item("large_title") { LargeTitle(title, titleTrailing, { titleAlpha }) }
            if (stickyContent != null) item("sticky_space") { Spacer(Modifier.height(SearchStickyHeight)) }
            content()
            item("bottom_inset") { Spacer(Modifier.height(bottomInset)) }
        }
        if (stickyContent != null) {
            Box(
                Modifier
                    .offset { IntOffset(0, stickyOffset) }
                    .fillMaxWidth()
                    .height(SearchStickyHeight)
                    .background(Color.Black)
            ) { stickyContent() }
        }
        overlay?.invoke(this)
        LibraryTitleBar(
            title = title,
            onBack = onBack,
            showSmallTitle = showSmallTitle,
            showDivider = stickyContent == null,
            actions = barActions,
            modifier = Modifier.onSizeChanged { barHeightPx = it.height }
        )
    }
}

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

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(15.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = LibraryBarHeight)
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
                    .background(Color.Black)
            ) { stickyContent() }
        }
        LibraryTitleBar(
            title = title,
            onBack = onBack,
            showSmallTitle = showSmallTitle,
            showDivider = stickyContent == null,
            actions = null,
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
            color = Color.White,
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
    modifier: Modifier = Modifier
) {
    // Always opaque: the page is black anyway, and fading this in only once the large title had
    // scrolled away let rows flash through the bar for a few frames on a fast fling.
    Box(modifier.fillMaxWidth().height(LibraryBarHeight).background(Color.Black)) {
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
            Text(title, color = Color.White, fontSize = 18.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            Spacer(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.12f)))
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
                Text(placeholder, color = Color.White.copy(alpha = 0.6f), fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BasicTextField(
                value = text,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 17.sp),
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
                tint = Color.White.copy(alpha = 0.45f),
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
        tint = Color.White.copy(alpha = 0.3f),
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
        Text(label, color = Color.White, fontSize = 20.sp, modifier = Modifier.weight(1f))
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
    val shape = RoundedCornerShape(10.dp)
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
    val offsetY = offsetYPx ?: with(LocalDensity.current) { 40.dp.roundToPx() }
    val origin = TransformOrigin(if (alignStart) 0.05f else 0.95f, 0f)
    Popup(
        alignment = if (alignStart) Alignment.TopStart else Alignment.TopEnd,
        offset = IntOffset(0, offsetY),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        val spec = spring<Float>(dampingRatio = 0.7f, stiffness = 340f)
        AnimatedVisibility(
            visible = show,
            enter = fadeIn(spec) + scaleIn(spec, initialScale = 0.618f, transformOrigin = origin),
            exit = fadeOut(spec) + scaleOut(spec, targetScale = 0.618f, transformOrigin = origin)
        ) {
            Column(
                Modifier
                    .width(250.dp)
                    .graphicsLayer {
                        shape = RoundedCornerShape(12.dp)
                        shadowElevation = 40f
                        clip = true
                    }
                    .background(Color(0xFF161616)),
                content = content
            )
        }
    }
}

@Composable
internal fun LibraryMenuItem(
    label: String,
    icon: ImageVector?,
    selected: Boolean = false,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    val color = when {
        destructive -> Color(0xFFFF453A)
        selected -> AppAccent
        else -> Color.White
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = color, fontSize = 17.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (icon != null) Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
    }
}

@Composable
internal fun LibraryMenuDivider() {
    Spacer(Modifier.fillMaxWidth().height(0.65.dp).background(Color.White.copy(alpha = 0.1f)))
}

@Composable
internal fun LibraryMenuGroupGap() {
    Spacer(Modifier.fillMaxWidth().height(8.dp).background(Color.Black.copy(alpha = 0.55f)))
}
