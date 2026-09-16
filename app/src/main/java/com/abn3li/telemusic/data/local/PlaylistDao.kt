package com.abn3li.telemusic.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY name ASC")
    fun observeAll(): Flow<List<PlaylistEntity>>

    // Playlists have no cover art of their own - this pulls one real song's art (the most
    // recently added track that actually has one) per playlist for the row icon, same
    // MIN()/subquery-per-group trick observeArtists() uses in SongDao.
    @Query(
        """
        SELECT playlists.id AS id, playlists.name AS name, playlists.createdAtMillis AS createdAtMillis,
            (
                SELECT songs.albumArtUrl FROM songs
                INNER JOIN playlist_song_cross_ref ON songs.telegramMessageId = playlist_song_cross_ref.songId
                WHERE playlist_song_cross_ref.playlistId = playlists.id
                    AND songs.albumArtUrl IS NOT NULL AND songs.albumArtUrl != ''
                ORDER BY playlist_song_cross_ref.addedAtMillis DESC
                LIMIT 1
            ) AS albumArtUrl
        FROM playlists ORDER BY playlists.name ASC
        """
    )
    fun observeAllWithArt(): Flow<List<PlaylistSummary>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observeById(id: Long): Flow<PlaylistEntity?>

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE playlists SET hiddenFromTracks = :hidden WHERE id = :id")
    suspend fun setHiddenFromTracks(id: Long, hidden: Boolean)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addSong(crossRef: PlaylistSongCrossRef)

    @Query(
        """
        SELECT songs.* FROM songs
        INNER JOIN playlist_song_cross_ref ON songs.telegramMessageId = playlist_song_cross_ref.songId
        WHERE playlist_song_cross_ref.playlistId = :playlistId
        ORDER BY playlist_song_cross_ref.addedAtMillis DESC
        """
    )
    fun observeSongsInPlaylist(playlistId: Long): Flow<List<SongEntity>>

    // Backs the Tracks tab's exclusion filter - every song id belonging to a playlist that's
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
