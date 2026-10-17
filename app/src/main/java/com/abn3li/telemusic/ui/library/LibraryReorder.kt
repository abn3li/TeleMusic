package com.abn3li.telemusic.ui.library

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.abn3li.telemusic.ui.nowplaying.LocalMiniPlayerInset
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One gesture owner on the list: moving/recycling a song cannot cancel the finger's drag. */
@Composable
internal fun Modifier.libraryReorderGestures(edit: LibraryEditing?, list: LazyListState): Modifier {
    if (edit == null) return this
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val songs = LocalEditingSongs.current
    val latestSongs by rememberUpdatedState(songs)
    val top = WindowInsets.statusBars.union(WindowInsets.displayCutout).asPaddingValues().calculateTopPadding() + LibraryBarHeight
    val topPx = with(density) { top.toPx() }
    val bottomPx = with(density) { (LocalMiniPlayerInset.current + 16.dp).toPx() }
    val edgePx = with(density) { 48.dp.toPx() }
    val handlePx = with(density) { 56.dp.toPx() }
    val speedPx = with(density) { 10.dp.toPx() }
    val latestTop by rememberUpdatedState(topPx)
    val latestBottom by rememberUpdatedState(bottomPx)
    val scope = rememberCoroutineScope()
    val pendingAnchor = remember(edit, list) { arrayOf<Pair<Int, Int>?>(null) }

    fun layoutMatchesDraft(): Boolean = list.layoutInfo.visibleItemsInfo.filter { it.key is Long }.all {
        it.index - edit.dragHeaderCount == edit.draftIndexOf(it.key as Long)
    }

    suspend fun restoreAnchorIfReady() {
        val anchor = pendingAnchor[0] ?: return
        if (!layoutMatchesDraft()) return
        pendingAnchor[0] = null
        // Preserve the viewport by index, not by the song whose index just changed.
        list.scrollToItem(anchor.first, anchor.second)
    }

    LaunchedEffect(edit.pendingReorder) {
        if (edit.pendingReorder == null) return@LaunchedEffect
        delay(200)
        if (list.firstVisibleItemIndex == 0) list.scrollToItem(0)
        edit.beginReorder(latestSongs)
        edit.pendingReorder = null
    }

    LaunchedEffect(songs, edit.reordering) {
        if (edit.reconcileReorder(songs)) pendingAnchor[0] = null
    }

    LaunchedEffect(edit.reordering) {
        pendingAnchor[0] = null
        if (!edit.reordering) return@LaunchedEffect
        // The hero/header change must settle before enabling placement animations or dragging.
        withFrameNanos { }
        withFrameNanos { }
        edit.reorderReady = true
    }

    fun updateCenter(pointer: Float, grabOffset: Float) {
        val info = list.layoutInfo
        val row = info.visibleItemsInfo.firstOrNull { it.key == edit.draggingId }
        val half = (row?.size ?: edit.dragRowHeight) / 2f
        val lower = maxOf(info.viewportStartOffset.toFloat(), latestTop + info.viewportStartOffset) + half
        val upper = info.viewportEndOffset - latestBottom - half
        edit.dragPointerY = pointer
        edit.dragCenterY = (pointer + grabOffset).coerceIn(lower, maxOf(lower, upper))
    }

    fun moveAtPointer() {
        val id = edit.draggingId ?: return
        val rows = list.layoutInfo.visibleItemsInfo.filter { it.key is Long }
        // Wait for the previous move's layout. Reusing its old offsets would move the song back.
        if (pendingAnchor[0] != null || !layoutMatchesDraft()) return
        val target = rows.minByOrNull { abs(it.offset + it.size / 2f - edit.dragCenterY) } ?: return
        if (target.key != id) {
            val first = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == list.firstVisibleItemIndex }?.key
            if (first == id || first == target.key) pendingAnchor[0] = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
            edit.move(id, target.key as Long)
        }
    }

    LaunchedEffect(edit.draggingId) {
        if (edit.draggingId == null) return@LaunchedEffect
        var previousFrame = withFrameNanos { it }
        while (edit.draggingId != null) {
            val frame = withFrameNanos { it }
            val elapsed = ((frame - previousFrame) / 16_666_667f).coerceIn(0.25f, 2f)
            previousFrame = frame
            restoreAnchorIfReady()
            val info = list.layoutInfo
            val visibleTop = maxOf(info.viewportStartOffset.toFloat(), latestTop + info.viewportStartOffset)
            val visibleBottom = maxOf(visibleTop + edgePx, info.viewportEndOffset - latestBottom)
            val penetration = when {
                edit.dragPointerY < visibleTop + edgePx -> -((visibleTop + edgePx - edit.dragPointerY) / edgePx).coerceIn(0f, 1f)
                edit.dragPointerY > visibleBottom - edgePx -> ((edit.dragPointerY - visibleBottom + edgePx) / edgePx).coerceIn(0f, 1f)
                else -> 0f
            }
            if (penetration != 0f) list.scroll(MutatePriority.UserInput) { scrollBy(penetration * speedPx * elapsed) }
            updateCenter(edit.dragPointerY, edit.dragGrabOffset)
            moveAtPointer()
        }
    }

    return pointerInput(edit, list, edit.reordering, layoutDirection) {
        if (!edit.reordering) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val onHandle = if (layoutDirection == LayoutDirection.Rtl) down.position.x <= handlePx
                else down.position.x >= size.width - handlePx
            if (!edit.reorderReady || !onHandle) return@awaitEachGesture
            val downY = down.position.y + list.layoutInfo.viewportStartOffset
            val row = list.layoutInfo.visibleItemsInfo.firstOrNull {
                it.key is Long && downY >= it.offset && downY < it.offset + it.size
            } ?: return@awaitEachGesture
            val grabOffset = row.offset + row.size / 2f - downY
            var dragging = false
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (dragging && edit.draggingId != row.key) break
                    if (!change.pressed) { if (dragging) change.consume(); break }
                    if (!dragging) {
                        val delta = change.position - down.position
                        if (abs(delta.y) <= viewConfiguration.touchSlop) continue
                        if (abs(delta.x) > abs(delta.y)) break
                        edit.dragRowHeight = row.size
                        edit.dragGrabOffset = grabOffset
                        edit.dragHeaderCount = row.index - edit.draftIndexOf(row.key as Long)
                        edit.draggingId = row.key as Long
                        dragging = true
                    }
                    // Claim a handle drag before LazyColumn's own scrolling sees this event.
                    change.consume()
                    updateCenter(change.position.y + list.layoutInfo.viewportStartOffset, grabOffset)
                    moveAtPointer()
                }
            } finally {
                if (dragging) {
                    edit.draggingId = null
                    if (pendingAnchor[0] != null) scope.launch {
                        repeat(3) { withFrameNanos { }; restoreAnchorIfReady() }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LazyItemScope.librarySongPlacement(songId: Long): Modifier {
    val edit = LocalLibraryEditing.current
    val dragging = edit?.draggingId == songId
    val placement = if (edit?.returning == true || (edit?.reordering == true && (!edit.reorderReady || dragging))) Modifier
        else Modifier.animateItemPlacement(tween(if (edit?.active == true) 150 else SelectionExitMillis))
    return placement.zIndex(if (dragging) 1f else 0f)
}
