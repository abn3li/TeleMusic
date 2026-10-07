package com.abn3li.telemusic.data.youtube

import com.abn3li.telemusic.data.browse.InnertubeBrowseClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Registers a real listen using YouTube's own tracking response, without changing playback. */
internal class YouTubeHistoryClient(
    private val headersForSession: (String) -> Map<String, String>?,
    private val trackingUrl: (String, Map<String, String>) -> String? = InnertubeBrowseClient()::historyTrackingUrl,
    // Once submission starts, a lost response is ambiguous: do not retry and create duplicates.
    private val http: OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .callTimeout(15, TimeUnit.SECONDS).build()
) {
    fun record(videoId: String, playbackNonce: String, accountSession: String): Boolean {
        val headers = headersForSession(accountSession) ?: return false
        val tracking = trackingUrl(videoId, headers) ?: error("YouTube returned no history tracking URL")
        val freshHeaders = headersForSession(accountSession) ?: return false
        val request = historyRequest(tracking, playbackNonce, freshHeaders)
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "YouTube history returned HTTP ${response.code}" }
        }
        return true
    }

    companion object {
        internal fun historyRequest(tracking: String, nonce: String, headers: Map<String, String>): Request {
            val url = tracking.toHttpUrlOrNull() ?: error("Invalid history tracking URL")
            require(url.scheme == "https" && url.host in setOf("s.youtube.com", "www.youtube.com", "music.youtube.com", "youtube.com") &&
                url.encodedPath == "/api/stats/playback" && url.port == 443 &&
                url.username.isEmpty() && url.password.isEmpty()) { "Unexpected history tracking endpoint" }
            val reportUrl = url.newBuilder().setQueryParameter("ver", "2")
                .setQueryParameter("c", "WEB_REMIX").setQueryParameter("cpn", nonce).build()
            return Request.Builder().url(reportUrl).header("Origin", YouTubeAccount.ORIGIN)
                .header("Referer", "${YouTubeAccount.ORIGIN}/")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
                .apply { headers.forEach { (name, value) -> header(name, value) } }.get().build()
        }
    }
}
