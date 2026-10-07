package com.abn3li.telemusic

import com.abn3li.telemusic.data.youtube.PlaybackTracking
import com.abn3li.telemusic.data.youtube.YouTubeHistoryClient
import com.abn3li.telemusic.data.youtube.YouTubeWebScope
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
            PlaybackTracking(tracking, null, null, 40)
        }, http = http(requests))
        assertNotNull(client.record("video", "same-play-nonce", "account"))
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
        val client = YouTubeHistoryClient({ null }, { _, _ -> error("must not fetch") }, http = http(requests))
        assertNull(client.record("video", "nonce", "signed-out"))
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
            { _, _ -> currentAccount = "new"; PlaybackTracking(tracking, null, null, 40) }, http = http(requests))
        assertNull(client.record("video", "nonce", "old"))
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
        val client = YouTubeHistoryClient({ headers }, { _, _ -> PlaybackTracking(tracking, null, null, 40) }, http = http(requests, 403))
        assertThrows(IllegalStateException::class.java) { client.record("video", "nonce", "account") }
        assertEquals(1, requests.size)
    }

    @Test fun registeringSendsTheCheckInAndListeningTimeIsReportedWithTheSameNonce() {
        val requests = mutableListOf<Request>()
        val play = PlaybackTracking(tracking, "https://s.youtube.com/api/stats/watchtime?ei=server-token",
            "https://s.youtube.com/api/stats/atr?ei=server-token&c=WEB_REMIX", 40)
        val scope = YouTubeWebScope("1.20990101.00.00", "session-visitor", "brand-page", "2", 20731)
        val client = YouTubeHistoryClient({ headers }, { _, _ -> play }, { scope }, http(requests))
        assertSame(play, client.record("video", "nonce", "account"))
        assertTrue(client.watchtime(play, "nonce", "account", 95, final = true))
        assertEquals(listOf("/api/stats/playback", "/api/stats/atr", "/api/stats/watchtime"), requests.map { it.url.encodedPath })
        requests.forEach { assertEquals(listOf("nonce"), it.url.queryParameterValues("cpn")) }
        val playback = requests[0]
        assertEquals("1.20990101.00.00", playback.url.queryParameter("cver"))
        assertEquals("session-visitor", playback.header("X-Goog-Visitor-Id"))
        assertEquals("brand-page", playback.header("X-Goog-PageId"))
        assertEquals("2", playback.header("X-Goog-AuthUser"))
        // The check-in's address already names its client: only the nonce is added.
        assertNull(requests[1].url.queryParameter("cver"))
        val watchtime = requests[2]
        assertEquals("95", watchtime.url.queryParameter("et"))
        assertEquals("1", watchtime.url.queryParameter("final"))
        assertEquals("paused", watchtime.url.queryParameter("state"))
    }

    @Test fun listeningTimeIsNotReportedAfterTheAccountChanged() {
        val requests = mutableListOf<Request>()
        val play = PlaybackTracking(tracking, "https://s.youtube.com/api/stats/watchtime?ei=t", null, 40)
        val client = YouTubeHistoryClient({ null }, { _, _ -> play }, http = http(requests))
        assertFalse(client.watchtime(play, "nonce", "old", 60, final = false))
        assertTrue(requests.isEmpty())
    }
}
