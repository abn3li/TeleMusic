package com.abn3li.telemusic.data.download

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File

/** One song found by [YtDlpService.search] - not yet downloaded. */
data class YtDlpSearchResult(
    val videoId: String,
    val title: String,
    val artist: String,
    val durationSeconds: Int,
    val thumbnailUrl: String?
)

/** The SongEntity.telegramMessageId a given video downloads as - a stable hash of its own video
 * id (not the file path, which doesn't exist until after the download completes) so re-tapping
 * the same search result twice always resolves to the same row instead of duplicating. Prefixed
 * so a video id can never collide with LocalAudioFile.stableSongId()'s own hash space. */
fun ytDlpStableSongId(videoId: String): Long = -kotlin.math.abs("yt:$videoId".hashCode().toLong())

/** The real, on-disk result of [YtDlpService.download]. */
data class YtDlpDownloadResult(
    val filePath: String,
    val title: String,
    val artist: String,
    val durationSeconds: Int,
    val thumbnailUrl: String?
)

/**
 * Kotlin-side wrapper around app/src/main/python/ytdlp_bridge.py, which does the actual work via
 * real yt-dlp (Unlicense/public domain) running on Chaquopy's bundled Python interpreter - see
 * that file's own doc for why this, not the GPL-3.0 youtubedl-android wrapper, is what this repo
 * uses to stay MIT. Every call here blocks on network/disk I/O - always invoke from a background
 * dispatcher (see YtDlpRepository), never from a composable or the main thread directly.
 */
class YtDlpService(private val context: Context) {
    // Deliberately NOT started in init{} - starting Chaquopy's Python interpreter is a real
    // (roughly half-a-second-plus) cost, and doing it eagerly here meant every single app launch
    // paid it inside TgMusicApp.onCreate() on the MAIN thread, whether or not that session ever
    // opens the YouTube download screen. Both callers below already run on Dispatchers.IO (see
    // YtDlpRepository), so paying that cost lazily, on first real use, on a background thread,
    // costs nothing for everyone else and doesn't block the UI thread for the one caller who
    // does use it either.
    private val bridge: PyObject by lazy {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context))
        Python.getInstance().getModule("ytdlp_bridge")
    }

    fun search(query: String, limit: Int = 15): List<YtDlpSearchResult> {
        val results = bridge.callAttr("search", query, limit)
        return results.asList().map { it.toSearchResult() }
    }

    fun download(videoId: String, destDir: File, destFilenameStem: String, formatSelector: String): YtDlpDownloadResult {
        val result = bridge.callAttr("download", videoId, destDir.absolutePath, destFilenameStem, formatSelector)
        return result.toDownloadResult()
    }

    private fun PyObject.stringKeyedMap(): Map<String, PyObject?> =
        asMap().entries.associate { it.key.toString() to it.value }

    private fun PyObject.toSearchResult(): YtDlpSearchResult {
        val map = stringKeyedMap()
        return YtDlpSearchResult(
            videoId = map["id"]?.toString().orEmpty(),
            title = map["title"]?.toString() ?: "Unknown title",
            artist = map["artist"]?.toString() ?: "Unknown artist",
            durationSeconds = map["duration"]?.toString()?.toIntOrNull() ?: 0,
            thumbnailUrl = map["thumbnail"]?.toString()
        )
    }

    private fun PyObject.toDownloadResult(): YtDlpDownloadResult {
        val map = stringKeyedMap()
        return YtDlpDownloadResult(
            filePath = map["path"]?.toString().orEmpty(),
            title = map["title"]?.toString() ?: "Unknown title",
            artist = map["artist"]?.toString() ?: "Unknown artist",
            durationSeconds = map["duration"]?.toString()?.toIntOrNull() ?: 0,
            thumbnailUrl = map["thumbnail"]?.toString()
        )
    }
}
