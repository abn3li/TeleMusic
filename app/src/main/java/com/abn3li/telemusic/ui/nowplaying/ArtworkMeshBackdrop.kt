package com.abn3li.telemusic.ui.nowplaying

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Gives the Now Playing screen a dynamic backdrop made of the current track's OWN colours in
 * roughly their own proportions, rather than a quantiser's idea of the "interesting" colour in
 * the cover (which is how a sleeve that's nine-tenths black with a red stripe becomes a red
 * screen). The sleeve is averaged into a small grid of means, then the grid below its top row
 * is cyclically shifted (and sometimes mirrored) so a face/logo/horizon doesn't reappear as a
 * literal upside-down reflection of itself - see [rotatedBelowSeam].
 */
@Immutable
class ArtworkMesh internal constructor(internal val image: ImageBitmap)

/** The mesh for the artwork at [imageUrl], or null until one has been read. */
@Composable
fun rememberArtworkMesh(imageUrl: String?, artPx: Int = 512): ArtworkMesh? {
    val context = LocalContext.current
    // Seeded from the cache so a cover seen before is on colour in its first frame.
    var mesh by remember(imageUrl) { mutableStateOf(imageUrl?.let(meshCache::get)) }

    LaunchedEffect(imageUrl) {
        if (imageUrl == null || mesh != null) return@LaunchedEffect
        repeat(MESH_ATTEMPTS) { attempt ->
            if (attempt > 0) kotlinx.coroutines.delay(MESH_RETRY_DELAY_MS)
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val request = ImageRequest.Builder(context)
                        .data(imageUrl)
                        .size(MESH_PX, MESH_PX)
                        .allowHardware(false) // the sleeve has to be read back pixel by pixel
                        .build()
                    val result = context.imageLoader.execute(request) as? SuccessResult
                    (result?.drawable as? BitmapDrawable)?.bitmap
                }.getOrNull()
            }
            if (bitmap != null) {
                val found = withContext(Dispatchers.Default) { meshOf(bitmap, imageUrl.hashCode()) }
                if (found != null) {
                    meshCache[imageUrl] = found
                    mesh = found
                }
                return@LaunchedEffect
            }
        }
    }
    return mesh
}

/**
 * The player's backdrop: [mesh] hung from where the artwork stops, and stretched over
 * everything below it. See [meshOf] for how the mesh itself is built.
 */
@Composable
fun ArtworkMeshBackdrop(
    mesh: ArtworkMesh?,
    modifier: Modifier = Modifier,
    seam: Dp = 0.dp,
    fallback: Color = FallbackBackdrop,
) {
    var shown by remember { mutableStateOf(mesh) }
    var incoming by remember { mutableStateOf<ArtworkMesh?>(null) }
    val fade = remember { Animatable(0f) }

    LaunchedEffect(mesh) {
        val next = mesh ?: return@LaunchedEffect
        incoming?.let { shown = it }
        incoming = null
        val current = shown
        if (next === current) return@LaunchedEffect
        if (current == null) {
            shown = next
            return@LaunchedEffect
        }
        incoming = next
        fade.snapTo(0f)
        fade.animateTo(1f, tween(MESH_FADE_MS, easing = FastOutSlowInEasing))
        shown = next
        incoming = null
    }

    // Hoisted so the same Brush instance (and the Shader it lazily builds and caches
    // internally) is reused across every frame of the expand/collapse animation, instead of a
    // fresh Brush - and fresh Shader - being built on every single draw call. That rebuild cost
    // scales with how much of the canvas is actually visible, which is exactly why it showed up
    // as the draw cost climbing through the back half of the expand animation (more of this
    // full-bleed Canvas becomes on-screen as the sheet slides up) rather than as one bad frame.
    val darkeningGradient = remember {
        Brush.verticalGradient(
            colors = listOf(
                Color.Black.copy(alpha = 0.06f),
                Color.Black.copy(alpha = 0.30f),
            ),
        )
    }

    // No Modifier.blur() here - a RenderEffect blur is re-evaluated on every frame the layer
    // is composited, and this backdrop sits inside NowPlayingContent's own actively-animating
    // graphicsLayer (translationY/alpha both moving every frame of the swipe-down-to-dismiss
    // drag and the expand-from-mini-player animation). Re-blurring a full-screen layer on every
    // one of those frames is real, avoidable GPU cost precisely during the gesture that most
    // needs to feel smooth, and it also meant the backdrop's alpha fading out during a collapse
    // dissolved through a blurry haze rather than cleanly - the mesh's OWN texture is already
    // smoothed on the CPU in meshOf()'s resampled() step, which is what actually makes the
    // gradient soft in the first place - a GPU blur on top of that would be pure polish, so
    // nothing here depends on it.
    Canvas(
        modifier = modifier.fillMaxSize(),
    ) {
        val seamY = seam.toPx().coerceIn(0f, size.height)
        // The fallback fill only needs to be visible before any mesh has ever loaded (or if one
        // never does) - once shown/incoming exist they already paint over the full canvas
        // themselves, so painting the fallback underneath every frame regardless was pure
        // overdraw across the whole (growing) visible area for no visual difference.
        if (shown == null && incoming == null) {
            drawRect(fallback)
        }
        shown?.let { drawMesh(it, seamY, alpha = 1f) }
        incoming?.let { drawMesh(it, seamY, alpha = fade.value) }

        drawRect(brush = darkeningGradient)
    }
}

