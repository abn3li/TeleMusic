package com.abn3li.telemusic.ui.download

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.data.browse.*
import com.abn3li.telemusic.data.local.SongEntity
import com.abn3li.telemusic.data.download.ytDlpStableSongId
import com.abn3li.telemusic.repository.DiscoveryRepository
import com.abn3li.telemusic.ui.library.*
import com.abn3li.telemusic.ui.theme.ink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class YouTubeMenuRequest(val track: BrowseTrack?, val collection: BrowseCollection?,
    val anchor: Float, val play: () -> Unit, val download: (() -> Unit)? = null,
    val beforeOpen: () -> Unit = {})

/** Only the open menu observes library state; cards hold no timers or network jobs. */
@Stable
internal class YouTubeMenus internal constructor(
    private val show: (BrowseTrack?, BrowseCollection?, Float, () -> Unit, (() -> Unit)?, () -> Unit) -> Unit
) {
    fun track(track: BrowseTrack, anchor: Float, play: () -> Unit, download: (() -> Unit)? = null,
        beforeOpen: () -> Unit = {}) = show(track, null, anchor, play, download, beforeOpen)
    fun collection(collection: BrowseCollection, anchor: Float, open: () -> Unit) = show(null, collection, anchor, open, null, {})
}

internal val LocalYouTubeMenus = staticCompositionLocalOf<YouTubeMenus> {
    error("YouTube menu provider is missing")
}

@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.youtubeTrackActions(track: BrowseTrack, onClick: () -> Unit,
    onDownload: (() -> Unit)? = null, beforeOpen: () -> Unit = {}): Modifier = composed {
    val menus = LocalYouTubeMenus.current
    val haptics = LocalHapticFeedback.current
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    onPlaced { coordinates[0] = it }.combinedClickable(
        interactionSource = remember { MutableInteractionSource() }, indication = null,
        onClick = onClick, onLongClickLabel = "Options for ${track.title}", onLongClick = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            menus.track(track, coordinates[0]?.takeIf { it.isAttached }?.positionInWindow()?.y ?: 0f, onClick, onDownload, beforeOpen)
        })
}

@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.youtubeCollectionActions(collection: BrowseCollection, onClick: () -> Unit): Modifier = composed {
    val menus = LocalYouTubeMenus.current
    val haptics = LocalHapticFeedback.current
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    onPlaced { coordinates[0] = it }.combinedClickable(
        interactionSource = remember { MutableInteractionSource() }, indication = null,
        onClick = onClick, onLongClickLabel = "Options for ${collection.title}", onLongClick = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            menus.collection(collection, coordinates[0]?.takeIf { it.isAttached }?.positionInWindow()?.y ?: 0f, onClick)
        })
}

internal fun youtubeCollectionLink(collection: BrowseCollection): String? = when {
    collection.browseId.startsWith("VL") -> "https://music.youtube.com/playlist?list=${java.net.URLEncoder.encode(collection.browseId.removePrefix("VL"), "UTF-8")}"
    collection.browseId.startsWith("UC") -> "https://music.youtube.com/channel/${java.net.URLEncoder.encode(collection.browseId, "UTF-8")}"
    collection.browseId.startsWith("MPRE") -> "https://music.youtube.com/browse/${java.net.URLEncoder.encode(collection.browseId, "UTF-8")}"
    else -> null // Local synthetic shelves have no public link to share.
}

private data class YouTubeMenuActions(val open: (BrowseCollection) -> Unit,
    val play: (List<BrowseTrack>, Int, Boolean) -> Unit, val playNext: (List<Long>) -> Unit)

private val LocalYouTubeMenuActions = staticCompositionLocalOf<YouTubeMenuActions> {
    error("YouTube menu actions are missing")
}

@Composable
internal fun YouTubeContextProvider(onOpen: (BrowseCollection) -> Unit,
    onPlay: (List<BrowseTrack>, Int, Boolean) -> Unit, onPlayNext: (List<Long>) -> Unit,
    content: @Composable () -> Unit) {
    val actions = remember(onOpen, onPlay, onPlayNext) { YouTubeMenuActions(onOpen, onPlay, onPlayNext) }
    CompositionLocalProvider(LocalYouTubeMenuActions provides actions) { YouTubeContextHost(content) }
}

