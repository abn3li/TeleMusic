package com.abn3li.telemusic.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.abn3li.telemusic.data.local.SongEntity
import com.abn3li.telemusic.data.local.SongDao
import com.abn3li.telemusic.data.remote.ITunesTrack
import com.abn3li.telemusic.data.remote.NetworkModule
import kotlinx.coroutines.CancellationException

internal data class AlbumPosition(val disc: Int, val track: Int)
internal fun trackTag(value: String?): Int? = value?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it > 0 }

internal fun albumOrder(songs: List<SongEntity>, positions: Map<Long, AlbumPosition>, ascending: Boolean): List<SongEntity> {
    val ordered = songs.sortedWith(compareBy<SongEntity>(
        { if (it.telegramMessageId in positions) 0 else 1 },
        { positions[it.telegramMessageId]?.disc ?: 1 },
        { positions[it.telegramMessageId]?.track ?: Int.MAX_VALUE },
        { if (!it.isLocalImport && it.youtubeVideoId == null) it.telegramMessageId else it.addedAtMillis },
        { it.telegramMessageId }
    ))
    return if (ascending) ordered else ordered.asReversed()
}

/** Read files off the UI thread and persist positions across restarts.
 * Missing positions can be filled from an unambiguous matching catalog album. */
internal class AlbumTrackOrder(private val context: Context, private val dao: SongDao) {
    private val tags = mutableMapOf<String, AlbumPosition?>()
    private val localPositions = mutableSetOf<Long>()
    suspend fun sort(songs: List<SongEntity>, ascending: Boolean): List<SongEntity> {
        val positions = songs.mapNotNull { song ->
            val saved = song.trackNumber?.takeIf { it > 0 }?.let { AlbumPosition(song.discNumber ?: 1, it) }
            if (saved != null) return@mapNotNull song.telegramMessageId to saved
            val path = song.localFilePath ?: return@mapNotNull null
            if (!tags.containsKey(path)) {
                val result = runCatching {
                    val reader = MediaMetadataRetriever()
                    try {
                        if (path.startsWith("content://")) reader.setDataSource(context, Uri.parse(path))
                        else reader.setDataSource(path)
                        trackTag(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER))?.let {
                            AlbumPosition(trackTag(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)) ?: 1, it)
                        }
                    } finally { reader.release() }
                }
                // Cache a successful inspection (even an untagged file), never a failed read.
                if (result.isSuccess) tags[path] = result.getOrNull()
            }
            tags[path]?.let {
                localPositions.add(song.telegramMessageId)
                dao.setAlbumPosition(song.telegramMessageId, it.disc, it.track)
                song.telegramMessageId to it
            }
        }.toMap()
        return albumOrder(songs, positions, ascending)
    }
    private var catalogAttempted = false
    suspend fun resolveCatalog(songs: List<SongEntity>) {
        if (catalogAttempted || songs.isEmpty() || songs.all { it.trackNumber != null }) return
        catalogAttempted = true
        val album = songs.first().album?.takeIf { it.isNotBlank() && it != "Unknown Album" } ?: return
        try {
            val api = NetworkModule.iTunesApi
            val candidates = api.search("$album ${songs.first().artist}", entity = "album", limit = 25).results
                .filter { albumKey(it.collectionName) == albumKey(album) && it.collectionId != null }
                .filter { candidate -> songs.any { albumKey(it.artist) == albumKey(candidate.artistName) } }
            // Same-name editions are ambiguous. Do not guess one from search ranking.
            val candidate = candidates.distinctBy { it.collectionId }.singleOrNull() ?: return
            val tracks = api.albumTracks(candidate.collectionId!!).results
            for (song in songs.filter { it.trackNumber == null && it.telegramMessageId !in localPositions }) {
                catalogPosition(song, tracks)?.let { dao.setAlbumPosition(song.telegramMessageId, it.disc, it.track) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { catalogAttempted = false }
    }
}

internal fun albumKey(value: String?): String = value.orEmpty().lowercase(java.util.Locale.ROOT).filter { it.isLetterOrDigit() }

internal fun catalogPosition(song: SongEntity, tracks: List<ITunesTrack>): AlbumPosition? {
    val matches = tracks.filter {
        !song.album.isNullOrBlank() && albumKey(it.collectionName) == albumKey(song.album) &&
            albumKey(it.trackName) == albumKey(song.title) && albumKey(it.artistName) == albumKey(song.artist) &&
            (song.durationSeconds <= 0 || it.trackTimeMillis == null || kotlin.math.abs(it.trackTimeMillis / 1000 - song.durationSeconds) <= 3) &&
            (it.trackNumber ?: 0) > 0
    }
    val track = matches.singleOrNull() ?: return null
    return AlbumPosition(track.discNumber?.takeIf { it > 0 } ?: 1, track.trackNumber!!)
}
