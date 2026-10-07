package com.abn3li.telemusic.data.download

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Coroutine-friendly front for [YtDlpService] - every call in there blocks on network/disk I/O
 * (real yt-dlp doing real work), so this is the only place that's ever touched from a ViewModel;
 * nothing here runs on the main thread. */
class YtDlpRepository(context: Context) {
    private val service = YtDlpService(context.applicationContext)
    private val streamCache = StreamUrlCache(SystemClock::elapsedRealtime)

    fun invalidateStreamCache(videoId: String) {
        streamCache.invalidate(videoId)
    }

    /** Forgets every remembered YouTube stream link (kept in memory only). */
    fun clearStreamCache() {
        streamCache.clear()
    }

    suspend fun search(query: String): List<YtDlpSearchResult> = withContext(Dispatchers.IO) {
        val startedAt = SystemClock.elapsedRealtime()
        runCatching { service.search(query) }
            .onFailure { e -> Log.e("YtDlpRepository", "search(query=$query) failed", e) }
            .getOrElse { emptyList() }
            .also { Log.d("YtDlpRepository", "search(query=$query): ${it.size} results in ${SystemClock.elapsedRealtime() - startedAt}ms") }
    }

    // File-name stems of downloads running right now, so a cache/library wipe never deletes a
    // file yt-dlp is still writing (its "<stem>.<ext>.part").
    private val activeStems = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun activeDownloadStems(): Set<String> = activeStems.toSet()

    suspend fun download(
        videoId: String,
        destDir: File,
        destFilenameStem: String,
        formatSelector: String
    ): Result<YtDlpDownloadResult> =
        withContext(Dispatchers.IO) {
            activeStems.add(destFilenameStem)
            try {
                runCatching { service.download(videoId, destDir, destFilenameStem, formatSelector) }
            } finally {
                activeStems.remove(destFilenameStem)
            }
        }

    suspend fun resolveStreamUrl(videoId: String, formatSelector: String): Result<YtDlpStreamResult> =
        withContext(Dispatchers.IO) {
            val cached = streamCache.get(videoId, formatSelector)
            if (cached != null) {
                Log.d("YtDlpRepository", "resolveStreamUrl(videoId=$videoId): cache hit! (0ms)")
                return@withContext Result.success(cached)
            }

            val result = runCatching { service.resolveStreamUrl(videoId, formatSelector) }
            result.onSuccess { stream ->
                if (stream.streamUrl.isNotBlank()) {
                    streamCache.put(videoId, formatSelector, stream)
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
