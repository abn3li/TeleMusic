package com.example.tgmusic.repository

import android.net.Uri
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.example.tgmusic.data.remote.LrcLibResponse
import com.example.tgmusic.data.remote.NetworkModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import kotlin.math.abs
import kotlin.math.min

data class LyricsResult(val plain: String?, val synced: String?)

/**
 * Lyrics are fetched ONLY when the user explicitly asks for them (the Lyrics button in Now
 * Playing) - never during library sync/metadata enrichment. See
 * MusicRepository.fetchLyricsForSong(), the sole caller of fetchLyrics() below.
 *
 * Providers are tried in this order, each one free and requiring no API key - and, unlike
 * what was here before, each one actually verified reachable (via curl) before being wired in:
 * 1. LRCLIB (lrclib.net) - open community database, line-synced (LRC) capable.
 * 2. KuGou (kugou.com) - a Chinese catalogue that nonetheless carries a large amount of
 *    English/Western music LRCLIB doesn't have. Also line-synced, so it's tried before any
 *    plain-only source - a synced match from here beats a plain one from anywhere else.
 * 3. lyrics.ovh - plain lyrics only, a real, long-standing free fallback for whatever the two
 *    synced sources above don't have. (Two earlier candidates here, Lyrist and a "LyricsPlus"
 *    wrapper, were verified dead via curl before ever landing in this file: one is blocked by
 *    a Vercel bot checkpoint that returns an HTML page instead of JSON, the other's deployment
 *    no longer exists at all - so don't reach for either as a "just add it back" fix later
 *    without re-verifying they're alive.)
 * 4. Google search scrape - last resort; fragile (HTML structure can change without notice)
 *    and plain-text only, kept only because it occasionally finds something the others miss.
 *
 * Not included: Musixmatch. Getting synced lyrics out of it means reimplementing the HMAC
 * signing scheme baked into their web player's own JS to call their un-metered internal API
 * for free instead of their paid public one - a deliberate authentication bypass, not just an
 * undocumented-but-open endpoint like the others here. Left out on purpose; flag if you want
 * it added anyway with that trade-off understood.
 */
class LyricsRepository {

    private val gson = Gson()

    /**
     * All three real providers are STARTED at the same time and only then awaited in priority
     * order - not run one after another. Sequentially, the wait is the SUM of every provider's
     * round trip (LRCLIB, then KuGou's three chained calls, then lyrics.ovh); in parallel it's
     * closer to whichever single one takes longest, since they're all already in flight by the
     * time the first is awaited. That's what made pressing the Lyrics button feel laggy after
     * KuGou was added - three sequential network round trips, not because anything is fetched
     * outside of the button press itself (that boundary hasn't changed: this whole function is
     * still only ever called from fetchLyricsForSong(), on-demand).
     */
    suspend fun fetchLyrics(title: String, artist: String, durationSeconds: Int?): LyricsResult? = coroutineScope {
        val (cleanTitle, cleanArtist) = sanitizeTitleAndArtist(title, artist)
        Log.d("LyricsRepository", "Searching lyrics for '$cleanTitle' by '$cleanArtist'")

        val lrcLibDeferred = async { fetchLrcLibLyrics(cleanTitle, cleanArtist, durationSeconds) }
        val kuGouDeferred = async { fetchKuGouLyrics(cleanTitle, cleanArtist, durationSeconds) }
        val ovhDeferred = async { fetchLyricsOvhLyrics(cleanTitle, cleanArtist) }

        try {
            val lrcLibResult = lrcLibDeferred.await()
            if (lrcLibResult?.synced != null) {
                Log.d("LyricsRepository", "[LRCLIB] Synced match")
                return@coroutineScope lrcLibResult
            }

            val kuGouResult = kuGouDeferred.await()
            if (kuGouResult != null) {
                Log.d("LyricsRepository", "[KuGou] Synced match")
                return@coroutineScope kuGouResult
            }

            if (lrcLibResult != null) {
                Log.d("LyricsRepository", "[LRCLIB] Plain-only match (no synced version found anywhere)")
                return@coroutineScope lrcLibResult
            }

            val ovhResult = ovhDeferred.await()
            if (ovhResult != null) {
                Log.d("LyricsRepository", "[lyrics.ovh] Succeeded")
                return@coroutineScope ovhResult
            }

            // Not raced with the rest: a plain-text scrape that's rarely reached (only when
            // all three real providers above have nothing), so there's no latency to hide by
            // starting it early, only bandwidth to waste on every single fetch if it were.
            try {
                val googleLyrics = fetchGoogleLyrics(cleanTitle, cleanArtist)
                if (!googleLyrics.isNullOrBlank()) {
                    Log.d("LyricsRepository", "[Google Search] Succeeded")
                    return@coroutineScope LyricsResult(googleLyrics, null)
                }
            } catch (e: Exception) {
                Log.w("LyricsRepository", "[Google Search] Failed: ${e.message}")
            }

            null
        } finally {
            // Whichever of these lost the race is no longer worth waiting on, and
            // coroutineScope will not return while they're still running.
            lrcLibDeferred.cancel()
            kuGouDeferred.cancel()
            ovhDeferred.cancel()
        }
    }

