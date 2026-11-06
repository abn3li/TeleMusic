package com.abn3li.telemusic.data.animatedcover

import android.content.SharedPreferences
import android.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

internal fun interface CoverSource {
    /** The clip, or null when this source has none. Throws when it couldn't be asked. */
    fun find(query: CoverQuery): AnimatedCover?
}

private val http = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .build()

/** The body of a GET, null for 404 (not there), and a failure for any other refusal. */
private fun fetch(url: String, headers: Map<String, String> = emptyMap()): String? {
    val request = Request.Builder().url(url).header("User-Agent", BROWSER_USER_AGENT)
    headers.forEach { (name, value) -> request.header(name, value) }
    http.newCall(request.build()).execute().use { response ->
        if (response.code == 404) return null
        if (!response.isSuccessful) throw CoverLookupFailed("HTTP ${response.code}")
        return response.body?.string()
    }
}

private fun JSONObject.text(name: String): String = optString(name).takeUnless { it == "null" }.orEmpty()

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull(::optJSONObject)

/**
 * Apple Music's motion artwork, which hangs off the album. The catalog API takes the read-only
 * token its web player gives every visitor. That token isn't published and changes every few
 * weeks, so it's read from the player's script, once, and kept until it expires.
 */
internal class AppleMotionCovers(private val prefs: SharedPreferences) : CoverSource {
    private val storefront = Locale.getDefault().country.takeIf { it.length == 2 }?.lowercase(Locale.ROOT) ?: "us"
    private var noTokenUntil = 0L

    override fun find(query: CoverQuery): AnimatedCover? {
        val bearer = token() ?: throw CoverLookupFailed("no token")
        val term = listOfNotNull(query.artist, query.title, query.album).joinToString(" ")
        val url = "$CATALOG/$storefront/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", term)
            .addQueryParameter("types", "songs")
            .addQueryParameter("limit", "10")
            .addQueryParameter("extend", "editorialVideo")
            .addQueryParameter("include", "albums")
            .build().toString()
        val songs = JSONObject(catalog(url, bearer) ?: return null)
            .optJSONObject("results")?.optJSONObject("songs")?.optJSONArray("data")
            ?: return null

