package com.abn3li.telemusic.ui.nowplaying

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One flat colour standing in for a track's artwork - just the average colour, darkened enough
 * to stay readable under fixed white text, not a copy of the artwork itself.
 */
@Composable
fun rememberArtworkColor(imageUrl: String?): Color? {
    val context = LocalContext.current
    var color by remember(imageUrl) { mutableStateOf(imageUrl?.let(dominantColorCache::get)) }

    LaunchedEffect(imageUrl) {
        if (imageUrl == null || color != null) return@LaunchedEffect
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(context)
                    .data(imageUrl)
                    .size(48, 48)
                    .allowHardware(false)
                    .build()
                val result = context.imageLoader.execute(request) as? SuccessResult
                (result?.drawable as? BitmapDrawable)?.bitmap
            }.getOrNull()
        }
        if (bitmap != null) {
            val found = withContext(Dispatchers.Default) { averageColorOf(bitmap) }
            dominantColorCache[imageUrl] = found
            color = found
        }
    }
    return color
}

private fun averageColorOf(bitmap: Bitmap): Color {
    val width = bitmap.width
    val height = bitmap.height
    var r = 0L
    var g = 0L
    var b = 0L
    var n = 0L
    val line = IntArray(width)
    for (y in 0 until height) {
        bitmap.getPixels(line, 0, width, 0, y, width, 1)
        for (x in 0 until width) {
            val pixel = line[x]
            r += (pixel shr 16) and 0xFF
            g += (pixel shr 8) and 0xFF
            b += pixel and 0xFF
            n++
        }
    }
    val count = n.coerceAtLeast(1)
    val avgArgb = (0xFF shl 24) or
        ((r / count).toInt() shl 16) or
        ((g / count).toInt() shl 8) or
        (b / count).toInt()

    val hsl = FloatArray(3).also { ColorUtils.colorToHSL(avgArgb, it) }
    hsl[1] = (hsl[1] * 1.15f).coerceAtMost(1f)
    hsl[2] = hsl[2].coerceIn(0.12f, 0.32f)
    return Color(ColorUtils.HSLToColor(hsl))
}

private val dominantColorCache = object : LinkedHashMap<String, Color>(0, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<String, Color>) = size > 64
}
