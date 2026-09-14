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

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addSong(crossRef: PlaylistSongCrossRef)

    @Query("DELETE FROM playlist_song_cross_ref WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSong(playlistId: Long, songId: Long)

    @Query(
        """
        SELECT songs.* FROM songs
        INNER JOIN playlist_song_cross_ref ON songs.telegramMessageId = playlist_song_cross_ref.songId
        WHERE playlist_song_cross_ref.playlistId = :playlistId
        ORDER BY playlist_song_cross_ref.addedAtMillis DESC
        """
    )
    fun observeSongsInPlaylist(playlistId: Long): Flow<List<SongEntity>>
}
