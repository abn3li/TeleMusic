package com.abn3li.telemusic.playback

import android.net.Uri
import android.util.Base64
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.abn3li.telemusic.data.local.SongEntity
import com.abn3li.telemusic.data.local.displayArtworkUri
import com.abn3li.telemusic.repository.MusicRepository
import com.abn3li.telemusic.repository.SortField
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val ROOT = "root"
private const val RECENTLY_PLAYED = "recently_played"
private const val LIKED_SONGS = "liked_songs"
private const val PLAYLISTS = "playlists"
private const val ALBUMS = "albums"
private const val ARTISTS = "artists"
private const val ALL_TRACKS = "all_tracks"
private const val PLAYLIST_PREFIX = "playlist:"
private const val ALBUM_PREFIX = "album:"
private const val ARTIST_PREFIX = "artist:"

/**
 * Android Auto/Automotive's browse tree - mirrors the phone app's own Library tabs (Playlists,
 * Tracks, Albums, Artists) rather than inventing a separate car-only structure, so anything found
 * here matches what's already on the phone. [MediaLibraryService.MediaLibrarySession.Callback]
 * answers "what's browsable from here" (onGetLibraryRoot/onGetChildren/onGetItem); the base
 * [MediaSession.Callback.onAddMediaItems] (also overridden here) answers "the user tapped a
 * playable leaf - turn its bare id into something actually playable," reusing the exact same
 * [MusicRepository.resolvePlaybackUri] MusicService's own next/previous handling uses, so a song
 * played from the car resolves identically to one played from the phone.
 *
 * A song leaf's mediaId is always just its plain [SongEntity.telegramMessageId] - the same id
 * used everywhere else in the app - so onAddMediaItems needs no special parsing for the common
 * case. Playlist/album/artist folder ids carry a Base64-encoded name/id after their prefix since
 * names can contain characters that don't belong in an id string.
 */