    /** LRCLIB's exact-match endpoint needs a precise title/artist/duration match and 404s
     * otherwise (routine, not an error worth logging loudly) - falls back to its fuzzier
     * search endpoint, picking the first result that actually has lyrics.
     *
     * Even when the exact-match endpoint succeeds, it returns ONE specific database entry,
     * which may only have plain lyrics while a different entry for the same song (findable via
     * search) has a synced version. Synced is always preferred when it exists anywhere in
     * LRCLIB, never just settled for plain because the first lookup happened to find it first. */
    private suspend fun fetchLrcLibLyrics(title: String, artist: String, durationSeconds: Int?): LyricsResult? =
        withContext(Dispatchers.IO) {
            val exactMatch = try {
                NetworkModule.lrcLibApi.getLyrics(
                    trackName = title,
                    artistName = artist,
                    durationSeconds = durationSeconds
                ).toLyricsResultOrNull()
            } catch (e: Exception) {
                null
            }

            if (exactMatch?.synced != null) return@withContext exactMatch

            val searchMatch = try {
                val results = NetworkModule.lrcLibApi.searchLyrics(trackName = title, artistName = artist)
                val bestMatch = results.firstOrNull { !it.syncedLyrics.isNullOrBlank() }
                    ?: results.firstOrNull { !it.plainLyrics.isNullOrBlank() }
                bestMatch?.toLyricsResultOrNull()
            } catch (searchException: Exception) {
                Log.w("LyricsRepository", "[LRCLIB] Search failed: ${searchException.message}")
                null
            }

            // Prefer whichever of the two actually has a synced version; otherwise take
            // whatever plain result is available.
            searchMatch?.takeIf { it.synced != null } ?: exactMatch ?: searchMatch
        }

    private fun LrcLibResponse.toLyricsResultOrNull(): LyricsResult? {
        val plain = plainLyrics?.takeIf { it.isNotBlank() }
        val synced = syncedLyrics?.takeIf { it.isNotBlank() }
        return if (plain != null || synced != null) LyricsResult(plain, synced) else null
    }

    /** Line-synced lyrics from KuGou's public mobile/lyrics endpoints. Three unauthenticated
     * calls, chained: search the song to get its audio fingerprint ("hash"), search lyrics
     * candidates against that hash, then download the winning candidate's LRC file (delivered
     * base64-encoded). A keyword-only lyrics search (skipping the hash) is the fallback for
     * whatever the fingerprint search misses. */
    private suspend fun fetchKuGouLyrics(title: String, artist: String, durationSeconds: Int?): LyricsResult? =
        withContext(Dispatchers.IO) {
            try {
                val keyword = "${stripParenthetical(title)} - ${stripParenthetical(artist)}"
                val seconds = durationSeconds ?: -1

                val hashes = kuGouSearchSongHashes(keyword, seconds)
                val candidate = hashes.firstNotNullOfOrNull { hash -> kuGouSearchLyricsCandidates(hash = hash)?.firstOrNull() }
                    ?: kuGouSearchLyricsCandidates(keyword = keyword, seconds = seconds)?.firstOrNull()
                    ?: return@withContext null

                val lrc = kuGouDownload(candidate.id, candidate.accesskey) ?: return@withContext null
                val stripped = stripKuGouCredits(lrc)
                stripped.takeIf { it.isNotBlank() }?.let { LyricsResult(plain = null, synced = it) }
            } catch (e: Exception) {
                Log.w("LyricsRepository", "[KuGou] Failed: ${e.message}")
                null
            }
        }

