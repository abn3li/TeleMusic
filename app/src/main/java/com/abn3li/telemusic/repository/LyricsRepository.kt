package com.abn3li.telemusic.repository

import okhttp3.HttpUrl.Companion.toHttpUrl
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.abn3li.telemusic.data.remote.LrcLibResponse
import com.abn3li.telemusic.data.remote.NetworkModule
import com.abn3li.telemusic.data.browse.BrowseParser
import com.abn3li.telemusic.data.browse.InnertubeBrowseClient
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.selects.select
import java.util.concurrent.atomic.AtomicReference
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
    BETTER_LYRICS("BetterLyrics", "Word by word when available"),
    LYRICS_PLUS("LyricsPlus", "Word by word when available"),
    LRCLIB("LRCLIB", "Synced when available"),
    KUGOU("KuGou", "Synced"),
    YOUTUBE_MUSIC("YouTube Music", "Plain text"),
    LYRICS_OVH("lyrics.ovh", "Plain text");

    companion object {
        /** The provider saved under [name] (LyricsCacheEntity.provider), if still known. */
        fun fromName(name: String?): LyricsProvider? = entries.firstOrNull { it.name == name }
    }
}

/** One automatic search. No [result] with [allAnswered] means no lyrics anywhere: every source
 * answered "nothing". Without it some source never
 * got through (offline, or blocked where you are), which says nothing about the song. */
data class LyricsSearch(val result: LyricsResult?, val allAnswered: Boolean)

/**
 * Lyrics are fetched ONLY when the user explicitly asks for them (the Lyrics button in Now
 * Playing) - never during library sync/metadata enrichment. See
 * MusicRepository.fetchLyricsForSong(), the sole caller of fetchLyrics() below.
 *
 * Every source is asked at the same time, and the answers taken in this order:
 * 1. Word by word: BetterLyrics (Apple Music's own timing, matched by title, artist and length)
 *    and LyricsPlus (syllable timing; it runs on several servers, all asked at once - the first
 *    real answer wins and that server is asked first next time).
 * 2. Line by line: those two, then LRCLIB (lrclib.net) and KuGou.
 * 3. Plain text: LRCLIB, YouTube Music (the song's Lyrics tab - by its own video for a YouTube
 *    song) and lyrics.ovh.
 *
 * BetterLyrics and LyricsPlus are searched by name and length, and don't always say which song
 * they answered with - a close length once gave another song by the same singer. So their words
 * are checked against lyrics found by the song's name and artist (LRCLIB, YouTube Music) when
 * there are any ([sameLyrics]); otherwise they must at least be in the song's own script.
 * LRCLIB, KuGou and YouTube Music check the name and artist themselves ([SongMatch]).
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

/**
 * Whether a source's result is the song asked for. Search results list every song sharing a word
 * with the title, and the first one with lyrics used to win: an Arabic song got another song's
 * words. Now the title and artist must match ([LookupMatcher] - Arabic spellings folded, the
 * length when the artist is written in another script). Synced lines also need the same
 * recording: within [SYNC_SLACK_S] seconds of its length, and live only for a live song -
 * otherwise only the plain words are used.
 */
internal class SongMatch(title: String, artist: String, private val seconds: Int?) {
    private val wanted = LookupMatcher.clean(title, artist, seconds ?: 0)
    private val live = LIVE.containsMatchIn(title)
    private val arabicTitle = hasArabic(wanted.title)

    /** The tidied title and artist, as a store search. */
    val searchTerm: String get() = LookupMatcher.term(wanted)

    fun accepts(title: String?, artist: String?, durationSeconds: Int?): Boolean =
        LookupMatcher.best(listOf(LookupMatcher.Candidate(title, artist, durationSeconds?.takeIf { it > 0 })), wanted) != null

