package com.abn3li.telemusic.repository

import com.abn3li.telemusic.data.local.SongEntity

/** Normalize text once per song instead of on every comparison in a library sort. */
internal fun List<SongEntity>.sortedForLibrary(field: SortField, ascending: Boolean): List<SongEntity> {
    val sorted = when (field) {
        SortField.DATE_ADDED -> sortedBy { it.addedAtMillis }
        // Ties (same count) by when last played, so equally played songs keep a stable order.
        SortField.MOST_PLAYED -> sortedWith(compareBy<SongEntity> { it.playCount }.thenBy { it.lastPlayedAtMillis })
        SortField.RECENTLY_PLAYED -> sortedBy { it.lastPlayedAtMillis }
        SortField.DURATION -> sortedBy { it.durationSeconds }
        else -> {
            val key: (SongEntity) -> String = when (field) {
                SortField.ARTIST -> { song -> song.artist.lowercase() }
                SortField.ALBUM -> { song -> (song.album ?: "Unknown Album").lowercase() }
                else -> { song -> song.title.lowercase() }
            }
            map { song -> song to key(song) }.sortedBy { it.second }.map { it.first }
        }
    }
    // Preserve the existing order of equal names too: descending used to reverse the whole
    // ascending result, including ties. Reversing only the comparator would change queues.
    return if (ascending) sorted else sorted.asReversed()
}