    /** Song hashes worth trying, restricted to cuts within 8 seconds of the track's own
     * duration - otherwise the first result for a common title is as likely to be a cover or a
     * remix as the right recording - ordered closest match first. */
    private fun kuGouSearchSongHashes(keyword: String, seconds: Int): List<String> {
        val url = "https://mobileservice.kugou.com/api/v3/search/song".toHttpUrl().newBuilder()
            .addQueryParameter("version", "9108")
            .addQueryParameter("plat", "0")
            .addQueryParameter("pagesize", "8")
            .addQueryParameter("showtype", "0")
            .addQueryParameter("keyword", keyword)
            .build()
        val body = kuGouGet(url.toString()) ?: return emptyList()
        val response = runCatching { gson.fromJson(body, KuGouSearchSongResponse::class.java) }.getOrNull()
        return response?.data?.info.orEmpty()
            .filter { seconds <= 0 || abs(it.duration - seconds) <= 8 }
            .sortedBy { abs(it.duration - seconds) }
            .map { it.hash }
    }

    private fun kuGouSearchLyricsCandidates(hash: String? = null, keyword: String? = null, seconds: Int = -1): List<KuGouCandidate>? {
        val builder = "https://lyrics.kugou.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("ver", "1")
            .addQueryParameter("man", "yes")
            .addQueryParameter("client", "pc")
        when {
            hash != null -> builder.addQueryParameter("hash", hash)
            keyword != null -> {
                builder.addQueryParameter("keyword", keyword)
                if (seconds > 0) builder.addQueryParameter("duration", (seconds * 1000).toString())
            }
            else -> return null
        }
        val body = kuGouGet(builder.build().toString()) ?: return null
        val response = runCatching { gson.fromJson(body, KuGouSearchLyricsResponse::class.java) }.getOrNull()
        return response?.candidates
    }

