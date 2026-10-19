package com.abn3li.telemusic.repository

import com.abn3li.telemusic.repository.LookupMatcher.Candidate
import com.abn3li.telemusic.repository.LookupMatcher.Wanted
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LookupMatcherTest {
    @Test fun uploadTagsAreRemovedBeforeSearching() {
        assertEquals(Wanted("Cruel World", "Lana Del Rey"), LookupMatcher.clean("Cruel World (Official Music Video)", "Lana Del Rey"))
        assertEquals(Wanted("Cruel World", "Lana Del Rey"), LookupMatcher.clean("Cruel World [Lyrics] | HD", "Lana Del Rey"))
        assertEquals(Wanted("Cruel World", "Lana Del Rey"), LookupMatcher.clean("Cruel World - Lyric Video", "Lana Del Rey"))
        assertEquals(Wanted("Believer", "Imagine Dragons"), LookupMatcher.clean("03. Believer (feat. X) [Audio].mp3", "Imagine Dragons"))
        assertEquals(Wanted("تملي معاك", "عمرو دياب"), LookupMatcher.clean("تملي معاك (كلمات)", "عمرو دياب"))
    }

    @Test fun artistInTheTitleIsUsedOrDropped() {
        // No artist: "Artist - Song" gives both.
        assertEquals(Wanted("Cruel World", "Lana Del Rey"), LookupMatcher.clean("Lana Del Rey - Cruel World (Lyrics)", "Unknown artist"))
        // Same artist repeated in the title: dropped from the title.
        assertEquals(Wanted("Cruel World", "Lana Del Rey"), LookupMatcher.clean("Lana Del Rey - Cruel World", "LanaDelReyVEVO"))
        // A real part of the title stays.
        assertEquals(Wanted("Song - Live", "Band"), LookupMatcher.clean("Song - Live", "Band"))
        // Channel suffixes are not part of the artist.
        assertEquals("Lana Del Rey", LookupMatcher.clean("Cruel World", "Lana Del Rey - Topic").artist)
    }

    @Test fun unknownTitleIsNotSearched() {
        assertEquals("", LookupMatcher.clean("Unknown title", "Lana Del Rey").title)
    }

    @Test fun onlyTheSameSongIsAccepted() {
        val wanted = Wanted("Cruel World", "Lana Del Rey")
        val results = listOf(
            Candidate("Cruel World", "Some Cover Band", 220),
            Candidate("Cruel World (Remastered)", "Lana Del Rey", 399),
        )
        assertEquals(1, LookupMatcher.best(results, wanted))
        assertNull(LookupMatcher.best(listOf(Candidate("Cruel World", "Someone Else", 220)), wanted))
        assertNull(LookupMatcher.best(listOf(Candidate("Brooklyn Baby", "Lana Del Rey", 351)), wanted))
    }

    @Test fun exactTitleBeatsOneThatOnlyStartsTheSame() {
        val wanted = Wanted("Believer", "Imagine Dragons")
        val results = listOf(Candidate("Believer (Kygo Remix)", "Imagine Dragons", 210), Candidate("Believer", "Imagine Dragons", 204))
        assertEquals(1, LookupMatcher.best(results, wanted))
    }

    @Test fun otherScriptArtistFallsBackToLength() {
        val wanted = Wanted("Tamally Maak", "عمرو دياب", durationSeconds = 270)
        assertEquals(0, LookupMatcher.best(listOf(Candidate("Tamally Maak", "Amr Diab", 268)), wanted))
        assertNull(LookupMatcher.best(listOf(Candidate("Tamally Maak", "Amr Diab", 200)), wanted))
    }

    @Test fun noArtistNeedsAnExactTitleOrMatchingLength() {
        assertEquals(0, LookupMatcher.best(listOf(Candidate("Crossfire", "Otherwise", null)), Wanted("Crossfire", null)))
        assertNull(LookupMatcher.best(listOf(Candidate("Crossfire Hurricane", "Someone", null)), Wanted("Crossfire", null)))
    }
}
