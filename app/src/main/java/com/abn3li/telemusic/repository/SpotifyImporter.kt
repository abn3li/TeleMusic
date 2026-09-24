package com.abn3li.telemusic.repository

import android.util.Log
import com.abn3li.telemusic.data.browse.BrowseParser
import com.abn3li.telemusic.data.browse.BrowseTrack
import com.abn3li.telemusic.data.browse.InnertubeBrowseClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Where a Spotify import is at, for the Import Playlist dialog. */
sealed interface SpotifyImportState {
    data object Idle : SpotifyImportState
    data class Running(val name: String, val done: Int, val total: Int) : SpotifyImportState
    data class Finished(val name: String, val matched: Int, val total: Int) : SpotifyImportState
    data class Failed(val message: String) : SpotifyImportState
}

/**
 * Imports a Spotify playlist or album as a library playlist of YouTube Music songs, the same
 * lightweight streamable rows a YouTube import creates - so they play on demand and download
 * (one by one or with Download All) through the normal yt-dlp path.
 *
 * 1. The track list (title, artists, exact duration) comes from Spotify's public embed page -
 *    no login or developer keys.
 * 2. Each track is matched with one YouTube Music "Songs" search, picking the result whose title
 *    matches and whose duration is within a few seconds.
 * 3. Only a track that finds no good match asks Songlink/Odesli (rate-limited to ~10/min without
 *    a key, so it's the fallback, never the main path; a 429 turns it off for the rest).
 *
 * Runs once, sequentially, in the app-wide scope so it survives leaving the screen, and stops
 * when done - no polling, no background loop.
 */
