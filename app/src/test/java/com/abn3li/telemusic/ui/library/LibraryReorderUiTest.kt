package com.abn3li.telemusic.ui.library

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import com.abn3li.telemusic.data.local.SongEntity
import com.abn3li.telemusic.ui.navigation.LocalComposeTest
import com.abn3li.telemusic.ui.theme.DarkPalette
import com.abn3li.telemusic.ui.theme.LocalPalette
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryReorderUiTest : LocalComposeTest() {
    private val tracks = (1L..60L).map { SongEntity(it, 0, "Song $it", "Artist") }
    private val actions = LibrarySongActions(emptyList(), emptySet(), {}, {}, {}, {}, {}, { _, _ -> }, { _, _ -> },
        { _, _ -> }, { _, _, _ -> true }, {}, {})
    private lateinit var edit: LibraryEditing
    private lateinit var list: LazyListState

    private fun liveList(key: String, rtl: Boolean = false): androidx.compose.runtime.MutableState<List<SongEntity>> {
        val source = mutableStateOf(tracks.take(7))
        setContent {
            edit = rememberLibraryEditing(key)
            list = rememberLazyListState()
            CompositionLocalProvider(LocalPalette provides DarkPalette, LocalLibraryEditing provides edit,
                LocalEditingListState provides list, LocalEditingSongs provides source.value,
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                LazyColumn(Modifier.fillMaxSize().libraryReorderGestures(edit, list).testTag("reorder-list"),
                    state = list, contentPadding = PaddingValues(top = LibraryBarHeight, bottom = 24.dp)) {
                    items(edit.ordered(source.value), key = { it.telegramMessageId }) { song ->
                        Column(librarySongPlacement(song.telegramMessageId)) { LibrarySongRow(song, actions, {}) }
                    }
                }
            }
        }
        compose.runOnIdle { edit.beginReorder(source.value) }
        compose.waitForIdle()
        return source
    }

    private fun dragBetween(from: Long, target: Long) {
        val viewport = compose.onNodeWithTag("reorder-list")
        val root = viewport.getUnclippedBoundsInRoot()
        val start = compose.onNodeWithContentDescription("Drag to reorder Song $from").getUnclippedBoundsInRoot()
        val end = compose.onNodeWithContentDescription("Drag to reorder Song $target").getUnclippedBoundsInRoot()
        viewport.performTouchInput {
            swipe(Offset((start.left.value + start.right.value) / 2 - root.left.value,
                (start.top.value + start.bottom.value) / 2 - root.top.value),
                Offset((end.left.value + end.right.value) / 2 - root.left.value,
                    (end.top.value + end.bottom.value) / 2 - root.top.value), durationMillis = 400)
        }
        compose.waitForIdle()
    }

    @Test fun rtlHandleDragsFromItsVisiblePosition() {
        liveList("ui:rtl", rtl = true)
        val handle = compose.onNodeWithContentDescription("Drag to reorder Song 2").getUnclippedBoundsInRoot()
        assertTrue("RTL handle must be on the left", handle.left.value < 56f)
        dragBetween(2, 4)
        compose.runOnIdle { assertTrue(edit.draft.indexOf(2L) > edit.draft.indexOf(3L)) }
    }

    @Test fun removedSongsDoNotBlockDraggingAndNewSongsCanBeMovedAndSaved() {
        val source = liveList("ui:live-membership")
        compose.runOnIdle { source.value = source.value.filter { it.telegramMessageId != 2L } + tracks[7] }
        compose.waitForIdle()
        dragBetween(1, 4)
        compose.runOnIdle { assertTrue(edit.draft.indexOf(1L) > edit.draft.indexOf(3L)) }
        dragBetween(8, 5)
        compose.runOnIdle {
            assertTrue("New song must be movable", edit.draft.indexOf(8L) < edit.draft.indexOf(7L))
            val expected = edit.draft.toList()
            edit.finish()
            assertEquals(source.value.map { it.telegramMessageId }.toSet(), edit.order.toSet())
            assertEquals(expected, edit.ordered(source.value).map { it.telegramMessageId })
        }
    }

    @Test fun deletingTheDraggedSongReleasesItAndAllowsAnotherDrag() {
        val source = liveList("ui:removed-drag")
        val viewport = compose.onNodeWithTag("reorder-list")
        val handle = compose.onNodeWithContentDescription("Drag to reorder Song 2").getUnclippedBoundsInRoot()
        val root = viewport.getUnclippedBoundsInRoot()
        compose.mainClock.autoAdvance = false
        viewport.performTouchInput {
            val start = Offset((handle.left.value + handle.right.value) / 2 - root.left.value,
                (handle.top.value + handle.bottom.value) / 2 - root.top.value)
            down(start)
            moveTo(start + Offset(0f, 30f))
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            assertEquals(2L, edit.draggingId)
            source.value = source.value.filter { it.telegramMessageId != 2L }
        }
        repeat(4) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.runOnIdle { assertNull(edit.draggingId); assertFalse(2L in edit.draft) }
        viewport.performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        dragBetween(3, 5)
        compose.runOnIdle { assertTrue(edit.draft.indexOf(3L) > edit.draft.indexOf(4L)) }
    }

    @Test fun liveChangesDuringAHeldDragKeepTheSongMovable() {
        val source = liveList("ui:held-live-drag")
        val viewport = compose.onNodeWithTag("reorder-list")
        val handle = compose.onNodeWithContentDescription("Drag to reorder Song 3").getUnclippedBoundsInRoot()
        val root = viewport.getUnclippedBoundsInRoot()
        compose.mainClock.autoAdvance = false
        viewport.performTouchInput {
            val start = Offset((handle.left.value + handle.right.value) / 2 - root.left.value,
                (handle.top.value + handle.bottom.value) / 2 - root.top.value)
            down(start)
            moveTo(start + Offset(0f, 30f))
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            assertEquals(3L, edit.draggingId)
            source.value = source.value.filter { it.telegramMessageId != 1L } + tracks[7]
        }
        repeat(4) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        val target = compose.onNodeWithContentDescription("Drag to reorder Song 6").getUnclippedBoundsInRoot()
        viewport.performTouchInput {
            moveTo(Offset((target.left.value + target.right.value) / 2 - root.left.value,
                (target.top.value + target.bottom.value) / 2 - root.top.value))
        }
        repeat(6) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.runOnIdle {
            assertEquals(3L, edit.draggingId)
            assertTrue(edit.draft.indexOf(3L) > edit.draft.indexOf(5L))
            assertEquals(source.value.map { it.telegramMessageId }.toSet(), edit.draft.toSet())
        }
        viewport.performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    @Test fun holdingAnUpwardDragScrollsPastTheOriginalViewportWithoutLosingTheSong() = edgeDrag(upward = true)
    @Test fun holdingADownwardDragScrollsPastTheOriginalViewportWithoutLosingTheSong() = edgeDrag(upward = false)

    private fun edgeDrag(upward: Boolean) {
        setContent {
            edit = rememberLibraryEditing("edge:${if (upward) "up" else "down"}")
            list = rememberLazyListState()
            CompositionLocalProvider(LocalPalette provides DarkPalette, LocalLibraryEditing provides edit,
                LocalEditingListState provides list, LocalEditingSongs provides tracks) {
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.fillMaxSize().libraryReorderGestures(edit, list).testTag("reorder-list"),
                        state = list, contentPadding = PaddingValues(top = LibraryBarHeight, bottom = 24.dp)) {
                        items(edit.ordered(tracks), key = { it.telegramMessageId }) { song ->
                            Column(librarySongPlacement(song.telegramMessageId)) { LibrarySongRow(song, actions, {}) }
                        }
                    }
                    if (edit.active) LibraryEditingTopBar(edit, Modifier.align(Alignment.TopCenter))
                }
            }
        }
        compose.runOnIdle { edit.beginReorder(tracks) }
        val viewport = compose.onNodeWithTag("reorder-list")
        viewport.performScrollToIndex(30)
        val handle = compose.onNodeWithContentDescription("Drag to reorder Song 33").getUnclippedBoundsInRoot()
        val root = viewport.getUnclippedBoundsInRoot()
        val startY = (handle.top.value + handle.bottom.value) / 2 - root.top.value
        var originalFirst = 0
        compose.runOnIdle { originalFirst = list.firstVisibleItemIndex; assertTrue(edit.reorderReady) }
        compose.mainClock.autoAdvance = false
        viewport.performTouchInput {
            val x = width - 24f
            down(Offset(x, startY))
            moveTo(Offset(x, if (upward) 62f else height - 24f), delayMillis = 200)
        }
        repeat(60) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.onNodeWithText("Song 33").assertIsDisplayed()
        }
        compose.runOnIdle {
            assertEquals("The same song must stay attached to the finger", 33L, edit.draggingId)
            assertEquals(60, edit.draft.distinct().size)
            if (upward) {
                assertTrue("Holding at top must scroll upward", list.firstVisibleItemIndex < originalFirst)
                assertTrue("Song must move earlier than the original viewport", edit.draft.indexOf(33L) < 30)
            } else {
                assertTrue("Holding at bottom must scroll downward", list.firstVisibleItemIndex > originalFirst)
                assertTrue("Song must move below the original viewport", edit.draft.indexOf(33L) > 36)
            }
        }
        viewport.performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        val finalOrder = edit.draft.toList()
        compose.onNodeWithText("Done").performClick()
        compose.runOnIdle { assertEquals(finalOrder, edit.ordered(tracks).map { it.telegramMessageId }); assertNull(edit.draggingId) }
    }

    @Test fun enteringReorderOnAScrolledDetailKeepsSongPositionsAndTheirIdentity() {
        val callbacks = LibraryCallbacks({}, { _, _ -> }, { _, _ -> }, {}, {}, {}, {})
        setContent {
            CompositionLocalProvider(LocalPalette provides DarkPalette) {
                CollectionDetailPage("Telegram Songs", actions, "ui:reorder-entry", subtitle = null, onSubtitleClick = null,
                    detailLine = "60 Songs", hero = {}, songs = tracks, loaded = true, callbacks = callbacks) { shown, _ ->
                    items(shown, key = { it.telegramMessageId }) { song ->
                        Column(librarySongPlacement(song.telegramMessageId)) { LibrarySongRow(song, actions, {}) }
                    }
                }
            }
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(30)
        val before = compose.onNodeWithText("Song 32").getUnclippedBoundsInRoot().top.value
        val identity = compose.onNodeWithText("Song 32").fetchSemanticsNode().id
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Reorder").performClick()
        compose.onNodeWithContentDescription("Drag to reorder Song 32").assertIsDisplayed()
        val after = compose.onNodeWithText("Song 32").getUnclippedBoundsInRoot().top.value
        assertEquals("Entering reorder must not throw the visible songs around", before, after, 8f)
        assertEquals(identity, compose.onNodeWithText("Song 32").fetchSemanticsNode().id)
    }
}
