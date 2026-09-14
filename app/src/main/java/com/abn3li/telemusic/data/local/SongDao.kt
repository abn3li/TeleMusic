package com.abn3li.telemusic.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {

    @Query("SELECT * FROM songs ORDER BY addedAtMillis DESC")
    fun observeAll(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE telegramMessageId = :id")
    suspend fun getById(id: Long): SongEntity?

    @Query("SELECT * FROM songs WHERE telegramMessageId = :id")
    fun observeById(id: Long): Flow<SongEntity?>

    @Query("SELECT * FROM songs WHERE LOWER(TRIM(title)) = LOWER(TRIM(:title)) AND LOWER(TRIM(artist)) = LOWER(TRIM(:artist)) LIMIT 1")
    suspend fun findByTitleAndArtist(title: String, artist: String): SongEntity?

    @Query("SELECT * FROM songs WHERE LOWER(TRIM(title)) = LOWER(TRIM(:title)) AND ABS(durationSeconds - :duration) <= 2 LIMIT 1")
    suspend fun findByTitleAndDuration(title: String, duration: Int): SongEntity?

    @Query("SELECT * FROM songs WHERE metadataEnriched = 0")
    suspend fun getUnenriched(): List<SongEntity>

    @Query("SELECT * FROM songs WHERE albumArtUrl IS NOT NULL AND albumArtUrl != '' AND (thumbnailPath IS NULL OR thumbnailPath = '')")
    suspend fun getSongsMissingThumbnail(): List<SongEntity>

    @Query("UPDATE songs SET thumbnailPath = :path WHERE telegramMessageId = :id")
    suspend fun setThumbnailPath(id: Long, path: String)

    @Query("SELECT * FROM songs WHERE title LIKE '%' || :query || '%' OR artist LIKE '%' || :query || '%'")
    fun search(query: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE isFavorite = 1")
    fun observeFavorites(): Flow<List<SongEntity>>

    @Query("UPDATE songs SET isFavorite = :isFavorite WHERE telegramMessageId = :id")
    suspend fun setFavorite(id: Long, isFavorite: Boolean)

    @Query(
        """
        SELECT COALESCE(NULLIF(album, ''), 'Unknown Album') AS album, artist AS artist,
               COUNT(*) AS songCount, MIN(albumArtUrl) AS albumArtUrl
        FROM songs GROUP BY COALESCE(NULLIF(album, ''), 'Unknown Album') ORDER BY album ASC
        """
    )
    fun observeAlbums(): Flow<List<AlbumSummary>>

    @Query("SELECT * FROM songs WHERE COALESCE(NULLIF(album, ''), 'Unknown Album') = :album ORDER BY title ASC")
    fun observeSongsByAlbum(album: String): Flow<List<SongEntity>>

    @Query(
        """
        SELECT artist AS artist, COUNT(*) AS songCount, MIN(albumArtUrl) AS albumArtUrl
        FROM songs GROUP BY artist ORDER BY artist ASC
        """
    )
    fun observeArtists(): Flow<List<ArtistSummary>>

    @Query("SELECT DISTINCT artist FROM songs")
    suspend fun getDistinctArtists(): List<String>

    @Query("UPDATE songs SET artist = :newArtist WHERE artist = :oldArtist")
    suspend fun renameArtist(oldArtist: String, newArtist: String)

    @Query("SELECT * FROM songs WHERE artist = :artist ORDER BY title ASC")
    fun observeSongsByArtist(artist: String): Flow<List<SongEntity>>

    // --- Cache management: only ever touches the auto-cache, never explicit downloads or a
    // local import (isLocalImport's localFilePath is the user's OWN file, never safe to delete) ---
    @Query("SELECT * FROM songs WHERE localFilePath IS NOT NULL AND isExplicitDownload = 0 AND isLocalImport = 0 ORDER BY lastPlayedAtMillis ASC")
    suspend fun getAutoCachedSongsOldestFirst(): List<SongEntity>

    @Query("UPDATE songs SET lastPlayedAtMillis = :timestamp WHERE telegramMessageId = :id")
    suspend fun stampLastPlayed(id: Long, timestamp: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(song: SongEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(songs: List<SongEntity>)

    @Update
    suspend fun update(song: SongEntity)

    @Query("DELETE FROM songs WHERE telegramMessageId = :id")
    suspend fun delete(id: Long)

    // For the local-import picker's "already imported" check - see LocalAudioFile.stableSongId().
    @Query("SELECT telegramMessageId FROM songs WHERE isLocalImport = 1")
    suspend fun getLocalImportSongIds(): List<Long>
}