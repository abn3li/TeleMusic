package com.abn3li.telemusic.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsSongMatchTest {
    @Test fun sameSongMatchesAndOtherSongsDoNot() {
        val match = SongMatch("Tamally Maak", "Amr Diab", 272)
        assertTrue(match.accepts("Tamally Maak", "Amr Diab", 272))
        // Seen in KuGou's results for this search: a cover, and the singer's other songs.
        assertFalse(match.accepts("Tamally Maak (Amr Diab Cover)", "Alireza Haghjoo", 247))
        assertFalse(match.accepts("Saaban Alaya", "Amr Diab", 222))
    }

    @Test fun arabicSpellingsMatch() {
        val match = SongMatch("تملي معاك", "عمرو دياب", 272)
        assertTrue(match.accepts("تملى معاك", "عمرو دياب", 271))
        assertFalse(match.accepts("قمرين", "عمرو دياب", 272))
        assertFalse(match.accepts("تملي معاك", "محمد منير", 272))
    }

    @Test fun artistInAnotherScriptFallsBackToLength() {
        val match = SongMatch("Tamally Maak", "عمرو دياب", 272)
        assertTrue(match.accepts("Tamally Maak", "Amr Diab", 268))
        assertFalse(match.accepts("Tamally Maak", "Amr Diab", 200))
    }

    @Test fun liveSongTimingOnlyFromLiveRecording() {
        val live = SongMatch("Ya Rayah (Live)", "Rachid Taha", 410)
        assertTrue(live.accepts("Ya Rayah", "Rachid Taha", 375))
        // The studio cut's synced lines don't fit the live recording.
        assertFalse(live.timingFits("Ya Rayah", 375))
        assertTrue(live.timingFits("Ya Rayah (Live)", 408))
        val studio = SongMatch("Ya Rayah", "Rachid Taha", 375)
        assertFalse(studio.timingFits("Ya Rayah (Live)", 377))
        assertTrue(studio.timingFits("Ya Rayah", 373))
    }
}
