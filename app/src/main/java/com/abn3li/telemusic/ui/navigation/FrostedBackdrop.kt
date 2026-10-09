package com.abn3li.telemusic.ui.navigation

import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.toSize
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.abn3li.telemusic.ui.theme.LocalPalette
import kotlin.math.ceil
import java.util.Random

private val FrostedBlurRadius = 24.dp

/** Fade only the material over artwork, leaving the header's controls fully visible. */
@Composable
internal fun BoxScope.FrostedHeaderBackground(backdrop: FrostedBackdrop, progress: () -> Float) {
    Box(
        Modifier.matchParentSize()
            .graphicsLayer { alpha = progress().coerceIn(0f, 1f) }
            .frostedSurface(backdrop, shape = RectangleShape, drawBorder = false)
    )
}

/** The page is recorded once; each small glass surface reads the same display list. */
internal class FrostedBackdrop {
    val source = if (Build.VERSION.SDK_INT >= 31) NativeFrostedSource() else null
    var position = Offset.Zero
    var ready by mutableStateOf(false)
}

@Composable
internal fun rememberFrostedBackdrop(): FrostedBackdrop {
    val backdrop = remember { FrostedBackdrop() }
    DisposableEffect(backdrop) {
        onDispose { if (Build.VERSION.SDK_INT >= 31) backdrop.source?.node?.discardDisplayList() }
    }
    return backdrop
}

internal fun Modifier.frostedBackdropSource(backdrop: FrostedBackdrop, background: Color): Modifier =
    onGloballyPositioned { backdrop.position = it.positionInRoot() }
        .drawWithContent {
            val source = backdrop.source
            if (Build.VERSION.SDK_INT >= 31 && source != null && drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
                source.record(this, background)
                // A fading header may be recorded before the page's first draw. Invalidate its
                // initial solid fallback once, so the cached layer starts sampling the page.
                if (!backdrop.ready) backdrop.ready = true
            } else {
                drawContent()
            }
        }

@RequiresApi(31)
internal class NativeFrostedSource {
    val node = RenderNode("Page backdrop")
    var background = Color.Black
        private set

    fun record(scope: ContentDrawScope, background: Color) = with(scope) {
        this@NativeFrostedSource.background = background
        val width = size.width.toInt().coerceAtLeast(1)
        val height = size.height.toInt().coerceAtLeast(1)
        node.setPosition(0, 0, width, height)
        val destination = drawContext.canvas
        val recording = node.beginRecording(width, height)
        try {
            drawContext.canvas = Canvas(recording)
            // A real page colour keeps transparent gaps from turning the glass into sharp text.
            drawRect(background)
            drawContent()
        } finally {
            drawContext.canvas = destination
            node.endRecording()
        }
        destination.nativeCanvas.drawRenderNode(node)
    }
}

@Composable
internal fun Modifier.frostedSurface(
    backdrop: FrostedBackdrop,
    shape: Shape = RoundedCornerShape(percent = 50),
    drawBorder: Boolean = true
): Modifier {
    val palette = LocalPalette.current
    val light = palette.isLight
    var position by remember { mutableStateOf(Offset.Zero) }
    val surface = remember { if (Build.VERSION.SDK_INT >= 31) NativeFrostedSurface() else null }
    DisposableEffect(surface) {
        onDispose { if (Build.VERSION.SDK_INT >= 31) surface?.node?.discardDisplayList() }
    }
    return onGloballyPositioned { position = it.positionInRoot() }
        .clip(shape)
        .drawWithContent {
            val source = backdrop.source
            val canvas = drawContext.canvas.nativeCanvas
            val sampled = backdrop.ready && Build.VERSION.SDK_INT >= 31 && surface != null && source != null &&
                source.node.hasDisplayList() && canvas.isHardwareAccelerated
            if (sampled) {
                surface!!.draw(this, source!!, position - backdrop.position)
                // Add grain at display resolution. Upscaling the blur must not enlarge its dots.
                drawRect(FrostedGrain.brush)
            }
            val tint = if (light) Color(0xFFF7F7F9) else Color(0xFF0D0D0F)
            drawRect(tint.copy(alpha = if (sampled) { if (light) 0.73f else 0.8f } else 1f))
            if (drawBorder) {
                drawRoundRect(Color.White.copy(alpha = 0.10f), cornerRadius = CornerRadius(size.height / 2f), style = Stroke(0.5.dp.toPx()))
            }
            drawContent()
        }
}

