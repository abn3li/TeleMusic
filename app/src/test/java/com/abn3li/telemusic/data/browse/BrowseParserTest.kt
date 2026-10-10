package com.abn3li.telemusic.data.browse

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BrowseParserTest {
    @Test fun categoryKeepsSongsPlaylistsArtistsAndAlbumsTogether() {
        val response = page(
            row("song-one", "First song"),
            card("VLplaylist", "Playlist", "PLAYLIST"),
            collectionRow("UCartist", "Artist", "ARTIST"),
            card("MPREalbum", "Album", "ALBUM"),
            songCard("song-two", "Second song")
        )
        val content = BrowseParser.parseBrowseContent(response)
        assertEquals(listOf("song-one", "song-two"), content.tracks.map { it.videoId })
        assertEquals(setOf(BrowseKind.PLAYLIST, BrowseKind.ARTIST, BrowseKind.ALBUM), content.collections.map { it.kind }.toSet())
        assertEquals(setOf("VLplaylist", "UCartist", "MPREalbum"), content.collections.map { it.browseId }.toSet())
    }

    @Test fun categoryLinksWithTheSamePageAndDifferentParamsStayDistinct() {
        val first = card("category", "Rock", "OTHER", "rock")
        val second = card("category", "Pop", "OTHER", "pop")
        val content = BrowseParser.parseBrowseContent(page(first, second, first))
        assertEquals(listOf("rock", "pop"), content.collections.map { it.params })
    }

    @Test fun responsiveCollectionLinksKeepDifferentParamsAndRemoveOnlyExactDuplicates() {
        val first = collectionRow("VLshared", "First playlist", "PLAYLIST", "first")
        val second = collectionRow("VLshared", "Second playlist", "PLAYLIST", "second")
        val response = page(first, second, first)
        for (collections in listOf(BrowseParser.parseSearchCollections(response), BrowseParser.parseBrowseContent(response).collections)) {
            assertEquals(listOf("first", "second"), collections.map { it.params })
            assertEquals(listOf("First playlist", "Second playlist"), collections.map { it.title })
        }
    }

    @Test fun songCardsArePlayableAndDuplicateRowsDoNotDuplicateTheQueue() {
        val content = BrowseParser.parseBrowseContent(page(
            row("song", "Song"), songCard("song", "Song"), songCard("other", "Other song")
        ))
        assertEquals(listOf("song", "other"), content.tracks.map { it.videoId })
        assertTrue(content.collections.isEmpty())
    }

    @Test fun playlistHeaderAndRowsSurviveAlongsideRecommendationsAndContinuationPages() {
        val response = page(row("song", "Song"), card("VLrecommended", "Recommended", "PLAYLIST"), songCard("recommended-song", "Recommended song"))
        response.put("header", JSONObject().put("musicResponsiveHeaderRenderer", JSONObject()
            .put("title", text("My playlist")).put("subtitle", text("Playlist"))))
        val content = BrowseParser.parseBrowseContent(response)
        assertEquals("My playlist", content.header?.title)
        assertEquals("Playlist", content.header?.subtitle)
        assertEquals("song", content.tracks.single().videoId)
        assertEquals("VLrecommended", content.collections.single().browseId)

        val continuation = JSONObject().put("continuationContents", JSONObject()
            .put("musicPlaylistShelfContinuation", JSONObject().put("contents", JSONArray()
                .put(row("next", "Next song")))))
        assertEquals("next", BrowseParser.parseBrowseContent(continuation).tracks.single().videoId)
    }

    @Test fun categoryHeaderDoesNotHidePlayableSongCards() {
        val response = page(songCard("song", "Song"), card("VLplaylist", "Playlist", "PLAYLIST"))
        response.put("header", JSONObject().put("musicResponsiveHeaderRenderer", JSONObject().put("title", text("Rock"))))
        val content = BrowseParser.parseBrowseContent(response, includeSongCards = true)
        assertEquals("song", content.tracks.single().videoId)
        assertEquals("VLplaylist", content.collections.single().browseId)
    }

    @Test fun anonymousCollectionOnlyAndEmptyPagesStillLoad() {
        val content = BrowseParser.parseBrowseContent(page(card("VLplaylist", "Playlist", "PLAYLIST")))
        assertTrue(content.tracks.isEmpty())
        assertEquals("Playlist", content.collections.single().title)
        assertEquals(BrowseContent(), BrowseParser.parseBrowseContent(JSONObject()))
    }

    private fun page(vararg items: JSONObject) = JSONObject().put("contents", JSONArray(items.toList()))
    private fun text(value: String) = JSONObject().put("runs", JSONArray().put(JSONObject().put("text", value)))
    private fun endpoint(id: String, type: String, params: String? = null) = JSONObject()
        .put("browseId", id).put("params", params)
        .put("browseEndpointContextSupportedConfigs", JSONObject()
            .put("browseEndpointContextMusicConfig", JSONObject().put("pageType", "MUSIC_PAGE_TYPE_$type")))

    private fun row(id: String, title: String) = JSONObject().put("musicResponsiveListItemRenderer", JSONObject()
        .put("navigationEndpoint", JSONObject().put("watchEndpoint", JSONObject().put("videoId", id)))
        .put("flexColumns", JSONArray().put(JSONObject().put("musicResponsiveListItemFlexColumnRenderer", JSONObject().put("text", text(title))))))

    private fun card(id: String, title: String, type: String, params: String? = null) = JSONObject()
        .put("musicTwoRowItemRenderer", JSONObject().put("title", text(title))
            .put("navigationEndpoint", JSONObject().put("browseEndpoint", endpoint(id, type, params))))

    private fun collectionRow(id: String, title: String, type: String, params: String? = null) = JSONObject()
        .put("musicResponsiveListItemRenderer", JSONObject()
            .put("navigationEndpoint", JSONObject().put("browseEndpoint", endpoint(id, type, params)))
            .put("flexColumns", JSONArray().put(JSONObject().put("musicResponsiveListItemFlexColumnRenderer", JSONObject().put("text", text(title))))))

    private fun songCard(id: String, title: String) = JSONObject().put("musicTwoRowItemRenderer", JSONObject()
        .put("title", text(title)).put("subtitle", text("Artist"))
        .put("navigationEndpoint", JSONObject().put("watchEndpoint", JSONObject().put("videoId", id))))
}
