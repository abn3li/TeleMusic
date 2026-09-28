package com.abn3li.telemusic.repository

import okhttp3.HttpUrl.Companion.toHttpUrl
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.abn3li.telemusic.data.remote.LrcLibResponse
import com.abn3li.telemusic.data.remote.NetworkModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import retrofit2.HttpException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.min

data class LyricsResult(val plain: String?, val synced: String?, val provider: LyricsProvider? = null)

/** Where lyrics come from - the Lyrics source picker in Now Playing lists these. */
enum class LyricsProvider(val label: String, val detail: String) {
    BINI_LYRICS("BiniLyrics", "Word by word when available"),
    LRCLIB("LRCLIB", "Synced when available"),
    KUGOU("KuGou", "Synced"),
    LYRICS_OVH("lyrics.ovh", "Plain text"),
    GOOGLE("Google", "Plain text");

    companion object {
        /** The provider saved under [name] (LyricsCacheEntity.provider), if still known. */
        fun fromName(name: String?): LyricsProvider? = entries.firstOrNull { it.name == name }
    }
}

/** One automatic search. No [result] with [allAnswered] means no lyrics anywhere: BiniLyrics,
 * LRCLIB, KuGou and lyrics.ovh each answered "nothing". Without it some source never got through (offline, or
 * blocked where you are), which says nothing about the song. (Google, a scrape, isn't counted.) */
data class LyricsSearch(val result: LyricsResult?, val allAnswered: Boolean)

