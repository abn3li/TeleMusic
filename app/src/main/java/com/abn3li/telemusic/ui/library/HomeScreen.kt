package com.abn3li.telemusic.ui.library

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.data.browse.BrowseCollection
import com.abn3li.telemusic.data.local.SongEntity

private val MixDaily = Color(0xFF72243E)
private val MixRediscover = Color(0xFF3C3489)

/**
 * The Home tab: shortcut tiles, then Recently Played, Made for You, Recently Added, Top Artists
 * and a few YouTube Music picks. The library sections come from [LibraryViewModel.home] (worked
 * out once per library change); the YouTube row reuses the Discovery feed's cache. Nothing here
 * animates or polls.
 */
@Composable
fun HomeScreen(
    viewModel: LibraryViewModel,
    callbacks: LibraryCallbacks,
    onOpenSettings: () -> Unit,
    onOpenPlaylist: (Long, String) -> Unit,
    onOpenSmartPlaylist: (SmartPlaylistKind) -> Unit,
    onOpenYouTubeCollection: (BrowseCollection) -> Unit
) {
    val app = LocalContext.current.applicationContext as TgMusicApp
    val home by viewModel.home.collectAsState()
    val artists by viewModel.artists.collectAsState()
    val pinned by viewModel.pinnedPlaylists.collectAsState()
    val youTubePicks by produceState<List<BrowseCollection>>(emptyList()) {
        value = runCatching { app.discoveryRepository.homeFeed() }.getOrNull()
            // The same playlist/album can sit in more than one shelf; LazyRow keys must be unique.
            ?.flatMap { it.items }?.distinctBy { it.browseId }?.take(10).orEmpty()
    }
    val topArtists = remember(artists) { artists.sortedByDescending { it.songCount }.take(12) }
    val pinnedRows = remember(pinned) { pinned.chunked(2) }

    LargeTitleList(
        title = "Home",
        titleTrailing = {
            Icon(
                Icons.Rounded.AccountCircle,
                contentDescription = "Settings",
                tint = AppAccent,
                modifier = Modifier
                    .size(34.dp)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onOpenSettings)
            )
        }
    ) {
        item("shortcuts") {
            Column(Modifier.padding(horizontal = 16.dp).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShortcutTile("Liked Songs", { IconTile(SmartPlaylistKind.LIKED.icon(), 34) }, Modifier.weight(1f)) {
                        onOpenSmartPlaylist(SmartPlaylistKind.LIKED)
                    }
                    ShortcutTile("Telegram Songs", { IconTile(SmartPlaylistKind.TELEGRAM.icon(), 34) }, Modifier.weight(1f)) {
                        onOpenSmartPlaylist(SmartPlaylistKind.TELEGRAM)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShortcutTile("Downloaded", { IconTile(SmartPlaylistKind.DOWNLOADED.icon(), 34) }, Modifier.weight(1f)) {
                        onOpenSmartPlaylist(SmartPlaylistKind.DOWNLOADED)
                    }
                    ShortcutTile("Shuffle All", { IconTile(Icons.Rounded.Shuffle, 34) }, Modifier.weight(1f)) {
                        if (home.allIds.isNotEmpty()) callbacks.onPlayCollection(home.allIds, true)
                    }
                }
            }
        }

        if (pinnedRows.isNotEmpty()) {
            item("pinned_header") { HomeSectionHeader("Pinned") }
            items(pinnedRows, key = { row -> "pinned_" + row.first().key }) { row ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    row.forEachIndexed { column, item ->
                        PinnedTile(
                            item = item,
                            onLeft = column == 0,
                            onOpen = {
                                if (item.smartKind != null) onOpenSmartPlaylist(item.smartKind)
                                else item.playlistId?.let { onOpenPlaylist(it, item.title) }
                            },
                            onUnpin = { viewModel.togglePin(item.key) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        if (home.recentlyPlayed.isNotEmpty()) {
            item("recent_played_header") { HomeSectionHeader("Recently Played") }
            item("recent_played") { SongShelf(home.recentlyPlayed, callbacks) }
        }

        if (home.dailyMix.isNotEmpty() || home.rediscover.isNotEmpty()) {
            item("mixes_header") { HomeSectionHeader("Made for You") }
            item("mixes") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (home.dailyMix.isNotEmpty()) item("daily") {
                        MixCard("Daily Mix", "Your most played", MixDaily) { callbacks.onPlayCollection(home.dailyMix.map { it.telegramMessageId }, false) }
                    }
                    if (home.rediscover.isNotEmpty()) item("rediscover") {
                        MixCard("Rediscover", "Not played in a while", MixRediscover) { callbacks.onPlayCollection(home.rediscover.map { it.telegramMessageId }, false) }
                    }
                }
            }
        }

        if (home.recentlyAdded.isNotEmpty()) {
            item("recent_added_header") { HomeSectionHeader("Recently Added") }
            item("recent_added") { SongShelf(home.recentlyAdded, callbacks) }
        }

        if (topArtists.isNotEmpty()) {
            item("artists_header") { HomeSectionHeader("Top Artists") }
            item("artists") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(topArtists, key = { it.artist }) { artist ->
                        Column(
                            Modifier.width(84.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { callbacks.onOpenArtist(artist.artist) },
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            ArtistAvatar(artist.albumArtUrl, 84)
                            Spacer(Modifier.height(6.dp))
                            Text(artist.artist, color = Color.White.copy(alpha = 0.9f), fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        if (youTubePicks.isNotEmpty()) {
            item("yt_header") { HomeSectionHeader("From YouTube Music") }
            item("yt") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(youTubePicks, key = { it.browseId }) { collection ->
                        Column(Modifier.width(128.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onOpenYouTubeCollection(collection) }) {
                            CoverTile(collection.thumbnailUrl, Modifier.size(128.dp), corner = 6)
                            Spacer(Modifier.height(5.dp))
                            Text(collection.title, color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            collection.subtitle?.let {
                                Text(it, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
        item("end") { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun HomeSectionHeader(title: String) {
    Text(
        title,
        color = Color.White,
        fontSize = 21.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 24.dp, bottom = 10.dp)
    )
}

@Composable
private fun ShortcutTile(label: String, icon: @Composable () -> Unit, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(LibraryFieldColor)
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.width(10.dp))
        Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A sideways row of song covers; tapping one plays the row from that song. */
@Composable
private fun SongShelf(songs: List<SongEntity>, callbacks: LibraryCallbacks) {
    val ids = remember(songs) { songs.map { it.telegramMessageId } }
    val rowState = rememberLazyListState()
    // A newly played/added song is inserted at the front; a keyed row stays anchored on the old
    // first item, leaving the new one hidden off-screen to the left. Snap back to the start when
    // the front changes - unless the user has scrolled along the row. Runs only on that change.
    LaunchedEffect(songs.firstOrNull()?.telegramMessageId) {
        if (rowState.firstVisibleItemIndex <= 1) rowState.scrollToItem(0)
    }
    LazyRow(state = rowState, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(songs, key = { _, s -> s.telegramMessageId }) { index, song ->
            Column(Modifier.width(128.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { callbacks.onPlay(ids, index) }) {
                SongArtwork(song, 128.dp, 6.dp)
                Spacer(Modifier.height(5.dp))
                Text(song.title, color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun MixCard(title: String, subtitle: String, color: Color, onClick: () -> Unit) {
    Column(Modifier.width(158.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)) {
        Box(
            Modifier.size(158.dp, 128.dp).clip(RoundedCornerShape(8.dp)).background(color).padding(12.dp),
            contentAlignment = Alignment.BottomStart
        ) {
            Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(5.dp))
        Text(subtitle, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, maxLines = 1)
    }
}
