package com.abn3li.telemusic.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Lyrics found (or not found) for a song, kept apart from the songs table so nothing that
 * rewrites a song can erase them, and so the same song from Telegram, YouTube or Spotify shares
 * one entry. [key] is LyricsRepository.cacheKey (cleaned title|artist). Both lyrics null means
 * "no lyrics anywhere" as of [fetchedAtMillis] - searched again only after a while.
 * [provider] is a LyricsProvider name.
 */
@Entity(tableName = "lyrics_cache")
data class LyricsCacheEntity(
    @PrimaryKey val key: String,
    val plain: String?,
    val synced: String?,
    val provider: String?,
    val fetchedAtMillis: Long
)

/** A song's name and lyrics, for copying lyrics songs already had into the cache. */
data class SongLyricsRow(val telegramMessageId: Long, val title: String, val artist: String, val lyricsPlain: String?, val lyricsSynced: String?)

@Dao
interface LyricsCacheDao {
    @Query("SELECT * FROM lyrics_cache WHERE `key` = :key")
    suspend fun get(key: String): LyricsCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: LyricsCacheEntity)

    @Query("DELETE FROM lyrics_cache WHERE fetchedAtMillis < :before")
    suspend fun deleteOlderThan(before: Long)

    /** Keeps an entry that's already there - for copying lyrics songs already had. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun putIfMissing(entries: List<LyricsCacheEntity>)
}
