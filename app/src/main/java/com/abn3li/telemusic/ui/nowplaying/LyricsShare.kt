package com.abn3li.telemusic.ui.nowplaying

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.abn3li.telemusic.data.browse.fullSizeArtwork
import com.abn3li.telemusic.ui.library.CalmSpinner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Lines picked on the lyrics page, with their translations when shown, and the song they're from. */
internal class LyricsShareCard(
    val lines: List<String>,
    val translations: List<String?>,
    val title: String,
    val artist: String,
    val artwork: String?
)

/** The most lines a card holds: past this the words get too small for a story. */
internal const val LYRICS_SHARE_MAX_LINES = 8

private const val CARD_WIDTH = 1080
private const val CARD_HEIGHT = 1920
private const val CARD_PADDING = 96f
private const val COVER_SIZE = 168
private const val LYRICS_TOP = 420f
private const val LYRICS_BOTTOM = CARD_HEIGHT - 230f
private const val MAX_LYRIC_SIZE = 84f
private const val MIN_LYRIC_SIZE = 40f
private const val INSTAGRAM = "com.instagram.android"
private val FallbackCardColor = 0xFF2A2A2E.toInt()

/**
 * The share screen: the card as it will be shared (the same picture, not a lookalike), then
 * Instagram (when it's on the phone), Save to Photos and the system share sheet.
 */