/** Capsules (the dock's player and tabs) that share one frosted material - see [frostedCapsuleGroup]. */
internal class FrostedCapsules {
    val bounds = mutableStateMapOf<Int, Rect>()
    // While true (the dock moving with the player) the glass is not blurred again: the blur
    // already made is drawn moved, scaled and faded with each capsule ([moves]) - the same
    // glass throughout, so nothing is swapped (or flashes) when the movement ends.
    var moving: () -> Boolean = { false }
    val moves = mutableMapOf<Int, () -> CapsuleMove>()
}

/** Where a capsule is drawn while it moves: shifted down by [dy], scaled about its centre, faded. */
internal class CapsuleMove(val dy: Float, val scale: Float, val alpha: Float)

/** One of [capsules]: its place in the group, so the group's material shows inside it. */
@Composable
internal fun Modifier.frostedCapsule(capsules: FrostedCapsules, id: Int): Modifier {
    DisposableEffect(capsules, id) { onDispose { capsules.bounds.remove(id) } }
    return onPlaced { coordinates ->
        // Its resting place only: mid-movement this position already includes the capsule's own
        // shift, which the group adds again ([CapsuleMove]) - the glass then ran ahead of it.
        if (capsules.moving()) return@onPlaced
        val rect = Rect(coordinates.positionInParent(), coordinates.size.toSize())
        if (capsules.bounds[id] != rect) capsules.bounds[id] = rect
    }.clip(RoundedCornerShape(percent = 50))
}

/**
 * The frosted material for several capsules at once: the page behind them is blurred once for
 * the whole group, then shown only inside each capsule ([frostedCapsule] on the children) -
 * half the work of blurring each capsule on its own, every frame the page behind changes.
 */
@Composable
internal fun Modifier.frostedCapsuleGroup(backdrop: FrostedBackdrop, capsules: FrostedCapsules): Modifier {
    val light = LocalPalette.current.isLight
    var position by remember { mutableStateOf(Offset.Zero) }
    val surface = remember { if (Build.VERSION.SDK_INT >= 31) NativeFrostedSurface() else null }
    DisposableEffect(surface) {
        onDispose { if (Build.VERSION.SDK_INT >= 31) surface?.node?.discardDisplayList() }
    }
    return onGloballyPositioned { position = it.positionInRoot() }
        .drawWithContent {
            if (capsules.moving()) {
                drawMovingCapsules(capsules, surface, light)
                drawContent()
                return@drawWithContent
            }
            val shapes = capsules.bounds.values.toList()
            if (shapes.isNotEmpty()) {
                val path = Path().apply {
                    shapes.forEach { addRoundRect(RoundRect(it, CornerRadius(it.height / 2f))) }
                }
                val source = backdrop.source
                val canvas = drawContext.canvas.nativeCanvas
                val sampled = backdrop.ready && Build.VERSION.SDK_INT >= 31 && surface != null && source != null &&
                    source.node.hasDisplayList() && canvas.isHardwareAccelerated
                clipPath(path) {
                    if (sampled) {
                        surface!!.draw(this, source!!, position - backdrop.position)
                        drawRect(FrostedGrain.brush)
                    }
                    val tint = if (light) Color(0xFFF7F7F9) else Color(0xFF0D0D0F)
                    drawRect(tint.copy(alpha = if (sampled) { if (light) 0.73f else 0.8f } else 1f))
                }
                val border = Stroke(0.5.dp.toPx())
                shapes.forEach {
                    drawRoundRect(Color.White.copy(alpha = 0.10f), topLeft = it.topLeft, size = it.size,
                        cornerRadius = CornerRadius(it.height / 2f), style = border)
                }
            }
            drawContent()
        }
}