private fun DrawScope.drawMesh(mesh: ArtworkMesh, seamY: Float, alpha: Float) {
    if (alpha <= 0.001f) return
    val image = mesh.image
    val width = size.width.roundToInt()

    if (seamY > 0.5f) {
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, 1),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(width, seamY.roundToInt()),
            alpha = alpha,
            filterQuality = FilterQuality.Low,
        )
    }

    drawImage(
        image = image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset(0, seamY.roundToInt()),
        dstSize = IntSize(width, (size.height - seamY).roundToInt()),
        alpha = alpha,
        filterQuality = FilterQuality.Low,
    )
}

private val FallbackBackdrop = Color(0xFF121212)

private val meshCache = object : LinkedHashMap<String, ArtworkMesh>(0, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<String, ArtworkMesh>) = size > MESH_CACHE_ENTRIES
}

private const val MESH_CACHE_ENTRIES = 64
private const val MESH_PX = 120
private const val MESH_ATTEMPTS = 4
private const val MESH_RETRY_DELAY_MS = 1_500L
private const val MESH_GRID = 6
private const val MESH_TEX = 32
private const val MESH_FADE_MS = 900
private const val MESH_SAMPLE = 128

/**
 * Averages [source] into the mesh texture, flipped top to bottom and then rotated sideways.
 * Row 0 of the grid is the sleeve's own bottom edge; later rows run back up into it, which is
 * the order [ArtworkMeshBackdrop] draws down the screen. A mean per cell, not a quantised
 * swatch - what sits under the artwork should be what a blur of the artwork would leave there.
 */
