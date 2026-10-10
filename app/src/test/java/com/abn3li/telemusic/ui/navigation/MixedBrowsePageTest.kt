package com.abn3li.telemusic.ui.navigation

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import com.abn3li.telemusic.data.browse.*
import com.abn3li.telemusic.ui.download.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MixedBrowsePageTest : LocalComposeTest() {
    @Test fun mixedCategoryShowsEachKindAndKeepsOpeningPlayingAndOptionsWorking() {
        val playlist = BrowseCollection("VLplaylist", "params", "A playlist", null, null, BrowseKind.PLAYLIST)
        val artist = BrowseCollection("UCartist", null, "An artist", null, null, BrowseKind.ARTIST)
        val album = BrowseCollection("MPREalbum", null, "An album", null, null, BrowseKind.ALBUM)
        val songs = listOf(BrowseTrack("first", "First song", "Singer", null), BrowseTrack("second", "Second song", "Singer", null))
        var opened: BrowseCollection? = null
        var options: BrowseCollection? = null
        var played: List<BrowseTrack> = emptyList()
        var playingIndex = -1
        val menus = YouTubeMenus { _, collection, _, _, _, _ -> options = collection }
        setContent {
            CompositionLocalProvider(LocalYouTubeMenus provides menus) {
                MixedBrowsePage(
                    BrowseCollectionUiState("Rock", isLoading = false, tracks = songs, collections = listOf(playlist, artist, album)),
                    {}, { opened = it }, { queue, index, _ -> played = queue; playingIndex = index }, {}
                )
            }
        }
        scrollTo(1)
        compose.onNodeWithText("Playlists").assertIsDisplayed()
        compose.onNodeWithText("A playlist").performClick()
        compose.runOnIdle { assertEquals(playlist, opened) }

        scrollTo(3)
        compose.onNodeWithText("Artists").assertIsDisplayed()
        compose.onNodeWithText("An artist").performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(artist, options) }

        scrollTo(5)
        compose.onNodeWithText("Albums").assertIsDisplayed()
        compose.onNodeWithText("An album").performClick()
        compose.runOnIdle { assertEquals(album, opened) }

        scrollTo(7)
        compose.onNodeWithText("Songs").assertIsDisplayed()
        val firstSong = compose.onNodeWithText("First song").fetchSemanticsNode().boundsInRoot
        val secondSong = compose.onNodeWithText("Second song").fetchSemanticsNode().boundsInRoot
        assertEquals(firstSong.top, secondSong.top, 0.5f)
        assertTrue(secondSong.left > firstSong.left)
        compose.onNodeWithText("Second song").performClick()
        compose.runOnIdle {
            assertEquals(songs, played)
            assertEquals(1, playingIndex)
        }
        compose.onAllNodesWithText("Show all").assertCountEquals(0)
    }

    @Test fun previewsStopAtSixAndShowAllKeepsEveryItemAndReturnsToTheSamePlace() {
        val playlists = List(8) { BrowseCollection("VLplaylist$it", null, "Playlist $it", null, null, BrowseKind.PLAYLIST) }
        val songs = List(8) { BrowseTrack("song$it", "Song $it", "Singer", null) }
        var opened: BrowseCollection? = null
        var played: List<BrowseTrack> = emptyList()
        var playingIndex = -1
        var downloaded: BrowseTrack? = null
        var downloadFromMenu: (() -> Unit)? = null
        var leftCategory = false
        val menus = YouTubeMenus { _, _, _, _, download, _ -> downloadFromMenu = download }
        setContent {
            CompositionLocalProvider(LocalYouTubeMenus provides menus) {
                MixedBrowsePage(
                    BrowseCollectionUiState("Rock", isLoading = false, tracks = songs, collections = playlists),
                    { leftCategory = true }, { opened = it },
                    { queue, index, _ -> played = queue; playingIndex = index }, { downloaded = it }
                )
            }
        }
        scrollTo(1)
        scrollShelf("Playlist 0", 5)
        compose.onNodeWithText("Playlist 5").assertIsDisplayed()
        compose.onNodeWithText("Playlist 6").assertDoesNotExist()
        compose.onNodeWithContentDescription("Show all Playlists").performClick()
        scrollTo(7)
        compose.onNodeWithText("Playlist 6").performClick()
        compose.runOnIdle {
            assertEquals(playlists[6], opened)
            activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.onNodeWithContentDescription("Show all Playlists").assertIsDisplayed()
        compose.onNodeWithText("Playlist 5").assertIsDisplayed()

        scrollTo(3)
        scrollShelf("Song 0", 5)
        compose.onNodeWithText("Song 5").assertIsDisplayed()
        compose.onNodeWithText("Song 6").assertDoesNotExist()
        compose.onNodeWithContentDescription("Show all Songs").performClick()
        scrollTo(7)
        compose.onNodeWithText("Song 6").performTouchInput { longClick() }
        compose.runOnIdle {
            checkNotNull(downloadFromMenu).invoke()
            assertEquals(songs[6], downloaded)
        }
        compose.onNodeWithText("Song 6").performClick()
        compose.runOnIdle {
            assertEquals(songs, played)
            assertEquals(6, playingIndex)
            activity.onBackPressedDispatcher.onBackPressed()
            assertFalse(leftCategory)
        }
        compose.onNodeWithContentDescription("Show all Songs").assertIsDisplayed()
        compose.onNodeWithText("Song 5").assertIsDisplayed()
    }

    private fun scrollShelf(title: String, index: Int) {
        compose.onNode(hasScrollToIndexAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange) and
            hasAnyDescendant(hasText(title))).performScrollToIndex(index)
    }

    private fun scrollTo(index: Int) {
        compose.onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToIndex(index)
    }
}
