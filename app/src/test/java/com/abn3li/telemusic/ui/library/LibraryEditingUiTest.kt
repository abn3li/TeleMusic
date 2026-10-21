package com.abn3li.telemusic.ui.library

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.abn3li.telemusic.ui.theme.TgMusicTheme
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.Alignment
import android.graphics.RenderNode
import android.os.Looper
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.abn3li.telemusic.data.local.SongEntity
import com.abn3li.telemusic.data.local.PlaylistEntity
import com.abn3li.telemusic.playback.PlaybackQueue
import com.abn3li.telemusic.ui.navigation.LocalComposeTest
import com.abn3li.telemusic.ui.navigation.renderNodeBitmap
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
class LibraryEditingUiTest : LocalComposeTest() {
    @Test fun sharedMenuWindowExistsOnlyWhileMenusAreUsed() {
        var open by mutableStateOf(false)
        var number by mutableStateOf(0)
        setContent {
            TgMusicTheme {
                Box(Modifier.fillMaxSize()) {
                    Text("Open menu", Modifier.clickable { number++; open = true })
                    if (open) LibraryContextMenu(0f, { open = false }) { _, onAction ->
                        Text("Menu $number", Modifier.clickable { onAction {} })
                    }
                }
            }
        }
        fun popupWindows(): Int {
            val type = Class.forName("android.view.WindowManagerGlobal")
            val global = type.getMethod("getInstance").invoke(null)
            val field = type.getDeclaredField("mViews").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            return (field.get(global) as List<android.view.View>).count { it.javaClass.simpleName == "PopupLayout" }
        }
        // No menu open: no window, so nothing polls the screen every frame while the app sits still.
        compose.runOnIdle { assertEquals(0, popupWindows()) }
        repeat(2) { index ->
            compose.onNodeWithText("Open menu").performTouchInput { click() }
            compose.onNodeWithText("Menu ${index + 1}").assertIsDisplayed().performClick()
            compose.waitUntil(timeoutMillis = 1000) { !open }
            compose.onNodeWithText("Menu ${index + 1}").assertDoesNotExist()
        }
        compose.mainClock.advanceTimeBy(4_000)
        compose.runOnIdle { assertEquals(0, popupWindows()) }
    }
    private val songs = (1L..3L).map { SongEntity(it, 0, "Song $it", "Artist") }
    private val queue = PlaybackQueue()
    private val downloaded = mutableListOf<Long>()
    private val liked = mutableListOf<Long>()
    private val created = mutableListOf<Long>()
    private val actions = LibrarySongActions(emptyList(), emptySet(), {}, { liked += it.telegramMessageId },
        { downloaded += it.telegramMessageId }, {}, {}, { _, _ -> }, { _, _ -> }, { _, selected -> created += selected.map { it.telegramMessageId } }, { _, _, _ -> true }, {}, {})

    @Test fun selectComesFromSongContextMenuAndPlayUsesOnlySelectedInDisplayOrder() {
        queue.setQueue(listOf(99, 100), 0); queue.playNext(101)
        setContent {
            CompositionLocalProvider(LocalPalette provides DarkPalette) {
                ContextMenuHost {
                SongListPage("Telegram Songs", songs, actions, {}, { _, _ -> fail("Selection must not play a row") },
                    { ids, shuffle -> assertFalse(shuffle); queue.setQueue(ids, 0) }, collectionKey = "ui:telegram")
                }
            }
        }
        val normalSongNode = compose.onNodeWithText("Song 1").fetchSemanticsNode().id
        compose.onNodeWithText("Song 1").performTouchInput { longClick() }
        compose.onNodeWithText("Select").performClick()
        compose.onNodeWithText("1 Selected").assertExists()
        assertEquals("Entering Select must keep the song subtree mounted", normalSongNode, compose.onNodeWithText("Song 1").fetchSemanticsNode().id)
        compose.onNodeWithContentDescription("Select Song 3").performScrollTo().performClick()
        compose.onNodeWithText("2 Selected").assertExists()
        capture("selection")
        compose.onNodeWithContentDescription("Playlist").performClick()
        compose.onNodeWithText("Add to Playlist").assertExists()
        assertCenteredPlaylistCard()
        capture("playlist-picker")
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithText("New Playlist…").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Selected Mix")
        compose.onNodeWithText("Create").performClick()
        compose.onNodeWithContentDescription("Download").performClick()
        compose.onNodeWithContentDescription("Add to Favorites").performClick()
        compose.onAllNodesWithText("Play").onLast().performClick()
        compose.runOnIdle {
            assertEquals(listOf(1L, 3L), downloaded)
            assertEquals(listOf(1L, 3L), liked)
            assertEquals(listOf(1L, 3L), created)
            assertEquals(1L, queue.currentSongId())
            assertEquals(listOf(3L), queue.upNextIds())
            assertTrue(queue.nextInQueueIds().isEmpty())
            assertEquals(listOf(1L, 3L), queue.sourceIds)
        }
    }