private fun meshOf(source: Bitmap, seed: Int): ArtworkMesh? {
    val width = source.width
    val height = source.height
    if (width < 1 || height < 1) return null

    val cols = MESH_GRID.coerceAtMost(width)
    val rows = MESH_GRID.coerceAtMost(height)
    val rowStep = (height / MESH_SAMPLE).coerceAtLeast(1)
    val colStep = (width / MESH_SAMPLE).coerceAtLeast(1)

    val cells = rows * cols
    val red = LongArray(cells)
    val green = LongArray(cells)
    val blue = LongArray(cells)
    val count = IntArray(cells)

    val line = IntArray(width)
    var y = 0
    while (y < height) {
        source.getPixels(line, 0, width, 0, y, width, 1)
        val rowBase = ((height - 1 - y) * rows / height) * cols
        var x = 0
        while (x < width) {
            val cell = rowBase + x * cols / width
            val pixel = line[x]
            red[cell] += (pixel shr 16) and 0xFF
            green[cell] += (pixel shr 8) and 0xFF
            blue[cell] += pixel and 0xFF
            count[cell]++
            x += colStep
        }
        y += rowStep
    }

    val grid = IntArray(cells) { cell ->
        val n = count[cell].coerceAtLeast(1)
        argb((red[cell] / n).toInt(), (green[cell] / n).toInt(), (blue[cell] / n).toInt()).lifted()
    }
    val texels = grid.rotatedBelowSeam(cols, rows, seed).resampled(cols, rows, MESH_TEX)
    val bitmap = Bitmap.createBitmap(texels, MESH_TEX, MESH_TEX, Bitmap.Config.ARGB_8888)
    return ArtworkMesh(bitmap.asImageBitmap())
}

/**
 * Every row but row 0 (the seam row, kept literal so there's no visible join), cyclically
 * shifted by the same random amount and sometimes mirrored. A flip alone reads as a mirror on
 * any cover with real structure to it (a face, a logo); a cyclic shift keeps every cell's exact
 * neighbours while moving where on screen the gradient between them sits.
 */
private fun IntArray.rotatedBelowSeam(cols: Int, rows: Int, seed: Int): IntArray {
    if (rows <= 1) return this
    val random = Random(seed)
    val mirror = random.nextBoolean()
    val shift = random.nextInt(cols)
    val out = copyOf()
    for (row in 1 until rows) {
        val base = row * cols
        for (x in 0 until cols) {
            val src = if (mirror) cols - 1 - x else x
            out[base + x] = this[base + (src + shift) % cols]
        }
    }
    return out
}

private fun IntArray.resampled(cols: Int, rows: Int, size: Int): IntArray {
    val out = IntArray(size * size)
    for (ty in 0 until size) {
        val fy = (ty + 0.5f) / size * rows - 0.5f
        val y0 = floor(fy).toInt().coerceIn(0, rows - 1)
        val y1 = (y0 + 1).coerceAtMost(rows - 1)
        val wy = smoothstep(fy - y0)
        for (tx in 0 until size) {
            val fx = (tx + 0.5f) / size * cols - 0.5f
            val x0 = floor(fx).toInt().coerceIn(0, cols - 1)
            val x1 = (x0 + 1).coerceAtMost(cols - 1)
            val wx = smoothstep(fx - x0)
            val top = lerpArgb(this[y0 * cols + x0], this[y0 * cols + x1], wx)
            val bottom = lerpArgb(this[y1 * cols + x0], this[y1 * cols + x1], wx)
            out[ty * size + tx] = lerpArgb(top, bottom, wy)
        }
    }
    return out
}

private fun smoothstep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

private fun lerpArgb(from: Int, to: Int, t: Float): Int {
    if (t <= 0f) return from
    if (t >= 1f) return to
    fun channel(shift: Int): Int {
        val a = (from shr shift) and 0xFF
        val b = (to shr shift) and 0xFF
        return (a + ((b - a) * t)).roundToInt().coerceIn(0, 255)
    }
    return argb(channel(16), channel(8), channel(0))
}

private fun argb(red: Int, green: Int, blue: Int): Int =
    (0xFF shl 24) or (red shl 16) or (green shl 8) or blue

/** Boost saturation a touch (averaging greys a block of pixels down) and floor lightness. */
private fun Int.lifted(): Int {
    val hsl = FloatArray(3).also { ColorUtils.colorToHSL(this, it) }
    hsl[1] = (hsl[1] * MESH_VIBRANCE).coerceAtMost(1f)
    hsl[2] = hsl[2].coerceAtLeast(MESH_FLOOR)
    return ColorUtils.HSLToColor(hsl)
}

private const val MESH_VIBRANCE = 1.12f
private const val MESH_FLOOR = 0.045f