/** The capsules mid-movement: the blur last made, moved and faded with each one - not made again. */
private fun DrawScope.drawMovingCapsules(capsules: FrostedCapsules, surface: NativeFrostedSurface?, light: Boolean) {
    val tint = if (light) Color(0xFFF7F7F9) else Color(0xFF0D0D0F)
    val recorded = Build.VERSION.SDK_INT >= 31 && surface != null && surface.hasRecording()
    // Kept in every frame of the movement, even with both capsules faded out: a picture the
    // screen stops drawing is thrown away, and the capsules then came back as plain black until
    // the glass was made again - a flash as the player finished closing.
    if (recorded) clipRect(0f, 0f, 0f, 0f) { surface!!.drawRecorded(this) }
    val border = Stroke(0.5.dp.toPx())
    for ((id, rect) in capsules.bounds) {
        val move = capsules.moves[id]?.invoke() ?: CapsuleMove(0f, 1f, 1f)
        if (move.alpha <= 0.001f) continue
        val corner = CornerRadius(rect.height / 2f)
        withTransform({
            translate(0f, move.dy)
            scale(move.scale, move.scale, rect.center)
        }) {
            val outline = Path().apply { addRoundRect(RoundRect(rect, corner)) }
            clipPath(outline) {
                drawIntoCanvas { canvas ->
                    // One capsule's glass faded as a whole (an offscreen layer, during the movement only).
                    canvas.saveLayer(rect, androidx.compose.ui.graphics.Paint().apply { alpha = move.alpha })
                    if (recorded) {
                        surface!!.drawRecorded(this)
                        drawRect(FrostedGrain.brush)
                    }
                    drawRect(tint.copy(alpha = if (recorded) { if (light) 0.73f else 0.8f } else 1f))
                    canvas.restore()
                }
            }
            drawRoundRect(Color.White.copy(alpha = 0.10f * move.alpha), topLeft = rect.topLeft, size = rect.size,
                cornerRadius = corner, style = border)
        }
    }
}

@RequiresApi(31)
private class NativeFrostedSurface {
    val node = RenderNode("Frosted surface")
    private var effectWidth = 0
    private var effectHeight = 0
    private var effectDensity = 0f
    private var blurEffect: RenderEffect? = null
    // How the last recording maps back onto the surface (see drawRecorded).
    private var recordedPadding = 0f
    private var recordedScale = 1f

    fun hasRecording(): Boolean = node.hasDisplayList()

    /** The blur as last recorded, drawn again without recording it (the surface moving). */
    fun drawRecorded(scope: DrawScope) = with(scope) {
        val canvas = drawContext.canvas.nativeCanvas
        canvas.save()
        try {
            canvas.translate(-recordedPadding, -recordedPadding)
            canvas.scale(1f / recordedScale, 1f / recordedScale)
            canvas.drawRenderNode(node)
        } finally {
            canvas.restore()
        }
    }

    fun draw(scope: DrawScope, source: NativeFrostedSource, position: Offset) = with(scope) {
        // Nine times fewer pixels than a full-resolution effect, confined to these small pills.
        val scale = 0.33f
        // Enough of the neighbourhood that the edge does not turn into a streak (the blur's sigma is
        // about 0.6 of its radius, so 1.5 radii covers ~2.5 sigma) - a wider margin only added
        // pixels to blur on every frame.
        val padding = FrostedBlurRadius.toPx() * 1.5f
        val width = ceil((size.width + padding * 2) * scale).toInt().coerceAtLeast(1)
        val height = ceil((size.height + padding * 2) * scale).toInt().coerceAtLeast(1)
        node.setPosition(0, 0, width, height)
        if (width != effectWidth || height != effectHeight || density != effectDensity) {
            if (blurEffect == null || density != effectDensity) {
                val radius = FrostedBlurRadius.toPx() * scale
                blurEffect = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
            }
            node.setRenderEffect(blurEffect!!)
            effectWidth = width
            effectHeight = height
            effectDensity = density
        }
        val recording = node.beginRecording(width, height)
        try {
            recording.drawColor(source.background.toArgb())
            recording.scale(scale, scale)
            recording.translate(padding - position.x, padding - position.y)
            // Only the part of the page this surface blurs: the rest of the page (a whole feed of
            // shelves and artwork) is skipped instead of being replayed for every surface, every frame.
            recording.clipRect(position.x - padding, position.y - padding,
                position.x + size.width + padding, position.y + size.height + padding)
            recording.drawRenderNode(source.node)
        } finally {
            node.endRecording()
        }
        recordedPadding = padding
        recordedScale = scale
        drawRecorded(this)
    }
}

/** Fine, faint grain softens banding without black/white speckles. Generated once and shared. */
private object FrostedGrain {
    val brush by lazy {
        val random = Random(41L)
        val pixels = IntArray(128 * 128) {
            val value = (128 + random.nextGaussian() * 18).toInt().coerceIn(96, 160)
            (12 shl 24) or (value shl 16) or (value shl 8) or value
        }
        val texture = Bitmap.createBitmap(pixels, 128, 128, Bitmap.Config.ARGB_8888)
        val shader = BitmapShader(texture, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        ShaderBrush(shader)
    }
}

