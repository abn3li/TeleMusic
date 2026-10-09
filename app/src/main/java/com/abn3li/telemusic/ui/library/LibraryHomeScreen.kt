package com.abn3li.telemusic.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abn3li.telemusic.data.local.SongEntity
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
    onOpenRecentlyAdded: () -> Unit
) {
    val home by viewModel.home.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val artists by viewModel.artists.collectAsState()
    val albums by viewModel.albums.collectAsState()
    val pinned by viewModel.pinnedPlaylists.collectAsState()
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
        item("device_music", contentType = "library_shortcuts") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LibraryDeviceButton("Downloads", Icons.Rounded.FileDownload, onOpenDownloads, Modifier.weight(1f))
                LibraryDeviceButton("Local music", Icons.Outlined.Folder, onOpenLocalMusic, Modifier.weight(1f))
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
    }
    if (createPlaylist) NewPlaylistDialog(
        onCreate = { name -> viewModel.createPlaylist(name); createPlaylist = false },
        onDismiss = { createPlaylist = false }
    )
}

@Composable
private fun LibraryShelfHeader(title: String, onSeeAll: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 14.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (onSeeAll != null) Row(Modifier.heightIn(min = 48.dp).clickable(onClick = onSeeAll).padding(start = 8.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("See all", color = ink.copy(alpha = 0.5f), fontSize = 14.sp)
            Spacer(Modifier.width(7.dp))
            ChevronIcon()
        }
    }
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

@Composable
private fun LibraryDeviceButton(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Row(modifier.heightIn(min = 50.dp).border(0.75.dp, ink.copy(alpha = 0.18f), shape)
        .clip(shape).background(ink.copy(alpha = 0.025f)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = ink.copy(alpha = 0.65f), modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
