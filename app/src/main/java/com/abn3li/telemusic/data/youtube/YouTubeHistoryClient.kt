package com.abn3li.telemusic.data.youtube

import com.abn3li.telemusic.data.browse.InnertubeBrowseClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** The listening-report addresses a player response nominates for one play. */
data class PlaybackTracking(
    val playbackUrl: String,
    val watchtimeUrl: String?,
    // The check-in real clients send a few seconds into a play.
    val atrUrl: String?,
    // How often the website reports listening time while a song plays.
    val flushSeconds: Long
)

/**
 * Reports a real listen the way YouTube Music's website does, so it lands in the account's
 * history and counts for its recommendations: "playback" (the history entry) and the check-in
 * when a play is registered, then "watchtime" reports of how much was actually heard - without
 * those a history entry reads as a skip. All with the account's own headers and this session's
 * visitor; never changes playback.
 */
internal class YouTubeHistoryClient(
    private val headersForSession: (String) -> Map<String, String>?,
    private val trackingFor: (String, Map<String, String>) -> PlaybackTracking? =
        { video, headers -> InnertubeBrowseClient().playbackTracking(video, headers, null) },
    // What music.youtube.com said about the session (see YouTubeWebSession), when read.
    private val scope: (String) -> YouTubeWebScope? = { null },
    // Once submission starts, a lost response is ambiguous: do not retry and create duplicates.
    private val http: OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .callTimeout(15, TimeUnit.SECONDS).build()
) {
    /**
     * Registers the play: its "playback" report, then the check-in. Returns the play's report
     * addresses for its listening-time reports, or null when signed out or the account changed.
     */
    fun record(videoId: String, playbackNonce: String, accountSession: String): PlaybackTracking? {
        val headers = headersForSession(accountSession) ?: return null
        val tracking = trackingFor(videoId, headers) ?: error("YouTube returned no history tracking URL")
        val freshHeaders = headersForSession(accountSession) ?: return null
        val session = scope(accountSession)
        send(historyRequest(tracking.playbackUrl, playbackNonce, freshHeaders, session))
        // The check-in's address already says which client it is, so it only gets the nonce.
        tracking.atrUrl?.let { atr ->
            runCatching { send(historyRequest(atr, playbackNonce, freshHeaders, session, statsParams = false)) }
        }
        return tracking
    }

    /** How much of the play was heard so far ([seconds]); [final] closes the play out. */
    fun watchtime(tracking: PlaybackTracking, playbackNonce: String, accountSession: String,
        seconds: Long, final: Boolean): Boolean {
        val url = tracking.watchtimeUrl ?: return false
        val headers = headersForSession(accountSession) ?: return false
        val extra = linkedMapOf(
            "st" to "0", "et" to seconds.toString(), "cmt" to seconds.toString(),
            "state" to if (final) "paused" else "playing"
        )
        if (final) extra["final"] = "1"
        send(historyRequest(url, playbackNonce, headers, scope(accountSession), extra = extra))
        return true
    }

    private fun send(request: Request) {
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "YouTube history returned HTTP ${response.code}" }
        }
    }

    companion object {
        private const val CHROME = "141.0.0.0"
        private const val DEFAULT_VERSION = "1.20261006.10.00"
        private val STATS_PATHS = setOf("/api/stats/playback", "/api/stats/watchtime", "/api/stats/atr")

        internal fun historyRequest(
            tracking: String,
            nonce: String,
            headers: Map<String, String>,
            scope: YouTubeWebScope? = null,
            statsParams: Boolean = true,
            extra: Map<String, String> = emptyMap()
        ): Request {
            val url = tracking.toHttpUrlOrNull() ?: error("Invalid history tracking URL")
            require(url.scheme == "https" && url.host in setOf("s.youtube.com", "www.youtube.com", "music.youtube.com", "youtube.com") &&
                url.encodedPath in STATS_PATHS && url.port == 443 &&
                url.username.isEmpty() && url.password.isEmpty()) { "Unexpected history tracking endpoint" }
            val report = url.newBuilder().setQueryParameter("cpn", nonce)
            if (statsParams) {
                // What the website says about itself on every report.
                linkedMapOf(
                    "ver" to "2", "c" to "WEB_REMIX", "cver" to (scope?.clientVersion ?: DEFAULT_VERSION),
                    "cplayer" to "UNIPLAYER", "cbr" to "Chrome", "cbrver" to CHROME,
                    "cos" to "Windows", "cosver" to "10.0", "hl" to "en_US", "cr" to "US"
                ).forEach { (name, value) -> report.setQueryParameter(name, value) }
            }
            extra.forEach { (name, value) -> report.setQueryParameter(name, value) }
            return Request.Builder().url(report.build())
                .header("Origin", YouTubeAccount.ORIGIN)
                .header("Referer", "${YouTubeAccount.ORIGIN}/")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$CHROME Safari/537.36")
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .apply {
                    scope?.visitorData?.let { header("X-Goog-Visitor-Id", it) }
                    scope?.pageId?.let { header("X-Goog-PageId", it) }
                    scope?.authUser?.let { header("X-Goog-AuthUser", it) }
                }
                .get().build()
        }
    }
}