/** Dialogs need their own host so the popup belongs to the window above the page. */
@Composable
internal fun YouTubeContextHost(content: @Composable () -> Unit) {
    val actions = LocalYouTubeMenuActions.current
    var request by remember { mutableStateOf<YouTubeMenuRequest?>(null) }
    var conflict by remember { mutableStateOf<DownloadConflict?>(null) }
    val downloads = remember { mutableStateListOf<String>() }
    val imports = remember { mutableStateListOf<String>() }
    val menus = remember { YouTubeMenus { track, collection, anchor, play, download, beforeOpen ->
        request = YouTubeMenuRequest(track, collection, anchor, play, download, beforeOpen)
    } }
    CompositionLocalProvider(LocalYouTubeMenus provides menus) {
        content()
        request?.let { selected ->
            key(selected) {
                YouTubeOptions(selected, onDismiss = { request = null }, actions.open, actions.play, actions.playNext,
                    onConflict = { conflict = it }, downloads = downloads, imports = imports)
            }
        }
        conflict?.let { DownloadAnywayPrompt(it) { conflict = null } }
    }
}

@Composable
private fun YouTubeOptions(request: YouTubeMenuRequest, onDismiss: () -> Unit,
    onOpen: (BrowseCollection) -> Unit, onPlay: (List<BrowseTrack>, Int, Boolean) -> Unit,
    onPlayNext: (List<Long>) -> Unit, onConflict: (DownloadConflict) -> Unit,
    downloads: MutableList<String>, imports: MutableList<String>) {
    val context = LocalContext.current
    val app = context.applicationContext as TgMusicApp
    val repository = app.musicRepository
    val track = request.track
    val collection = request.collection
    val title = track?.title ?: collection!!.title
    val subtitle = track?.artist ?: collection?.subtitle
    val artwork = track?.thumbnailUrl ?: collection?.thumbnailUrl
    var song by remember { mutableStateOf<SongEntity?>(null) }
    var loaded by remember { mutableStateOf(track == null) }
    var playlistsOpen by remember { mutableStateOf(false) }
    val playlistFlow = remember(repository) { repository.observePlaylists() }
    val playlists by playlistFlow.collectAsState(initial = emptyList())
    LaunchedEffect(track?.videoId) {
        if (track != null) song = repository.getSongById(ytDlpStableSongId(track.videoId))
        loaded = true
    }
    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    fun work(action: suspend () -> Unit) {
        app.workScope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { toast("Couldn't complete this action. Try again.") }
        }
    }
    fun share(link: String) {
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, link), title))
    }
    suspend fun savedSong(): SongEntity = repository.importPlaylistTrackAsStreamable(track!!)
    suspend fun collectionTracks(): List<BrowseTrack> {
        val page = app.discoveryRepository.browse(collection!!.browseId, collection.params)
        return page.tracks.ifEmpty { page.artist?.topSongs.orEmpty() }
    }
    LibraryContextMenu(request.anchor, onDismiss) { maxHeight, onAction ->
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight)
            .graphicsLayer { shape = RoundedCornerShape(18.dp); clip = true }
            .background(LibraryCardColor)
            .border(0.5.dp, ink.copy(alpha = 0.12f), RoundedCornerShape(18.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Thumbnail(artwork, Modifier.size(44.dp), 6, 150)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(title, color = ink, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle ?: collection?.kind?.name?.lowercase()?.replaceFirstChar { it.uppercase() }.orEmpty(),
                        color = ink.copy(alpha = .55f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            CardDivider()
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                if (track != null) {
                    CardItem("Play", Icons.Rounded.PlayArrow) { onAction(request.play) }
                    CardDivider()
                    CardItem("Play Next", Icons.Rounded.QueuePlayNext) { onAction { work {
                        val id = withContext(Dispatchers.Default) { repository.queueIdsForStreams(listOf(track)).single() }
                        onPlayNext(listOf(id)); toast("Playing next")
                    } } }
                    CardDivider()
                    CardItem("Add to Playlist", Icons.AutoMirrored.Rounded.PlaylistAdd, expandable = true, expanded = playlistsOpen) {
                        playlistsOpen = !playlistsOpen
                    }
                    AnimatedVisibility(playlistsOpen) {
                        PlaylistPicker(playlists, onPick = { playlist -> onAction { work {
                            repository.addSongToPlaylist(playlist.id, savedSong()); toast("Added to ${playlist.name}")
                        } } }, onCreate = { name -> onAction { work {
                            val saved = savedSong()
                            repository.addSongToPlaylist(repository.createPlaylist(name), saved); toast("Added to $name")
                        } } })
                    }
                    CardDivider()
                    CardItem(if (song?.isFavorite == true) "Remove from Favorites" else "Add to Favorites",
                        if (song?.isFavorite == true) Icons.Rounded.Star else Icons.Rounded.StarBorder, enabled = loaded) {
                        onAction { work { val saved = savedSong(); repository.setFavorite(saved, !saved.isFavorite) } }
                    }
                    CardDivider()
                    CardItem(when { track.videoId in downloads -> "Downloading…"; song?.isExplicitDownload == true -> "Downloaded"; else -> "Download" }, Icons.Rounded.Download,
                        enabled = loaded && song?.isExplicitDownload != true && track.videoId !in downloads) { onAction {
                        fun download() { app.downloadGate.run {
                            if (track.videoId !in downloads) {
                                downloads.add(track.videoId)
                                work {
                                    try {
                                        val id = withContext(Dispatchers.Default) { repository.queueIdsForStreams(listOf(track)).single() }
                                        val current = repository.getSongById(id) ?: error("Song unavailable")
                                        repository.downloadExplicitly(current)
                                        toast("Downloaded")
                                    } finally { downloads.remove(track.videoId) }
                                }
                            }
                        } }
                        if (request.download != null) request.download.invoke() else work {
                            val existing = repository.betterCopyInLibrary(track.videoId, track.title, track.artist, track.durationSeconds)
                            if (existing == null) download() else onConflict(DownloadConflict(track.title, existing.higherQuality) {
                                repository.keepBothCopies(track.videoId); download()
                            })
                        }
                    } }
                    CardDivider()
                    CardItem("Go to Artist", Icons.Rounded.MicExternalOn) { onAction {
                        request.beforeOpen()
                        onOpen(BrowseCollection(DiscoveryRepository.ARTIST_OF_PREFIX + track.videoId,
                            track.artist, track.artist, null, track.thumbnailUrl, BrowseKind.ARTIST))
                    } }
                    CardDivider()
                    CardItem("Share", Icons.Rounded.Share) { onAction { share("https://music.youtube.com/watch?v=${android.net.Uri.encode(track.videoId)}") } }
                } else if (collection != null) {
                    CardItem("Open", Icons.AutoMirrored.Rounded.OpenInNew) { onAction(request.play) }
                    if (collection.kind == BrowseKind.ALBUM || collection.kind == BrowseKind.PLAYLIST) {
                        CardDivider()
                        CardItem("Play", Icons.Rounded.PlayArrow) { onAction { work {
                            val tracks = collectionTracks()
                            if (tracks.isEmpty()) toast("No songs available") else onPlay(tracks, 0, false)
                        } } }
                        CardDivider()
                        CardItem("Shuffle", Icons.Rounded.Shuffle) { onAction { work {
                            val tracks = collectionTracks()
                            if (tracks.isEmpty()) toast("No songs available") else onPlay(tracks, 0, true)
                        } } }
                        CardDivider()
                        CardItem("Play Next", Icons.Rounded.QueuePlayNext) { onAction { work {
                            val tracks = collectionTracks()
                            val ids = withContext(Dispatchers.Default) { repository.queueIdsForStreams(tracks) }
                            if (ids.isNotEmpty()) onPlayNext(ids)
                            toast(if (ids.isEmpty()) "No songs available" else "Playing next")
                        } } }
                        CardDivider()
                        CardItem(if (collection.browseId in imports) "Importing…" else "Import to Library", Icons.Rounded.LibraryAdd,
                            enabled = collection.browseId !in imports) { onAction {
                            if (collection.browseId !in imports) {
                                imports.add(collection.browseId)
                                work {
                                    try {
                                        val tracks = collectionTracks()
                                        if (tracks.isEmpty()) toast("No songs available") else {
                                            if (collection.kind == BrowseKind.ALBUM) {
                                                tracks.forEach { repository.importPlaylistTrackAsStreamable(it, title) }
                                            } else {
                                                val playlistId = repository.createPlaylist(title)
                                                // Library playlists sort newest first; preserve the collection's order.
                                                val startedAt = System.currentTimeMillis()
                                                tracks.forEachIndexed { index, item ->
                                                    val saved = repository.importPlaylistTrackAsStreamable(item)
                                                    repository.addSongToPlaylistAt(playlistId, saved.telegramMessageId, startedAt - index)
                                                }
                                            }
                                            repository.backfillThumbnails()
                                            repository.mergeCrossSourceDuplicates()
                                            toast("Added to Library")
                                        }
                                    } finally { imports.remove(collection.browseId) }
                                    }
                            }
                        } }
                    }
                    youtubeCollectionLink(collection)?.let { link ->
                        CardDivider()
                        CardItem("Share", Icons.Rounded.Share) { onAction { share(link) } }
                    }
                }
            }
        }
    }
}
