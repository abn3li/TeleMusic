package com.abn3li.telemusic.ui.navigation

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.abn3li.telemusic.ui.nowplaying.cardColorOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How strongly the playing cover's colour shows at the top of the main pages (0 = off). */
private const val GLOW_STRENGTH = 0.55f

/** The playing song's cover colour, shown softly at the top of Home, Discover and Library.
 * Worked out once per cover; it changes only when the song does, so nothing runs while idle. */
internal object CoverGlow {
    val color = Animatable(Color.Transparent)
}

private val glowCache = LruCache<String, Int>(64)

/** Keeps [CoverGlow] on [artwork]'s colour; transparent when nothing plays or there's no cover. */
@Composable
internal fun CoverGlowEffect(artwork: String?, light: Boolean) {
    val context = LocalContext.current
    LaunchedEffect(artwork, light) {
        val key = "$light|$artwork"
        val colour = if (artwork.isNullOrEmpty()) null
        else glowCache.get(key) ?: withContext(Dispatchers.IO) { glowColour(context, artwork, light) }?.also { glowCache.put(key, it) }
        CoverGlow.color.animateTo(colour?.let { Color(it) } ?: Color.Transparent, tween(900))
    }
}

/** Draws the glow: the colour at the top centre, fading out over the upper part of the page. */
internal fun Modifier.coverGlow(): Modifier = drawBehind {
    val colour = CoverGlow.color.value
    if (colour.alpha <= 0f) return@drawBehind
    val radius = size.width * 1.05f
    // A circle stretched taller, so the colour reaches further down than across. Kept inside
    // this element: the status bar strip draws its own matching slice of the same glow.
    clipRect { scale(1f, 1.25f, pivot = Offset(size.width / 2f, 0f)) {
        drawRect(
            Brush.radialGradient(
                listOf(colour.copy(alpha = colour.alpha * GLOW_STRENGTH), Color.Transparent),
                center = Offset(size.width / 2f, 0f),
                radius = radius
            ),
            size = Size(size.width, radius)
        )
    } }
}

private suspend fun glowColour(context: Context, artwork: String, light: Boolean): Int? = runCatching {
    val request = ImageRequest.Builder(context).data(artwork).size(48).allowHardware(false).build()
    val result = context.imageLoader.execute(request) as? SuccessResult ?: return null
    val bitmap = result.drawable.toBitmap(24, 24, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(24 * 24)
    bitmap.getPixels(pixels, 0, 24, 0, 0, 24, 24)
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(cardColorOf(pixels), hsl)
    // On a dark page a deep, rich tone; on a light page a pale wash of the same colour.
    if (light) { hsl[1] = hsl[1].coerceAtMost(0.55f); hsl[2] = 0.78f }
    else { hsl[1] = (hsl[1] * 1.15f).coerceAtMost(0.7f); hsl[2] = hsl[2].coerceIn(0.3f, 0.4f) }
    ColorUtils.HSLToColor(hsl)
}.getOrNull()
