package com.abn3li.telemusic.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {

    @Query("SELECT * FROM songs ORDER BY addedAtMillis DESC")
    fun observeAll(): Flow<List<SongEntity>>

    // Android Auto's "Recently Played" browse category - see MusicService's MediaLibrarySession
    // callback. Excludes lastPlayedAtMillis = 0 (never actually played, just added/synced).
    @Query("SELECT * FROM songs WHERE lastPlayedAtMillis > 0 ORDER BY lastPlayedAtMillis DESC LIMIT :limit")
    suspend fun getRecentlyPlayed(limit: Int = 50): List<SongEntity>

    @Query("SELECT * FROM songs WHERE telegramMessageId = :id")
    suspend fun getById(id: Long): SongEntity?

    // Telegram sync's duplicate checks: only another Telegram song counts as "already synced" -
    // a YouTube or local copy of the same song is a different source and doesn't block it.
    @Query("SELECT * FROM songs WHERE telegramFileId != 0 AND LOWER(TRIM(title)) = LOWER(TRIM(:title)) AND LOWER(TRIM(artist)) = LOWER(TRIM(:artist)) LIMIT 1")
    suspend fun findTelegramByTitleAndArtist(title: String, artist: String): SongEntity?

    @Query("SELECT * FROM songs WHERE telegramFileId != 0 AND LOWER(TRIM(title)) = LOWER(TRIM(:title)) AND ABS(durationSeconds - :duration) <= 2 LIMIT 1")
    suspend fun findTelegramByTitleAndDuration(title: String, duration: Int): SongEntity?

    @Query("SELECT * FROM songs WHERE metadataEnriched = 0")
    suspend fun getUnenriched(): List<SongEntity>

    @Query("SELECT * FROM songs WHERE albumArtUrl IS NOT NULL AND albumArtUrl != '' AND (thumbnailPath IS NULL OR thumbnailPath = '')")
    suspend fun getSongsMissingThumbnail(): List<SongEntity>

    @Query("UPDATE songs SET thumbnailPath = :path WHERE telegramMessageId = :id")
    suspend fun setThumbnailPath(id: Long, path: String)

    // Full-size local artwork path (ThumbnailGenerator.saveFullArtwork) for embedded/Telegram-
    // provided art that has no online URL of its own - see displayArtwork's own doc for why this
    // reuses the albumArtUrl column rather than adding a new one (every artwork-display site
    // already reads that column first).
    @Query("UPDATE songs SET albumArtUrl = :path WHERE telegramMessageId = :id")
    suspend fun setAlbumArtUrl(id: Long, path: String)

    @Query("SELECT * FROM songs WHERE title LIKE '%' || :query || '%' OR artist LIKE '%' || :query || '%'")
    fun search(query: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE isFavorite = 1")
    fun observeFavorites(): Flow<List<SongEntity>>

    @Query("UPDATE songs SET isFavorite = :isFavorite WHERE telegramMessageId = :id")
    suspend fun setFavorite(id: Long, isFavorite: Boolean)

    // A real Telegram-synced row always has a non-zero telegramFileId - both a YouTube download
    // and a local import use 0 there (see SongEntity's own doc), so this is the same check the
    // rest of the app already relies on to tell a Telegram row apart from the other two sources.
    @Query("SELECT * FROM songs WHERE telegramFileId != 0")
    fun observeTelegramSongs(): Flow<List<SongEntity>>

    // Only ever true for a song the user explicitly tapped Download on (YouTube or a re-download
    // of a Telegram track) - never a plain streaming cache hit, see isExplicitDownload's own doc.
    @Query("SELECT * FROM songs WHERE isExplicitDownload = 1")
    fun observeDownloaded(): Flow<List<SongEntity>>

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

    // Every localFilePath currently referenced by ANY song row, regardless of source/flags - see
    // MusicRepository.reconcileOrphanedTdlibFiles's own doc for why this needs to span the whole
    // table rather than just the auto-cache subset.
    @Query("SELECT localFilePath FROM songs WHERE localFilePath IS NOT NULL")
    suspend fun getAllReferencedLocalFilePaths(): List<String>

    @Query("UPDATE songs SET lastPlayedAtMillis = :timestamp WHERE telegramMessageId = :id")
    suspend fun stampLastPlayed(id: Long, timestamp: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(song: SongEntity)

    @Update
    suspend fun update(song: SongEntity)

    @Query("DELETE FROM songs WHERE telegramMessageId = :id")
    suspend fun delete(id: Long)

    // For the local-import picker's "already imported" check - see LocalAudioFile.stableSongId().
    @Query("SELECT telegramMessageId FROM songs WHERE isLocalImport = 1")
    suspend fun getLocalImportSongIds(): List<Long>

    // Artwork already in hand counts as "has artwork" whether it came from an online lookup
    // (albumArtUrl) or was pulled straight from the source - an embedded picture for a local
    // import, a Telegram-provided cover for a synced song (both land in thumbnailPath with no
    // albumArtUrl at all). Without the thumbnailPath half of this OR, a song with real local/
    // Telegram artwork would never satisfy this query, stay metadataEnriched=0 forever, and get
    // re-queued for an online lookup on every single enrichMissingMetadata() run - the exact
    // repeated-work loop this flag exists to prevent.
    @Query("UPDATE songs SET metadataEnriched = 1 WHERE title IS NOT NULL AND title != '' AND title != 'Unknown title' AND artist IS NOT NULL AND artist != '' AND artist != 'Unknown artist' AND ((albumArtUrl IS NOT NULL AND albumArtUrl != '') OR (thumbnailPath IS NOT NULL AND thumbnailPath != ''))")
    suspend fun markCompleteSongsEnriched()
}