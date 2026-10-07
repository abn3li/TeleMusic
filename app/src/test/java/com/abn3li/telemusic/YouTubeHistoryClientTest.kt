package com.abn3li.telemusic

import com.abn3li.telemusic.data.youtube.YouTubeHistoryClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class YouTubeHistoryClientTest {
    private val tracking = "https://s.youtube.com/api/stats/playback?event=playback&ei=server-token&cpn=old"
    private val headers = mapOf("Cookie" to "test-cookie", "Authorization" to "test-auth", "X-Goog-AuthUser" to "1")

    private fun http(requests: MutableList<Request>, status: Int = 204) = OkHttpClient.Builder()
        .followRedirects(false).retryOnConnectionFailure(false)
        .addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("test").body("".toResponseBody("text/plain".toMediaType())).build()
        }.build()

    @Test fun actualRegistrationUsesAccountAndServerTokenWithOneNonce() {
        val requests = mutableListOf<Request>()
        var fetches = 0
        val client = YouTubeHistoryClient({ headers }, { video, auth ->
            assertEquals("video", video)
            assertEquals(headers, auth)
            fetches++
            tracking
        }, http(requests))
        assertTrue(client.record("video", "same-play-nonce", "account"))
        assertEquals(1, fetches)
        assertEquals(1, requests.size)
        val request = requests.single()
        assertEquals("test-cookie", request.header("Cookie"))
        assertEquals("test-auth", request.header("Authorization"))
        assertEquals("1", request.header("X-Goog-AuthUser"))
        assertEquals("server-token", request.url.queryParameter("ei"))
        assertEquals(listOf("same-play-nonce"), request.url.queryParameterValues("cpn"))
        assertEquals("WEB_REMIX", request.url.queryParameter("c"))
    }

    @Test fun signedOutPlaybackDoesNotFetchOrSubmitAnything() {
        val requests = mutableListOf<Request>()
        val client = YouTubeHistoryClient({ null }, { _, _ -> error("must not fetch") }, http(requests))
        assertFalse(client.record("video", "nonce", "signed-out"))
        assertTrue(requests.isEmpty())
    }

    @Test fun officialStatsAndMusicHostsAreAcceptedWithoutRedirectingCredentials() {
        for (host in listOf("s.youtube.com", "www.youtube.com", "music.youtube.com")) {
            val request = YouTubeHistoryClient.historyRequest("https://$host/api/stats/playback?ei=token", "nonce", headers)
            assertEquals(host, request.url.host)
        }
    }

    @Test fun changingAccountDuringMetadataFetchPreventsSubmission() {
        val requests = mutableListOf<Request>()
        var currentAccount = "old"
        val client = YouTubeHistoryClient({ expected -> if (expected == currentAccount) headers else null },
            { _, _ -> currentAccount = "new"; tracking }, http(requests))
        assertFalse(client.record("video", "nonce", "old"))
        assertTrue(requests.isEmpty())
    }

    @Test fun trackingUrlCannotSendAccountCredentialsToAnotherHostOrEndpoint() {
        for (url in listOf("https://evil.test/api/stats/playback", "https://youtube.com.evil.test/api/stats/playback",
            "http://www.youtube.com/api/stats/playback", "https://www.youtube.com/redirect",
            "https://www.youtube.com:444/api/stats/playback", "https://user@www.youtube.com/api/stats/playback")) {
            assertThrows(IllegalArgumentException::class.java) { YouTubeHistoryClient.historyRequest(url, "nonce", headers) }
        }
    }

    @Test fun rejectedSubmissionIsReportedAsFailureAndNotRetried() {
        val requests = mutableListOf<Request>()
        val client = YouTubeHistoryClient({ headers }, { _, _ -> tracking }, http(requests, 403))
        assertThrows(IllegalStateException::class.java) { client.record("video", "nonce", "account") }
        assertEquals(1, requests.size)
    }
}
