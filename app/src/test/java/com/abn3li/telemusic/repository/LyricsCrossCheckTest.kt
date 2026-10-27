package com.abn3li.telemusic.repository

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LyricsCrossCheckTest {
    private val tamallyMaak = "تملي معاك ولو حتى بعيد عني\nفي قلبي هواك تملي معاك\nتملي في بالي في قلبي ساكن"

    @Test fun anotherSongBySameSingerIsRejected() {
        // Seen live: a title-and-length search for "Tamally Maak" answered with this other song.
        val other = "أعمل إيه في اللي أنا حبيته\nواللى القلب إختاره\nجنة قلبي وناره"
        assertFalse(sameLyrics(other, tamallyMaak, "Tamally Maak"))
    }

    @Test fun theSameSongIsKeptEvenWithOtherSpellings() {
        val same = "تملى معاك ولو حتى بعيد عنى\nفى قلبى هواك تملى معاك"
        assertTrue(sameLyrics(same, tamallyMaak, "Tamally Maak"))
    }

    @Test fun withNothingToCompareTheScriptMustFit() {
        assertFalse(sameLyrics("Tamally maak we law hata ba eed any", null, "تملي معاك"))
        assertTrue(sameLyrics(tamallyMaak, null, "Tamally Maak"))
        assertTrue(sameLyrics("Is this the real life", null, "Bohemian Rhapsody"))
    }

    @Test fun lyricsPlusSyllablesBecomeWordTimedLrc() {
        val json = JSONObject("""{"type":"Word","metadata":{"title":"Bohemian Rhapsody","artist":"Queen"},"lyrics":[
            {"time":1000,"duration":2000,"text":"Is this","syllabus":[{"time":1000,"duration":400,"text":"Is "},{"time":1500,"duration":500,"text":"this"}]},
            {"time":4000,"duration":1500,"text":"the real life"}]}""")
        val result = lyricsPlusResult(json, SongMatch("Bohemian Rhapsody", "Queen", 355))
        assertNotNull(result)
        assertEquals("Is this\nthe real life", result!!.plain)
        assertTrue(result.synced!!.contains("]<00:01.000>Is<00:01.400> <00:01.500>this"))
        assertTrue(result.synced!!.contains("[00:04.000]the real life"))
    }

    @Test fun timedLyricsCompareByTheirWords() {
        val synced = "[00:00.15] Is this the real life? Is this just fantasy?\n[00:06.10] Caught in a landslide"
        assertTrue(sameLyrics("Is this the real life?\nIs this just fantasy?\nCaught in a landslide", synced, "Bohemian Rhapsody"))
    }

    @Test fun lyricsPlusNamingAnotherSongIsRejected() {
        val json = JSONObject("""{"metadata":{"title":"Saaban Alaya","artist":"Amr Diab"},"lyrics":[{"time":1000,"text":"x y z"}]}""")
        assertNull(lyricsPlusResult(json, SongMatch("Tamally Maak", "Amr Diab", 272)))
    }
}
