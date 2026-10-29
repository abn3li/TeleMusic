package com.abn3li.telemusic.ui.nowplaying

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LyricsShareTest {
    private fun render(card: LyricsShareCard): Bitmap = runBlocking {
        renderLyricsCard(ApplicationProvider.getApplicationContext(), card)
    }

    private fun save(bitmap: Bitmap, name: String) {
        val dir = File(System.getProperty("user.dir"), "../output/lyrics_share").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Rows between the lyrics and the footer hold nothing but the card's colour. */
    private fun assertBandEmpty(bitmap: Bitmap, fromY: Int, toY: Int) {
        val background = bitmap.getPixel(4, 4)
        for (y in fromY until toY step 2) for (x in 0 until bitmap.width step 3) {
            assertEquals("pixel at $x,$y", background, bitmap.getPixel(x, y))
        }
    }

    @Test fun cardIsStorySizedWithTheLinesOnIt() {
        val bitmap = render(LyricsShareCard(
            lines = listOf("We drove all night under silver lights", "and the city sang our names"),
            translations = listOf(null, null),
            title = "Silver Lights", artist = "Night Drive", artwork = null
        ))
        save(bitmap, "two_lines")
        assertEquals(1080, bitmap.width)
        assertEquals(1920, bitmap.height)
        // White words somewhere in the middle of the card.
        var white = 0
        for (y in 700 until 1300 step 2) for (x in 96 until 984 step 2) {
            if (Color.red(bitmap.getPixel(x, y)) > 240 && Color.blue(bitmap.getPixel(x, y)) > 240) white++
        }
        assertTrue("no lyric text drawn", white > 500)
    }

    @Test fun manyLongLinesShrinkInsteadOfRunningIntoTheFooter() {
        val long = "This is a long line that keeps going and going so it wraps over several rows on the card"
        val bitmap = render(LyricsShareCard(
            lines = List(LYRICS_SHARE_MAX_LINES) { "$long ($it)" },
            translations = List(LYRICS_SHARE_MAX_LINES) { "ترجمة هذا السطر الطويل إلى العربية مع كلمات كثيرة" },
            title = "A very long song title that should be cut short with an ellipsis", artist = "Someone", artwork = null
        ))
        save(bitmap, "eight_long_lines")
        assertBandEmpty(bitmap, 1920 - 225, 1920 - 150)
    }

    @Test fun arabicLinesRender() {
        val bitmap = render(LyricsShareCard(
            lines = listOf("يا ليل طوّل شوية", "والقمر يسهر ويانا"),
            translations = listOf("Oh night, stay a little longer", null),
            title = "أغنية", artist = "فنان", artwork = null
        ))
        save(bitmap, "arabic")
        // Right-to-left: the words start at the right edge, so the right side has text.
        var rightInk = 0
        for (y in 600 until 1400 step 2) for (x in 700 until 984 step 2) {
            if (Color.red(bitmap.getPixel(x, y)) > 240) rightInk++
        }
        assertTrue(rightInk > 100)
    }

    @Test fun cardColourIsDarkEnoughForWhiteText() {
        val hsl = FloatArray(3)
        for (pixel in listOf(Color.WHITE, Color.YELLOW, Color.BLACK, Color.rgb(230, 120, 90))) {
            ColorUtils.colorToHSL(cardColorOf(IntArray(64) { pixel }), hsl)
            assertTrue("too light for $pixel: ${hsl[2]}", hsl[2] <= 0.34f + 5e-3f)
            assertTrue("too dark for $pixel: ${hsl[2]}", hsl[2] >= 0.2f - 5e-3f)
        }
    }

    @Test fun cardColourFollowsTheCoversColour() {
        // Mostly grey with a red patch: the red should win.
        val pixels = IntArray(100) { if (it < 30) Color.rgb(220, 30, 40) else Color.rgb(128, 128, 128) }
        val colour = cardColorOf(pixels)
        assertTrue(Color.red(colour) > Color.green(colour) + 20)
        assertTrue(Color.red(colour) > Color.blue(colour) + 20)
    }
}