/**
 * Lyrics are fetched ONLY when the user explicitly asks for them (the Lyrics button in Now
 * Playing) - never during library sync/metadata enrichment. See
 * MusicRepository.fetchLyricsForSong(), the sole caller of fetchLyrics() below.
 *
 * Providers are tried in this order, each one free and requiring no API key - and, unlike
 * what was here before, each one actually verified reachable (via curl) before being wired in:
 * 0. BiniLyrics (lyrics-api.binimum.org, now served from lrc.red) - community lyrics as TTML,
 *    many timed word by word, Arabic included. Its word-timed lyrics win over everything, since
 *    only those let the Now Playing sweep follow the singing exactly. Its line-timed ones rank
 *    right after LRCLIB's. New searches there sometimes fail (503) or hang, so it gets a short
 *    time budget and the others run meanwhile. (Better Lyrics, the other word-synced API
 *    checked, answers only songs it already has cached without an API key - left out.)
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
// Built once: cacheKey runs for every lyrics lookup and for every song in the one-time backfill.
private val AUDIO_EXTENSION = Regex("""(?i)\.(mp3|m4a|flac|ogg|wav|aac|opus|webm)$""")
private val WHITESPACE = Regex("""\s+""")

private const val BINI_SEARCH_URL = "https://lyrics-api.binimum.org/"
// BiniLyrics sometimes hangs on a new search; past this the others' answer is used.
private const val BINI_BUDGET_MS = 6_000L
private const val BINI_DURATION_SLACK_S = 5

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
    suspend fun fetchLyrics(title: String, artist: String, durationSeconds: Int?): LyricsSearch {
        val reached = Reached()
        val result = searchAll(title, artist, durationSeconds, reached)
        return LyricsSearch(result, reached.bini.get() && reached.lrcLib.get() && reached.kuGou.get() && reached.ovh.get())
    }

    /** Which sources actually answered during one search (see LyricsSearch.allAnswered). */
    private class Reached {
        val bini = AtomicBoolean(false)
        val lrcLib = AtomicBoolean(false)
        val kuGou = AtomicBoolean(false)
        val ovh = AtomicBoolean(false)
    }

    private suspend fun searchAll(title: String, artist: String, durationSeconds: Int?, reached: Reached): LyricsResult? = coroutineScope {
        val (cleanTitle, cleanArtist) = sanitizeTitleAndArtist(title, artist)
        Log.d("LyricsRepository", "Searching lyrics for '$cleanTitle' by '$cleanArtist'")

        val biniDeferred = async { fetchBiniLyrics(cleanTitle, cleanArtist, durationSeconds, reached.bini) }
        val lrcLibDeferred = async { fetchLrcLibLyrics(cleanTitle, cleanArtist, durationSeconds, reached.lrcLib) }
        val kuGouDeferred = async { fetchKuGouLyrics(cleanTitle, cleanArtist, durationSeconds, reached.kuGou) }
        val ovhDeferred = async { fetchLyricsOvhLyrics(cleanTitle, cleanArtist, reached.ovh) }

        try {
            val biniResult = biniDeferred.await()
            if (biniResult?.synced != null && hasWordTiming(biniResult.synced)) {
                Log.d("LyricsRepository", "[BiniLyrics] Word-synced match")
                return@coroutineScope biniResult.copy(provider = LyricsProvider.BINI_LYRICS)
            }

            val lrcLibResult = lrcLibDeferred.await()
            if (lrcLibResult?.synced != null) {
                Log.d("LyricsRepository", "[LRCLIB] Synced match")
                return@coroutineScope lrcLibResult.copy(provider = LyricsProvider.LRCLIB)
            }

            if (biniResult?.synced != null) {
                Log.d("LyricsRepository", "[BiniLyrics] Line-synced match")
                return@coroutineScope biniResult.copy(provider = LyricsProvider.BINI_LYRICS)
            }

            val kuGouResult = kuGouDeferred.await()
            if (kuGouResult != null) {
                Log.d("LyricsRepository", "[KuGou] Synced match")
                return@coroutineScope kuGouResult.copy(provider = LyricsProvider.KUGOU)
            }

            if (lrcLibResult != null) {
                Log.d("LyricsRepository", "[LRCLIB] Plain-only match (no synced version found anywhere)")
                return@coroutineScope lrcLibResult.copy(provider = LyricsProvider.LRCLIB)
            }

            if (biniResult != null) {
                Log.d("LyricsRepository", "[BiniLyrics] Plain-only match")
                return@coroutineScope biniResult.copy(provider = LyricsProvider.BINI_LYRICS)
            }

            val ovhResult = ovhDeferred.await()
            if (ovhResult != null) {
                Log.d("LyricsRepository", "[lyrics.ovh] Succeeded")
                return@coroutineScope ovhResult.copy(provider = LyricsProvider.LYRICS_OVH)
            }

            // Not raced with the rest: a plain-text scrape that's rarely reached (only when
            // all three real providers above have nothing), so there's no latency to hide by
            // starting it early, only bandwidth to waste on every single fetch if it were.
            try {
                val googleLyrics = fetchGoogleLyrics(cleanTitle, cleanArtist)
                if (!googleLyrics.isNullOrBlank()) {
                    Log.d("LyricsRepository", "[Google Search] Succeeded")
                    return@coroutineScope LyricsResult(googleLyrics, null, LyricsProvider.GOOGLE)
                }
            } catch (e: Exception) {
                Log.w("LyricsRepository", "[Google Search] Failed: ${e.message}")
            }

            null
        } finally {
            // Whichever of these lost the race is no longer worth waiting on, and
            // coroutineScope will not return while they're still running.
            biniDeferred.cancel()
            lrcLibDeferred.cancel()
            kuGouDeferred.cancel()
            ovhDeferred.cancel()
        }
    }

    /** Asks only [provider] - the Lyrics source picker. [LyricsSearch.allAnswered] tells a real
     * "it has none" apart from "couldn't reach it" when nothing is found. */
    suspend fun fetchFrom(provider: LyricsProvider, title: String, artist: String, durationSeconds: Int?): LyricsSearch {
        val (cleanTitle, cleanArtist) = sanitizeTitleAndArtist(title, artist)
        val reached = AtomicBoolean(false)
        val result = try {
            when (provider) {
                LyricsProvider.BINI_LYRICS -> fetchBiniLyrics(cleanTitle, cleanArtist, durationSeconds, reached)
                LyricsProvider.LRCLIB -> fetchLrcLibLyrics(cleanTitle, cleanArtist, durationSeconds, reached)
                LyricsProvider.KUGOU -> fetchKuGouLyrics(cleanTitle, cleanArtist, durationSeconds, reached)
                LyricsProvider.LYRICS_OVH -> fetchLyricsOvhLyrics(cleanTitle, cleanArtist, reached)
                LyricsProvider.GOOGLE -> fetchGoogleLyrics(cleanTitle, cleanArtist, reached)?.takeIf { it.isNotBlank() }?.let { LyricsResult(it, null) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("LyricsRepository", "[${provider.label}] Failed: ${e.message}")
            null
        }
        val found = result?.takeIf { it.plain != null || it.synced != null }?.copy(provider = provider)
        return LyricsSearch(found, found != null || reached.get())
    }

    /** The key a song's lyrics are cached under (see LyricsCacheEntity): its whole title and
     * artist, only tidied (case, spacing, a file extension) - so the same song from Telegram,
     * YouTube or Spotify shares them, but "Hello - Live" and "Someone Like You - Live" never do.
     * (The search's own cleanup keeps only the part after " - ", too loose for a key.) */
    fun cacheKey(title: String, artist: String): String {
        fun tidy(text: String) = text.trim()
            .replace(AUDIO_EXTENSION, "")
            .replace('_', ' ')
            .replace(WHITESPACE, " ")
            .trim()
            .lowercase()
        return "${tidy(title)}|${tidy(artist)}"
    }

    /**
     * BiniLyrics: search by title and artist, pick the best entry, download its TTML. Entries
     * with no timing are skipped (they're DJ mixes and the like), as is any cut more than
     * [BINI_DURATION_SLACK_S] seconds off the track's own length - the search lists remixes and
     * live versions too. Among the rest, a word-timed one wins over a line-timed one only when
     * the length says it's the same recording; without a length, the search's own order does.
     */
    private suspend fun fetchBiniLyrics(
        title: String,
        artist: String,
        durationSeconds: Int?,
        reached: AtomicBoolean? = null
    ): LyricsResult? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(BINI_BUDGET_MS) { fetchBiniLyricsNow(title, artist, durationSeconds, reached) }
    }

    private suspend fun fetchBiniLyricsNow(
        title: String,
        artist: String,
        durationSeconds: Int?,
        reached: AtomicBoolean?
    ): LyricsResult? = run {
        try {
            val url = BINI_SEARCH_URL.toHttpUrl().newBuilder()
                .addQueryParameter("track", title)
                .addQueryParameter("artist", artist)
                .build()
            val body = httpGet(url.toString()) ?: return@run null
            val response = runCatching { gson.fromJson(body, BiniSearchResponse::class.java) }.getOrNull()
                ?: return@run null
            reached?.set(true)
            val seconds = durationSeconds ?: -1
            val usable = response.results.orEmpty()
                .filter { it.timing_type == "word" || it.timing_type == "line" }
                .filter { it.lyricsUrl?.startsWith("https://") == true }
                .filter { seconds <= 0 || it.duration <= 0 || abs(it.duration - seconds) <= BINI_DURATION_SLACK_S }
            val pick = (if (seconds > 0) usable.firstOrNull { it.timing_type == "word" } else null)
                ?: usable.firstOrNull()
                ?: return@run null
            val ttml = httpGet(pick.lyricsUrl!!)
            if (ttml == null) {
                // Found but not downloaded: says nothing about whether it has the lyrics.
                reached?.set(false)
                return@run null
            }
            val converted = TtmlLyrics.convert(ttml) ?: return@run null
            LyricsResult(plain = converted.plain.takeIf { it.isNotBlank() }, synced = converted.synced)
                .takeIf { it.plain != null || it.synced != null }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("LyricsRepository", "[BiniLyrics] Failed: ${e.message}")
            null
        }
    }

    private fun hasWordTiming(synced: String): Boolean = synced.contains("]<")

    private data class BiniSearchResponse(val results: List<BiniEntry>? = null)
    @Suppress("PropertyName")
    private data class BiniEntry(
        val duration: Int = -1,
        val timing_type: String? = null,
        val lyricsUrl: String? = null
    )

    /** LRCLIB's exact-match endpoint needs a precise title/artist/duration match and 404s
     * otherwise (routine, not an error worth logging loudly) - falls back to its fuzzier
     * search endpoint, picking the first result that actually has lyrics.
     *
     * Even when the exact-match endpoint succeeds, it returns ONE specific database entry,
     * which may only have plain lyrics while a different entry for the same song (findable via
     * search) has a synced version. Synced is always preferred when it exists anywhere in
     * LRCLIB, never just settled for plain because the first lookup happened to find it first. */
    private suspend fun fetchLrcLibLyrics(
        title: String,
        artist: String,
        durationSeconds: Int?,
        reached: AtomicBoolean? = null
    ): LyricsResult? =
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

            if (exactMatch?.synced != null) return@withContext exactMatch.also { reached?.set(true) }

            val searchMatch = try {
                val results = NetworkModule.lrcLibApi.searchLyrics(trackName = title, artistName = artist)
                // Only the broad search answering (found or not) counts as LRCLIB having answered.
                reached?.set(true)
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
    private suspend fun fetchKuGouLyrics(
        title: String,
        artist: String,
        durationSeconds: Int?,
        reached: AtomicBoolean? = null
    ): LyricsResult? =
        withContext(Dispatchers.IO) {
            try {
                val keyword = "${stripParenthetical(title)} - ${stripParenthetical(artist)}"
                val seconds = durationSeconds ?: -1

                // KuGou counts as having answered only when every call it made replied: a failed
                // candidate search or download says nothing about whether it has the lyrics.
                val hashes = kuGouSearchSongHashes(keyword, seconds) ?: return@withContext null
                var allReplied = true
                var candidate: KuGouCandidate? = null
                for (hash in hashes) {
                    val found = kuGouSearchLyricsCandidates(hash = hash)
                    if (found == null) allReplied = false else if (found.isNotEmpty()) { candidate = found.first(); break }
                }
                if (candidate == null) {
                    val found = kuGouSearchLyricsCandidates(keyword = keyword, seconds = seconds)
                    if (found == null) allReplied = false else candidate = found.firstOrNull()
                }
                if (candidate == null) {
                    if (allReplied) reached?.set(true)
                    return@withContext null
                }

                val lrc = kuGouDownload(candidate.id, candidate.accesskey) ?: return@withContext null
                reached?.set(true)
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
    /** Null when KuGou couldn't be reached (as opposed to no matching songs). */
    private suspend fun kuGouSearchSongHashes(keyword: String, seconds: Int): List<String>? {
        val url = "https://mobileservice.kugou.com/api/v3/search/song".toHttpUrl().newBuilder()
            .addQueryParameter("version", "9108")
            .addQueryParameter("plat", "0")
            .addQueryParameter("pagesize", "8")
            .addQueryParameter("showtype", "0")
            .addQueryParameter("keyword", keyword)
            .build()
        val body = httpGet(url.toString()) ?: return null
        val response = runCatching { gson.fromJson(body, KuGouSearchSongResponse::class.java) }.getOrNull() ?: return null
        return response.data?.info.orEmpty()
            .filter { seconds <= 0 || abs(it.duration - seconds) <= 8 }
            .sortedBy { abs(it.duration - seconds) }
            .map { it.hash }
    }

    private suspend fun kuGouSearchLyricsCandidates(hash: String? = null, keyword: String? = null, seconds: Int = -1): List<KuGouCandidate>? {
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
        val body = httpGet(builder.build().toString()) ?: return null
        val response = runCatching { gson.fromJson(body, KuGouSearchLyricsResponse::class.java) }.getOrNull()
        return response?.candidates
    }

    private suspend fun kuGouDownload(id: String, accessKey: String): String? {
        val url = "https://lyrics.kugou.com/download".toHttpUrl().newBuilder()
            .addQueryParameter("fmt", "lrc")
            .addQueryParameter("charset", "utf8")
            .addQueryParameter("client", "pc")
            .addQueryParameter("ver", "1")
            .addQueryParameter("id", id)
            .addQueryParameter("accesskey", accessKey)
            .build()
        val body = httpGet(url.toString()) ?: return null
        val response = runCatching { gson.fromJson(body, KuGouDownloadResponse::class.java) }.getOrNull() ?: return null
        return runCatching {
            String(Base64.decode(response.content, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull()
    }

    /** Cancellable: when a faster provider already answered, fetchLyrics cancels KuGou (or
     * BiniLyrics), and that now aborts the request in flight. A blocking execute() ignored the cancel, so the
     * lyrics waited for KuGou's whole chain of up to ten requests before showing. */
    private suspend fun httpGet(url: String): String? = try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
            .build()
        val call = NetworkModule.client.newCall(request)
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    val body = runCatching {
                        response.use { if (it.isSuccessful) it.body?.string() else null }
                    }.getOrNull()
                    continuation.resume(body)
                }
            })
        }
    } catch (e: CancellationException) {
        throw e
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

    private suspend fun fetchLyricsOvhLyrics(title: String, artist: String, reached: AtomicBoolean? = null): LyricsResult? =
        withContext(Dispatchers.IO) {
            try {
                val response = NetworkModule.lyricsOvhApi.getLyrics(
                    artist = Uri.encode(artist),
                    title = Uri.encode(title)
                )
                reached?.set(true)
                response.lyrics?.takeIf { it.isNotBlank() }?.let { LyricsResult(it, null) }
            } catch (e: HttpException) {
                // lyrics.ovh answers "not found" with a 404.
                if (e.code() == 404) reached?.set(true)
                null
            } catch (e: Exception) {
                Log.w("LyricsRepository", "[lyrics.ovh] Failed: ${e.message}")
                null
            }
        }

    private suspend fun fetchGoogleLyrics(title: String, artist: String, reached: AtomicBoolean? = null): String? = withContext(Dispatchers.IO) {
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
            if (response.isSuccessful) reached?.set(true)

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
