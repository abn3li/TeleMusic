package com.abn3li.telemusic.data.browse

import android.app.Application
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class YouTubeLikeTest {
    @Test fun likeUsesAuthenticatedVideoTargetAndReportsRejection() {
        var calls = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.method == "GET") return@addInterceptor Response.Builder()
                .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("".toResponseBody()).build()
            calls++
            assertEquals("/youtubei/v1/like/like", request.url.encodedPath)
            assertEquals("test-auth", request.header("Authorization"))
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            assertEquals("video123", JSONObject(buffer.readUtf8()).getJSONObject("target").getString("videoId"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body((if (calls == 1) "{}" else "{\"error\":{\"code\":403}}").toResponseBody()).build()
        }.build()
        val client = InnertubeBrowseClient(auth = { mapOf("Authorization" to "test-auth") }, client = http)
        client.likeSong("video123")
        assertTrue(runCatching { client.likeSong("video123") }.isFailure)
        assertTrue(runCatching { InnertubeBrowseClient(client = http).likeSong("video123") }.isFailure)
        assertEquals(2, calls) // Signed-out calls must never reach the server.
    }
    @Test fun removeUsesNeutralRatingEndpointNotDislike() {
        var calls = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.method != "GET") {
                calls++
                assertEquals("/youtubei/v1/like/removelike", request.url.encodedPath)
                assertEquals("test-auth", request.header("Authorization"))
                val buffer = Buffer(); request.body!!.writeTo(buffer)
                assertEquals("video123", JSONObject(buffer.readUtf8()).getJSONObject("target").getString("videoId"))
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{}".toResponseBody()).build()
        }.build()
        InnertubeBrowseClient(auth = { mapOf("Authorization" to "test-auth") }, client = http).setSongLiked("video123", false)
        assertEquals(1, calls)
    }

    @Test fun statusIsReadOnlyFromRequestedSongAndInvertsTheMenuAction() {
        fun row(id: String, status: String) = JSONObject().put("playlistPanelVideoRenderer",
            JSONObject().put("videoId", id).put("menu", JSONObject().put("toggleMenuServiceItemRenderer",
                JSONObject().put("defaultServiceEndpoint", JSONObject().put("likeEndpoint", JSONObject()
                    .put("status", status).put("target", JSONObject().put("videoId", id)))))))
        val response = JSONObject().put("contents", org.json.JSONArray().put(row("other", "LIKE")).put(row("wanted", "INDIFFERENT")))
        assertEquals(true, parseYouTubeSongLiked(response, "wanted"))
        assertEquals(false, parseYouTubeSongLiked(response, "other"))
        assertNull(parseYouTubeSongLiked(response, "missing"))
        assertNull(parseYouTubeSongLiked(JSONObject(), "wanted"))
        assertNull(parseYouTubeSongLiked(row("wanted", "UNRECOGNIZED"), "wanted"))
    }
}
