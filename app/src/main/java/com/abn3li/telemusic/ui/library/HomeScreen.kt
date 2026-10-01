package com.abn3li.telemusic.ui.library

import com.abn3li.telemusic.ui.theme.LocalPalette
import com.abn3li.telemusic.ui.theme.paper
import com.abn3li.telemusic.ui.theme.ink
import androidx.compose.foundation.border
import com.abn3li.telemusic.data.local.displayArtwork
import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.foundation.layout.fillMaxSize
import com.abn3li.telemusic.ui.nowplaying.BlurredArtwork
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import com.abn3li.telemusic.data.browse.BrowseKind
import com.abn3li.telemusic.repository.DiscoveryRepository
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.data.browse.BrowseCollection
import com.abn3li.telemusic.data.local.SongEntity
import com.abn3li.telemusic.ui.download.CollectionCard
import com.abn3li.telemusic.ui.download.NewReleasesViewModel

private val MixDaily = Color(0xFF72243E)
private val MixRediscover = Color(0xFF3C3489)

/**
 * The Home tab: shortcuts and the user's library sections from [LibraryViewModel.home] (worked
 * out once per library change), with YouTube Music's New releases shelf last.
 */
@Composable
fun HomeScreen(
    viewModel: LibraryViewModel,
    callbacks: LibraryCallbacks,
    onOpenSettings: () -> Unit,
    onOpenPlaylist: (Long, String) -> Unit,
    onOpenSmartPlaylist: (SmartPlaylistKind) -> Unit,
    onOpenCollection: (BrowseCollection) -> Unit,
    // The song loaded in the player, if any - lets "Continue Listening" pause/resume it in place.
    nowPlayingId: Long? = null,
    isPlaying: Boolean = false
) {
    val app = LocalContext.current.applicationContext as TgMusicApp
    val newReleasesViewModel = viewModel<NewReleasesViewModel>(factory = viewModelFactory { initializer {
        NewReleasesViewModel(app.discoveryRepository)
    } })
    val newReleases by newReleasesViewModel.uiState.collectAsState()
    LaunchedEffect(Unit) { newReleasesViewModel.retryIfMissing() }
    val home by viewModel.home.collectAsState()
    val artists by viewModel.artists.collectAsState()
    val pinned by viewModel.pinnedPlaylists.collectAsState()
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
        // The last song played, large, with its own blurred colours: one tap back into it.
        home.recentlyPlayed.firstOrNull()?.let { last ->
            item("continue") {
                val isCurrent = last.telegramMessageId == nowPlayingId
                ContinueListeningCard(last, isCurrent && isPlaying) {
                    if (isCurrent) callbacks.onTogglePlayPause()
                    else callbacks.onPlay(home.recentlyPlayed.map { it.telegramMessageId }, 0)
                }
            }
        }

        item("shortcuts") {
            Row(
                Modifier.padding(horizontal = 16.dp).padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ShortcutButton("Liked", SmartPlaylistKind.LIKED.icon(), Modifier.weight(1f)) {
                    onOpenSmartPlaylist(SmartPlaylistKind.LIKED)
                }
                ShortcutButton("Telegram", SmartPlaylistKind.TELEGRAM.icon(), Modifier.weight(1f)) {
                    onOpenSmartPlaylist(SmartPlaylistKind.TELEGRAM)
                }
                ShortcutButton("Downloaded", SmartPlaylistKind.DOWNLOADED.icon(), Modifier.weight(1f)) {
                    onOpenSmartPlaylist(SmartPlaylistKind.DOWNLOADED)
                }
                ShortcutButton("Shuffle", Icons.Rounded.Shuffle, Modifier.weight(1f)) {
                    if (home.allIds.isNotEmpty()) callbacks.onPlayCollection(home.allIds, true)
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

        // The rest of Recently Played - the newest is the card at the top.
        if (home.recentlyPlayed.size > 1) {
            item("recent_played_header") { HomeSectionHeader("Recently Played") }
            item("recent_played") { SongShelf(home.recentlyPlayed.drop(1), callbacks) }
        }

        if (home.dailyMix.isNotEmpty() || home.rediscover.isNotEmpty()) {
            item("mixes_header") { HomeSectionHeader("Made for You") }
            item("mixes") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (home.dailyMix.isNotEmpty()) item("daily") {
                        MixCard("Daily Mix", "Your most played", MixDaily, home.dailyMix) { callbacks.onPlayCollection(home.dailyMix.map { it.telegramMessageId }, false) }
                    }
                    if (home.rediscover.isNotEmpty()) item("rediscover") {
                        MixCard("Rediscover", "Not played in a while", MixRediscover, home.rediscover) { callbacks.onPlayCollection(home.rediscover.map { it.telegramMessageId }, false) }
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
                            Text(artist.artist, color = ink.copy(alpha = 0.9f), fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        newReleases.community?.takeIf { it.items.isNotEmpty() }?.let { community ->
            // A few up front; See All opens the rest as a grid.
            val more = community.items.size > COMMUNITY_SHELF_SIZE
            item("community_header") {
                HomeSectionHeader(
                    community.title,
                    onSeeAll = if (more) {
                        { onOpenCollection(BrowseCollection(DiscoveryRepository.COMMUNITY_BROWSE_ID, null, community.title, null, null, BrowseKind.OTHER)) }
                    } else null
                )
            }
            item("community") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(
                        community.items.take(COMMUNITY_SHELF_SIZE),
                        key = { index, item -> "community_${index}_${item.browseId}" }
                    ) { _, item -> CollectionCard(item) { onOpenCollection(item) } }
                }
            }
        }

        newReleases.section?.takeIf { it.items.isNotEmpty() }?.let { releases ->
            item("new_releases_header") { HomeSectionHeader("New releases") }
            item("new_releases") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(
                        releases.items,
                        key = { index, item -> "release_${index}_${item.browseId}_${item.params.orEmpty()}" }
                    ) { _, item -> CollectionCard(item) { onOpenCollection(item) } }
                }
            }
        }

        item("end") { Spacer(Modifier.height(8.dp)) }
    }
}

private const val COMMUNITY_SHELF_SIZE = 5

// How bright (average, 0-1) a dark cover's blurred card is lifted to - see BlurredArtwork.
private const val CARD_MIN_BRIGHTNESS = 0.2f

@Composable
private fun HomeSectionHeader(title: String, onSeeAll: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 24.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = ink, fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (onSeeAll != null) {
            Text(
                "See All",
                color = AppAccent,
                fontSize = 15.sp,
                modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onSeeAll)
            )
        }
    }
}

