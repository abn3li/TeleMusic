package com.abn3li.telemusic.data.download

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private data class CachedStream(
    val result: YtDlpStreamResult,
    val cachedAtMillis: Long = System.currentTimeMillis()
)

private const val STREAM_CACHE_TTL_MS = 3 * 60 * 60 * 1000L // 3 hours (YouTube stream URLs expire after ~6h)

/** Coroutine-friendly front for [YtDlpService] - every call in there blocks on network/disk I/O
 * (real yt-dlp doing real work), so this is the only place that's ever touched from a ViewModel;
 * nothing here runs on the main thread. */
class YtDlpRepository(context: Context) {
    private val service = YtDlpService(context.applicationContext)
    private val streamCache = ConcurrentHashMap<String, CachedStream>()

    fun invalidateStreamCache(videoId: String) {
        streamCache.remove(videoId)
    }

    suspend fun search(query: String): List<YtDlpSearchResult> = withContext(Dispatchers.IO) {
        val startedAt = SystemClock.elapsedRealtime()
        runCatching { service.search(query) }
            .onFailure { e -> Log.e("YtDlpRepository", "search(query=$query) failed", e) }
            .getOrElse { emptyList() }
            .also { Log.d("YtDlpRepository", "search(query=$query): ${it.size} results in ${SystemClock.elapsedRealtime() - startedAt}ms") }
    }

    suspend fun download(
        videoId: String,
        destDir: File,
        destFilenameStem: String,
        formatSelector: String
    ): Result<YtDlpDownloadResult> =
        withContext(Dispatchers.IO) {
            runCatching { service.download(videoId, destDir, destFilenameStem, formatSelector) }
        }

    suspend fun resolveStreamUrl(videoId: String, formatSelector: String): Result<YtDlpStreamResult> =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val cached = streamCache[videoId]
            if (cached != null && (now - cached.cachedAtMillis) < STREAM_CACHE_TTL_MS) {
                Log.d("YtDlpRepository", "resolveStreamUrl(videoId=$videoId): cache hit! (0ms)")
                return@withContext Result.success(cached.result)
            }

            val result = runCatching { service.resolveStreamUrl(videoId, formatSelector) }
            result.onSuccess { stream ->
                if (stream.streamUrl.isNotBlank()) {
                    streamCache[videoId] = CachedStream(stream, now)
                }
            }
            result
        }

    suspend fun fetchPlaylistMetadata(url: String): YtDlpPlaylistMetadata? = withContext(Dispatchers.IO) {
        runCatching { service.fetchPlaylistMetadata(url) }
            .onFailure { e -> Log.e("YtDlpRepository", "fetchPlaylistMetadata(url=$url) failed", e) }
            .getOrNull()
    }
}
