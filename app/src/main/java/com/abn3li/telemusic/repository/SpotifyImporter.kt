package com.abn3li.telemusic.repository

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.abn3li.telemusic.data.browse.BrowseParser
import com.abn3li.telemusic.data.browse.BrowseTrack
import com.abn3li.telemusic.data.browse.InnertubeBrowseClient
import com.abn3li.telemusic.data.browse.SAVED_ARTWORK_SIZE
import com.abn3li.telemusic.data.browse.googleArtworkAtSize
import com.abn3li.telemusic.data.local.SpotifyDao
import com.abn3li.telemusic.data.local.SpotifyLinkEntity
import com.abn3li.telemusic.data.local.SpotifyTrackMapEntity
import com.abn3li.telemusic.data.spotify.SpotifyAccount
import com.abn3li.telemusic.data.spotify.SpotifyPlaylist
import com.abn3li.telemusic.data.spotify.SpotifyTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Where a Spotify import is at, for the Import Playlist dialog and Home. */
sealed interface SpotifyImportState {
    data object Idle : SpotifyImportState
    data class Running(val name: String, val done: Int, val total: Int, val key: String = "") : SpotifyImportState
    data class Finished(
        val name: String,
        val matched: Int,
        val total: Int,
        val playlistId: Long = 0L,
        val added: Int = 0,
        val updated: Boolean = false
    ) : SpotifyImportState {
        /** One line for a dialog or a toast. */
        val summary: String
            get() = if (updated) {
                if (added == 0) "\"$name\" is up to date." else "Added $added new ${if (added == 1) "song" else "songs"} to \"$name\"."
            } else {
                "Added $matched of $total songs to \"$name\"." + if (matched < total) " The rest couldn't be found on YouTube Music." else ""
            }
    }
    data class Failed(val message: String) : SpotifyImportState
}

/**
 * Imports Spotify songs as a library playlist of YouTube Music songs, the same lightweight
 * streamable rows a YouTube import creates - so they play on demand and download (one by one or
 * with Download All) through the normal yt-dlp path. The songs come from:
 * - a public playlist/album link (Spotify's embed page - no login, first 100 songs), or
 * - your signed-in account ([SpotifyAccount]) - Liked Songs and your playlists, no limit.
 *
 * Each track is matched with one YouTube Music "Songs" search (title plus a duration within a
 * few seconds); only a miss asks Songlink/Odesli (rate-limited, so a 429 turns it off for the
 * rest). A match is remembered (spotify_track_map), and the source is linked to its playlist
 * (spotify_links): importing the same source again - "Update from Spotify" - only adds songs
 * that aren't in the playlist yet and never searches for a song it already found.
 *
 * Runs once, sequentially, in the app-wide scope so it survives leaving the screen, and stops
 * when done - no polling, no background loop.
 */