        val ranked = songs.objects()
            .mapNotNull { song -> score(song, query)?.let { it to song } }
            .sortedByDescending { it.first }
        for ((_, song) in ranked) {
            val attributes = song.optJSONObject("attributes") ?: continue
            attributes.optJSONObject("editorialVideo")?.let(::clips)?.let { return it }
            val albumId = albumId(song) ?: continue
            albumClip(albumId, bearer)?.let { return it }
        }
        return null
    }

    /** How well a result fits, or null when it's the wrong song or a playlist's artwork. */
    private fun score(song: JSONObject, query: CoverQuery): Int? {
        val attributes = song.optJSONObject("attributes") ?: return null
        val name = attributes.text("name")
        val albumName = attributes.text("albumName")
        if (isCompilation(name) || isCompilation(albumName)) return null
        if (!creditsMatch(query.artist, listOf(attributes.text("artistName")))) return null
        val wanted = query.title.forMatching()
        val got = name.forMatching()
        var score = when {
            wanted.isEmpty() || got.isEmpty() -> return null
            got == wanted -> 2
            got.contains(wanted) || wanted.contains(got) -> 1
            else -> return null
        }
        if (query.album != null && query.album.forMatching() == albumName.forMatching()) score += 2
        return score
    }

    private fun isCompilation(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return COMPILATION_WORDS.any { it in lower }
    }

    private fun albumId(song: JSONObject): String? {
        val related = song.optJSONObject("relationships")?.optJSONObject("albums")
            ?.optJSONArray("data")?.optJSONObject(0)?.text("id").orEmpty()
        if (related.isNotEmpty()) return related.takeUnless { it.startsWith("pl.") }
        return song.optJSONObject("attributes")?.text("url").orEmpty()
            .substringAfter("/album/", "").substringBefore("?").substringAfterLast("/")
            .takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
    }

    private fun albumClip(albumId: String, bearer: String): AnimatedCover? {
        val url = "$CATALOG/$storefront/albums/$albumId".toHttpUrl().newBuilder()
            .addQueryParameter("extend", "editorialVideo")
            .build().toString()
        val attributes = JSONObject(catalog(url, bearer) ?: return null)
            .optJSONArray("data")?.optJSONObject(0)?.optJSONObject("attributes") ?: return null
        if (isCompilation(attributes.text("name"))) return null
        return attributes.optJSONObject("editorialVideo")?.let(::clips)
    }

    /** The square clip has the cover's own framing, so it lands exactly over the still artwork;
     * the tall one is framed differently and would jump. It's kept in case the square won't play. */
    private fun clips(video: JSONObject): AnimatedCover? {
        val found = listOf("motionDetailSquare", "motionSquareVideo1x1", "motionDetailTall", "motionTallVideo3x4")
            .mapNotNull { video.optJSONObject(it)?.text("video")?.takeIf(String::isNotEmpty) }
            .distinct()
        return found.firstOrNull()?.let { AnimatedCover(it, found.getOrNull(1)) }
    }

    private fun catalog(url: String, bearer: String): String? = try {
        fetch(url, mapOf(
            "Authorization" to "Bearer $bearer",
            "Origin" to "https://music.apple.com",
            "Referer" to "https://music.apple.com/"
        ))
    } catch (e: CoverLookupFailed) {
        // A refused token is dropped, so the next song reads a fresh one.
        if (e.message == "HTTP 401") prefs.edit().remove(KEY_TOKEN).remove(KEY_TOKEN_EXPIRY).apply()
        throw e
    }

    private fun token(): String? {
        val now = System.currentTimeMillis()
        val kept = prefs.getString(KEY_TOKEN, null)
        if (kept != null && now < prefs.getLong(KEY_TOKEN_EXPIRY, 0L) - 60_000) return kept
        // When the page changes shape, retrying for every song would fix nothing.
        if (now < noTokenUntil) return null
        noTokenUntil = now + TOKEN_RETRY_MS

        val page = fetch("https://music.apple.com/us/browse") ?: return null
        val scripts = SCRIPT_PATH.findAll(page).map { it.value }.distinct()
        for (path in scripts) {
            val script = fetch("https://music.apple.com$path") ?: continue
            val tokens = JWT.findAll(script).map { it.value }.distinct()
                .mapNotNull { jwt -> expiryOf(jwt)?.takeIf { it > now }?.let { jwt to it } }
                .toList()
            val (jwt, expiry) = tokens.firstOrNull { isWebPlayerToken(it.first) } ?: tokens.firstOrNull() ?: continue
            prefs.edit().putString(KEY_TOKEN, jwt).putLong(KEY_TOKEN_EXPIRY, expiry).apply()
            noTokenUntil = 0L
            return jwt
        }
        return null
    }

    private fun jwtPart(jwt: String, index: Int): String? = runCatching {
        String(Base64.decode(jwt.split('.')[index], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
    }.getOrNull()

    private fun isWebPlayerToken(jwt: String): Boolean =
        jwtPart(jwt, 0).orEmpty().contains("WebPlayKid") || jwtPart(jwt, 1).orEmpty().contains("AMPWebPlay")

    private fun expiryOf(jwt: String): Long? =
        Regex("\"exp\"\\s*:\\s*(\\d+)").find(jwtPart(jwt, 1).orEmpty())?.groupValues?.get(1)?.toLongOrNull()?.times(1000)

    private companion object {
        const val CATALOG = "https://amp-api.music.apple.com/v1/catalog"
        const val KEY_TOKEN = "apple_token"
        const val KEY_TOKEN_EXPIRY = "apple_token_expiry"
        val TOKEN_RETRY_MS = TimeUnit.MINUTES.toMillis(30)
        val SCRIPT_PATH = Regex("""/assets/index(?:-legacy)?[~-][A-Za-z0-9_-]+\.js""")
        val JWT = Regex("""ey[A-Za-z0-9_-]+\.ey[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+""")
        val COMPILATION_WORDS = listOf("playlist", "essentials", "dj mix", "apple music", "today's hits")
    }
}

/**
 * Tidal's video covers: square, so they fill the cover without cropping. The search is the one
 * its public embed player uses, with the embed player's own read-only token and no account.
 */
internal class TidalVideoCovers : CoverSource {
    private val country = Locale.getDefault().country.takeIf { it.length == 2 }?.uppercase(Locale.ROOT) ?: "US"

    override fun find(query: CoverQuery): AnimatedCover? {
        val url = "https://api.tidal.com/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("query", listOfNotNull(query.album, query.artist, query.title).joinToString(" "))
            .addQueryParameter("limit", "10")
            .addQueryParameter("types", "TRACKS")
            .addQueryParameter("countryCode", country)
            .build().toString()
        val tracks = JSONObject(fetch(url, mapOf("X-Tidal-Token" to EMBED_TOKEN)) ?: return null)
            .optJSONObject("tracks")?.optJSONArray("items") ?: return null
        val wanted = query.title.forMatching()
        for (track in tracks.objects()) {
            if (track.text("title").forMatching() != wanted) continue
            val artists = track.optJSONArray("artists")?.objects()?.map { it.text("name") }.orEmpty()
            if (!creditsMatch(query.artist, artists)) continue
            val id = track.optJSONObject("album")?.text("videoCover").orEmpty()
            val path = id.split('-').takeIf { it.size == 5 } ?: continue
            return AnimatedCover("https://resources.tidal.com/videos/${path.joinToString("/")}/1280x1280.mp4")
        }
        return null
    }

    private companion object {
        const val EMBED_TOKEN = "vNVdglQOjFJJGG2U"
    }
}

/**
 * A hand-made list of song to clip, one JSON file for the whole list, so it's fetched once and
 * then searched here. Small, but the only source that reaches older songs.
 */
internal class CommunityCovers : CoverSource {
    private class Entry(val song: String, val artist: String, val album: String, val url: String)

    private var entries: List<Entry> = emptyList()
    private var fetchedAt = 0L

    override fun find(query: CoverQuery): AnimatedCover? {
        val title = query.title.forMatching()
        val artist = query.artist.forMatching()
        val album = query.album?.forMatching()
        if (title.isEmpty() || artist.isEmpty()) return null
        return list().firstOrNull { entry ->
            entry.song.isNotEmpty() && (title.contains(entry.song) || entry.song.contains(title)) &&
                entry.artist.isNotEmpty() && (artist.contains(entry.artist) || entry.artist.contains(artist)) &&
                (entry.album.isEmpty() || album.isNullOrEmpty() || entry.album == album)
        }?.let { AnimatedCover(it.url) }
    }

    private fun list(): List<Entry> {
        val now = System.currentTimeMillis()
        if (entries.isNotEmpty() && now - fetchedAt < LIST_KEPT_MS) return entries
        val items = JSONObject(fetch(LIST_URL) ?: return entries).optJSONArray("items") ?: return entries
        entries = items.objects().mapNotNull { item ->
            val url = item.text("url").takeIf { it.startsWith("https://") } ?: return@mapNotNull null
            Entry(item.text("song").forMatching(), item.text("artist").forMatching(), item.text("album").forMatching(), url)
        }
        fetchedAt = now
        return entries
    }

    private companion object {
        const val LIST_URL = "https://vivimusicanvas.mkmdevilmi.workers.dev/canvas.json"
        val LIST_KEPT_MS = TimeUnit.MINUTES.toMillis(30)
    }
}
