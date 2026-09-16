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

/** The result of [YtDlpService.resolveStreamUrl] - same metadata [YtDlpDownloadResult] carries,
 * but [streamUrl] is a short-lived googlevideo.com link, never a local file path - nothing was
 * written to disk to produce it. */
data class YtDlpStreamResult(
    val title: String,
    val artist: String,
    val durationSeconds: Int,
    val thumbnailUrl: String?,
    val streamUrl: String
)

/** A playlist's own metadata, resolved by [YtDlpService.fetchPlaylistMetadata] from a pasted URL
 * - backs Discovery's "Import playlist" feature. [playlistId] is a bare YouTube playlist id (no
 * "VL" prefix), see YouTubeDownloadViewModel.importPlaylist for where that prefix gets added to
 * turn it into a real Innertube browseId. */
data class YtDlpPlaylistMetadata(
    val playlistId: String,
    val title: String,
    val subtitle: String?,
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

    fun search(query: String, limit: Int = 12): List<YtDlpSearchResult> {
        val results = bridge.callAttr("search", query, limit)
        return results.asList().map { it.toSearchResult() }
    }

    fun download(videoId: String, destDir: File, destFilenameStem: String, formatSelector: String): YtDlpDownloadResult {
        val result = bridge.callAttr("download", videoId, destDir.absolutePath, destFilenameStem, formatSelector)
        return result.toDownloadResult()
    }

    /** Resolves a playable stream URL for [videoId] without downloading anything - backs the
     * "Play" button (see resolve_stream_url's own doc in ytdlp_bridge.py). */
    fun resolveStreamUrl(videoId: String, formatSelector: String): YtDlpStreamResult {
        val result = bridge.callAttr("resolve_stream_url", videoId, formatSelector)
        return result.toStreamResult()
    }

    /** Resolves a pasted playlist URL into its id/title/uploader/thumbnail, or null if yt-dlp
     * couldn't recognize it as a playlist - see fetch_playlist_metadata's own doc. */
    fun fetchPlaylistMetadata(url: String): YtDlpPlaylistMetadata? {
        val result = bridge.callAttr("fetch_playlist_metadata", url)
        // Chaquopy's PyObject has no direct "is this Python None" check - str(None) is the
        // reliable way to tell it apart from a real dict result.
        if (result.toString() == "None") return null
        return result.toPlaylistResult()
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

    private fun PyObject.toStreamResult(): YtDlpStreamResult {
        val map = stringKeyedMap()
        return YtDlpStreamResult(
            title = map["title"]?.toString() ?: "Unknown title",
            artist = map["artist"]?.toString() ?: "Unknown artist",
            durationSeconds = map["duration"]?.toString()?.toIntOrNull() ?: 0,
            thumbnailUrl = map["thumbnail"]?.toString(),
            streamUrl = map["url"]?.toString().orEmpty()
        )
    }

    private fun PyObject.toPlaylistResult(): YtDlpPlaylistMetadata {
        val map = stringKeyedMap()
        return YtDlpPlaylistMetadata(
            playlistId = map["id"]?.toString().orEmpty(),
            title = map["title"]?.toString() ?: "Unknown playlist",
            subtitle = map["subtitle"]?.toString(),
            thumbnailUrl = map["thumbnail"]?.toString()
        )
    }
}
