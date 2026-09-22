package com.abn3li.telemusic.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import coil.imageLoader
import coil.request.ImageRequest
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

    suspend fun generate(songId: Long, sourceUrl: String): String? = withContext(Dispatchers.IO) {
        val file = File(directory, "$songId.jpg")
        if (file.exists() && file.length() > 0) {
            return@withContext file.absolutePath
        }
        runCatching {
            val request = ImageRequest.Builder(context)
                .data(sourceUrl)
                .size(160, 160)
                .allowHardware(false) // need software pixels to compress to a file below
                .build()
            val bitmap = (context.imageLoader.execute(request).drawable as? BitmapDrawable)?.bitmap
                ?: return@withContext null
            FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out) }
            file.absolutePath
        }.getOrNull()
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