    /** [accepts], or - for the search's own top result - a name written in the other script
     * ("تملي معاك" found as "Tamally Maak"), which can't be compared: then its length must be
     * within [CROSS_SCRIPT_SLACK_S] seconds. */
    fun acceptsTop(title: String?, artist: String?, durationSeconds: Int?): Boolean =
        accepts(title, artist, durationSeconds) ||
            (title != null && hasArabic(title) != arabicTitle && seconds != null && seconds > 0 &&
                durationSeconds != null && durationSeconds > 0 && abs(durationSeconds - seconds) <= CROSS_SCRIPT_SLACK_S)

    /** Results in the source's own order: the first one gets [acceptsTop], the rest [accepts]. */
    fun <T> sameSong(results: List<T>, title: (T) -> String?, artist: (T) -> String?, seconds: (T) -> Int?): List<T> =
        results.filterIndexed { index, it ->
            if (index == 0) acceptsTop(title(it), artist(it), seconds(it)) else accepts(title(it), artist(it), seconds(it))
        }

    fun timingFits(title: String?, durationSeconds: Int?): Boolean =
        LIVE.containsMatchIn(title.orEmpty()) == live &&
            (seconds == null || seconds <= 0 || durationSeconds == null || durationSeconds <= 0 || abs(durationSeconds - seconds) <= SYNC_SLACK_S)
}

private fun hasArabic(s: String) = s.any { it in '\u0600'..'\u06FF' }
private const val CROSS_SCRIPT_SLACK_S = 3
private val LIVE = Regex("""(?i)\blive\b|لايف|حفلة|مباشر""")
private const val SYNC_SLACK_S = 6

/** The Lyrics tab's browseId ("MPLY...") in a YouTube Music `next` answer, if the song has one. */
internal fun youTubeLyricsBrowseId(next: JSONObject): String? {
    val tabs = next.optJSONObject("contents")?.optJSONObject("singleColumnMusicWatchNextResultsRenderer")
        ?.optJSONObject("tabbedRenderer")?.optJSONObject("watchNextTabbedResultsRenderer")?.optJSONArray("tabs") ?: return null
    for (i in 0 until tabs.length()) {
        val id = tabs.optJSONObject(i)?.optJSONObject("tabRenderer")?.optJSONObject("endpoint")
            ?.optJSONObject("browseEndpoint")?.optString("browseId").orEmpty()
        if (id.startsWith("MPLY")) return id
    }
    return null
}

/** The lyrics text in YouTube Music's answer for that tab, if it has any. */
internal fun youTubeLyricsText(browse: JSONObject): String? {
    val sections = browse.optJSONObject("contents")?.optJSONObject("sectionListRenderer")?.optJSONArray("contents") ?: return null
    for (i in 0 until sections.length()) {
        val runs = sections.optJSONObject(i)?.optJSONObject("musicDescriptionShelfRenderer")
            ?.optJSONObject("description")?.optJSONArray("runs") ?: continue
        val text = (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }.trim()
        if (text.isNotEmpty()) return text
    }
    return null
}

private const val BETTER_LYRICS_URL = "https://lyrics-api.boidu.dev/getLyrics"
private val LYRICS_PLUS_HOSTS = listOf(
    "https://lyricsplus.prjktla.my.id",
    "https://lyricsplus.atomix.one",
    "https://lyricsplus.binimum.org",
    "https://lyricsplus.prjktla.workers.dev",
    "https://lyricsplus-seven.vercel.app",
    "https://lyrics-plus-backend.vercel.app"
)
// How long the check of an unnamed answer waits on YouTube Music's lyrics to compare with.
private const val REFERENCE_WAIT_MS = 4_000L
// A word-timed source that hangs holds the others' answer back; past this it's given up on.
private const val WORD_SOURCE_BUDGET_MS = 6_000L

/** One request's outcome: the HTTP code (-1 when it never got an answer) and the body when 200. */
private class HttpAnswer(val code: Int, val body: String?) {
    /** Answered, and the answer was "not here" or the lyrics: either says something about the song. */
    val answered: Boolean get() = code == 200 || code == 401 || code == 404
}

