package com.example.tgmusic.repository

import android.content.Context
import android.graphics.Bitmap
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

    suspend fun generate(songId: Long, sourceUrl: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = ImageRequest.Builder(context)
                .data(sourceUrl)
                .size(160, 160)
                .allowHardware(false) // need software pixels to compress to a file below
                .build()
            val bitmap = (context.imageLoader.execute(request).drawable as? BitmapDrawable)?.bitmap
                ?: return@withContext null
            val file = File(directory, "$songId.jpg")
            FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out) }
            file.absolutePath
        }.getOrNull()
    }
}