class SpotifyImporter(
    private val musicRepository: MusicRepository,
    private val scope: CoroutineScope,
    private val account: SpotifyAccount,
    private val dao: SpotifyDao,
    context: Context,
    private val innertube: InnertubeBrowseClient = InnertubeBrowseClient()
) {
    private val appContext = context.applicationContext
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow<SpotifyImportState>(SpotifyImportState.Idle)
    val state: StateFlow<SpotifyImportState> = _state
    private var job: Job? = null

    // A playlist whose import finished with "and download all": picked up once by the app's
    // navigation host, which owns the download queue (see NavGraph), then cleared.
    private val _pendingDownload = MutableStateFlow<Long?>(null)
    val pendingDownload: StateFlow<Long?> = _pendingDownload
    fun downloadStarted() { _pendingDownload.value = null }

    val isRunning: Boolean get() = job?.isActive == true

    fun isSpotifyLink(text: String): Boolean = parseLink(text) != null

    /** A pasted public playlist/album link (the Import Playlist dialog). */
    fun start(link: String) {
        val target = parseLink(link) ?: run {
            _state.value = SpotifyImportState.Failed("That isn't a Spotify playlist or album link.")
            return
        }
        launchImport("${target.first}:${target.second}", "Spotify", announce = false, downloadAll = false) {
            fetchEmbed(target.first, target.second)
                ?: throw IllegalStateException("Couldn't read that Spotify link. Make sure the playlist is public.")
        }
    }

    /** Your Liked Songs, from the signed-in account. */
    fun importLiked(downloadAll: Boolean) =
        launchImport(LIKED_KEY, LIKED_NAME, announce = true, downloadAll = downloadAll) { LIKED_NAME to account.likedTracks() }

    /** One of your playlists, from the signed-in account. */
    fun importPlaylist(playlist: SpotifyPlaylist, downloadAll: Boolean) =
        launchImport("playlist:${playlist.id}", playlist.name, announce = true, downloadAll = downloadAll) {
            playlist.name to account.playlistTracks(playlist.id)
        }

    /** "Update from Spotify" on an imported playlist: adds only what's new. */
    fun update(link: SpotifyLinkEntity) {
        val key = link.sourceKey
        val type = key.substringBefore(':')
        val id = key.substringAfter(':', "")
        when {
            key == LIKED_KEY -> {
                if (!account.isConnected) return toast("Connect Spotify in Settings to update Liked Songs")
                launchImport(key, link.name, announce = true, downloadAll = false) { link.name to account.likedTracks() }
            }
            type == "playlist" && account.isConnected ->
                launchImport(key, link.name, announce = true, downloadAll = false) { link.name to account.playlistTracks(id) }
            else -> launchImport(key, link.name, announce = true, downloadAll = false) {
                fetchEmbed(type, id) ?: throw IllegalStateException("Couldn't read this playlist from Spotify.")
            }
        }
    }

    /** Clears a finished/failed result once the dialog has shown it. */
    fun acknowledge() {
        if (_state.value !is SpotifyImportState.Running) _state.value = SpotifyImportState.Idle
    }

    private fun launchImport(
        key: String,
        displayName: String,
        announce: Boolean,
        downloadAll: Boolean,
        fetch: suspend () -> Pair<String, List<SpotifyTrack>>
    ) {
        if (job?.isActive == true) {
            toast("A Spotify import is already running")
            return
        }
        _state.value = SpotifyImportState.Running(displayName, 0, 0, key)
        job = scope.launch(Dispatchers.IO) {
            try {
                val (name, tracks) = fetch()
                if (tracks.isEmpty()) {
                    fail("\"$name\" has no songs to import.", announce)
                    return@launch
                }
                val total = tracks.size
                _state.value = SpotifyImportState.Running(name, 0, total, key)

                val existing = dao.getLink(key)?.playlistId?.takeIf { musicRepository.playlistExists(it) }
                val playlistId = existing ?: musicRepository.createPlaylist(name)
                dao.upsertLink(SpotifyLinkEntity(key, playlistId, name, System.currentTimeMillis()))
                val inPlaylist = musicRepository.songIdsInPlaylist(playlistId).toHashSet()
                // Playlists list newest first: the first song gets the latest time, so the
                // playlist reads in Spotify's order (and new songs of an update go on top).
                val base = System.currentTimeMillis()

                var matched = 0
                var added = 0
                var odesliAllowed = true
                // Matches are written in batches: every write to the library makes open screens
                // reload it, so one transaction per batch instead of two writes per song.
                val pending = mutableListOf<PendingTrack>()
                suspend fun flush() {
                    if (pending.isEmpty()) return
                    musicRepository.inTransaction {
                        for (p in pending) {
                            val songId = p.knownSongId ?: musicRepository.importPlaylistTrackAsStreamable(p.found!!).telegramMessageId.also { id ->
                                if (p.spotifyId.isNotBlank()) dao.saveMatch(SpotifyTrackMapEntity(p.spotifyId, id))
                            }
                            if (inPlaylist.add(songId)) {
                                musicRepository.addSongToPlaylistAt(playlistId, songId, base - p.index)
                                added++
                            }
                        }
                    }
                    pending.clear()
                }
                tracks.forEachIndexed { index, track ->
                    val knownId = track.id.takeIf { it.isNotBlank() }
                        ?.let { dao.songIdFor(it) }
                        ?.takeIf { musicRepository.getSongById(it) != null }
                    if (knownId != null) {
                        matched++
                        pending += PendingTrack(index, track.id, knownId, null)
                    } else {
                        var found = runCatching { matchOnYouTubeMusic(track) }.getOrNull()
                        if (found == null && odesliAllowed) {
                            val result = runCatching { matchWithOdesli(track) }.getOrNull()
                            if (result == OdesliResult.RateLimited) odesliAllowed = false
                            found = (result as? OdesliResult.Found)?.track
                        }
                        if (found != null) {
                            matched++
                            pending += PendingTrack(index, track.id, null, found)
                        }
                    }
                    if (pending.size >= WRITE_BATCH) flush()
                    _state.value = SpotifyImportState.Running(name, index + 1, total, key)
                }
                flush()
                val finished = SpotifyImportState.Finished(name, matched, total, playlistId, added, updated = existing != null)
                _state.value = finished
                if (downloadAll) _pendingDownload.value = playlistId
                if (announce) toast(finished.summary)
                // Covers for the list rows fill in after "done" - they show from the full
                // artwork link meanwhile, so nothing waits on this.
                musicRepository.backfillThumbnails()
            } catch (e: Exception) {
                Log.w(TAG, "Spotify import failed", e)
                fail(e.message ?: "Import failed: network error", announce)
            }
        }
    }

    private suspend fun fail(message: String, announce: Boolean) {
        _state.value = SpotifyImportState.Failed(message)
        if (announce) toast(message)
    }

    private fun toast(message: String) {
        scope.launch(Dispatchers.Main) { Toast.makeText(appContext, message, Toast.LENGTH_LONG).show() }
    }

    // ---- Public links (embed page) ----

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

    /** Name and songs from Spotify's public embed page (at most the first 100 songs). */
    private suspend fun fetchEmbed(type: String, id: String): Pair<String, List<SpotifyTrack>>? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://open.spotify.com/embed/$type/$id")
            .header("User-Agent", USER_AGENT)
            .build()
        val html = http.newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null } ?: return@withContext null
        val json = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: return@withContext null
        val entity = JSONObject(json).optJSONObject("props")?.optJSONObject("pageProps")?.optJSONObject("state")
            ?.optJSONObject("data")?.optJSONObject("entity") ?: return@withContext null
        val list = entity.optJSONArray("trackList") ?: return@withContext null
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
        name to tracks
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
            ?.let { it.copy(title = track.title, thumbnailUrl = googleArtworkAtSize(it.thumbnailUrl, SAVED_ARTWORK_SIZE)) }
    }

    /** A matched track waiting for the next batched write: an already-known song, or a new match. */
    private class PendingTrack(val index: Int, val spotifyId: String, val knownSongId: Long?, val found: BrowseTrack?)

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

    companion object {
        private const val TAG = "SpotifyImporter"
        const val LIKED_KEY = "liked"
        private const val WRITE_BATCH = 25
        const val LIKED_NAME = "Spotify Liked Songs"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    }
}
