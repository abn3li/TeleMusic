package com.abn3li.telemusic.repository

import java.io.IOException
import coil.request.ErrorResult
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.CachePolicy
import coil.request.ImageResult
import kotlinx.coroutines.CancellationException
import coil.size.Scale
import android.net.Uri
import com.abn3li.telemusic.data.local.playlistCoverThumbPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Downloads [sourceUrl] once and saves a small local JPEG for list rows to use instead.
 *
 * The metadata enrichment pipeline stores album art at iTunes/Deezer's largest available
 * resolution (600-1000px) so Now Playing's big cover looks sharp - but every list row asking
 * Coil to decode that same source down to a 52dp thumbnail pays real, avoidable CPU cost on
 * every scroll, over and over, for as long as the app runs. Generating one small copy per song,
 * once, and reusing it forever removes that cost from the scroll path entirely.
 */
class ThumbnailGenerator(private val context: Context) {
    private val directory by lazy { File(context.filesDir, "thumbnails").apply { mkdirs() } }

    // A full-size local copy of embedded/Telegram-provided artwork - see saveFullArtwork's own
    // doc for why this exists as a SEPARATE, larger copy rather than reusing the small
    // 160x160 `directory` above for everything.
    private val fullArtDirectory by lazy { File(context.filesDir, "artwork").apply { mkdirs() } }

    private val playlistCoverDirectory by lazy { File(context.filesDir, "playlist_covers").apply { mkdirs() } }

    /**
     * Saves a picked photo as a playlist's cover, once: up to 1200 px for its page, plus the small
     * copy rows and tiles use (playlistCoverThumbPath). A new name each time, so an image cache
     * never shows the previous photo. Returns the large copy's path, or null.
     */
    suspend fun savePlaylistCover(playlistId: Long, photo: Uri): String? = withContext(Dispatchers.IO) {
        val file = File(playlistCoverDirectory, "$playlistId-${System.currentTimeMillis()}.jpg")
        var saved = false
        try {
            // Both copies or neither: a missing small copy would leave rows without a cover.
            saved = saveScaled(photo, file, 1200, 88, Scale.FIT, memoryCache = false) == null &&
                saveScaled(photo, File(playlistCoverThumbPath(file.absolutePath)), 240, 82, memoryCache = false) == null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // An unreadable photo: reported as null below.
        } finally {
            if (!saved) deletePlaylistCover(file.absolutePath)
        }
        if (saved) file.absolutePath else null
    }

    fun deletePlaylistCover(path: String) {
        File(path).delete()
        File(playlistCoverThumbPath(path)).delete()
    }

    /** Decodes [data] at [size] and writes it to [file] as a JPEG. Null once written, else
     * Coil's result saying why not. */
    private suspend fun saveScaled(data: Any, file: File, size: Int, quality: Int, scale: Scale = Scale.FILL, memoryCache: Boolean = true): ImageResult? {
        val request = ImageRequest.Builder(context)
            .data(data)
            .size(size, size)
            .scale(scale)
            .allowHardware(false) // need software pixels to compress to a file below
            .apply { if (!memoryCache) memoryCachePolicy(CachePolicy.DISABLED) }
            .build()
        val result = context.imageLoader.execute(request)
        val bitmap = (result.drawable as? BitmapDrawable)?.bitmap ?: return result
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out) }
        return null
    }

    /** [path] of the saved thumbnail, or null; [retryLater] when it failed only because the
     * network couldn't be reached (offline, timeout) - worth another try, unlike a dead link. */
    class Result(val path: String?, val retryLater: Boolean)

    suspend fun generate(songId: Long, sourceUrl: String): Result = withContext(Dispatchers.IO) {
        val file = File(directory, "$songId.jpg")
        if (file.exists() && file.length() > 0) {
            return@withContext Result(file.absolutePath, false)
        }
        runCatching {
            val failure = saveScaled(sourceUrl, file, 160, 82)
                ?: return@withContext Result(file.absolutePath, false)
            // A bad status (404...) is Coil's HttpException, not an IOException: only a
            // connection that never got through counts as "try again later".
            Result(null, (failure as? ErrorResult)?.throwable is IOException)
        }.getOrElse { Result(null, false) }
    }

    /**
     * Same idea as [generate], but for artwork bytes already in hand - an embedded picture read
     * straight from a local import's own tags, or a Telegram-provided cover already downloaded
     * to disk. No network fetch, so this is called at most once per song (the caller only has
     * these bytes right after import/sync). Always produces the SMALL 160x160 list-row copy -
     * see [saveFullArtwork] for the separate full-size copy Now Playing/the media notification
     * need, which must be generated from the same original [bytes] too, not from this file.
     */
    suspend fun generateFromBytes(songId: Long, bytes: ByteArray): String? =
        decodeAndSave(File(directory, "$songId.jpg"), bytes, targetSize = 160, quality = 82)

    /**
     * Saves embedded/Telegram-provided artwork [bytes] AS-IS - no decode, no resize, no JPEG
     * recompression - for Now Playing's big cover, the blurred backdrop, and the media
     * notification/lock screen (anywhere [displayArtwork]/[displayArtworkUri] is read as the
     * primary, non-list-row artwork source). Whatever resolution the file's own tag embeds it
     * at is exactly what this saves - a decode-resample-recompress round trip through
     * BitmapFactory (an earlier version of this method capped at 1024px "to save space") is
     * lossy on its own even before any resizing, which is exactly why that version still looked
     * soft blown up on a phone screen. [generateFromBytes]'s downsampled 160x160 copy is still
     * the right, deliberately lossy choice for a 44dp list badge - it's just never reused here.
     */
    suspend fun saveFullArtwork(songId: Long, bytes: ByteArray): String? = withContext(Dispatchers.IO) {
        val file = File(fullArtDirectory, "$songId.jpg")
        if (file.exists() && file.length() > 0) {
            return@withContext file.absolutePath
        }
        runCatching {
            file.writeBytes(bytes)
            file.absolutePath
        }.getOrNull()
    }

    private suspend fun decodeAndSave(file: File, bytes: ByteArray, targetSize: Int, quality: Int): String? =
        withContext(Dispatchers.IO) {
            if (file.exists() && file.length() > 0) {
                return@withContext file.absolutePath
            }
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sampleSize = 1
                while (bounds.outWidth / (sampleSize * 2) >= targetSize && bounds.outHeight / (sampleSize * 2) >= targetSize) {
                    sampleSize *= 2
                }
                val bitmap = BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sampleSize }
                ) ?: return@withContext null
                FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out) }
                file.absolutePath
            }.getOrNull()
        }
}