    private fun kuGouDownload(id: String, accessKey: String): String? {
        val url = "https://lyrics.kugou.com/download".toHttpUrl().newBuilder()
            .addQueryParameter("fmt", "lrc")
            .addQueryParameter("charset", "utf8")
            .addQueryParameter("client", "pc")
            .addQueryParameter("ver", "1")
            .addQueryParameter("id", id)
            .addQueryParameter("accesskey", accessKey)
            .build()
        val body = kuGouGet(url.toString()) ?: return null
        val response = runCatching { gson.fromJson(body, KuGouDownloadResponse::class.java) }.getOrNull() ?: return null
        return runCatching {
            String(Base64.decode(response.content, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun kuGouGet(url: String): String? = try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
            .build()
        NetworkModule.client.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }
    } catch (e: Exception) {
        null
    }

    private fun stripParenthetical(s: String): String =
        s.replace(Regex("""[(（].*?[)）]"""), "").trim().ifBlank { s }

    /** KuGou's lyric files open and close with uncredited lines - songwriter, composer,
     * arranger - that carry a real timestamp and would otherwise be sung as the first and
     * last lines of the song. Cut from either end, up to the first/last line matching
     * "label: value", and only within the first and last 30 lines so a legit lyric that
     * happens to contain a colon deep in the song is left alone. */
    private fun stripKuGouCredits(lrc: String): String {
        val stamped = Regex("""\[\d{2}:\d{2}\.\d{2,3}].*""")
        val credit = Regex(""".+][^\[]+[:：].+""")
        val lines = lrc.lineSequence().filter { stamped.matches(it) }.toList()
        if (lines.isEmpty()) return ""
        val headLimit = min(30, lines.lastIndex)
        val headCut = (headLimit downTo 0).firstOrNull { credit.matches(lines[it]) }?.let { it + 1 } ?: 0
        val body = lines.drop(headCut)
        val tailLimit = min(30, body.lastIndex)
        val tailCut = (0..tailLimit).firstOrNull { credit.matches(body[body.lastIndex - it]) }?.let { it + 1 } ?: 0
        return body.dropLast(tailCut).joinToString("\n")
    }

    private data class KuGouSearchSongResponse(val data: KuGouSearchSongData?)
    private data class KuGouSearchSongData(val info: List<KuGouSongInfo> = emptyList())
    private data class KuGouSongInfo(val hash: String, val duration: Int = -1)
    private data class KuGouSearchLyricsResponse(val candidates: List<KuGouCandidate> = emptyList())
    private data class KuGouCandidate(val id: String, val accesskey: String)
    private data class KuGouDownloadResponse(val content: String = "")

    private suspend fun fetchLyricsOvhLyrics(title: String, artist: String): LyricsResult? =
        withContext(Dispatchers.IO) {
            try {
                val response = NetworkModule.lyricsOvhApi.getLyrics(
                    artist = Uri.encode(artist),
                    title = Uri.encode(title)
                )
                response.lyrics?.takeIf { it.isNotBlank() }?.let { LyricsResult(it, null) }
            } catch (e: Exception) {
                Log.w("LyricsRepository", "[lyrics.ovh] Failed: ${e.message}")
                null
            }
        }

    private suspend fun fetchGoogleLyrics(title: String, artist: String): String? = withContext(Dispatchers.IO) {
        val query = "$artist $title lyrics".trim()
        val url = "https://www.google.com/search?q=${Uri.encode(query)}"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
            .header("Accept-Language", "en-US,en;q=0.9,ar;q=0.8")
            .build()

        try {
            val response = NetworkModule.client.newCall(request).execute()
            val html = response.body?.string() ?: return@withContext null

            // Regex matches Google's lyrics card containers
            val regexDiv = Regex("""(?s)<div[^>]*class="[^"]*(?:BNeawe|hwc|XzT28c)[^"]*"[^>]*>(.*?)</div>""")
            val matches = regexDiv.findAll(html).map { it.groupValues[1] }.toList()

            for (match in matches) {
                val plainText = match.replace(Regex("""<[^>]*>"""), "\n")
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .replace("&#39;", "'")
                    .lines()
                    .map { it.trim() }
                    .filter { it.isNotBlank() && !it.contains("Google") && !it.contains("Search") }
                    .joinToString("\n")

                if (plainText.length > 60 && plainText.lines().size > 3) {
                    return@withContext plainText
                }
            }
            null
        } catch (e: Exception) {
            Log.w("LyricsRepository", "Google Search lyrics failed: ${e.message}")
            null
        }
    }

    fun sanitizeTitleAndArtist(title: String, artist: String): Pair<String, String> {
        var cleanTitle = title.trim()
        var cleanArtist = artist.trim()

        // 1. Strip file extensions (.mp3, .m4a, .flac, .ogg, .wav, .aac, .opus, .webm)
        cleanTitle = cleanTitle.replace(Regex("""(?i)\.(mp3|m4a|flac|ogg|wav|aac|opus|webm)$"""), "").trim()

        // 2. Replace underscores with spaces
        cleanTitle = cleanTitle.replace("_", " ").trim()
        cleanArtist = cleanArtist.replace("_", " ").trim()

        // 3. Strip track numbers at start (e.g. "01 ", "01. ", "01 - ")
        cleanTitle = cleanTitle.replace(Regex("""^\d{1,3}[\s.\-_]+"""), "").trim()

        // 4. If title is "Artist - Song Title", split them cleanly
        if (cleanTitle.contains(" - ")) {
            val parts = cleanTitle.split(" - ", limit = 2)
            if (cleanArtist == "Unknown artist" || cleanArtist.isBlank()) {
                cleanArtist = parts[0].trim()
            }
            cleanTitle = parts[1].trim()
        }

        // 5. Remove common unwanted suffixes in parentheses or brackets
        val regexUnwanted = Regex("""(?i)\s*[(\[](official|lyric|lyrics|video|audio|remastered|hd|4k|feat\.|ft\.).*?[)\]]""")
        cleanTitle = cleanTitle.replace(regexUnwanted, "").trim()
        cleanArtist = cleanArtist.replace(regexUnwanted, "").trim()

        // 6. Remove quotes & brackets
        cleanTitle = cleanTitle.replace("'", "").replace("\"", "").replace("[", "").replace("]", "").trim()
        cleanArtist = cleanArtist.replace("'", "").replace("\"", "").trim()

        return cleanTitle.ifBlank { title } to cleanArtist.ifBlank { artist }
    }
}
