package com.abn3li.telemusic.repository

import com.abn3li.telemusic.data.local.SongEntity
import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class LibrarySortTest {
    @Test fun sortKeepsExistingOrderForEveryFieldAndDirection() {
        val random = Random(55)
        val names = listOf("Adele", "ADELE", "", "Unknown Album", "أغنية", "Zebra", "İstanbul", "Beyoncé")
        val songs = List(2000) { i -> SongEntity(i.toLong(), 0,
            names[random.nextInt(names.size)], names[random.nextInt(names.size)],
            album = if (i % 3 == 0) null else names[random.nextInt(names.size)],
            addedAtMillis = random.nextInt(100).toLong()) }
        for (field in SortField.entries) for (ascending in listOf(true, false)) {
            val comparator = when (field) {
                SortField.TITLE -> compareBy<SongEntity> { it.title.lowercase() }
                SortField.ARTIST -> compareBy { it.artist.lowercase() }
                SortField.ALBUM -> compareBy { (it.album ?: "Unknown Album").lowercase() }
                SortField.DATE_ADDED -> compareBy { it.addedAtMillis }
            }
            val expected = songs.sortedWith(comparator).let { if (ascending) it else it.reversed() }
            assertEquals("$field ascending=$ascending", expected, songs.sortedForLibrary(field, ascending))
        }
    }

    @Test fun equalTitlesKeepThePreviousDescendingQueueOrder() {
        val songs = listOf(SongEntity(1, 0, "a", "artist"), SongEntity(2, 0, "A", "artist"))
        assertEquals(listOf(1L, 2L), songs.sortedForLibrary(SortField.TITLE, true).map { it.telegramMessageId })
        assertEquals(listOf(2L, 1L), songs.sortedForLibrary(SortField.TITLE, false).map { it.telegramMessageId })
        assertEquals(listOf(1L, 2L), songs.map { it.telegramMessageId })
        assertTrue(emptyList<SongEntity>().sortedForLibrary(SortField.TITLE, true).isEmpty())
    }
    @Test fun albumUsesDiscThenTrackWithSourceOrderForMissingTags() {
        val songs = listOf(SongEntity(30, 0, "A", "artist"), SongEntity(20, 0, "Z", "artist"),
            SongEntity(10, 0, "B", "artist"), SongEntity(40, 0, "C", "artist"))
        val positions = mapOf(30L to AlbumPosition(2, 1), 20L to AlbumPosition(1, 2))
        assertEquals(listOf(20L, 30L, 10L, 40L), albumOrder(songs, positions, true).map { it.telegramMessageId })
        assertEquals(listOf(40L, 10L, 30L, 20L), albumOrder(songs, positions, false).map { it.telegramMessageId })
        assertEquals(listOf(10L, 20L, 30L, 40L), albumOrder(songs, emptyMap(), true).map { it.telegramMessageId })
    }

    @Test fun trackTagsAcceptTotalsAndRejectInvalidNumbers() {
        assertEquals(3, trackTag("03/12"))
        assertEquals(2, trackTag(" 2 "))
        for (value in listOf(null, "", "0", "-2", "abc")) assertNull(trackTag(value))
    }
    @Test fun catalogRequiresMatchingEditionArtistTitleAndDuration() {
        val song = SongEntity(1, 0, "Song", "Artist", album = "Album", durationSeconds = 180)
        val track = com.abn3li.telemusic.data.remote.ITunesTrack("Song", "Artist", "Album", null, 180000, 100L, 4, 2)
        assertEquals(AlbumPosition(2, 4), catalogPosition(song, listOf(track)))
        for (other in listOf(track.copy(collectionName = "Album Deluxe"), track.copy(artistName = "Other"),
            track.copy(trackTimeMillis = 200000), track.copy(trackName = "Song Live"), track.copy(trackNumber = null))) {
            assertNull(catalogPosition(song, listOf(other)))
        }
        assertNull(catalogPosition(song, listOf(track, track.copy(trackNumber = 5))))
    }
}
