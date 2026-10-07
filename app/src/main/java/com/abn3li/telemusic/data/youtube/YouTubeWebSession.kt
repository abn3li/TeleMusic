package com.abn3li.telemusic.data.youtube

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * What music.youtube.com itself says about the signed-in session, read from the page's own
 * config the way the site's player reads it:
 * - [clientVersion]: the website's current version, so requests never claim one Google retired;
 * - [visitorData]: the visitor id bound to this session - listening reports are credited to the
 *   visitor a player response was issued to, so an anonymous one would credit nobody;
 * - [pageId]: a brand account's id (X-Goog-PageId), when the signed-in profile is one;
 * - [signatureTimestamp]: the current web player's timestamp. Without it YouTube answers the
 *   website's player request with "Video unavailable" and leaves out the listening-report URLs.
 */
data class YouTubeWebScope(
    val clientVersion: String?,
    val visitorData: String?,
    val pageId: String?,
    val authUser: String?,
    val signatureTimestamp: Int?
)

/**
 * Reads [YouTubeWebScope] once per sign-in, the first time a listen is reported (not at
 * start-up, and never on a timer): one page load and, when the web player changed, one read of
 * its script. A failure leaves everything as it was, and is tried again on the next listen.
 */
internal class YouTubeWebSession(
    private val headers: (String) -> Map<String, String>?,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
) {
    private var scope: YouTubeWebScope? = null
    // One read at a time; held only by the reading (network) thread, so forget() never waits.
    private val readLock = Any()
    private var scopeSession: String? = null
    // The web player's timestamp, by player version: the script is only read again when it changes.
    private var playerTimestamp: Pair<String, Int>? = null

    /** The scope already read for [accountSession], without reading anything. */
    @Synchronized fun cached(accountSession: String?): YouTubeWebScope? =
        scope?.takeIf { accountSession != null && scopeSession == accountSession }

    /** Blocking (network): call off the main thread. Null when signed out or unreadable. */
    fun scope(accountSession: String): YouTubeWebScope? = synchronized(readLock) {
        cached(accountSession)?.let { return it }
        read(accountSession)
    }

    private fun read(accountSession: String): YouTubeWebScope? {
        val auth = headers(accountSession) ?: return null
        val html = get(MUSIC_ORIGIN + "/", auth) ?: return null
        if (LOGGED_IN.find(html)?.groupValues?.get(1) != "true") {
            runCatching { android.util.Log.w(TAG, "music.youtube.com served a signed-out page") }
            return null
        }
        val playerPath = JS_URL.find(html)?.groupValues?.get(1)
        val read = YouTubeWebScope(
            clientVersion = CLIENT_VERSION.find(html)?.groupValues?.get(1),
            visitorData = VISITOR_DATA.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() },
            pageId = PAGE_ID.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() },
            authUser = SESSION_INDEX.find(html)?.groupValues?.get(1),
            signatureTimestamp = playerPath?.let { timestampOf(it) }
        )
        // A sign-out or account change during the read must not leave the old account's scope.
        if (headers(accountSession) == null) return null
        synchronized(this) {
            scope = read
            scopeSession = accountSession
        }
        runCatching {
            android.util.Log.i(TAG, "Session read; version=${read.clientVersion}; sts=${read.signatureTimestamp}; " +
                "visitor=${read.visitorData != null}; brandAccount=${read.pageId != null}")
        }
        return read
    }

    @Synchronized fun forget() {
        scope = null
        scopeSession = null
    }

    private fun timestampOf(playerPath: String): Int? {
        val version = PLAYER_VERSION.find(playerPath)?.groupValues?.get(1) ?: playerPath
        playerTimestamp?.takeIf { it.first == version }?.let { return it.second }
        val script = get(MUSIC_ORIGIN + playerPath, null) ?: return null
        val sts = STS.find(script)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        playerTimestamp = version to sts
        return sts
    }

    private fun get(url: String, auth: Map<String, String>?): String? = runCatching {
        val request = Request.Builder().url(url)
            .header("User-Agent", WEB_USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .apply { auth?.forEach { (name, value) -> header(name, value) } }
            .get().build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            response.body?.string()
        }
    }.onFailure { runCatching { android.util.Log.w(TAG, "Couldn't read ${url.substringBefore('?').takeLast(40)}: ${it.message}") } }
        .getOrNull()

    companion object {
        private const val TAG = "YouTubeHistory"
        const val MUSIC_ORIGIN = "https://music.youtube.com"
        const val WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
        private val LOGGED_IN = Regex(""""LOGGED_IN"\s*:\s*(true|false)""")
        private val CLIENT_VERSION = Regex(""""INNERTUBE_CLIENT_VERSION"\s*:\s*"([^"]+)"""")
        private val VISITOR_DATA = Regex(""""VISITOR_DATA"\s*:\s*"([^"]+)"""")
        private val PAGE_ID = Regex(""""DELEGATED_SESSION_ID"\s*:\s*"([^"]+)"""")
        private val SESSION_INDEX = Regex(""""SESSION_INDEX"\s*:\s*"?(\d+)""")
        private val JS_URL = Regex(""""jsUrl"\s*:\s*"(/s/player/[^"]+?base\.js)"""")
        private val PLAYER_VERSION = Regex("""/s/player/([^/]+)/""")
        private val STS = Regex("""(?:signatureTimestamp|sts)\s*:\s*(\d{5})""")
    }
}
