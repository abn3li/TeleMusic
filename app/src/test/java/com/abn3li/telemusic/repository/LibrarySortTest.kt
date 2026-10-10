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
}