    @Test fun reorderIsInThreeDotMenuAndDoesNotEnterSelection() {
        setContent {
            CompositionLocalProvider(LocalPalette provides DarkPalette) {
                SongListPage("Downloads", songs, actions, {}, { _, _ -> }, { _, _ -> }, collectionKey = "ui:downloads")
            }
        }
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Select").assertDoesNotExist()
        compose.onNodeWithText("Reorder").performClick()
        compose.onNodeWithContentDescription("Drag to reorder Song 1").assertExists()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithContentDescription("Drag to reorder Song 1").assertDoesNotExist()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Custom").assertExists()
    }

    @Test fun playlistDetailUsesTheSameContextSelectionAndThreeDotReorder() {
        val callbacks = LibraryCallbacks({}, { _, _ -> }, { ids, _ -> queue.setQueue(ids, 0) }, {}, {}, {}, {})
        setContent {
            CompositionLocalProvider(LocalPalette provides DarkPalette) {
                CollectionDetailPage("Telegram Songs", actions, "smart:TELEGRAM", subtitle = null, onSubtitleClick = null,
                    detailLine = "3 Songs", hero = {}, songs = songs, loaded = true, callbacks = callbacks) { shown, _ ->
                    items(shown, key = { it.telegramMessageId }) { song -> LibrarySongRow(song, actions, {}) }
                }
            }
        }
        compose.onNodeWithText("Song 1").performScrollTo().performTouchInput { longClick() }
        compose.onNodeWithText("Select").performClick()
        compose.onNodeWithText("Select All").performClick()
        compose.onNodeWithText("3 Selected").assertExists()
        compose.onAllNodesWithText("Play").onLast().performClick()
        compose.runOnIdle { assertEquals(listOf(1L, 2L, 3L), queue.sourceIds) }
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Reorder").performClick()
        compose.onNodeWithContentDescription("Drag to reorder Song 1").assertExists()
        capture("reorder")
        compose.onNodeWithText("Cancel").performClick()
    }

