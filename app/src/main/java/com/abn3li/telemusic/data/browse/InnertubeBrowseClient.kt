package com.abn3li.telemusic.data.browse

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Browse-only client for YouTube Music's public Innertube API (music.youtube.com/youtubei/v1) -
 * fetches the home feed and lets a browse card's own browseId be opened further (a playlist's
 * track list, an artist's page, a chart). Deliberately does NOT touch the /player endpoint or
 * do any signature/cipher deciphering. Playback and downloads use the downloader's extraction
 * path instead of maintaining a second implementation. A real download still
 * only ever happens through yt-dlp (data/download) once the user picks a track here - this
 * client only ever returns metadata (titles, thumbnails, ids), never a stream URL.
 *
 * [FALLBACK_API_KEY] is WEB_REMIX's own public Innertube key - shipped to every anonymous
 * visitor of music.youtube.com, not a secret.
 */
class InnertubeBrowseClient {
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

    private fun post(endpoint: String, extra: JSONObject): JSONObject {
        val visitor = visitorId()
        val body = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", CLIENT_VERSION)
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
            .addHeader("X-YouTube-Client-Version", CLIENT_VERSION)
            .addHeader("User-Agent", WEB_USER_AGENT)
            .addHeader("Origin", "https://music.youtube.com")
            .apply { if (visitor != null) addHeader("X-Goog-Visitor-Id", visitor) }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            check(response.isSuccessful) { "Innertube $endpoint failed: HTTP ${response.code}" }
            return JSONObject(responseBody).also { json ->
                if (visitorData == null) {
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
        private const val CLIENT_VERSION = "1.20250101.01.00"
        private const val WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
        private val VISITOR_DATA = Regex(""""(Cg[A-Za-z0-9_%-]{40,})"""")
        private const val FALLBACK_API_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
    }
}
