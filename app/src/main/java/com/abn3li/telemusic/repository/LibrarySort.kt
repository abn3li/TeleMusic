package com.abn3li.telemusic.repository

import com.abn3li.telemusic.data.local.SongEntity

/** Normalize text once per song instead of on every comparison in a library sort. */
internal fun List<SongEntity>.sortedForLibrary(field: SortField, ascending: Boolean): List<SongEntity> {
    val sorted = if (field == SortField.DATE_ADDED) sortedBy { it.addedAtMillis } else {
        val key: (SongEntity) -> String = when (field) {
            SortField.TITLE -> { song -> song.title.lowercase() }
            SortField.ARTIST -> { song -> song.artist.lowercase() }
            SortField.ALBUM -> { song -> (song.album ?: "Unknown Album").lowercase() }
            SortField.DATE_ADDED -> error("Date sorting has no text key")
        }
        map { song -> song to key(song) }.sortedBy { it.second }.map { it.first }
    }
    // Preserve the existing order of equal names too: descending used to reverse the whole
    // ascending result, including ties. Reversing only the comparator would change queues.
    return if (ascending) sorted else sorted.asReversed()
}