class SpotifyImporter(
    private val musicRepository: MusicRepository,
    private val scope: CoroutineScope,
    private val innertube: InnertubeBrowseClient = InnertubeBrowseClient()
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow<SpotifyImportState>(SpotifyImportState.Idle)
    val state: StateFlow<SpotifyImportState> = _state
    private var job: Job? = null

    fun isSpotifyLink(text: String): Boolean = parseLink(text) != null

    fun start(link: String) {
        if (job?.isActive == true) return
        val target = parseLink(link) ?: run {
            _state.value = SpotifyImportState.Failed("That isn't a Spotify playlist or album link.")
            return
        }
        job = scope.launch(Dispatchers.IO) {
            try {
                val collection = fetchCollection(target.first, target.second)
                if (collection == null || collection.tracks.isEmpty()) {
                    _state.value = SpotifyImportState.Failed("Couldn't read that Spotify link. Make sure the playlist is public.")
                    return@launch
                }
                val total = collection.tracks.size
                _state.value = SpotifyImportState.Running(collection.name, 0, total)
                val playlistId = musicRepository.createPlaylist(collection.name)
                var matched = 0
                var odesliAllowed = true
                collection.tracks.forEachIndexed { index, track ->
                    var found = runCatching { matchOnYouTubeMusic(track) }.getOrNull()
                    if (found == null && odesliAllowed) {
                        val result = runCatching { matchWithOdesli(track) }.getOrNull()
                        if (result == OdesliResult.RateLimited) odesliAllowed = false
                        found = (result as? OdesliResult.Found)?.track
                    }
                    if (found != null) {
                        val song = musicRepository.importPlaylistTrackAsStreamable(found)
                        musicRepository.addSongToPlaylist(playlistId, song)
                        matched++
                    }
                    _state.update { SpotifyImportState.Running(collection.name, index + 1, total) }
                }
                musicRepository.backfillThumbnails()
                _state.value = SpotifyImportState.Finished(collection.name, matched, total)
            } catch (e: Exception) {
                Log.w(TAG, "Spotify import failed", e)
                _state.value = SpotifyImportState.Failed("Import failed: ${e.message ?: "network error"}")
            }
        }
    }

    /** Clears a finished/failed result once the dialog has shown it. */
    fun acknowledge() {
        if (_state.value !is SpotifyImportState.Running) _state.value = SpotifyImportState.Idle
    }

    // ---- Spotify ----

    private class SpotifyTrack(val id: String, val title: String, val artists: String, val durationMs: Long)
    private class SpotifyCollection(val name: String, val tracks: List<SpotifyTrack>)

    /** (type, id) from open.spotify.com/(intl-xx/)(playlist|album)/ID or spotify:playlist:ID. */
    private fun parseLink(text: String): Pair<String, String>? {
        val t = text.trim()
        Regex("""open\.spotify\.com/(?:intl-[a-zA-Z-]+/)?(playlist|album)/([A-Za-z0-9]+)""").find(t)?.let {
            return it.groupValues[1] to it.groupValues[2]
        }
        Regex("""spotify:(playlist|album):([A-Za-z0-9]+)""").find(t)?.let {
            return it.groupValues[1] to it.groupValues[2]
        }
        return null
    }

    private fun fetchCollection(type: String, id: String): SpotifyCollection? {
        val request = Request.Builder()
            .url("https://open.spotify.com/embed/$type/$id")
            .header("User-Agent", USER_AGENT)
            .build()
        val html = http.newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null } ?: return null
        val json = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: return null
        val entity = JSONObject(json).optJSONObject("props")?.optJSONObject("pageProps")?.optJSONObject("state")
            ?.optJSONObject("data")?.optJSONObject("entity") ?: return null
        val list = entity.optJSONArray("trackList") ?: return null
        val tracks = (0 until list.length()).mapNotNull { i ->
            val t = list.optJSONObject(i) ?: return@mapNotNull null
            val title = t.optString("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SpotifyTrack(
                id = t.optString("uri").substringAfterLast(':'),
                title = title,
                artists = t.optString("subtitle"),
                durationMs = t.optLong("duration")
            )
        }
        val name = entity.optString("name").ifBlank { entity.optString("title") }.ifBlank { "Spotify Playlist" }
        return SpotifyCollection(name, tracks)
    }

    // ---- Matching ----

    private fun matchOnYouTubeMusic(track: SpotifyTrack): BrowseTrack? {
        val firstArtist = track.artists.split(",").first().trim()
        val response = innertube.searchSongs("${track.title} $firstArtist")
        val candidates = BrowseParser.parseBrowseContent(response).tracks.take(6)
        val wantSeconds = track.durationMs / 1000
        val wantTitle = normalize(track.title)
        val wantArtist = normalize(firstArtist)
        // A title that normalizes to nothing (only brackets/symbols) can't be compared - leave it
        // to Songlink rather than "matching" whatever comes first.
        if (wantTitle.isBlank()) return null
        return candidates
            .map { c ->
                val candidateTitle = normalize(c.title)
                val titleOk = candidateTitle.isNotBlank() &&
                    (candidateTitle == wantTitle || candidateTitle.contains(wantTitle) || wantTitle.contains(candidateTitle))
                val artistOk = wantArtist.isNotBlank() && normalize(c.artist).contains(wantArtist)
                val diff = if (c.durationSeconds > 0 && wantSeconds > 0) abs(c.durationSeconds - wantSeconds) else 999L
                // Same artist, same length, nearly the same spelling: a transliteration
                // ("Zayak Ana" / "Zayek Ana") rather than a different song.
                val spellingOk = artistOk && diff <= 5 && candidateTitle.isNotBlank() && similarity(candidateTitle, wantTitle) >= 0.8
                Triple(c, (titleOk && (diff <= 7 || (artistOk && diff <= 15))) || spellingOk, diff)
            }
            .filter { it.second }
            .minByOrNull { it.third }
            ?.first
            ?.let { it.copy(title = track.title, thumbnailUrl = biggerArtwork(it.thumbnailUrl)) }
    }

    private sealed interface OdesliResult {
        data class Found(val track: BrowseTrack) : OdesliResult
        data object NotFound : OdesliResult
        data object RateLimited : OdesliResult
    }

    private fun matchWithOdesli(track: SpotifyTrack): OdesliResult {
        if (track.id.isBlank()) return OdesliResult.NotFound
        val spotifyUrl = URLEncoder.encode("https://open.spotify.com/track/${track.id}", "UTF-8")
        val request = Request.Builder()
            .url("https://api.song.link/v1-alpha.1/links?url=$spotifyUrl")
            .header("User-Agent", USER_AGENT)
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 429) return OdesliResult.RateLimited
            if (!response.isSuccessful) return OdesliResult.NotFound
            val json = JSONObject(response.body?.string().orEmpty())
            val ytUrl = json.optJSONObject("linksByPlatform")?.let {
                it.optJSONObject("youtubeMusic") ?: it.optJSONObject("youtube")
            }?.optString("url").orEmpty()
            val videoId = Regex("""[?&]v=([A-Za-z0-9_-]{11})""").find(ytUrl)?.groupValues?.get(1) ?: return OdesliResult.NotFound
            val cover = json.optJSONObject("entitiesByUniqueId")?.optJSONObject("SPOTIFY_SONG::${track.id}")?.optString("thumbnailUrl")
            return OdesliResult.Found(
                BrowseTrack(
                    videoId = videoId,
                    title = track.title,
                    artist = track.artists.split(",").first().trim().ifBlank { "Unknown artist" },
                    thumbnailUrl = cover?.takeIf { it.isNotBlank() },
                    durationSeconds = (track.durationMs / 1000).toInt()
                )
            )
        }
    }

    /** Lower-case, accents and "(feat. …)" / "- Remastered …" / punctuation stripped. */
    private fun normalize(text: String): String {
        val noAccents = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return noAccents.lowercase()
            .replace(Regex("""\s*[(\[].*?[)\]]"""), "")
            .replace(Regex("""\s+-\s+.*(remaster|version|edit|mix|live|mono|stereo).*$"""), "")
            .replace(Regex("""[^\p{L}\p{N} ]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    /** 1 - (edit distance / longer length): 1.0 identical, 0.8 = about one letter in five differs. */
    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val longer = maxOf(a.length, b.length)
        if (longer == 0) return 0.0
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return 1.0 - previous[b.length].toDouble() / longer
    }

    /** YouTube Music search art is 120 px; the same image is served at 544 px by changing the size. */
    private fun biggerArtwork(url: String?): String? = url?.replace(Regex("""=w\d+-h\d+"""), "=w544-h544")

    companion object {
        private const val TAG = "SpotifyImporter"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    }
}