private val LRC_TAG = Regex("""\[[^\]]*]|<[^>]*>""")
private val NOT_LETTERS = Regex("""[^\p{L}\p{N}]+""")
private const val SAME_LYRICS_SHARE = 0.35

/** Lyrics as a set of comparable words: tags out, case and Arabic spellings folded. */
private fun lyricWords(text: String): Set<String> = text.replace(LRC_TAG, " ").lowercase()
    .map { when (it) { 'أ', 'إ', 'آ' -> 'ا'; 'ة' -> 'ه'; 'ى' -> 'ي'; else -> it } }.joinToString("")
    .split(NOT_LETTERS).filter { it.length >= 2 }.toSet()

/**
 * Whether [candidate] is this song's lyrics, for a source that doesn't name what it answered:
 * enough of its words shared with [reference] (lyrics found by the song's name and artist), or,
 * with nothing to compare, at least in the song's script - an Arabic title wants Arabic words.
 */
internal fun sameLyrics(candidate: String, reference: String?, title: String): Boolean {
    val words = lyricWords(candidate)
    if (words.isEmpty()) return false
    val known = reference?.let(::lyricWords)?.takeIf { it.isNotEmpty() }
        ?: return !mostlyArabic(title) || mostlyArabic(candidate)
    if (mostlyArabic(candidate) != mostlyArabic(reference)) return false
    return words.count { it in known } >= SAME_LYRICS_SHARE * min(words.size, known.size)
}

/**
 * A LyricsPlus answer as lyrics: syllables back into enhanced LRC (a syllable ending in a space
 * ends its word), or line times when it only has those. Null when it names another song.
 */
internal fun lyricsPlusResult(json: JSONObject, match: SongMatch): LyricsResult? {
    val named = json.optJSONObject("metadata")
    val namedTitle = named?.optString("title")?.takeIf { it.isNotBlank() }
    if (namedTitle != null && !match.acceptsTop(namedTitle, named.optString("artist").takeIf { it.isNotBlank() }, null)) return null
    val lines = json.optJSONArray("lyrics") ?: return null
    val timed = (0 until lines.length()).mapNotNull { i ->
        val line = lines.optJSONObject(i) ?: return@mapNotNull null
        if (!line.has("time")) return@mapNotNull null
        val begin = line.optLong("time")
        val end = line.optLong("duration", 0L).takeIf { it > 0 }?.let { begin + it }
        val syllables = line.optJSONArray("syllabus")
        val words = (0 until (syllables?.length() ?: 0)).mapNotNull { j ->
            val syllable = syllables!!.optJSONObject(j) ?: return@mapNotNull null
            val text = syllable.optString("text")
            if (text.isBlank() || !syllable.has("time")) return@mapNotNull null
            val start = syllable.optLong("time")
            TtmlLyrics.TimedWord(text.trimEnd(), start, syllable.optLong("duration", 0L).takeIf { it > 0 }?.let { start + it },
                spaceAfter = text.last().isWhitespace())
        }
        val text = line.optString("text").trim()
        if (words.isEmpty() && text.isEmpty()) null else TtmlLyrics.TimedLine(minOf(begin, words.firstOrNull()?.beginMs ?: begin), end, text, words)
    }.sortedBy { it.beginMs }
    val converted = TtmlLyrics.fromTimedLines(timed) ?: return null
    return LyricsResult(plain = converted.plain.takeIf { it.isNotBlank() }, synced = converted.synced)
}

/** Written mostly in Arabic letters - one Arabic word ("Bismillah") doesn't make a song Arabic. */
private fun mostlyArabic(text: String): Boolean {
    var arabic = 0
    var other = 0
    for (c in text) if (c.isLetter()) { if (c in '؀'..'ۿ') arabic++ else other++ }
    return arabic > other
}

/** A result's words as text: the plain lyrics, or the synced ones without their times. */
private fun LyricsResult.text(): String? = plain ?: synced?.replace(LRC_TAG, " ")

