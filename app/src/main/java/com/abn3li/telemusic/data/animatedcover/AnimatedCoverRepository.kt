package com.abn3li.telemusic.data.animatedcover

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.abn3li.telemusic.data.local.SongEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A looping clip for a song's cover. [fallbackUrl] gets one try if [url] won't play. */
data class AnimatedCover(val url: String, val fallbackUrl: String? = null)

/**
 * Finds the clip behind a song's cover. The sources are asked in turn until one answers, and only
 * for the song on the open player, so a closed player costs nothing.
 *
 * Answers are kept on disk, "no clip" included: most songs have none, and without this every
 * visit would ask all three services again. A failed request isn't an answer and isn't kept.
 */
class AnimatedCoverRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs by lazy {
        appContext.getSharedPreferences("animated_covers", Context.MODE_PRIVATE).also(::dropOldAnswers)
    }
    private val lock = Mutex()
    private val sources by lazy {
        listOf(AppleMotionCovers(prefs), TidalVideoCovers(), CommunityCovers())
    }

    @OptIn(UnstableApi::class)
    private val cache: SimpleCache by lazy {
        SimpleCache(
            File(appContext.cacheDir, "animated_covers"),
            LeastRecentlyUsedCacheEvictor(CLIP_CACHE_BYTES),
            StandaloneDatabaseProvider(appContext)
        )
    }

    /** Each clip plays on a loop, so without this every loop would download it again. */
    @OptIn(UnstableApi::class)
    val dataSourceFactory: DataSource.Factory by lazy {
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(
                DefaultHttpDataSource.Factory()
                    .setUserAgent(BROWSER_USER_AGENT)
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(10_000)
                    .setReadTimeoutMs(10_000)
            )
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /** The clip for [song], or null when there's none or it can't be looked for now. */
    suspend fun find(song: SongEntity, allowMobileData: Boolean): AnimatedCover? = withContext(Dispatchers.IO) {
        val query = CoverQuery.of(song) ?: return@withContext null
        lock.withLock {
            val key = query.key
            val saved = saved(key)
            if (saved != null) return@withLock saved.cover
            if (!allowMobileData && isMetered()) return@withLock null
            var incomplete = false
            for (source in sources) {
                val found = try {
                    source.find(query)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    incomplete = true
                    null
                }
                if (found != null) {
                    save(key, found)
                    return@withLock found
                }
            }
            if (!incomplete) save(key, null)
            null
        }
    }

    /** Runs once: answers saved before the clip choice changed point at the wrong clip. */
    private fun dropOldAnswers(saved: SharedPreferences) {
        if (saved.getInt(KEY_ANSWERS_VERSION, 1) >= ANSWERS_VERSION) return
        val editor = saved.edit()
        saved.all.keys.filter { it.startsWith(KEY_PREFIX) }.forEach(editor::remove)
        editor.putInt(KEY_ANSWERS_VERSION, ANSWERS_VERSION).apply()
    }

    private fun isMetered(): Boolean =
        (appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered

    private class Saved(val cover: AnimatedCover?)

    private fun saved(key: String): Saved? {
        val parts = prefs.getString(key, null)?.split('\n') ?: return null
        val url = parts.getOrNull(1).orEmpty()
        val keptFor = if (url.isEmpty()) MISS_KEPT_MS else HIT_KEPT_MS
        if (System.currentTimeMillis() - (parts[0].toLongOrNull() ?: 0L) > keptFor) return null
        if (url.isEmpty()) return Saved(null)
        return Saved(AnimatedCover(url, parts.getOrNull(2)?.takeIf { it.isNotEmpty() }))
    }

    private fun save(key: String, cover: AnimatedCover?) {
        val editor = prefs.edit()
        if (prefs.all.size >= MAX_SAVED) prefs.all.keys.filter { it.startsWith(KEY_PREFIX) }.forEach(editor::remove)
        editor.putString(key, listOf(System.currentTimeMillis(), cover?.url.orEmpty(), cover?.fallbackUrl.orEmpty()).joinToString("\n")).apply()
    }

    private companion object {
        const val CLIP_CACHE_BYTES = 150L * 1024 * 1024
        const val MAX_SAVED = 3000
        const val KEY_ANSWERS_VERSION = "answers_version"
        const val ANSWERS_VERSION = 3
        val HIT_KEPT_MS = TimeUnit.DAYS.toMillis(30)
        val MISS_KEPT_MS = TimeUnit.DAYS.toMillis(7)
    }
}

internal const val KEY_PREFIX = "song|"

/** What's playing, cleaned of the packaging the music services never see. */
internal class CoverQuery(val title: String, val artist: String, val album: String?) {
    val key: String = "$KEY_PREFIX${title.forMatching()}|${artist.forMatching()}"

    companion object {
        private val TITLE_NOISE = Regex(
            """\((?:from|official|lyrics?|lyrical|video|audio|visuali[sz]er|hd|4k)[^)]*\)|\[[^]]*]|""" +
                """\b(?:official (?:music )?(?:video|audio)|lyrics? video|lyrical|4k video)\b""",
            RegexOption.IGNORE_CASE
        )
        private val ARTIST_NOISE = Regex("""\s*-\s*Topic$|VEVO$""", RegexOption.IGNORE_CASE)

        fun of(song: SongEntity): CoverQuery? {
            val title = song.title.replace(TITLE_NOISE, " ").substringBefore(" | ")
                .replace(Regex("\\s+"), " ").trim()
            val artist = song.artist.replace(ARTIST_NOISE, "").trim()
            if (title.isBlank() || artist.isBlank() || artist.equals("Unknown Artist", ignoreCase = true)) return null
            return CoverQuery(title, artist, song.album?.takeIf { it.isNotBlank() })
        }
    }
}

/** Lower case with only letters and digits left, in any script, so Arabic names match too. */
internal fun String.forMatching(): String =
    lowercase().filter(Char::isLetterOrDigit)

/** The separate names in a credit like "A, B & C feat. D". */
internal fun String.artistNames(): List<String> =
    split(Regex("""\s*(?:,|&|،|;|\bx\b|\band\b|\bfeat\.?\b|\bft\.?\b|\bwith\b|\bو\b)\s*""", RegexOption.IGNORE_CASE))
        .map { it.forMatching() }
        .filter { it.isNotEmpty() }

/** Every artist asked for is credited. A clip by someone else is never the right one. */
internal fun creditsMatch(wanted: String, credited: List<String>): Boolean {
    val names = wanted.artistNames()
    val got = credited.flatMap { it.artistNames() }
    return names.isNotEmpty() && names.all { it in got }
}

/** Thrown for a request that got no real answer, so "no clip" isn't remembered for the song. */
internal class CoverLookupFailed(message: String) : IOException(message)

internal const val BROWSER_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
