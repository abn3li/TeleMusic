package com.abn3li.telemusic.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abn3li.telemusic.data.local.SongEntity
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.ui.theme.ink

@Composable
fun LibraryHomeScreen(
    viewModel: LibraryViewModel,
    callbacks: LibraryCallbacks,
    onOpenPlaylists: () -> Unit,
    onOpenArtists: () -> Unit,
    onOpenAlbums: () -> Unit,
    onOpenSongs: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPlaylist: (Long, String) -> Unit,
    onOpenSmartPlaylist: (SmartPlaylistKind) -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenLocalMusic: () -> Unit,
    onOpenRecentlyAdded: () -> Unit,
    onOpenYouTubePlaylist: (String, String, String?) -> Unit,
    onOpenYouTubePlaylists: () -> Unit
) {
    val app = LocalContext.current.applicationContext as TgMusicApp
    val account by app.youtubeAccount.state.collectAsState()
    var followedPlaylists by remember(account.sessionId) {
        mutableStateOf(emptyList<com.abn3li.telemusic.data.browse.BrowseCollection>())
    }
    LaunchedEffect(account.sessionId, account.signedIn) {
        followedPlaylists = if (account.signedIn) app.discoveryRepository.accountPlaylists().orEmpty() else emptyList()
    }
    val home by viewModel.home.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val artists by viewModel.artists.collectAsState()
    val albums by viewModel.albums.collectAsState()
    val pinned by viewModel.pinnedPlaylists.collectAsState()
    val smartCounts by viewModel.smartCounts.collectAsState()
    val actions = rememberLibrarySongActions(viewModel, callbacks.onPlayNext, callbacks.onOpenArtist, callbacks.onOpenAlbum)
    var createPlaylist by rememberSaveable { mutableStateOf(false) }

    LargeTitleList(title = "Library", titleTrailing = {
        Box(Modifier.size(44.dp).clickable(onClick = onOpenSettings), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.AccountCircle, "Settings", tint = AppAccent, modifier = Modifier.size(34.dp))
        }
    }) {
        item("links", contentType = "library_links") {
            Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                LibraryCategoryRow(Icons.AutoMirrored.Rounded.QueueMusic, "Playlists", playlists.size, onOpenPlaylists) { createPlaylist = true }
                LibraryDivider(start = 60.dp)
                LibraryCategoryRow(Icons.Rounded.MicExternalOn, "Artists", artists.size, onOpenArtists)
                LibraryDivider(start = 60.dp)
                LibraryCategoryRow(Icons.Rounded.Album, "Albums", albums.size, onOpenAlbums)
                LibraryDivider(start = 60.dp)
                LibraryCategoryRow(Icons.Rounded.MusicNote, "Songs", home.allIds.size, onOpenSongs)
                LibraryDivider(start = 60.dp)
            }
        }
        // Where the songs come from: the built-in playlists and the phone's own music.
        item("collections", contentType = "library_shortcuts") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // The sources that hold songs come first; an empty one steps back and dims.
                val tiles = listOf(
                    CollectionTile("Liked", smartCounts.liked, SmartPlaylistKind.LIKED.icon()) { onOpenSmartPlaylist(SmartPlaylistKind.LIKED) },
                    CollectionTile("Telegram", smartCounts.telegram, SmartPlaylistKind.TELEGRAM.icon()) { onOpenSmartPlaylist(SmartPlaylistKind.TELEGRAM) },
                    CollectionTile("Downloads", smartCounts.downloaded, Icons.Rounded.FileDownload, onOpenDownloads),
                    CollectionTile("Local music", smartCounts.local, Icons.Outlined.Folder, onOpenLocalMusic)
                ).sortedBy { it.count == 0 }
                tiles.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { LibraryCollectionButton(it.label, it.count, it.icon, it.onClick, Modifier.weight(1f)) }
                    }
                }
            }
        }
        if (pinned.isNotEmpty()) {
            item("pinned_header", contentType = "section_header") { LibraryShelfHeader("Pinned") }
            item("pinned_shelf", contentType = "playlist_shelf") {
                LazyRow(Modifier.graphicsLayer(), contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(pinned, key = { _, item -> item.key }, contentType = { _, _ -> "pinned_playlist" }) { _, item ->
                        PinnedTile(item = item, onOpen = {
                            if (item.smartKind != null) onOpenSmartPlaylist(item.smartKind)
                            else item.playlistId?.let { onOpenPlaylist(it, item.title) }
                        }, onUnpin = { viewModel.togglePin(item.key) }, modifier = Modifier.width(156.dp))
                    }
                }
            }
        }
        item("recent_header", contentType = "section_header") {
            LibraryShelfHeader("Recently added", onOpenRecentlyAdded)
        }
        if (home.recentlyAdded.isEmpty()) {
            item("recent_empty") {
                Text("Songs you add to your library appear here.", color = ink.copy(alpha = 0.55f), fontSize = 15.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
            }
        } else {
            item("recent_shelf", contentType = "song_shelf") {
                val songs = home.recentlyAdded
                val ids = remember(songs) { songs.map { it.telegramMessageId } }
                LazyRow(Modifier.graphicsLayer(), contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(songs, key = { _, song -> song.telegramMessageId }, contentType = { _, _ -> "recent_song" }) { index, song ->
                        RecentlyAddedCard(song, actions) { callbacks.onPlay(ids, index) }
                    }
                }
            }
        }
        if (followedPlaylists.isNotEmpty()) {
            item("youtube_header", contentType = "section_header") { LibraryShelfHeader("YouTube Playlists", onOpenYouTubePlaylists) }
            item("youtube_shelf", contentType = "playlist_shelf") {
                LazyRow(Modifier.graphicsLayer(), contentPadding = PaddingValues(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(followedPlaylists, key = { _, playlist -> playlist.browseId }) { _, playlist ->
                        Column(Modifier.width(156.dp).clickable { onOpenYouTubePlaylist(playlist.browseId, playlist.title, playlist.params) }) {
                            CoverTile(playlist.thumbnailUrl, Modifier.fillMaxWidth().aspectRatio(1f)
                                .feedArtworkBorder(RoundedCornerShape(9.dp)), corner = 9,
                                placeholder = if (playlist.browseId == "VLLM") Icons.Rounded.Star else Icons.AutoMirrored.Rounded.QueueMusic)
                            Spacer(Modifier.height(7.dp))
                            Text(playlist.title, color = ink, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(2.dp))
                            Text(playlist.subtitle ?: "YouTube Music", color = ink.copy(alpha = 0.5f),
                                fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
    if (createPlaylist) NewPlaylistDialog(
        onCreate = { name -> viewModel.createPlaylist(name); createPlaylist = false },
        onDismiss = { createPlaylist = false }
    )
}

private class CollectionTile(val label: String, val count: Int, val icon: ImageVector, val onClick: () -> Unit)

/** Every playlist of the signed-in YouTube Music account, as a grid: "See all" of the Library shelf. */
@Composable
fun LibraryYouTubePlaylistsScreen(onBack: () -> Unit, onOpenYouTubePlaylist: (String, String, String?) -> Unit) {
    val app = LocalContext.current.applicationContext as TgMusicApp
    // The Library page loaded the same list for its shelf, so this is answered from memory.
    val playlists by produceState(emptyList<com.abn3li.telemusic.data.browse.BrowseCollection>()) {
        value = app.discoveryRepository.accountPlaylists().orEmpty()
    }
    LargeTitleGrid(title = "YouTube Playlists", onBack = onBack) {
        items(playlists, key = { it.browseId }) { playlist ->
            Column(Modifier.fillMaxWidth().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                onOpenYouTubePlaylist(playlist.browseId, playlist.title, playlist.params)
            }) {
                CoverTile(playlist.thumbnailUrl, Modifier.fillMaxWidth().aspectRatio(1f)
                    .feedArtworkBorder(RoundedCornerShape(9.dp)), corner = 9,
                    placeholder = if (playlist.browseId == "VLLM") Icons.Rounded.Star else Icons.AutoMirrored.Rounded.QueueMusic)
                Spacer(Modifier.height(7.dp))
                Text(playlist.title, color = ink, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(playlist.subtitle ?: "YouTube Music", color = ink.copy(alpha = 0.5f),
                    fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun LibraryShelfHeader(title: String, onSeeAll: (() -> Unit)? = null) {
    // Same header as Home's shelves: one title size, one "See all" style, across the app.
    HomeSectionHeader(title, onSeeAll = onSeeAll)
}

@Composable
private fun LibraryCategoryRow(icon: ImageVector, label: String, count: Int, onClick: () -> Unit, onAdd: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().height(56.dp).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = AppAccent, modifier = Modifier.padding(start = 18.dp, end = 12.dp).size(30.dp))
        Text(label, color = ink, fontSize = 21.sp, modifier = Modifier.weight(1f))
        if (onAdd != null) Box(Modifier.size(48.dp).clickable(onClick = onAdd), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Add, "Create playlist", tint = AppAccent, modifier = Modifier.size(25.dp))
        }
        Text(count.toString(), color = ink.copy(alpha = 0.5f), fontSize = 16.sp)
        ChevronIcon(Modifier.padding(start = 18.dp, end = 21.dp))
    }
}

/** A source of songs under the Library rows: icon, name, and how many songs; dimmed while it holds none. */
@Composable
private fun LibraryCollectionButton(label: String, count: Int, icon: ImageVector, onClick: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Row(modifier.heightIn(min = 54.dp).border(0.75.dp, ink.copy(alpha = 0.18f), shape)
        .clip(shape).background(ink.copy(alpha = 0.025f)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        val empty = count == 0
        Icon(icon, null, tint = if (empty) ink.copy(alpha = 0.35f) else AppAccent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(11.dp))
        Text(label, color = if (empty) ink.copy(alpha = 0.5f) else ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(count.toString(), color = ink.copy(alpha = if (empty) 0.3f else 0.45f), fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentlyAddedCard(song: SongEntity, actions: LibrarySongActions, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    var anchorTop by remember { mutableFloatStateOf(0f) }
    Column(Modifier.width(156.dp).onPlaced { anchorTop = it.positionInWindow().y }
        .combinedClickable(onClick = onClick, onLongClick = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            menuOpen = true
        })) {
        SongArtwork(song, 156.dp, 9.dp, Modifier.feedArtworkBorder(RoundedCornerShape(9.dp)))
        Spacer(Modifier.height(7.dp))
        Text(song.title, color = ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        Text(song.artist, color = ink.copy(alpha = 0.5f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (menuOpen) SongContextMenu(song, actions, anchorTop) { menuOpen = false }
}
