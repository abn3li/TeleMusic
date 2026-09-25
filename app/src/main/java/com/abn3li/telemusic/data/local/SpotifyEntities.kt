package com.abn3li.telemusic.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** A Spotify source (your Liked Songs, a playlist or an album) and the TeleMusic playlist it was
 * imported into. [sourceKey] is "liked", "playlist:<id>" or "album:<id>" - importing the same
 * source again updates that playlist instead of making a second one. */
@Entity(tableName = "spotify_links")
data class SpotifyLinkEntity(
    @PrimaryKey val sourceKey: String,
    val playlistId: Long,
    val name: String,
    val lastImportedAtMillis: Long
)

/** Which library song a Spotify track was matched to, so an update never searches for (or
 * re-matches) a song it already found. */
@Entity(tableName = "spotify_track_map")
data class SpotifyTrackMapEntity(
    @PrimaryKey val spotifyTrackId: String,
    val songId: Long
)

/** How many songs each imported Spotify source's playlist holds right now (a deleted
 * playlist drops out, so its source shows as not imported again). */
data class SpotifyLinkCount(val sourceKey: String, val playlistId: Long, val name: String, val count: Int)

@Dao
interface SpotifyDao {
    @Query(
        """
        SELECT l.sourceKey AS sourceKey, l.playlistId AS playlistId, l.name AS name, COUNT(c.songId) AS count
        FROM spotify_links l
        INNER JOIN playlists p ON p.id = l.playlistId
        LEFT JOIN playlist_song_cross_ref c ON c.playlistId = l.playlistId
        GROUP BY l.sourceKey
        """
    )
    fun observeLinkCounts(): Flow<List<SpotifyLinkCount>>

    @Query("SELECT * FROM spotify_links WHERE sourceKey = :key")
    suspend fun getLink(key: String): SpotifyLinkEntity?

    @Query("SELECT * FROM spotify_links")
    fun observeLinks(): Flow<List<SpotifyLinkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLink(link: SpotifyLinkEntity)

    @Query("SELECT songId FROM spotify_track_map WHERE spotifyTrackId = :spotifyTrackId")
    suspend fun songIdFor(spotifyTrackId: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveMatch(match: SpotifyTrackMapEntity)
}
