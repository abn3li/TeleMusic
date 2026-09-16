package com.abn3li.telemusic.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ImportedPlaylistDao {
    @Query("SELECT * FROM imported_playlists ORDER BY addedAtMillis DESC")
    fun observeAll(): Flow<List<ImportedPlaylistEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(playlist: ImportedPlaylistEntity)

    @Query("DELETE FROM imported_playlists WHERE browseId = :browseId")
    suspend fun delete(browseId: String)
}
