package com.abn3li.telemusic.repository

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LyricsTranslatorTest {
    @Test fun joinsTheTranslatedPiecesBackIntoLines() {
        val body = """[[["هل هذه هي الحياة الحقيقية؟\n","Is this the real life?\n",null,null,10],
            ["هل هذا مجرد خيال؟","Is this just fantasy?",null,null,10]],null,"en"]"""
        val lines = LyricsTranslator.translatedText(body).split('\n')
        assertEquals(listOf("هل هذه هي الحياة الحقيقية؟", "هل هذا مجرد خيال؟"), lines)
    }

    @Test fun longLyricsGoInSeveralBatchesInOrder() {
        val lines = (1..400).map { "Line number $it of a long song" }
        val batches = LyricsTranslator.batches(lines)
        assert(batches.size > 1)
        assert(batches.all { batch -> batch.sumOf { it.length + 1 } <= 3_500 })
        assertEquals(lines, batches.flatten())
    }
}
