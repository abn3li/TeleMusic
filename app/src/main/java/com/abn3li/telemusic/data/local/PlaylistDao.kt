package com.abn3li.telemusic.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY name ASC")
    fun observeAll(): Flow<List<PlaylistEntity>>

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
