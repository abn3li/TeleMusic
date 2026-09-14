package com.abn3li.telemusic.data.download

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Coroutine-friendly front for [YtDlpService] - every call in there blocks on network/disk I/O
 * (real yt-dlp doing real work), so this is the only place that's ever touched from a ViewModel;
 * nothing here runs on the main thread. */
class YtDlpRepository(context: Context) {
    private val service = YtDlpService(context.applicationContext)

    suspend fun search(query: String): List<YtDlpSearchResult> = withContext(Dispatchers.IO) {
        runCatching { service.search(query) }.getOrElse { emptyList() }
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
}
