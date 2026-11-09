package com.abn3li.telemusic.data.local

import androidx.room.Dao
import androidx.room.Index
import androidx.room.Transaction
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

/** A Spotify song an import couldn't find on YouTube Music, kept with its playlist so it can be
 * looked up by hand later. [orderAt] is where it belongs in the playlist (see SpotifyImporter). */
@Entity(tableName = "spotify_missing", indices = [Index("playlistId")])
data class SpotifyMissingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val spotifyTrackId: String,
    val title: String,
    val artists: String,
    val album: String?,
    val durationMs: Long,
    val orderAt: Long
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLink(link: SpotifyLinkEntity)

    @Query("SELECT songId FROM spotify_track_map WHERE spotifyTrackId = :spotifyTrackId")
    suspend fun songIdFor(spotifyTrackId: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveMatch(match: SpotifyTrackMapEntity)

    /** A song replaced by a better copy: Spotify tracks matched to it now point at the copy. */
    @Query("UPDATE spotify_track_map SET songId = :to WHERE songId = :from")
    suspend fun remapSong(from: Long, to: Long)

    @Query("SELECT * FROM spotify_missing WHERE playlistId = :playlistId ORDER BY orderAt DESC")
    fun observeMissing(playlistId: Long): Flow<List<SpotifyMissingEntity>>

    @Query("DELETE FROM spotify_missing WHERE playlistId = :playlistId")
    suspend fun clearMissing(playlistId: Long)

    @Insert
    suspend fun insertMissing(rows: List<SpotifyMissingEntity>)

    /** An import's songs not found, in place of the last import's. */
    @Transaction
    suspend fun replaceMissing(playlistId: Long, rows: List<SpotifyMissingEntity>) {
        clearMissing(playlistId)
        if (rows.isNotEmpty()) insertMissing(rows)
    }

    @Query("DELETE FROM spotify_missing WHERE id = :id")
    suspend fun deleteMissing(id: Long)

    @Query("DELETE FROM spotify_links")
    suspend fun deleteAllLinks()

    @Query("DELETE FROM spotify_track_map")
    suspend fun deleteAllMatches()

    @Query("DELETE FROM spotify_missing")
    suspend fun deleteAllMissing()
}
