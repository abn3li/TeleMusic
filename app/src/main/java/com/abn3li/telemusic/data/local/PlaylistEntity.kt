package com.abn3li.telemusic.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAtMillis: Long = System.currentTimeMillis(),
    // The playlist's own "Hide from tracks" button - when on, every song in this playlist is
    // excluded from the Tracks tab (they stay fully visible inside this playlist, untouched).
    val hiddenFromTracks: Boolean = false
)