class AutoLibraryCallback(
    private val repository: MusicRepository,
    private val playbackQueue: PlaybackQueue,
    private val serviceScope: CoroutineScope
) : MediaLibraryService.MediaLibrarySession.Callback {

    override fun onGetLibraryRoot(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> =
        immediateResult(LibraryResult.ofItem(folderItem(ROOT, "TeleMusic", mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED), params))

    override fun onGetItem(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String
    ): ListenableFuture<LibraryResult<MediaItem>> = future {
        val songId = mediaId.toLongOrNull() ?: return@future LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        val song = repository.getSongById(songId) ?: return@future LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        LibraryResult.ofItem(song.toMediaItem(), null)
    }

    override fun onGetChildren(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
        val children: List<MediaItem> = when {
            parentId == ROOT -> listOf(
                folderItem(RECENTLY_PLAYED, "Recently Played", mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
                folderItem(LIKED_SONGS, "Liked Songs", mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
                folderItem(PLAYLISTS, "Playlists", mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
                folderItem(ALBUMS, "Albums", mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
                folderItem(ARTISTS, "Artists", mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
                folderItem(ALL_TRACKS, "All Tracks", mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            )
            parentId == RECENTLY_PLAYED -> repository.getRecentlyPlayed().map { it.toMediaItem() }
            parentId == LIKED_SONGS -> firstSnapshot(repository.observeFavorites(SortField.TITLE, true)).map { it.toMediaItem() }
            parentId == ALL_TRACKS -> firstSnapshot(repository.observeLibrary(SortField.TITLE, true)).map { it.toMediaItem() }
            parentId == PLAYLISTS -> firstSnapshot(repository.observePlaylistSummaries()).map { summary ->
                folderItem(
                    PLAYLIST_PREFIX + summary.id,
                    summary.name,
                    artUri = summary.albumArtUrl?.let(Uri::parse),
                    mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST
                )
            }
            parentId == ALBUMS -> firstSnapshot(repository.observeAlbums()).map { summary ->
                folderItem(
                    ALBUM_PREFIX + encode(summary.album),
                    summary.album,
                    subtitle = summary.artist,
                    artUri = summary.albumArtUrl?.let(Uri::parse),
                    mediaType = MediaMetadata.MEDIA_TYPE_ALBUM
                )
            }
            parentId == ARTISTS -> firstSnapshot(repository.observeArtists()).map { summary ->
                folderItem(
                    ARTIST_PREFIX + encode(summary.artist),
                    summary.artist,
                    artUri = summary.albumArtUrl?.let(Uri::parse),
                    mediaType = MediaMetadata.MEDIA_TYPE_ARTIST
                )
            }
            parentId.startsWith(PLAYLIST_PREFIX) -> {
                val playlistId = parentId.removePrefix(PLAYLIST_PREFIX).toLongOrNull()
                    ?: return@future LibraryResult.ofItemList(ImmutableList.of(), params)
                firstSnapshot(repository.observeSongsInPlaylist(playlistId)).map { it.toMediaItem() }
            }
            parentId.startsWith(ALBUM_PREFIX) -> {
                val album = decode(parentId.removePrefix(ALBUM_PREFIX))
                firstSnapshot(repository.observeSongsByAlbum(album, SortField.TITLE, true)).map { it.toMediaItem() }
            }
            parentId.startsWith(ARTIST_PREFIX) -> {
                val artist = decode(parentId.removePrefix(ARTIST_PREFIX))
                firstSnapshot(repository.observeSongsByArtist(artist, SortField.TITLE, true)).map { it.toMediaItem() }
            }
            else -> emptyList()
        }
        LibraryResult.ofItemList(ImmutableList.copyOf(children), params)
    }

    /**
     * The user tapped a playable leaf in the car's UI - [mediaItems] arrives as the bare browse
     * item (mediaId only, no real URI, built by [SongEntity.toMediaItem] above), which needs
     * resolving into something the player can actually open. Also sets the shared [PlaybackQueue]
     * to just this one song (see this class's own doc for why a richer sibling queue isn't
     * reconstructed here) so the rest of the app's queue-driven UI (MiniPlayer, Now Playing)
     * stays consistent with what the car started playing, exactly like every other "play this one
     * song" entry point already does.
     *
     * Media3's framework routes EVERY controller's setMediaItem()/addMediaItems() call through
     * this same session-wide callback, not just Android Auto's - including PlaybackController
     * .playUri(), which the phone's own in-app UI uses for every single song play. An item that
     * already has a real URI (item.localConfiguration != null) is one of those - already fully
     * resolved and already correctly queued by NowPlayingViewModel - and MUST pass through
     * unchanged here. Without this check, every ordinary phone-side song change was ALSO stomping
     * the real multi-song queue down to a 1-song one via the branch below, which is exactly what
     * made Next/Previous appear to restart the same song: hasNext()/hasPrevious() both go false
     * the instant the queue only has one entry.
     */
    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>
    ): ListenableFuture<List<MediaItem>> {
        if (mediaItems.all { it.localConfiguration != null }) {
            return Futures.immediateFuture(mediaItems)
        }
        val result = SettableFuture.create<List<MediaItem>>()
        serviceScope.launch {
            val resolved = mediaItems.mapNotNull { item ->
                if (item.localConfiguration != null) return@mapNotNull item.mediaId.toLongOrNull()?.let { it to item }
                val songId = item.mediaId.toLongOrNull() ?: return@mapNotNull null
                val song = repository.getSongById(songId) ?: return@mapNotNull null
                val uri = repository.resolvePlaybackUri(song) ?: return@mapNotNull null
                repository.stampLastPlayed(song)
                song.telegramMessageId to buildPlayableItem(song, uri)
            }
            if (resolved.isNotEmpty()) {
                playbackQueue.setQueue(resolved.map { it.first }, 0)
            }
            result.set(resolved.map { it.second })
        }
        return result
    }

    private fun buildPlayableItem(song: SongEntity, uri: Uri): MediaItem =
        MediaItem.Builder()
            .setUri(uri)
            .setMediaId(song.telegramMessageId.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setArtworkUri(song.displayArtworkUri)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build()
            )
            .build()

    private fun SongEntity.toMediaItem(): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setArtworkUri(displayArtworkUri)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setFolderType(MediaMetadata.FOLDER_TYPE_NONE)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()
        return MediaItem.Builder().setMediaId(telegramMessageId.toString()).setMediaMetadata(metadata).build()
    }

    private fun folderItem(id: String, title: String, subtitle: String? = null, artUri: Uri? = null, mediaType: Int): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setArtworkUri(artUri)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
            .setMediaType(mediaType)
            .build()
        return MediaItem.Builder().setMediaId(id).setMediaMetadata(metadata).build()
    }

    private fun encode(value: String): String =
        Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)

    private fun decode(value: String): String =
        String(Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)

    private suspend fun <T> firstSnapshot(flow: Flow<List<T>>): List<T> = flow.first()

    private fun <V> immediateResult(value: V): ListenableFuture<V> = Futures.immediateFuture(value)

    private fun <V> future(block: suspend () -> LibraryResult<V>): ListenableFuture<LibraryResult<V>> {
        val result = SettableFuture.create<LibraryResult<V>>()
        serviceScope.launch { result.set(block()) }
        return result
    }
}
