package com.abn3li.telemusic.data.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn

data class AlbumSummary(val album: String, val artist: String, val songCount: Int, val albumArtUrl: String?)
// albumArtUrl here is one of the artist's own songs' art (there's no separate "artist photo"
// concept anywhere in this app's data - Telegram/local imports/YouTube downloads only ever carry
// per-track cover art), same MIN()-over-the-group trick observeAlbums() already uses.
data class ArtistSummary(val artist: String, val songCount: Int, val albumArtUrl: String?)

/** A playlist for rows and tiles: [covers] is four images for the 2x2 cover, or one, or none;
 * [artworkUrl] is the one link-style image for Android Auto (null for a photo). */
data class PlaylistSummary(
    val id: Long,
    val name: String,
    val createdAtMillis: Long,
    val covers: List<String>,
    val artworkUrl: String?
)

data class PlaylistCoverChoice(
    val id: Long,
    val name: String,
    val createdAtMillis: Long,
    val coverImagePath: String?,
    val coverSongArt: String?,
    val coverSongArtUrl: String?
)

data class PlaylistArtwork(val playlistId: Long, val art: String, val artUrl: String?, val addedAtMillis: Long)

/** Every playlist with its cover: the chosen photo or song, else its four newest songs with
 * different art as a 2x2 grid, or the newest one when there are fewer (as autoPlaylistCovers). */
fun PlaylistDao.observeAllWithArt(): Flow<List<PlaylistSummary>> =
    combine(observeCoverChoices(), observePlaylistArtwork()) { choices, artwork ->
        val newestFirst = artwork.groupBy { it.playlistId }.mapValues { (_, art) -> art.sortedByDescending { it.addedAtMillis } }
        choices.map { choice ->
            val art = newestFirst[choice.id].orEmpty()
            val covers = when {
                choice.coverImagePath != null -> listOf(playlistCoverThumbPath(choice.coverImagePath))
                choice.coverSongArt != null -> listOf(choice.coverSongArt)
                art.size >= 4 -> art.take(4).map { it.art }
                else -> art.take(1).map { it.art }
            }
            val artworkUrl = when {
                choice.coverImagePath != null -> null
                choice.coverSongArt != null -> choice.coverSongArtUrl
                else -> art.firstNotNullOfOrNull { it.artUrl }
            }
            PlaylistSummary(choice.id, choice.name, choice.createdAtMillis, covers, artworkUrl)
        }
    }.flowOn(Dispatchers.Default)

/** The small copy saved next to a playlist's cover photo, for list rows and tiles. */
fun playlistCoverThumbPath(coverImagePath: String): String = coverImagePath.removeSuffix(".jpg") + "-thumb.jpg"
