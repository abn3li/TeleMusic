package com.abn3li.telemusic.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY name ASC")
    fun observeAll(): Flow<List<PlaylistEntity>>

    // What each playlist's cover was set to (see observeAllWithArt): the photo, or the chosen
    // song's list artwork and link - only while that song is still in the playlist.
    @Query(
        """
        SELECT playlists.id AS id, playlists.name AS name, playlists.createdAtMillis AS createdAtMillis,
            playlists.coverImagePath AS coverImagePath,
            (
                SELECT COALESCE(NULLIF(NULLIF(s.thumbnailPath, ''), 'none'), NULLIF(s.albumArtUrl, '')) FROM songs s
                INNER JOIN playlist_song_cross_ref r ON s.telegramMessageId = r.songId
                WHERE r.playlistId = playlists.id AND s.telegramMessageId = playlists.coverSongId
                LIMIT 1
            ) AS coverSongArt,
            (
                SELECT NULLIF(s.albumArtUrl, '') FROM songs s
                INNER JOIN playlist_song_cross_ref r ON s.telegramMessageId = r.songId
                WHERE r.playlistId = playlists.id AND s.telegramMessageId = playlists.coverSongId
                LIMIT 1
            ) AS coverSongArtUrl
        FROM playlists ORDER BY playlists.name ASC
        """
    )
    fun observeCoverChoices(): Flow<List<PlaylistCoverChoice>>

    // Every distinct artwork in every playlist, with when its newest song was added - one scan
    // of the playlist entries; observeAllWithArt keeps each playlist's newest four.
    @Query(
        """
        SELECT r.playlistId AS playlistId, COALESCE(NULLIF(NULLIF(s.thumbnailPath, ''), 'none'), NULLIF(s.albumArtUrl, '')) AS art,
            NULLIF(s.albumArtUrl, '') AS artUrl, MAX(r.addedAtMillis) AS addedAtMillis
        FROM playlist_song_cross_ref r INNER JOIN songs s ON s.telegramMessageId = r.songId
        GROUP BY r.playlistId, COALESCE(NULLIF(s.albumArtUrl, ''), NULLIF(NULLIF(s.thumbnailPath, ''), 'none'))
        HAVING COALESCE(NULLIF(s.albumArtUrl, ''), NULLIF(NULLIF(s.thumbnailPath, ''), 'none')) IS NOT NULL
        """
    )
    fun observePlaylistArtwork(): Flow<List<PlaylistArtwork>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observeById(id: Long): Flow<PlaylistEntity?>

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE playlists SET coverSongId = :songId, coverImagePath = :imagePath WHERE id = :id")
    suspend fun setCover(id: Long, songId: Long?, imagePath: String?)

    @Query("SELECT coverImagePath FROM playlists WHERE id = :id")
    suspend fun coverImagePath(id: Long): String?

    @Query("SELECT coverImagePath FROM playlists WHERE coverImagePath IS NOT NULL")
    suspend fun coverImagePaths(): List<String>

    /** A merged-away song's cover choice follows its playlist places to [to]. */
    @Query("UPDATE playlists SET coverSongId = :to WHERE coverSongId = :from")
    suspend fun moveCoverSong(from: Long, to: Long)

    /** A song leaving the library stops being any playlist's cover. */
    @Query("UPDATE playlists SET coverSongId = NULL WHERE coverSongId = :songId")
    suspend fun clearCoverSong(songId: Long)

    @Query("UPDATE playlists SET hiddenFromTracks = :hidden WHERE id = :id")
    suspend fun setHiddenFromTracks(id: Long, hidden: Boolean)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addSong(crossRef: PlaylistSongCrossRef)

    @Query("SELECT COUNT(*) FROM playlists WHERE id = :id")
    suspend fun countById(id: Long): Int

    @Query("SELECT songId FROM playlist_song_cross_ref WHERE playlistId = :playlistId")
    suspend fun songIdsInPlaylist(playlistId: Long): List<Long>

    @Query("SELECT * FROM playlist_song_cross_ref WHERE songId = :songId")
    suspend fun getEntriesForSong(songId: Long): List<PlaylistSongCrossRef>

    @Query("DELETE FROM playlist_song_cross_ref WHERE songId = :songId")
    suspend fun removeEntriesForSong(songId: Long)

    /** Songs whose playlist entry points at a playlist that no longer exists. */
    @Query("SELECT DISTINCT songId FROM playlist_song_cross_ref WHERE playlistId NOT IN (SELECT id FROM playlists)")
    suspend fun songIdsInDeletedPlaylists(): List<Long>

    @Query("DELETE FROM playlist_song_cross_ref WHERE playlistId NOT IN (SELECT id FROM playlists)")
    suspend fun removeEntriesForDeletedPlaylists()

    @Query("DELETE FROM playlist_song_cross_ref WHERE playlistId = :playlistId")
    suspend fun removeEntriesForPlaylist(playlistId: Long)

    @Query(
        """
        SELECT songs.* FROM songs
        INNER JOIN playlist_song_cross_ref ON songs.telegramMessageId = playlist_song_cross_ref.songId
        WHERE playlist_song_cross_ref.playlistId = :playlistId
        ORDER BY playlist_song_cross_ref.addedAtMillis DESC
        """
    )
    fun observeSongsInPlaylist(playlistId: Long): Flow<List<SongEntity>>

    // Backs the Songs page's exclusion filter - every song id belonging to a playlist that's
    // had its own "Hide from tracks" button turned on, not every playlisted song in general.
    @Query(
        """
        SELECT DISTINCT playlist_song_cross_ref.songId FROM playlist_song_cross_ref
        INNER JOIN playlists ON playlists.id = playlist_song_cross_ref.playlistId
        WHERE playlists.hiddenFromTracks = 1
        """
    )
    fun observeSongIdsInHiddenPlaylists(): Flow<List<Long>>
}