/** One of the four shortcuts: a pink icon over a short label, in one compact row. */
@Composable
private fun ShortcutButton(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .height(64.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(LibraryFieldColor)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = AppAccent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(5.dp))
        Text(label, color = ink.copy(alpha = 0.92f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "Continue Listening": the last song's cover on a blurred copy of itself, its name, and a
 * Resume (or Pause, while it plays) button. The whole card is the button. */
@Composable
private fun ContinueListeningCard(song: SongEntity, playing: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(horizontal = 16.dp)
            .padding(top = 4.dp)
            .fillMaxWidth()
            // Grows past its usual size when a long (or Arabic, taller) title takes two lines,
            // so the Resume button is never cut off at the bottom edge.
            .heightIn(min = 176.dp)
            .clip(RoundedCornerShape(18.dp))
            // A faint edge, so the card's shape shows on a black OLED page whatever the cover.
            .border(1.dp, ink.copy(alpha = 0.1f), RoundedCornerShape(18.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
    ) {
        BlurredArtwork(song.displayArtwork, Modifier.matchParentSize(), minBrightness = CARD_MIN_BRIGHTNESS)
        // Just enough shade for the white text over a bright cover.
        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.2f)))
        Row(Modifier.fillMaxWidth().heightIn(min = 176.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            SongArtwork(song, 126.dp, 10.dp)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text("CONTINUE LISTENING", color = Color.White.copy(alpha = 0.7f), fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.9.sp)
                Spacer(Modifier.height(5.dp))
                Text(song.title, color = Color.White, fontSize = 20.sp, lineHeight = 23.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(song.artist, color = Color.White.copy(alpha = 0.75f), fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.clip(RoundedCornerShape(18.dp)).background(Color.White).padding(start = 10.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = null, tint = AppAccent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (playing) "Pause" else "Resume", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** A sideways row of song covers; tapping one plays the row from that song. */
@Composable
private fun SongShelf(songs: List<SongEntity>, callbacks: LibraryCallbacks) {
    val ids = remember(songs) { songs.map { it.telegramMessageId } }
    val rowState = rememberLazyListState()
    // A newly played/added song is inserted at the front; a keyed row stays anchored on the old
    // first item, leaving the new ones hidden off-screen to the left. Snap back to the start when
    // the front changes - unless the user scrolled along the row. "Scrolled" is judged only when
    // a scroll ends, so the row being pushed along by new songs (several can arrive while Home
    // isn't showing) doesn't count as the user having moved it.
    var atStart by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(rowState) {
        snapshotFlow { rowState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) atStart = rowState.firstVisibleItemIndex == 0
        }
    }
    LaunchedEffect(songs.firstOrNull()?.telegramMessageId) {
        if (atStart) rowState.scrollToItem(0)
    }
    LazyRow(state = rowState, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(songs, key = { _, s -> s.telegramMessageId }) { index, song ->
            Column(Modifier.width(128.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { callbacks.onPlay(ids, index) }) {
                SongArtwork(song, 128.dp, 6.dp)
                Spacer(Modifier.height(5.dp))
                Text(song.title, color = ink.copy(alpha = 0.9f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, color = ink.copy(alpha = 0.55f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A mix: four of its songs' covers in a 2x2 grid, its name on a band of its colour below. */
@Composable
private fun MixCard(title: String, subtitle: String, color: Color, songs: List<SongEntity>, onClick: () -> Unit) {
    val covers = remember(songs) { songs.distinctBy { it.displayArtwork }.take(4) }
    Box(
        Modifier
            .size(156.dp, 196.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(color)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
    ) {
        Column {
            for (row in 0 until 2) {
                Row {
                    for (column in 0 until 2) {
                        val song = covers.getOrNull(row * 2 + column)
                        if (song != null) SongArtwork(song, 78.dp, 0.dp) else Spacer(Modifier.size(78.dp))
                    }
                }
            }
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(66.dp)
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.4f to color))
                .padding(horizontal = 11.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            Text(subtitle, color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
