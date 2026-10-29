package com.abn3li.telemusic.data.download

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.File

/** Coroutine-friendly front for [YtDlpService] - every call in there blocks on network/disk I/O
 * (real yt-dlp doing real work), so this is the only place that's ever touched from a ViewModel;
 * nothing here runs on the main thread. */
class YtDlpRepository(context: Context) {
    private val service = YtDlpService(context.applicationContext)
    private val streamCache = StreamUrlCache(SystemClock::elapsedRealtime)

    /** Readies yt-dlp off the main thread, once, a little after launch, so the first song
     * played doesn't wait for Python to start. */
    suspend fun warmUp() = withContext(Dispatchers.IO) {
        if (!warmUpStarted.compareAndSet(false, true)) return@withContext
        kotlinx.coroutines.delay(WARM_UP_DELAY_MS)
        val startedAt = SystemClock.elapsedRealtime()
        runCatching { service.warmUp() }
            .onSuccess { Log.d("YtDlpRepository", "warmed up in ${SystemClock.elapsedRealtime() - startedAt}ms") }
            .onFailure { Log.w("YtDlpRepository", "warm-up failed", it) }
    }

    private val warmUpStarted = java.util.concurrent.atomic.AtomicBoolean(false)

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

    // Lookups running now: a second ask for the same song (the next song's pre-fetch, then
    // playing it) waits for the first instead of running yt-dlp twice.
    private val lookups = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Result<YtDlpStreamResult>>>()
    private val lookupScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    suspend fun resolveStreamUrl(videoId: String, formatSelector: String): Result<YtDlpStreamResult> {
        streamCache.get(videoId, formatSelector)?.let { cached ->
            Log.d("YtDlpRepository", "resolveStreamUrl(videoId=$videoId): cache hit! (0ms)")
            com.abn3li.telemusic.playback.StreamVideoIds.remember(cached.streamUrl, videoId)
            return Result.success(cached)
        }
        val key = "$videoId|$formatSelector"
        // Not tied to the caller: a pre-fetch given up on leaves the lookup running for the play.
        val lookup = lookups.computeIfAbsent(key) {
            lookupScope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                try {
                    runCatching { service.resolveStreamUrl(videoId, formatSelector) }.onSuccess { stream ->
                        if (stream.streamUrl.isNotBlank()) {
                            com.abn3li.telemusic.playback.StreamVideoIds.remember(stream.streamUrl, videoId)
                            streamCache.put(videoId, formatSelector, stream)
                        }
                    }
                } finally {
                    lookups.remove(key)
                }
            }
        }
        return lookup.await()
    }

    private companion object {
        // Past the launch itself, so the first screen draws without it.
        const val WARM_UP_DELAY_MS = 3_000L
    }

    suspend fun fetchPlaylistMetadata(url: String): YtDlpPlaylistMetadata? = withContext(Dispatchers.IO) {
        runCatching { service.fetchPlaylistMetadata(url) }
            .onFailure { e -> Log.e("YtDlpRepository", "fetchPlaylistMetadata(url=$url) failed", e) }
            .getOrNull()
    }
}
