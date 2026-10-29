package com.abn3li.telemusic.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap

/**
 * YouTube audio kept on the phone as it plays, so a song heard before starts at once (offline
 * too, once it's all here), and the next song's opening fetched ahead so it starts without a wait.
 *
 * Kept by video and exact file (format and size), not by link: links change on every lookup and
 * expire, while the file behind them doesn't - and a different file of the same video never
 * mixes its bytes in. Its size counts toward the cache limit in Settings (see
 * MusicRepository.enforceCacheLimit), which trims the least recently played first.
 */
@OptIn(UnstableApi::class)
class YouTubeAudioCache(private val context: Context) {
    private val cache: SimpleCache by lazy {
        SimpleCache(File(context.filesDir, "youtube_audio"), NoOpCacheEvictor(), StandaloneDatabaseProvider(context))
    }

    private val httpFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(STREAM_USER_AGENT)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(15_000)

    /** Reads what's here and fetches the rest, keeping it as it comes. */
    val dataSourceFactory: DataSource.Factory by lazy {
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory { CachedOnlyGuard(ChunkedHttpDataSource(httpFactory.createDataSource())) }
            .setCacheKeyFactory { spec -> keyFor(spec.uri) ?: spec.uri.toString() }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /** Whether the player should read [uri] through this cache. */
    fun handles(uri: Uri): Boolean = keyFor(uri) != null

    /** Every song that's here whole, with its size: they play without internet. */
    fun fullyCached(): Map<String, Long> = runCatching {
        cache.keys.mapNotNull { key ->
            val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
            if (length > 0 && cache.isCached(key, 0, length)) key.removePrefix(KEY_PREFIX).substringBefore(':') to length else null
        }.toMap()
    }.getOrDefault(emptyMap())

    /** A link to [videoId] when the whole song is here: it plays without looking anything up. */
    fun fullyCachedUri(videoId: String): Uri? = runCatching {
        val prefix = "$KEY_PREFIX$videoId:"
        cache.keys.firstNotNullOfOrNull { key ->
            if (!key.startsWith(prefix)) return@firstNotNullOfOrNull null
            val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
            if (length > 0 && cache.isCached(key, 0, length)) {
                Uri.Builder().scheme(CACHED_SCHEME).authority("audio")
                    .appendQueryParameter("v", videoId).appendQueryParameter("f", key.removePrefix(prefix)).build()
            } else null
        }
    }.getOrNull()

    /** Fetches the first stretch of [uri] - about 45 seconds of the song - into the cache. Stops
     * when the calling coroutine is cancelled (the queue moved on). */
    suspend fun cacheOpening(uri: Uri, durationSeconds: Int) = withContext(Dispatchers.IO) {
        val key = keyFor(uri) ?: return@withContext
        val total = uri.getQueryParameter("clen")?.toLongOrNull()
        val bytes = if (total != null && durationSeconds > 0) {
            (total * OPENING_SECONDS / durationSeconds).coerceIn(MIN_OPENING_BYTES, MAX_OPENING_BYTES).coerceAtMost(total)
        } else DEFAULT_OPENING_BYTES
        if (runCatching { cache.isCached(key, 0, bytes) }.getOrDefault(false)) return@withContext
        val spec = DataSpec.Builder().setUri(uri).setPosition(0).setLength(bytes).setKey(key).build()
        val writer = CacheWriter(dataSourceFactory.createDataSource() as CacheDataSource, spec, null, null)
        val cancelHandle = currentCoroutineContext().job.invokeOnCompletion { writer.cancel() }
        try {
            writer.cache()
        } catch (_: InterruptedIOException) {
        } catch (e: java.io.IOException) {
            android.util.Log.w(TAG, "Couldn't fetch the opening of $key: ${e.message}")
        } finally {
            cancelHandle.dispose()
        }
    }

    internal class Entry(val key: String, val videoId: String, val size: Long, val lastPlayed: Long)

    /** Everything kept, for the cache limit. */
    internal fun entries(): List<Entry> = runCatching {
        cache.keys.mapNotNull { key ->
            val spans = cache.getCachedSpans(key)
            if (spans.isEmpty()) return@mapNotNull null
            Entry(key, key.removePrefix(KEY_PREFIX).substringBefore(':'), spans.sumOf { it.length }, spans.maxOf { it.lastTouchTimestamp })
        }
    }.getOrDefault(emptyList())

    /** Removes one song's audio; how many bytes that freed. */
    internal fun remove(key: String): Long = runCatching {
        val size = cache.getCachedSpans(key).sumOf { it.length }
        cache.removeResource(key)
        size
    }.getOrDefault(0L)

    /** Removes all of [videoId]'s audio; how many bytes that freed. */
    internal fun removeVideo(videoId: String): Long =
        runCatching { cache.keys.filter { it.startsWith("$KEY_PREFIX$videoId:") }.sumOf { remove(it) } }.getOrDefault(0L)

    /** Removes everything: how many songs and bytes. */
    internal fun clear(): Pair<Int, Long> {
        val all = entries()
        all.forEach { remove(it.key) }
        return all.size to all.sumOf { it.size }
    }

    private fun keyFor(uri: Uri): String? = when (uri.scheme) {
        CACHED_SCHEME -> {
            val videoId = uri.getQueryParameter("v")
            val file = uri.getQueryParameter("f")
            if (videoId.isNullOrBlank() || file.isNullOrBlank()) null else "$KEY_PREFIX$videoId:$file"
        }
        "http", "https" -> {
            val videoId = StreamVideoIds.of(uri.toString())
            val format = uri.getQueryParameter("itag")
            if (videoId == null || format.isNullOrBlank()) null
            else "$KEY_PREFIX$videoId:$format" + (uri.getQueryParameter("clen")?.let { ":$it" } ?: "")
        }
        else -> null
    }

    /**
     * A [CACHED_SCHEME] link only names what's in the cache; it can't be fetched. Should a part of
     * it be missing after all (Clear Cache while it played), it fails like an expired link, so the
     * player looks the song up again (see MusicService.refreshExpiredStreamLink).
     */
    private class CachedOnlyGuard(private val upstream: DataSource) : DataSource by upstream {
        override fun open(dataSpec: DataSpec): Long {
            if (dataSpec.uri.scheme == CACHED_SCHEME) {
                throw HttpDataSource.InvalidResponseCodeException(410, "Not in the cache", null, emptyMap(), dataSpec, ByteArray(0))
            }
            return upstream.open(dataSpec)
        }
    }

    companion object {
        private const val TAG = "YouTubeAudioCache"
        private const val KEY_PREFIX = "yt:"
        const val CACHED_SCHEME = "ytcache"
        private const val OPENING_SECONDS = 45
        private const val MIN_OPENING_BYTES = 512L * 1024
        private const val MAX_OPENING_BYTES = 4L * 1024 * 1024
        private const val DEFAULT_OPENING_BYTES = 1536L * 1024

        // A real browser's: a resolved link is signed for the client that asked for it (yt-dlp,
        // as a browser), and the CDN can refuse or slow an unknown player's User-Agent.
        const val STREAM_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}

/** Which video each looked-up stream link is for - links don't say, and the cache keys on it. */
object StreamVideoIds {
    private val ids = ConcurrentHashMap<String, String>()

    fun remember(url: String, videoId: String) {
        if (ids.size >= 128) ids.clear()
        ids[url] = videoId
    }

    fun of(url: String): String? = ids[url]
}

/**
 * Fetches a stream in ranges of [CHUNK_BYTES] instead of one long read: YouTube's servers slow a
 * long read down to about playing speed after the first megabyte or so, while each range comes at
 * full speed - so the cache fills ahead quickly and a seek further on finds it there.
 */
@OptIn(UnstableApi::class)
internal class ChunkedHttpDataSource(private val upstream: DataSource) : DataSource {
    private var spec: DataSpec? = null
    private var position = 0L
    private var end = C.LENGTH_UNSET.toLong()
    private var chunkEnd = 0L

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        position = dataSpec.position
        val known = dataSpec.uri.getQueryParameter("clen")?.toLongOrNull()
        end = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.position + dataSpec.length
            known != null -> known
            else -> C.LENGTH_UNSET.toLong()
        }
        openChunk()
        if (end == C.LENGTH_UNSET.toLong()) end = totalFromHeaders() ?: C.LENGTH_UNSET.toLong()
        return if (end == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else end - dataSpec.position
    }

    private fun openChunk() {
        val base = spec!!
        val length = if (end == C.LENGTH_UNSET.toLong()) CHUNK_BYTES else minOf(CHUNK_BYTES, end - position)
        upstream.open(base.subrange(position - base.position, length))
        chunkEnd = position + length
    }

    private fun totalFromHeaders(): Long? = upstream.responseHeaders.entries
        .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
        ?.value?.firstOrNull()?.substringAfterLast('/')?.trim()?.toLongOrNull()

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            val read = upstream.read(buffer, offset, length)
            if (read != C.RESULT_END_OF_INPUT) {
                position += read
                return read
            }
            // This range is done: on to the next, unless that was the end of the file.
            if (end != C.LENGTH_UNSET.toLong() && position >= end) return C.RESULT_END_OF_INPUT
            if (end == C.LENGTH_UNSET.toLong() && position < chunkEnd) return C.RESULT_END_OF_INPUT
            upstream.close()
            try {
                openChunk()
            } catch (e: HttpDataSource.InvalidResponseCodeException) {
                // Past the end of a file whose length was never given.
                if (e.responseCode == 416) return C.RESULT_END_OF_INPUT
                throw e
            }
        }
    }

    override fun getUri(): Uri? = upstream.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
    override fun close() = upstream.close()

    companion object {
        private const val CHUNK_BYTES = 2L * 1024 * 1024
    }
}
