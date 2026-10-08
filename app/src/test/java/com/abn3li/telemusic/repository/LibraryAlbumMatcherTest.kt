package com.abn3li.telemusic.repository

import com.abn3li.telemusic.repository.LibraryAlbumMatcher.LibraryAlbum
import org.junit.Assert.*
import org.junit.Test

class LibraryAlbumMatcherTest {
    private val library = listOf(
        LibraryAlbum("30", "ADELE"),
        LibraryAlbum("25 (Deluxe Edition)", "Adele"),
        LibraryAlbum("After Hours", "The Weeknd"),
        LibraryAlbum("Greatest Hits", "Queen")
    )

    @Test fun joinsLibraryAlbumWithItsOwnSpelling() {
        assertEquals("25 (Deluxe Edition)", LibraryAlbumMatcher.match(listOf("25"), "Adele", library))
        assertEquals("30", LibraryAlbumMatcher.match(listOf("30 [Explicit]"), "Adele", library))
    }

    @Test fun singleFallsBackToAnotherSourcesAlbum() {
        // YouTube Music names the single; the lookup names the album the library has.
        assertEquals("30", LibraryAlbumMatcher.match(listOf("Easy On Me", "30"), "Adele", library))
    }

    @Test fun featuredCreditStillMatchesTheArtist() {
        assertEquals("After Hours", LibraryAlbumMatcher.match(listOf("After Hours"), "The Weeknd, Daft Punk", library))
    }

    @Test fun sameNameByAnotherArtistIsNotMerged() {
        assertNull(LibraryAlbumMatcher.match(listOf("Greatest Hits"), "ABBA", library))
    }

    @Test fun releaseWordsAreIgnoredButRealSubtitlesAreNot() {
        assertEquals(LibraryAlbumMatcher.albumKey("Easy On Me"), LibraryAlbumMatcher.albumKey("Easy On Me - Single"))
        assertEquals(LibraryAlbumMatcher.albumKey("Rumours"), LibraryAlbumMatcher.albumKey("Rumours (Super Deluxe)"))
        assertNotEquals(LibraryAlbumMatcher.albumKey("Hybrid Theory"), LibraryAlbumMatcher.albumKey("Hybrid Theory (Live in Texas)"))
    }

    @Test fun artistTakesTheLibrarysSpelling() {
        val artists = listOf("ADELE", "Beyoncé", "AC/DC", "Drake Bell")
        assertEquals("ADELE", LibraryAlbumMatcher.canonicalArtist("Adele", artists))
        assertEquals("Beyoncé", LibraryAlbumMatcher.canonicalArtist("Beyonce", artists))
        assertEquals("AC/DC", LibraryAlbumMatcher.canonicalArtist("ACDC", artists))
        assertEquals("ADELE", LibraryAlbumMatcher.canonicalArtist("Adele, Someone", artists))
        // Not a spelling of anyone in the library: kept, as its primary artist.
        assertEquals("Drake", LibraryAlbumMatcher.canonicalArtist("Drake feat. Rihanna", artists))
        assertEquals("Unknown artist", LibraryAlbumMatcher.canonicalArtist("Unknown artist", artists))
    }

    @Test fun splitArtistsMergeIntoTheSpellingMostSongsUse() {
        val merges = LibraryAlbumMatcher.artistMerges(listOf(
            "ADELE" to 2, "Adele" to 9, "Beyonce" to 3, "BEYONCÉ" to 3, "Drake" to 4, "Drake Bell" to 1))
        assertEquals(mapOf("ADELE" to "Adele", "BEYONCÉ" to "Beyonce"), merges)
    }

    @Test fun arabicAndLatinNamesStayApart() {
        assertFalse(LibraryAlbumMatcher.sameArtist("عمرو دياب", "Amr Diab"))
        assertTrue(LibraryAlbumMatcher.sameArtist("عمرو دياب", "عمرو  دياب"))
    }

    @Test fun bandNamesWithAmpersandOrSlashStayWhole() {
        val library = listOf("AC/DC", "Simon & Garfunkel", "The Weeknd")
        assertEquals("AC/DC", LibraryAlbumMatcher.collapseCredit("AC/DC", library))
        assertEquals("Simon & Garfunkel", LibraryAlbumMatcher.collapseCredit("Simon & Garfunkel", library))
        // The first name is an artist of its own here, so this is a collab.
        assertEquals("The Weeknd", LibraryAlbumMatcher.collapseCredit("The Weeknd & Daft Punk", library))
        // A new band not in the library yet keeps its whole name.
        assertEquals("Florence + The Machine", LibraryAlbumMatcher.canonicalArtist("Florence + The Machine", library))
        assertEquals("Mumford & Sons", LibraryAlbumMatcher.canonicalArtist("Mumford & Sons", library))
    }
}
