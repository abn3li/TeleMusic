package com.abn3li.telemusic.data.download

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Coroutine-friendly front for [YtDlpService] - every call in there blocks on network/disk I/O
 * (real yt-dlp doing real work), so this is the only place that's ever touched from a ViewModel;
 * nothing here runs on the main thread. */
class YtDlpRepository(context: Context) {
    private val service = YtDlpService(context.applicationContext)

    suspend fun search(query: String): List<YtDlpSearchResult> = withContext(Dispatchers.IO) {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        runCatching { service.search(query) }
            .onFailure { e -> Log.e("YtDlpRepository", "search(query=$query) failed", e) }
            .getOrElse { emptyList() }
            .also { Log.d("YtDlpRepository", "search(query=$query): ${it.size} results in ${android.os.SystemClock.elapsedRealtime() - startedAt}ms") }
    }

    suspend fun download(
        videoId: String,
        destDir: java.io.File,
        destFilenameStem: String,
        formatSelector: String
    ): Result<YtDlpDownloadResult> =
        withContext(Dispatchers.IO) {
            runCatching { service.download(videoId, destDir, destFilenameStem, formatSelector) }
        }

    suspend fun resolveStreamUrl(videoId: String, formatSelector: String): Result<YtDlpStreamResult> =
        withContext(Dispatchers.IO) {
            runCatching { service.resolveStreamUrl(videoId, formatSelector) }
        }

    suspend fun searchPlaylists(query: String): List<YtDlpPlaylistResult> = withContext(Dispatchers.IO) {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        runCatching { service.searchPlaylists(query) }
            .onFailure { e -> Log.e("YtDlpRepository", "searchPlaylists(query=$query) failed", e) }
            .getOrElse { emptyList() }
            .also { Log.d("YtDlpRepository", "searchPlaylists(query=$query): ${it.size} results in ${android.os.SystemClock.elapsedRealtime() - startedAt}ms") }
    }

    suspend fun searchArtists(query: String): List<YtDlpArtistResult> = withContext(Dispatchers.IO) {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        runCatching { service.searchArtists(query) }
            .onFailure { e -> Log.e("YtDlpRepository", "searchArtists(query=$query) failed", e) }
            .getOrElse { emptyList() }
            .also { Log.d("YtDlpRepository", "searchArtists(query=$query): ${it.size} results in ${android.os.SystemClock.elapsedRealtime() - startedAt}ms") }
    }
}
