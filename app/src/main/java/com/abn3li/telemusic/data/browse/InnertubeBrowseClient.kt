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
 * do any signature/cipher deciphering - that's the part that would need NewPipeExtractor's
 * GPL-3.0 code (see data/download/YtDlpService's own doc on staying MIT). A real download still
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
    fun browseContinuation(continuation: String): JSONObject =
        post("browse", JSONObject().apply { put("continuation", continuation) })

    private fun post(endpoint: String, extra: JSONObject): JSONObject {
        val body = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", CLIENT_VERSION)
                    put("hl", "en")
                    put("gl", "US")
                })
            })
            extra.keys().forEach { key -> put(key, extra.get(key)) }
        }

        val request = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/$endpoint?key=$FALLBACK_API_KEY")
            .addHeader("Content-Type", "application/json")
            .addHeader("X-YouTube-Client-Name", "67")
            .addHeader("X-YouTube-Client-Version", CLIENT_VERSION)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            check(response.isSuccessful) { "Innertube $endpoint failed: HTTP ${response.code}" }
            return JSONObject(responseBody)
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
        private const val CLIENT_VERSION = "1.20240101.01.00"
        private const val FALLBACK_API_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
    }
}
