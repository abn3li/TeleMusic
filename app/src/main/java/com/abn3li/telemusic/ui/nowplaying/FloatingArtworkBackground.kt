package com.abn3li.telemusic.ui.nowplaying

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.LruCache
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.random.Random

private const val BACKDROP_SIZE_PX = 96
private const val KEN_BURNS_STEP_MS = 9_000
private const val KEN_BURNS_FRAME_MS = 33L

private val backdropCache = LruCache<String, ImageBitmap>(8)

/**
 * Full-screen "floating light" backdrop: the artwork, saturated and heavily blurred, drifting
 * slowly in a Ken Burns pan/zoom/rotate behind the player.
 *
 * The blur is done once per song on a tiny 96px copy on the CPU, so each frame the GPU only
 * scales one small texture - no per-frame RenderEffect. The drift only runs while [animate] is
 * true (player expanded and playing); when it turns false the coroutine is cancelled and the
 * image simply holds its current position.
 */
@Composable
fun FloatingArtworkBackground(
    artwork: String?,
    animate: Boolean,
    dim: Float,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var backdrop by remember { mutableStateOf(artwork?.let { backdropCache.get(it) }) }

    LaunchedEffect(artwork) {
        if (artwork.isNullOrEmpty()) {
            backdrop = null
            return@LaunchedEffect
        }
        backdropCache.get(artwork)?.let { backdrop = it; return@LaunchedEffect }
        val built = withContext(Dispatchers.IO) { buildBackdrop(context, artwork) } ?: return@LaunchedEffect
        backdropCache.put(artwork, built)
        backdrop = built
    }

    var scale by remember { mutableFloatStateOf(1.6f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var rotation by remember { mutableFloatStateOf(0f) }

    // Driven at ~30fps instead of every display frame (up to 120Hz): the drift is so slow the
    // difference is invisible, but each frame here repaints the whole screen. withFrameNanos
    // suspends while the app is in the background, so this stops there on its own.
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        while (true) {
            val from = floatArrayOf(scale, offsetX, offsetY, rotation)
            val to = floatArrayOf(
                1.5f + Random.nextFloat() * 0.5f,
                Random.nextFloat() * 0.3f - 0.15f,
                Random.nextFloat() * 0.24f - 0.12f,
                Random.nextFloat() * 16f - 8f
            )
            val startNanos = withFrameNanos { it }
            var fraction = 0f
            while (fraction < 1f) {
                val now = withFrameNanos { it }
                fraction = ((now - startNanos) / 1_000_000f / KEN_BURNS_STEP_MS).coerceIn(0f, 1f)
                val eased = FastOutSlowInEasing.transform(fraction)
                scale = from[0] + (to[0] - from[0]) * eased
                offsetX = from[1] + (to[1] - from[1]) * eased
                offsetY = from[2] + (to[2] - from[2]) * eased
                rotation = from[3] + (to[3] - from[3]) * eased
                delay(KEN_BURNS_FRAME_MS)
            }
        }
    }

    val animatedDim by animateFloatAsState(targetValue = dim, animationSpec = tween(350), label = "backdropDim")

    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF2A2A2E), Color(0xFF0B0B0C))))
    ) {
        Crossfade(targetState = backdrop, animationSpec = tween(700), label = "backdropCrossfade") { image ->
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.High,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offsetX * size.width
                            translationY = offsetY * size.height
                            rotationZ = rotation
                        }
                )
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = animatedDim }
                .background(Color.Black)
        )
    }
}

private suspend fun buildBackdrop(context: Context, artwork: String): ImageBitmap? {
    val request = ImageRequest.Builder(context)
        .data(artwork)
        .size(BACKDROP_SIZE_PX * 2)
        .allowHardware(false)
        .build()
    val result = context.imageLoader.execute(request) as? SuccessResult ?: return null
    val source = result.drawable.toBitmap(BACKDROP_SIZE_PX, BACKDROP_SIZE_PX, Bitmap.Config.ARGB_8888)

    val graded = Bitmap.createBitmap(BACKDROP_SIZE_PX, BACKDROP_SIZE_PX, Bitmap.Config.ARGB_8888)
    Canvas(graded).apply {
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.9f) })
        }
        drawBitmap(source, 0f, 0f, paint)
        drawColor(0x4D000000)
    }

    val pixels = IntArray(BACKDROP_SIZE_PX * BACKDROP_SIZE_PX)
    graded.getPixels(pixels, 0, BACKDROP_SIZE_PX, 0, 0, BACKDROP_SIZE_PX, BACKDROP_SIZE_PX)
    repeat(2) { boxBlur(pixels, BACKDROP_SIZE_PX, BACKDROP_SIZE_PX, radius = 3) }
    graded.setPixels(pixels, 0, BACKDROP_SIZE_PX, 0, 0, BACKDROP_SIZE_PX, BACKDROP_SIZE_PX)
    return graded.asImageBitmap()
}

// Separable box blur (horizontal then vertical pass) with edge clamping. Repeated passes of this
// approximate a gaussian; on a 96x96 image it costs well under a millisecond.
private fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int) {
    val temp = IntArray(pixels.size)
    blurPass(pixels, temp, width, height, radius, horizontal = true)
    blurPass(temp, pixels, width, height, radius, horizontal = false)
}

private fun blurPass(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int, horizontal: Boolean) {
    val lineCount = if (horizontal) height else width
    val lineLength = if (horizontal) width else height
    val window = radius * 2 + 1
    for (line in 0 until lineCount) {
        var a = 0; var r = 0; var g = 0; var b = 0
        fun pixelAt(i: Int): Int {
            val clamped = i.coerceIn(0, lineLength - 1)
            return if (horizontal) src[line * width + clamped] else src[clamped * width + line]
        }
        for (i in -radius..radius) {
            val p = pixelAt(i)
            a += p ushr 24; r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
        }
        for (i in 0 until lineLength) {
            val out = ((a / window) shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
            if (horizontal) dst[line * width + i] = out else dst[i * width + line] = out
            val leaving = pixelAt(i - radius)
            val entering = pixelAt(i + radius + 1)
            a += (entering ushr 24) - (leaving ushr 24)
            r += ((entering shr 16) and 0xFF) - ((leaving shr 16) and 0xFF)
            g += ((entering shr 8) and 0xFF) - ((leaving shr 8) and 0xFF)
            b += (entering and 0xFF) - (leaving and 0xFF)
        }
    }
}