@Composable
internal fun LyricsShareDialog(card: LyricsShareCard, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    val preview: ImageBitmap? = remember(bitmap) { bitmap?.asImageBitmap() }
    LaunchedEffect(card) { bitmap = renderLyricsCard(context, card) }
    val hasInstagram = remember {
        runCatching { context.packageManager.getPackageInfo(INSTAGRAM, 0) }.isSuccess
    }

    fun share(packageName: String?) {
        val image = bitmap ?: return
        scope.launch {
            val uri = runCatching { withContext(Dispatchers.IO) { writeShareFile(context, image) } }.getOrNull()
            if (uri == null) {
                android.widget.Toast.makeText(context, "Couldn't prepare the picture", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(null, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                packageName?.let { setPackage(it) }
            }
            runCatching {
                context.startActivity(if (packageName != null) send else Intent.createChooser(send, null))
            }.onFailure {
                android.widget.Toast.makeText(context, "No app to share with", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun save() {
        val image = bitmap ?: return
        scope.launch {
            val saved = runCatching { withContext(Dispatchers.IO) { saveToPhotos(context, image, card.title) } }.isSuccess
            android.widget.Toast.makeText(context, if (saved) "Saved to Photos" else "Couldn't save the picture",
                android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xFF0B0B0D)).systemBarsPadding()) {
            Icon(
                Icons.Rounded.Close, contentDescription = "Close", tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp).size(48.dp).clip(CircleShape)
                    .clickable(onClick = onDismiss).padding(12.dp)
            )
            Column(
                Modifier.fillMaxSize().padding(top = 64.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.fillMaxHeight().aspectRatio(CARD_WIDTH / CARD_HEIGHT.toFloat())
                            .clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.06f)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (preview != null) Image(preview, contentDescription = "Lyrics card", modifier = Modifier.fillMaxSize())
                        else CalmSpinner(color = Color.White.copy(alpha = 0.7f), strokeWidth = 2.5.dp)
                    }
                }
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    if (hasInstagram) ShareAction(Icons.Rounded.PhotoCamera, "Instagram", bitmap != null) { share(INSTAGRAM) }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ShareAction(Icons.Rounded.Download, "Save", bitmap != null, ::save)
                    ShareAction(Icons.Rounded.IosShare, "Share", bitmap != null) { share(null) }
                }
            }
        }
    }
}

@Composable
private fun ShareAction(icon: ImageVector, label: String, ready: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(88.dp).clip(RoundedCornerShape(14.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = ready, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Color.White.copy(alpha = if (ready) 0.95f else 0.4f), modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
    }
}

/** The card as a 1080x1920 picture - a phone story's size: the artwork's colour behind, the
 * cover and song at the top, the lines as large as they fit, and the app's name at the bottom. */
internal suspend fun renderLyricsCard(context: Context, card: LyricsShareCard): Bitmap {
    val cover = card.artwork?.let { loadCover(context, it) }
    return withContext(Dispatchers.Default) {
        val background = cover?.let { cardColor(it) } ?: FallbackCardColor
        val bitmap = Bitmap.createBitmap(CARD_WIDTH, CARD_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(background)

        // Cover and song.
        val coverTop = 150f
        val coverRect = RectF(CARD_PADDING, coverTop, CARD_PADDING + COVER_SIZE, coverTop + COVER_SIZE)
        val coverPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (cover != null) {
            canvas.save()
            canvas.clipPath(android.graphics.Path().apply { addRoundRect(coverRect, 22f, 22f, android.graphics.Path.Direction.CW) })
            canvas.drawBitmap(cover, null, coverRect, coverPaint)
            canvas.restore()
        } else {
            coverPaint.color = 0x26FFFFFF
            canvas.drawRoundRect(coverRect, 22f, 22f, coverPaint)
        }
        val textLeft = coverRect.right + 32f
        val textWidth = CARD_WIDTH - CARD_PADDING - textLeft
        val titlePaint = textPaint(44f, bold = true, alpha = 255)
        val artistPaint = textPaint(38f, bold = false, alpha = 180)
        val title = fitOneLine(card.title, titlePaint, textWidth)
        val artist = fitOneLine(card.artist, artistPaint, textWidth)
        canvas.drawText(title, textLeft, coverTop + 72f, titlePaint)
        canvas.drawText(artist, textLeft, coverTop + 126f, artistPaint)

        // The lines, centred in the space between.
        val width = (CARD_WIDTH - CARD_PADDING * 2).toInt()
        val paragraphs = lyricsLayouts(card, width, LYRICS_BOTTOM - LYRICS_TOP)
        var y = LYRICS_TOP + ((LYRICS_BOTTOM - LYRICS_TOP) - paragraphs.height) / 2f
        for ((layout, gapAfter) in paragraphs.layouts) {
            canvas.save()
            canvas.translate(CARD_PADDING, y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + gapAfter
        }

        canvas.drawText("TeleMusic", CARD_PADDING, CARD_HEIGHT - 110f, textPaint(34f, bold = true, alpha = 150))
        bitmap
    }
}

private class LyricsLayouts(val layouts: List<Pair<StaticLayout, Float>>, val height: Float)

/** The lines (and their translations) at the largest size that fits [maxHeight]. */
private fun lyricsLayouts(card: LyricsShareCard, width: Int, maxHeight: Float): LyricsLayouts {
    var size = MAX_LYRIC_SIZE
    while (true) {
        val built = buildLyricsLayouts(card, width, size)
        if (built.height <= maxHeight || size <= MIN_LYRIC_SIZE) return built
        size -= 4f
    }
}

private fun buildLyricsLayouts(card: LyricsShareCard, width: Int, size: Float): LyricsLayouts {
    val linePaint = textPaint(size, bold = true, alpha = 255)
    val translationPaint = textPaint((size * 0.6f).coerceAtLeast(30f), bold = false, alpha = 190)
    val lineGap = size * 0.45f
    val layouts = mutableListOf<Pair<StaticLayout, Float>>()
    card.lines.forEachIndexed { index, line ->
        val translation = card.translations.getOrNull(index)
        layouts += staticLayout(line, linePaint, width, spacing = 1.08f) to (if (translation != null) size * 0.18f else lineGap)
        if (translation != null) layouts += staticLayout(translation, translationPaint, width, spacing = 1.1f) to lineGap
    }
    val height = layouts.sumOf { (layout, gap) -> (layout.height + gap).toDouble() }.toFloat() - (layouts.lastOrNull()?.second ?: 0f)
    return LyricsLayouts(layouts, height)
}

private fun staticLayout(text: String, paint: TextPaint, width: Int, spacing: Float): StaticLayout =
    StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        // Each line in its own direction: an Arabic line starts at the right.
        .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
        .setLineSpacing(0f, spacing)
        .setIncludePad(false)
        .build()

/** [text] cut with an ellipsis to fit [width] on one line. */
private fun fitOneLine(text: String, paint: TextPaint, width: Float): String {
    if (paint.measureText(text) <= width) return text
    val room = width - paint.measureText("…")
    val count = paint.breakText(text, true, room.coerceAtLeast(0f), null)
    return text.take(count).trimEnd() + "…"
}

private fun textPaint(size: Float, bold: Boolean, alpha: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    color = android.graphics.Color.WHITE
    this.alpha = alpha
    textSize = size
    typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
}

private suspend fun loadCover(context: Context, artwork: String): Bitmap? = runCatching {
    val request = ImageRequest.Builder(context)
        .data(fullSizeArtwork(artwork) ?: artwork)
        .size(COVER_SIZE * 2)
        .allowHardware(false)
        .build()
    val result = context.imageLoader.execute(request) as? SuccessResult ?: return null
    result.drawable.toBitmap(COVER_SIZE * 2, COVER_SIZE * 2, Bitmap.Config.ARGB_8888)
}.getOrNull()

/** The card's colour from the cover: its most colourful tone, dark enough for white words. */
private fun cardColor(cover: Bitmap): Int {
    val small = Bitmap.createScaledBitmap(cover, 24, 24, true)
    val pixels = IntArray(24 * 24)
    small.getPixels(pixels, 0, 24, 0, 0, 24, 24)
    if (small !== cover) small.recycle()
    return cardColorOf(pixels)
}

/** See [cardColor]: the pixels averaged, the colourful ones counting most, then the result's
 * lightness held between dark and mid so white text always reads on it. */
internal fun cardColorOf(pixels: IntArray): Int {
    var r = 0.0
    var g = 0.0
    var b = 0.0
    var total = 0.0
    val hsl = FloatArray(3)
    for (pixel in pixels) {
        androidx.core.graphics.ColorUtils.colorToHSL(pixel, hsl)
        // Colourful, not near black or white: those tell the cover's colour best.
        val weight = 0.08 + hsl[1] * (1f - kotlin.math.abs(hsl[2] * 2f - 1f))
        r += android.graphics.Color.red(pixel) * weight
        g += android.graphics.Color.green(pixel) * weight
        b += android.graphics.Color.blue(pixel) * weight
        total += weight
    }
    if (total <= 0.0) return FallbackCardColor
    val average = android.graphics.Color.rgb((r / total).toInt(), (g / total).toInt(), (b / total).toInt())
    androidx.core.graphics.ColorUtils.colorToHSL(average, hsl)
    hsl[1] = hsl[1].coerceAtMost(0.62f)
    hsl[2] = hsl[2].coerceIn(0.2f, 0.34f)
    return androidx.core.graphics.ColorUtils.HSLToColor(hsl)
}

/** The picture in the app's cache, shared through its FileProvider (one file, replaced each time). */
private fun writeShareFile(context: Context, bitmap: Bitmap): android.net.Uri {
    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
    val file = File(dir, "lyrics.png")
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return FileProvider.getUriForFile(context, "${context.packageName}.share", file)
}

/** Into Pictures/TeleMusic, where the gallery shows it. */
private fun saveToPhotos(context: Context, bitmap: Bitmap, title: String) {
    check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
    val resolver = context.contentResolver
    val safeTitle = title.replace(Regex("""[\\/:*?"<>|]"""), "").take(60).ifBlank { "Lyrics" }
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "$safeTitle lyrics ${System.currentTimeMillis()}.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/TeleMusic")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("No place to save")
    try {
        resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: error("Can't write")
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    } catch (e: Exception) {
        resolver.delete(uri, null, null)
        throw e
    }
}