    @Test fun customSortReturnsToSavedOrderAfterChoosingTitle() {
        var played = emptyList<Long>()
        setContent {
            CompositionLocalProvider(LocalPalette provides DarkPalette) {
                SongListPage("Songs", songs, actions, {}, { ids, _ -> played = ids }, { _, _ -> },
                    collectionKey = "ui:sort", customSortIncluded = true,
                    menu = { close ->
                        val edit = LocalLibraryEditing.current!!
                        LibraryMenuItem("Title", null, selected = !edit.isCustom) { edit.resetOrder(); close() }
                        CustomSortMenuItem(close)
                    })
            }
        }
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Reorder").performClick()
        compose.onNodeWithContentDescription("Drag to reorder Song 1").performTouchInput {
            down(center); moveBy(Offset(0f, 80f), delayMillis = 200); up()
        }
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Title").performClick()
        compose.onNodeWithText("Song 1").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(1L, 2L, 3L), played) }
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Custom").performClick()
        compose.onNodeWithText("Song 2").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(2L, 1L, 3L), played) }
    }

    @Test fun draggingHandleChangesOrderAndCancelDiscardsIt() {
        lateinit var edit: LibraryEditing
        setContent {
            edit = rememberLibraryEditing("ui:drag")
            val list = rememberLazyListState()
            CompositionLocalProvider(LocalPalette provides DarkPalette, LocalLibraryEditing provides edit, LocalEditingListState provides list, LocalEditingSongs provides songs) {
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.fillMaxWidth().height(284.dp).libraryReorderGestures(edit, list), state = list,
                        contentPadding = PaddingValues(top = LibraryBarHeight)) {
                        items(edit.ordered(songs), key = { it.telegramMessageId }) { song ->
                            Column(librarySongPlacement(song.telegramMessageId)) { LibrarySongRow(song, actions, {}) }
                        }
                    }
                    LibraryEditingBar(edit, songs, actions) { _, _ -> }
                    if (edit.active) LibraryEditingTopBar(edit, Modifier.align(Alignment.TopCenter))
                }
            }
        }
        compose.runOnIdle { edit.beginReorder(songs) }
        compose.onNodeWithContentDescription("Drag to reorder Song 1").performTouchInput {
            down(center); moveBy(Offset(0f, 80f), delayMillis = 200); up()
        }
        compose.runOnIdle { assertNotEquals(1L, edit.ordered(songs).first().telegramMessageId) }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(songs, edit.ordered(songs)) }
    }

    @Test fun selectedFavoritesCanBeRemovedAndMixedSelectionOnlyAddsMissingOnes() {
        lateinit var edit: LibraryEditing
        var current by mutableStateOf(songs.map { it.copy(isFavorite = true) })
        setContent {
            edit = rememberLibraryEditing("ui:favorite-toggle")
            CompositionLocalProvider(LocalPalette provides DarkPalette) {
                Box(Modifier.fillMaxSize()) { LibraryEditingBar(edit, current, actions) { _, _ -> } }
            }
        }
        compose.runOnIdle { edit.select(1); edit.selected = current.map { it.telegramMessageId }.toSet() }
        compose.onNodeWithContentDescription("Remove from Favorites").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf(1L, 2L, 3L), liked)
            liked.clear()
            current = current.map { it.copy(isFavorite = it.telegramMessageId != 1L) }
        }
        compose.onNodeWithContentDescription("Add to Favorites").performClick()
        compose.runOnIdle { assertEquals(listOf(1L), liked) }
    }

    @Test fun longPlaylistPickerStaysInsideTheWindowAndCanReachLastPlaylist() {
        val added = mutableListOf<Long>()
        val pickerActions = LibrarySongActions(
            playlists = (1L..40L).map { PlaylistEntity(it, "Playlist $it") }, downloadingIds = emptySet(),
            onPlayNext = {}, onToggleFavorite = {}, onDownload = {}, onRemoveDownload = {}, onClearSong = {},
            onAddToPlaylist = { id, _ -> added += id }, onCreatePlaylistAndAdd = { _, _ -> },
            onCreatePlaylistAndAddSongs = { _, _ -> }, onFindArtwork = { _, _, _ -> true }, onGoToArtist = {}, onGoToAlbum = {})
        lateinit var edit: LibraryEditing
        setContent {
            edit = rememberLibraryEditing("ui:long-picker")
            CompositionLocalProvider(LocalPalette provides DarkPalette) {
                Box(Modifier.fillMaxSize()) { LibraryEditingBar(edit, songs, pickerActions) { _, _ -> } }
            }
        }
        compose.runOnIdle { edit.select(1) }
        compose.onNodeWithContentDescription("Playlist").performClick()
        assertCenteredPlaylistCard()
        compose.onNodeWithText("Playlist 40").performScrollTo().performClick()
        // The action runs after the popup's 200 ms close delay, independently of its dim layer.
        compose.waitUntil(timeoutMillis = 1000) { added == listOf(40L) }
        compose.runOnIdle { assertEquals(listOf(40L), added) }
    }

    @Test fun selectionAnimationsStopDrawingAndRecomposingWhenSettled() {
        lateinit var edit: LibraryEditing
        var compositions = 0
        var draws = 0
        setContent {
            edit = rememberLibraryEditing("ui:animation")
            SideEffect { compositions++ }
            CompositionLocalProvider(LocalPalette provides DarkPalette) {
                Box(Modifier.fillMaxSize().drawWithContent { draws++; drawContent() }) {
                    LibraryEditingBar(edit, songs, actions) { _, _ -> }
                }
            }
        }
        compose.runOnIdle { edit.select(1) }
        compose.onNodeWithContentDescription("Playlist").assertIsDisplayed()
        capture("selection-animation-settled")
        var settledCompositions = 0
        var settledDraws = 0
        compose.runOnIdle { settledCompositions = compositions; settledDraws = draws }
        compose.mainClock.advanceTimeBy(2000)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(settledCompositions, compositions)
            assertEquals(settledDraws, draws)
            edit.cancel()
        }
        compose.onNodeWithContentDescription("Playlist").assertDoesNotExist()
    }

    @Test fun selectionGutterMovesContinuouslyAndKeepsTheSameSongOnExit() {
        lateinit var edit: LibraryEditing
        setContent {
            edit = rememberLibraryEditing("ui:selection-motion")
            CompositionLocalProvider(LocalPalette provides DarkPalette, LocalLibraryEditing provides edit) {
                Box(Modifier.fillMaxSize()) { LibrarySongRow(songs.first(), actions, {}) }
            }
        }
        val songNode = compose.onNodeWithText("Song 1").fetchSemanticsNode().id
        val normalLeft = compose.onNodeWithText("Song 1").getUnclippedBoundsInRoot().left.value
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { edit.select(1) }
        repeat(5) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        capture("selection-enter-mid")
        val enteringLeft = compose.onNodeWithText("Song 1").getUnclippedBoundsInRoot().left.value
        compose.mainClock.advanceTimeBy(200)
        capture("selection-enter-end")
        val selectedLeft = compose.onNodeWithText("Song 1").getUnclippedBoundsInRoot().left.value
        assertTrue("Entry should move smoothly: normal=$normalLeft intermediate=$enteringLeft selected=$selectedLeft", enteringLeft > normalLeft && enteringLeft < selectedLeft)
        compose.runOnIdle { edit.cancel() }
        repeat(5) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        capture("selection-exit-mid")
        val leavingLeft = compose.onNodeWithText("Song 1").getUnclippedBoundsInRoot().left.value
        assertTrue("Exit should move smoothly: normal=$normalLeft intermediate=$leavingLeft selected=$selectedLeft", leavingLeft > normalLeft && leavingLeft < selectedLeft)
        compose.mainClock.advanceTimeBy(240)
        capture("selection-exit-end")
        assertEquals(normalLeft, compose.onNodeWithText("Song 1").getUnclippedBoundsInRoot().left.value, 0.1f)
        assertEquals(songNode, compose.onNodeWithText("Song 1").fetchSemanticsNode().id)
        compose.mainClock.autoAdvance = true
    }

    private fun assertCenteredPlaylistCard() {
        val card = compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Add to Playlist")).getUnclippedBoundsInRoot()
        val window = compose.onAllNodes(isRoot()).onLast().getUnclippedBoundsInRoot()
        val cardCenter = (card.top.value + card.bottom.value) / 2f
        val windowCenter = (window.top.value + window.bottom.value) / 2f
        assertTrue("Playlist card should be centered", kotlin.math.abs(cardCenter - windowCenter) <= 24f)
        assertTrue("Playlist card must stay in the window", card.top >= window.top && card.bottom <= window.bottom)
    }

    private fun capture(name: String) {
        compose.runOnIdle {
            val view = activity.window.decorView
            val root = RenderNode("Library editing")
            root.setPosition(0, 0, view.width, view.height)
            val canvas = root.beginRecording()
            try {
                view.draw(canvas)
                // Include Compose Popup windows in the same native-rendered screenshot.
                val globalClass = Class.forName("android.view.WindowManagerGlobal")
                val global = globalClass.getMethod("getInstance").invoke(null)
                val field = globalClass.getDeclaredField("mViews").apply { isAccessible = true }
                val base = IntArray(2).also { view.getLocationOnScreen(it) }
                @Suppress("UNCHECKED_CAST")
                val windows = field.get(global) as List<android.view.View>
                windows.filter { it !== view && it.visibility == android.view.View.VISIBLE }.forEach { popup ->
                    val location = IntArray(2).also { popup.getLocationOnScreen(it) }
                    canvas.save()
                    canvas.translate((location[0] - base[0]).toFloat(), (location[1] - base[1]).toFloat())
                    popup.draw(canvas)
                    canvas.restore()
                }
            } finally { root.endRecording() }
            val image = renderNodeBitmap(root, view.width, view.height)
            val folder = java.io.File("build/reports/library-editing").apply { mkdirs() }
            java.io.File(folder, "$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }
}
