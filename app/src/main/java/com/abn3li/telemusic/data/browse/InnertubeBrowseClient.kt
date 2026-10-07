package com.abn3li.telemusic.data.browse

import com.abn3li.telemusic.data.youtube.PlaybackTracking
import com.abn3li.telemusic.data.youtube.YouTubeWebScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Browse-only client for YouTube Music's public Innertube API (music.youtube.com/youtubei/v1) -
 * fetches the home feed and lets a browse card's own browseId be opened further (a playlist's
 * track list, an artist's page, a chart). The /player endpoint is used only to obtain a history
 * tracking URL; no stream extraction or signature/cipher deciphering happens here.
 * Playback and downloads use the downloader's extraction
 * path instead of maintaining a second implementation. A real download still
 * only ever happens through yt-dlp (data/download) once the user picks a track here - this
 * client only ever returns metadata (titles, thumbnails, ids), never a stream URL.
 *
 * [FALLBACK_API_KEY] is WEB_REMIX's own public Innertube key - shipped to every anonymous
 * visitor of music.youtube.com, not a secret.
 */
class InnertubeBrowseClient(
    // The signed-in YouTube Music account's headers (see YouTubeAccount.authHeaders), or null:
    // with them every request - Home, Search, a playlist - is answered as that account.
    private val auth: () -> Map<String, String>? = { null },
    // What music.youtube.com said about that session (live version, its own visitor id, a brand
    // account's id), once read - see YouTubeWebSession. Null until then: the defaults below.
    private val webScope: () -> YouTubeWebScope? = { null }
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // A user-facing region picker used to sit in front of this (Settings > YouTube region), but
    // was removed: verified on-device that neither the context.client.gl field below nor a
    // PREF=gl=<region> cookie changes what Home/Charts/New Releases/Genres actually come back for
    // this anonymous (no sign-in) endpoint - YouTube's backend gates that content off the
    // request's real IP geolocation instead, which this app has no way to override. `gl`/`hl`
    // stay hardcoded to a real value (still needed - they affect response language/labels) rather
    // than exposing a setting that looked functional but couldn't actually do what it claimed.
    fun browse(browseId: String, params: String? = null): JSONObject =
        post("browse", JSONObject().apply {
            put("browseId", browseId)
            if (params != null) put("params", params)
        })

    /** The next page of a browse response that had a [BrowseParser.findContinuationToken] -
     * same `/browse` endpoint, just a continuation token instead of a browseId (this is
     * Innertube's own convention for paging any shelf, not something specific to this client). */
    /** YouTube Music search restricted to the "Songs" shelf (real audio tracks, not videos) -
     * one request; each result's title / artist / duration / thumbnail is right in the response. */
    fun searchSongs(query: String): JSONObject = search(query, SearchFilter.SONGS)

    /** YouTube Music search restricted to one tab ([filter]): songs, albums, artists or
     * playlists - the same filters the site's own search chips use. */
    fun search(query: String, filter: SearchFilter): JSONObject =
        post("search", JSONObject().apply {
            put("query", query)
            put("params", filter.params)
        })

    /** The signed-in account's name and picture (YouTube Music's own account menu). */
    fun accountMenu(): JSONObject = post("account/account_menu", JSONObject())

    /**
     * Where to report a listen (history registration only; stream URLs still come from yt-dlp):
     * the website's own player request, signed in, quoting the current web player's timestamp
     * ([scope]) - without it YouTube says "Video unavailable" and leaves the report URLs out.
     * The answer is issued to this session's own visitor, which is who the reports are credited
     * to. If it still has none, the iPhone app's anonymous answer gives at least the addresses.
     */
    fun playbackTracking(videoId: String, headers: Map<String, String>, scope: YouTubeWebScope?): PlaybackTracking? {
        val web = runCatching {
            post("player", JSONObject().apply {
                put("videoId", videoId)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
                put("playbackContext", JSONObject().put("contentPlaybackContext", JSONObject().apply {
                    put("html5Preference", "HTML5_PREF_WANTS")
                    put("referer", "https://music.youtube.com/watch?v=$videoId")
                    scope?.signatureTimestamp?.let { put("signatureTimestamp", it) }
                }))
            }, headers, scope)
        }.getOrNull()
        web?.let(::trackingOf)?.let { return it }
        logRefusal("website", web)
        return iosTracking(videoId)
    }

    private fun iosTracking(videoId: String): PlaybackTracking? {
        val body = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "IOS")
                    put("clientVersion", IOS_CLIENT_VERSION)
                    put("deviceMake", "Apple")
                    put("deviceModel", "iPhone16,2")
                    put("osName", "iPhone")
                    put("osVersion", "18.3.2.22D82")
                    put("hl", "en")
                    put("gl", "US")
                })
            })
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .addHeader("Content-Type", "application/json")
            .addHeader("X-YouTube-Client-Name", "5")
            .addHeader("X-YouTube-Client-Version", IOS_CLIENT_VERSION)
            .addHeader("User-Agent", IOS_USER_AGENT)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val response = client.newCall(request).execute().use { r ->
            check(r.isSuccessful) { "Innertube player failed: HTTP ${r.code}" }
            JSONObject(r.body?.string().orEmpty())
        }
        return trackingOf(response) ?: run { logRefusal("iPhone", response); null }
    }

    private fun trackingOf(response: JSONObject): PlaybackTracking? {
        val tracking = response.optJSONObject("playbackTracking") ?: return null
        fun url(key: String) = tracking.optJSONObject(key)?.optString("baseUrl")?.takeIf { it.isNotBlank() }
        return PlaybackTracking(
            playbackUrl = url("videostatsPlaybackUrl") ?: return null,
            watchtimeUrl = url("videostatsWatchtimeUrl"),
            atrUrl = url("atrUrl"),
            flushSeconds = tracking.optLong("videostatsDefaultFlushIntervalSeconds", 40L)
        )
    }

    // YouTube's own refusal ("LOGIN_REQUIRED", "Video unavailable"...): safe to log - no
    // cookies or links in it.
    private fun logRefusal(client: String, response: JSONObject?) {
        val playability = response?.optJSONObject("playabilityStatus")
        runCatching {
            android.util.Log.w("YouTubeHistory", "No tracking from the $client player; playability=" +
                playability?.optString("status") + "; reason=" + playability?.optString("reason"))
        }
    }

    fun browseContinuation(continuation: String): JSONObject =
        post("browse", JSONObject().apply { put("continuation", continuation) })

    fun next(videoId: String): JSONObject = post("next", JSONObject().apply {
        put("videoId", videoId)
        put("playlistId", "RDAMVM$videoId")
        put("enablePersistentPlaylistPanel", true)
        put("isAudioOnly", true)
        put("tunerSettingValue", "AUTOMIX_SETTING_NORMAL")
    })

    // An anonymous visitor id, like the one the website gets on its first visit. Sent with
    // every request, it makes these look like one returning visitor instead of a stranger each
    // time - fewer "are you a bot" refusals. Minted once per run; kept from any response too.
    @Volatile private var visitorData: String? = null

    private fun visitorId(): String? {
        visitorData?.let { return it }
        val minted = runCatching {
            val request = Request.Builder()
                .url("https://www.youtube.com/sw.js_data")
                .header("User-Agent", WEB_USER_AGENT)
                .build()
            client.newCall(request).execute().use { response ->
                // A short anti-hijacking prefix, then nested arrays; the id is the one string
                // shaped like a visitor id.
                VISITOR_DATA.find(response.body?.string().orEmpty())?.groupValues?.get(1)
            }
        }.onFailure { android.util.Log.w("InnertubeBrowse", "Couldn't get a visitor id: ${it.message}") }.getOrNull()
        if (minted != null) visitorData = minted
        return minted
    }

    private fun post(
        endpoint: String,
        extra: JSONObject,
        headers: Map<String, String>? = auth(),
        scope: YouTubeWebScope? = if (headers != null) webScope() else null
    ): JSONObject {
        val visitor = scope?.visitorData ?: visitorId()
        val version = scope?.clientVersion ?: CLIENT_VERSION
        val body = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", version)
                    put("hl", "en")
                    put("gl", "US")
                    if (visitor != null) put("visitorData", visitor)
                })
            })
            extra.keys().forEach { key -> put(key, extra.get(key)) }
        }

        val request = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/$endpoint?key=$FALLBACK_API_KEY&prettyPrint=false")
            .addHeader("Content-Type", "application/json")
            .addHeader("X-YouTube-Client-Name", "67")
            .addHeader("X-YouTube-Client-Version", version)
            .addHeader("User-Agent", WEB_USER_AGENT)
            .addHeader("Origin", "https://music.youtube.com")
            .apply { scope?.pageId?.let { addHeader("X-Goog-PageId", it) } }
            .apply { if (visitor != null) addHeader("X-Goog-Visitor-Id", visitor) }
            .apply { headers?.forEach { (name, value) -> header(name, value) } }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            check(response.isSuccessful) { "Innertube $endpoint failed: HTTP ${response.code}" }
            return JSONObject(responseBody).also { json ->
                if (visitorData == null && scope == null) {
                    json.optJSONObject("responseContext")?.optString("visitorData")?.takeIf { it.isNotBlank() }?.let { visitorData = it }
                }
            }
        }
    }

    companion object {
        const val HOME_BROWSE_ID = "FEmusic_home"
        // Anonymous (no sign-in) requests only ever get a sparse Home feed - these are
        // YouTube Music's own real, standalone browse pages for everything else the Home tab
        // itself links out to but doesn't inline, verified as actually reachable anonymously.
        const val NEW_RELEASES_BROWSE_ID = "FEmusic_new_releases_albums"
        // Grid of real navigation chips (Genres/Moods), not playlists themselves - each one's
        // own browseId+params (FEmusic_moods_and_genres_category) opens a real page of playlists
        // for that genre, same as any other browse card (see BrowseParser.parseGenreChips).
        const val GENRES_BROWSE_ID = "FEmusic_moods_and_genres"
        private const val CLIENT_VERSION = "1.20261006.10.00"
        private const val IOS_CLIENT_VERSION = "20.10.4"
        private const val IOS_USER_AGENT = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"
        private const val WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
        private val VISITOR_DATA = Regex(""""(Cg[A-Za-z0-9_%-]{40,})"""")
        private const val FALLBACK_API_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
    }
}