class LyricsRepository(
    private val innertube: InnertubeBrowseClient = InnertubeBrowseClient()
) {

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
    suspend fun fetchLyrics(
        title: String,
        artist: String,
        durationSeconds: Int?,
        videoId: String? = null,
        // The sources switched on in Settings. With any off, "none anywhere" is never claimed:
        // the song is searched again once they're back on.
        enabled: Set<LyricsProvider> = LyricsProvider.entries.toSet()
    ): LyricsSearch {
        val reached = Reached()
        val result = searchAll(title, artist, durationSeconds, videoId, enabled, reached)
        return LyricsSearch(result, enabled.size == LyricsProvider.entries.size && reached.better.get() &&
            reached.plus.get() && reached.lrcLib.get() && reached.kuGou.get() && reached.youtube.get() && reached.ovh.get())
    }

    /** Which sources actually answered during one search (see LyricsSearch.allAnswered). */
    private class Reached {
        val better = AtomicBoolean(false)
        val plus = AtomicBoolean(false)
        val lrcLib = AtomicBoolean(false)
        val kuGou = AtomicBoolean(false)
        val youtube = AtomicBoolean(false)
        val ovh = AtomicBoolean(false)
    }

    private suspend fun searchAll(
        title: String,
        artist: String,
        durationSeconds: Int?,
        videoId: String?,
        enabled: Set<LyricsProvider>,
        reached: Reached
    ): LyricsResult? = coroutineScope {
        val (cleanTitle, cleanArtist) = sanitizeTitleAndArtist(title, artist)
        Log.d("LyricsRepository", "Searching lyrics for '$cleanTitle' by '$cleanArtist'")

        fun <T> ifOn(provider: LyricsProvider, fetch: suspend () -> T?) = async { if (provider in enabled) fetch() else null }
        val betterDeferred = ifOn(LyricsProvider.BETTER_LYRICS) { fetchBetterLyrics(cleanTitle, cleanArtist, durationSeconds, reached.better) }
        val plusDeferred = ifOn(LyricsProvider.LYRICS_PLUS) { fetchLyricsPlus(cleanTitle, cleanArtist, durationSeconds, reached.plus) }
        val lrcLibDeferred = ifOn(LyricsProvider.LRCLIB) { fetchLrcLibLyrics(cleanTitle, cleanArtist, durationSeconds, reached.lrcLib) }
        val kuGouDeferred = ifOn(LyricsProvider.KUGOU) { fetchKuGouLyrics(cleanTitle, cleanArtist, durationSeconds, reached.kuGou) }
        val youtubeDeferred = ifOn(LyricsProvider.YOUTUBE_MUSIC) {
            fetchYouTubeMusicLyrics(cleanTitle, cleanArtist, durationSeconds, videoId, reached.youtube)
        }
        val ovhDeferred = ifOn(LyricsProvider.LYRICS_OVH) { fetchLyricsOvhLyrics(cleanTitle, cleanArtist, reached.ovh) }

        // Lyrics found by the song's name and artist - what the unnamed answers are checked
        // against. Waited for only once one of those has something.
        var referenceRead = false
        var reference: String? = null
        suspend fun reference(): String? {
            if (!referenceRead) {
                reference = lrcLibDeferred.await()?.text()
                    ?: withTimeoutOrNull(REFERENCE_WAIT_MS) { youtubeDeferred.await() }?.text()
                referenceRead = true
            }
            return reference
        }
        // Nor do they say which recording they timed: a live song keeps only their words.
        val live = LIVE.containsMatchIn(cleanTitle)
        suspend fun checked(result: LyricsResult?, provider: LyricsProvider): LyricsResult? {
            val text = result?.text() ?: return null
            if (sameLyrics(text, reference(), cleanTitle)) {
                return result.copy(synced = result.synced.takeUnless { live }, plain = result.plain ?: text, provider = provider)
            }
            Log.d("LyricsRepository", "[${provider.label}] Answered with another song's lyrics - not used")
            return null
        }

        try {
            val better = checked(betterDeferred.await(), LyricsProvider.BETTER_LYRICS)
            val plus = checked(plusDeferred.await(), LyricsProvider.LYRICS_PLUS)
            val unnamed = listOfNotNull(better, plus)
            unnamed.firstOrNull { it.synced != null && hasWordTiming(it.synced) }?.let {
                Log.d("LyricsRepository", "[${it.provider?.label}] Word-synced match")
                return@coroutineScope it
            }
            unnamed.firstOrNull { it.synced != null }?.let {
                Log.d("LyricsRepository", "[${it.provider?.label}] Line-synced match")
                return@coroutineScope it
            }

            val lrcLibResult = lrcLibDeferred.await()
            if (lrcLibResult?.synced != null) {
                Log.d("LyricsRepository", "[LRCLIB] Synced match")
                return@coroutineScope lrcLibResult.copy(provider = LyricsProvider.LRCLIB)
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

            unnamed.firstOrNull()?.let {
                Log.d("LyricsRepository", "[${it.provider?.label}] Plain-only match")
                return@coroutineScope it
            }

            val youtubeResult = youtubeDeferred.await()
            if (youtubeResult != null) {
                Log.d("LyricsRepository", "[YouTube Music] Plain match")
                return@coroutineScope youtubeResult.copy(provider = LyricsProvider.YOUTUBE_MUSIC)
            }

            val ovhResult = ovhDeferred.await()
            if (ovhResult != null) {
                Log.d("LyricsRepository", "[lyrics.ovh] Succeeded")
                return@coroutineScope ovhResult.copy(provider = LyricsProvider.LYRICS_OVH)
            }

            null
        } finally {
            // Whichever of these lost the race is no longer worth waiting on, and
            // coroutineScope will not return while they're still running.
            betterDeferred.cancel()
            plusDeferred.cancel()
            lrcLibDeferred.cancel()
            kuGouDeferred.cancel()
            youtubeDeferred.cancel()
            ovhDeferred.cancel()
        }
    }

    /** Asks only [provider] - the Lyrics source picker. [LyricsSearch.allAnswered] tells a real
     * "it has none" apart from "couldn't reach it" when nothing is found. */
    suspend fun fetchFrom(provider: LyricsProvider, title: String, artist: String, durationSeconds: Int?, videoId: String? = null): LyricsSearch {
        val (cleanTitle, cleanArtist) = sanitizeTitleAndArtist(title, artist)
        val reached = AtomicBoolean(false)
        val result = try {
            when (provider) {
                LyricsProvider.BETTER_LYRICS -> fetchBetterLyrics(cleanTitle, cleanArtist, durationSeconds, reached)
                LyricsProvider.LYRICS_PLUS -> fetchLyricsPlus(cleanTitle, cleanArtist, durationSeconds, reached)
                LyricsProvider.LRCLIB -> fetchLrcLibLyrics(cleanTitle, cleanArtist, durationSeconds, reached)
                LyricsProvider.KUGOU -> fetchKuGouLyrics(cleanTitle, cleanArtist, durationSeconds, reached)
                LyricsProvider.YOUTUBE_MUSIC -> fetchYouTubeMusicLyrics(cleanTitle, cleanArtist, durationSeconds, videoId, reached)
                LyricsProvider.LYRICS_OVH -> fetchLyricsOvhLyrics(cleanTitle, cleanArtist, reached)
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

    /** BetterLyrics: one request by title, artist and length, answered with Apple Music's TTML.
     * It answers 401 when it has nothing that fits - a real "not here". */
    private suspend fun fetchBetterLyrics(
        title: String,
        artist: String,
        durationSeconds: Int?,
        reached: AtomicBoolean? = null
    ): LyricsResult? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(WORD_SOURCE_BUDGET_MS) {
            try {
                val url = BETTER_LYRICS_URL.toHttpUrl().newBuilder()
                    .addQueryParameter("s", title)
                    .addQueryParameter("a", artist)
                    .apply { if (durationSeconds != null && durationSeconds > 0) addQueryParameter("d", durationSeconds.toString()) }
                    .build()
                val answer = httpCall(url.toString())
                if (answer.answered) reached?.set(true)
                val ttml = answer.body?.let { runCatching { JSONObject(it).optString("ttml") }.getOrNull() }
                    ?.takeIf { it.isNotBlank() } ?: return@withTimeoutOrNull null
                val converted = TtmlLyrics.convert(ttml) ?: return@withTimeoutOrNull null
                LyricsResult(plain = converted.plain.takeIf { it.isNotBlank() }, synced = converted.synced)
                    .takeIf { it.plain != null || it.synced != null }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("LyricsRepository", "[BetterLyrics] Failed: ${e.message}")
                null
            }
        }
    }

    // The LyricsPlus server that last answered, asked first next time.
    private val lyricsPlusHost = AtomicReference<String?>(null)

    /** LyricsPlus: every server asked at once; the first with lyrics wins. When it names the song
     * it answered with, that name must be this song's. */
    private suspend fun fetchLyricsPlus(
        title: String,
        artist: String,
        durationSeconds: Int?,
        reached: AtomicBoolean? = null
    ): LyricsResult? = withTimeoutOrNull(WORD_SOURCE_BUDGET_MS) {
        coroutineScope {
            val hosts = lyricsPlusHost.get()?.let { first -> listOf(first) + LYRICS_PLUS_HOSTS.filterNot { it == first } }
                ?: LYRICS_PLUS_HOSTS
            val pending = hosts.map { host -> host to async(Dispatchers.IO) { lyricsPlusFrom(host, title, artist, durationSeconds) } }
                .toMutableList()
            try {
                while (pending.isNotEmpty()) {
                    val (host, answer) = select { pending.forEach { (host, job) -> job.onAwait { host to it } } }
                    pending.removeAll { it.first == host }
                    if (answer.answered) reached?.set(true)
                    val body = answer.body ?: continue
                    val result = runCatching { lyricsPlusResult(JSONObject(body), SongMatch(title, artist, durationSeconds)) }.getOrNull()
                    if (result != null) {
                        lyricsPlusHost.set(host)
                        return@coroutineScope result
                    }
                }
                null
            } finally {
                pending.forEach { it.second.cancel() }
            }
        }
    }

    private suspend fun lyricsPlusFrom(host: String, title: String, artist: String, durationSeconds: Int?): HttpAnswer {
        val url = "$host/v2/lyrics/get".toHttpUrl().newBuilder()
            .addQueryParameter("title", title)
            .addQueryParameter("artist", artist)
            .apply { if (durationSeconds != null && durationSeconds > 0) addQueryParameter("duration", durationSeconds.toString()) }
            .build()
        return httpCall(url.toString())
    }

    private fun hasWordTiming(synced: String): Boolean = synced.contains("]<")

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
                val match = SongMatch(title, artist, durationSeconds)
                val same = match.sameSong(results, { it.trackName }, { it.artistName }, { it.duration?.toInt() })
                same.firstOrNull { !it.syncedLyrics.isNullOrBlank() && match.timingFits(it.trackName, it.duration?.toInt()) }
                    ?.toLyricsResultOrNull()
                    ?: same.firstOrNull { !it.plainLyrics.isNullOrBlank() }?.toLyricsResultOrNull(withSynced = false)
            } catch (searchException: Exception) {
                Log.w("LyricsRepository", "[LRCLIB] Search failed: ${searchException.message}")
                null
            }

            // Prefer whichever of the two actually has a synced version; otherwise take
            // whatever plain result is available.
            searchMatch?.takeIf { it.synced != null } ?: exactMatch ?: searchMatch
        }

    private fun LrcLibResponse.toLyricsResultOrNull(withSynced: Boolean = true): LyricsResult? {
        val plain = plainLyrics?.takeIf { it.isNotBlank() }
        val synced = syncedLyrics?.takeIf { it.isNotBlank() && withSynced }
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
                val match = SongMatch(title, artist, durationSeconds)
                val hashes = kuGouSearchSongHashes(keyword, seconds, match) ?: return@withContext null
                var allReplied = true
                var candidate: KuGouCandidate? = null
                for (hash in hashes) {
                    val found = kuGouSearchLyricsCandidates(hash = hash)
                    if (found == null) allReplied = false else if (found.isNotEmpty()) { candidate = found.first(); break }
                }
                if (candidate == null) {
                    val found = kuGouSearchLyricsCandidates(keyword = keyword, seconds = seconds)
                    if (found == null) allReplied = false
                    else candidate = match.sameSong(found, { it.song }, { it.singer }, { it.duration / 1000 })
                        .firstOrNull { match.timingFits(it.song, it.duration / 1000) }
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

    /** Song hashes worth trying: this song (SongMatch), cut to the same recording - otherwise the
     * first result for a common title is as likely to be a cover or a remix - closest length first. */
    /** Null when KuGou couldn't be reached (as opposed to no matching songs). */
    private suspend fun kuGouSearchSongHashes(keyword: String, seconds: Int, match: SongMatch): List<String>? {
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
            .let { songs -> match.sameSong(songs, { it.songname }, { it.singername }, { it.duration }) }
            .filter { match.timingFits(it.songname, it.duration) }
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

    private suspend fun httpGet(url: String): String? = httpCall(url).body

    /** Cancellable: when a faster provider already answered, fetchLyrics cancels the slower
     * ones, and that aborts the request in flight. A blocking execute() ignored the cancel, so the
     * lyrics waited for KuGou's whole chain of up to ten requests before showing. */
    private suspend fun httpCall(url: String): HttpAnswer = try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
            .build()
        val call = NetworkModule.client.newCall(request)
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resume(HttpAnswer(-1, null))
                }

                override fun onResponse(call: Call, response: Response) {
                    val body = runCatching {
                        response.use { if (it.isSuccessful) it.body?.string() else null }
                    }.getOrNull()
                    continuation.resume(HttpAnswer(response.code, body))
                }
            })
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        HttpAnswer(-1, null)
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
    private data class KuGouSongInfo(val hash: String, val songname: String? = null, val singername: String? = null, val duration: Int = -1)
    private data class KuGouSearchLyricsResponse(val candidates: List<KuGouCandidate> = emptyList())
    private data class KuGouCandidate(val id: String, val accesskey: String, val song: String? = null, val singer: String? = null, val duration: Int = -1)
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

    /** YouTube Music's Lyrics tab for this song (see the provider order above): [videoId] when
     * it's a YouTube song, else the YouTube Music song matching this title and artist. */
    private suspend fun fetchYouTubeMusicLyrics(
        title: String,
        artist: String,
        durationSeconds: Int?,
        videoId: String?,
        reached: AtomicBoolean? = null
    ): LyricsResult? = withContext(Dispatchers.IO) {
        try {
            val id = videoId ?: run {
                val match = SongMatch(title, artist, durationSeconds)
                val songs = BrowseParser.parseSearchSongs(innertube.searchSongs(match.searchTerm))
                val same = match.sameSong(songs, { it.title }, { it.artist }, { it.durationSeconds })
                val pick = same.firstOrNull()
                if (pick == null) {
                    reached?.set(true)
                    return@withContext null
                }
                pick.videoId
            }
            val text = youTubeLyricsBrowseId(innertube.next(id))?.let { youTubeLyricsText(innertube.browse(it)) }
            reached?.set(true)
            text?.let { LyricsResult(it, null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("LyricsRepository", "[YouTube Music] Failed: ${e.message}")
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
