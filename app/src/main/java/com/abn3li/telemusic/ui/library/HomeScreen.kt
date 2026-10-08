package com.abn3li.telemusic.ui.library

import coil.imageLoader
import androidx.compose.foundation.gestures.stopScroll
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import com.abn3li.telemusic.ui.theme.ink
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
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
import com.abn3li.telemusic.data.browse.BrowseTrack
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
@OptIn(ExperimentalMaterial3Api::class)
fun HomeScreen(
    viewModel: LibraryViewModel,
    callbacks: LibraryCallbacks,
    onOpenSettings: () -> Unit,
    onOpenPlaylist: (Long, String) -> Unit,
    onOpenSmartPlaylist: (SmartPlaylistKind) -> Unit,
    onOpenCollection: (BrowseCollection) -> Unit,
    onPlayTracks: (List<BrowseTrack>, Int) -> Unit,
    // The song loaded in the player, if any - lets "Continue Listening" pause/resume it in place.
    nowPlayingId: Long? = null,
    isPlaying: Boolean = false
) {
    val app = LocalContext.current.applicationContext as TgMusicApp
    val newReleasesViewModel = viewModel<NewReleasesViewModel>(factory = viewModelFactory { initializer {
        NewReleasesViewModel(app.discoveryRepository, app.youtubeAccount, app.settingsStore)
    } })
    val newReleases by newReleasesViewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, newReleasesViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) newReleasesViewModel.onHomeVisible()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val homeFeeds by app.settingsStore.homeFeeds.collectAsState()
    val canRefresh by rememberUpdatedState(homeFeeds.youtube)
    val refresh = rememberPullToRefreshState(enabled = { canRefresh })
    LaunchedEffect(refresh.isRefreshing) {
        if (refresh.isRefreshing) {
            try { newReleasesViewModel.refreshHome() }
            finally { refresh.endRefresh() }
        }
    }
    val home by viewModel.home.collectAsState()
    val artists by viewModel.artists.collectAsState()
    val pinned by viewModel.pinnedPlaylists.collectAsState()
    val topArtists = remember(artists) { artists.sortedByDescending { it.songCount }.take(12) }
    val pinnedRows = remember(pinned) { pinned.chunked(2) }

    Box(Modifier.fillMaxSize().nestedScroll(refresh.nestedScrollConnection)) {
    LargeTitleList(
        title = "Home",
        overlay = {
            // Clip the indicator's animated entry to the feed, below the fixed title bar.
            Box(Modifier.fillMaxSize().padding(top = LibraryBarHeight).clipToBounds()) {
                PullToRefreshContainer(state = refresh, containerColor = LibraryFieldColor, contentColor = AppAccent,
                    modifier = Modifier.align(Alignment.TopCenter))
            }
        },
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
        if (homeFeeds.telegram) home.recentlyPlayed.firstOrNull()?.let { last ->
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

        val personal = if (homeFeeds.youtube && newReleases.account.signedIn) newReleases.personal.orEmpty() else emptyList()
        personal.forEachIndexed { index, shelf -> youTubeShelf(shelf, index, onPlayTracks, onOpenCollection) }

        if (homeFeeds.telegram && pinnedRows.isNotEmpty()) {
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
        if (homeFeeds.telegram && home.recentlyPlayed.size > 1) {
            item("recent_played_header") { HomeSectionHeader("Recently Played") }
            item("recent_played") { SongShelf(home.recentlyPlayed.drop(1), callbacks) }
        }

        if (homeFeeds.telegram && (home.dailyMix.isNotEmpty() || home.rediscover.isNotEmpty())) {
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

        if (homeFeeds.telegram && home.recentlyAdded.isNotEmpty()) {
            item("recent_added_header") { HomeSectionHeader("Recently Added") }
            item("recent_added") { SongShelf(home.recentlyAdded, callbacks) }
        }

        if (homeFeeds.telegram && topArtists.isNotEmpty()) {
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

        newReleases.community?.takeIf { homeFeeds.youtube && !newReleases.account.signedIn && it.items.isNotEmpty() }?.let { community ->
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

        newReleases.section?.takeIf { homeFeeds.youtube && !newReleases.account.signedIn && it.items.isNotEmpty() }?.let { releases ->
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

        if (!homeFeeds.telegram && !homeFeeds.youtube) item("feeds_off") {
            Text("Home feeds are turned off. Enable them in Settings.", color = ink.copy(alpha = 0.55f),
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 24.dp))
        }
        item("end") { Spacer(Modifier.height(8.dp)) }
    }
    }
}

private const val COMMUNITY_SHELF_SIZE = 5

// How bright (average, 0-1) a dark cover's blurred card is lifted to - see BlurredArtwork.
private const val CARD_MIN_BRIGHTNESS = 0.2f

@Composable
private fun HomeSectionHeader(
    title: String,
    onSeeAll: (() -> Unit)? = null,
    strapline: String? = null,
    // In place of "See All": "Play all" is drawn as an outlined pill.
    action: String? = null,
    seeAllLabel: String = "See All"
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 24.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Column(Modifier.weight(1f)) {
            if (strapline != null) {
                Text(strapline.uppercase(), color = ink.copy(alpha = 0.5f), fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    letterSpacing = 0.3.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(title, color = ink, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onSeeAll != null) {
            if (action != null) {
                Text(
                    action,
                    color = ink,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .border(1.dp, ink.copy(alpha = 0.25f), RoundedCornerShape(50))
                        .clickable(onClick = onSeeAll)
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                )
            } else {
                Text(
                    seeAllLabel,
                    color = AppAccent,
                    fontSize = 15.sp,
                    modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onSeeAll)
                )
            }
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

private fun androidx.compose.foundation.lazy.LazyListScope.youTubeShelf(
    shelf: com.abn3li.telemusic.data.browse.HomeShelf,
    index: Int,
    onPlayTracks: (List<BrowseTrack>, Int) -> Unit,
    onOpenCollection: (BrowseCollection) -> Unit
) {
    item("yt_header_$index", contentType = "yt_header") {
        val showAll: () -> Unit = {
            onOpenCollection(
                BrowseCollection(
                    shelf.moreBrowseId ?: (DiscoveryRepository.SHELF_BROWSE_PREFIX + shelf.title),
                    if (shelf.moreBrowseId != null) shelf.moreParams else null,
                    shelf.title, null, null, BrowseKind.OTHER
                )
            )
        }
        when {
            shelf.listRows && shelf.tracks.size > 1 && !shelf.isForgottenFavorites() ->
                HomeSectionHeader(shelf.title, strapline = shelf.strapline, action = "Play all", onSeeAll = { onPlayTracks(shelf.tracks, 0) })
            else -> HomeSectionHeader(shelf.title, strapline = shelf.strapline, onSeeAll = showAll, seeAllLabel = "Show all")
        }
    }
    item("yt_shelf_$index", contentType = shelf.layoutKind()) {
        // Each shelf's row owns its scrolling (see shelfDrag); the title row above doesn't move it.
        val row = rememberLazyListState()
        val fling = rememberStartSnap(row)
        androidx.compose.runtime.CompositionLocalProvider(LocalShelfRow provides ShelfRow(row, fling)) {
            YouTubeShelf(shelf, index, onPlayTracks, onOpenCollection)
        }
    }
}

/** The row a shelf's covers scroll in, handed to whichever layout the shelf uses. */
private class ShelfRow(val state: androidx.compose.foundation.lazy.LazyListState, val fling: androidx.compose.foundation.gestures.FlingBehavior)
private val LocalShelfRow = androidx.compose.runtime.staticCompositionLocalOf<ShelfRow?> { null }

/** Which layout a shelf gets (see YouTubeShelf) - shelves of one kind reuse each other's build. */
private fun com.abn3li.telemusic.data.browse.HomeShelf.layoutKind(): String {
    val name = title.lowercase()
    return when {
        isForgottenFavorites() -> "yt_covers"
        listRows -> "yt_rows"
        "listen again" in name -> "yt_grid"
        "mix" in name && collections.isNotEmpty() && tracks.isEmpty() -> "yt_mixes"
        else -> "yt_covers"
    }
}

private fun com.abn3li.telemusic.data.browse.HomeShelf.isForgottenFavorites(): Boolean {
    val name = title.lowercase()
    return "forgot" in name && ("favor" in name || "favour" in name)
}

private val MixColors = listOf(Color(0xFF72243E), Color(0xFF3C3489), Color(0xFF0F5E5A), Color(0xFF7A4A0E), Color(0xFF2B4C7E), Color(0xFF5B2A6E))

/**
 * One shelf of the signed-in YouTube Music feed, laid out by kind: Quick picks (list rows) four
 * songs to a column; "Listen again" as a two-row grid of covers; mixes as colour tiles; other
 * songs as a row of covers; albums, playlists and artists as cards (artists round).
 */
@Composable
private fun YouTubeShelf(
    shelf: com.abn3li.telemusic.data.browse.HomeShelf,
    index: Int,
    onPlayTracks: (List<BrowseTrack>, Int) -> Unit,
    onOpenCollection: (BrowseCollection) -> Unit
) {
    val title = shelf.title.lowercase()
    when {
        shelf.isForgottenFavorites() -> HomeCoverShelf(shelf, onPlayTracks, onOpenCollection)
        shelf.listRows -> QuickPicks(shelf.tracks, onPlayTracks)
        "listen again" in title -> ListenAgainGrid(shelf, onPlayTracks, onOpenCollection)
        "mix" in title && shelf.collections.isNotEmpty() && shelf.tracks.isEmpty() ->
            LazyRow(state = LocalShelfRow.current!!.state, modifier = Modifier.shelfDrag(LocalShelfRow.current!!.state, LocalShelfRow.current!!.fling, COVER_MAX_FLING),
                flingBehavior = LocalShelfRow.current!!.fling, userScrollEnabled = false,
                contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(shelf.collections, key = { i, item -> "mix_${index}_${i}_${item.browseId}" }) { i, item ->
                    YouTubeMixTile(item, MixColors[i % MixColors.size]) { onOpenCollection(item) }
                }
            }
        else -> HomeCoverShelf(shelf, onPlayTracks, onOpenCollection)
    }
}

/** Quick picks, like YouTube Music: columns of four songs in a row that scrolls freely and
 * stops at the nearest column, the next one peeking in from the edge. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickPicks(tracks: List<BrowseTrack>, onPlay: (List<BrowseTrack>, Int) -> Unit) {
    if (tracks.isEmpty()) return
    val play by rememberUpdatedState(onPlay)
    val columns = remember(tracks) { tracks.withIndex().chunked(4) }
    // Every cover is fetched and decoded at its row size up front (a few small images), so a
    // column sliding in draws covers that are ready instead of preparing them on that frame.
    val context = LocalContext.current
    val coverPx = with(LocalDensity.current) { 46.dp.roundToPx() }
    LaunchedEffect(tracks) {
        tracks.forEach { track ->
            track.thumbnailUrl?.let { url ->
                context.imageLoader.enqueue(coil.request.ImageRequest.Builder(context).data(url).size(coverPx).build())
            }
        }
    }
    // A column is most of the screen, so the next one shows at the edge.
    val columnWidth = LocalConfiguration.current.screenWidthDp.dp * 0.86f
    val shelfRow = LocalShelfRow.current!!
    LazyRow(state = shelfRow.state, modifier = Modifier.shelfDrag(shelfRow.state, shelfRow.fling), flingBehavior = shelfRow.fling, userScrollEnabled = false,
        contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(columns.size, key = { "qp_$it" }, contentType = { "qp_column" }) { column ->
            Column(Modifier.width(columnWidth).clip(RoundedCornerShape(20.dp))
                .background(LibraryFieldColor).border(1.dp, ink.copy(alpha = 0.06f), RoundedCornerShape(20.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp).heightIn(min = 256.dp)) {
                columns[column].forEachIndexed { rowIndex, (i, track) ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 62.dp)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { play(tracks, i) },
                        verticalAlignment = Alignment.CenterVertically) {
                        coil.compose.AsyncImage(model = track.thumbnailUrl, contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.size(46.dp).clip(RoundedCornerShape(9.dp)).background(LibraryFieldColor))
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(track.title, color = ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(track.artist, color = ink.copy(alpha = 0.55f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = ink.copy(alpha = 0.45f),
                            modifier = Modifier.padding(start = 8.dp).size(20.dp))
                    }
                    if (rowIndex < columns[column].lastIndex) Box(Modifier.fillMaxWidth().padding(start = 58.dp)
                        .height(1.dp).background(ink.copy(alpha = 0.07f)))
                }
            }
        }
    }
}

/**
 * How a shelf follows a finger, like YouTube Music's: a touch stops a gliding shelf; a move that
 * is clearly sideways (more sideways than up/down) drags it and flings it with the finger's speed,
 * then it stops at a cover; anything else is left to the page, which scrolls. (The row's own
 * scrolling grabbed any touch on a moving shelf at once, whatever its direction, so a scroll up
 * or down right after a flick shook the shelf instead of moving the page.)
 */
@Composable
private fun Modifier.shelfDrag(row: androidx.compose.foundation.lazy.LazyListState,
    fling: androidx.compose.foundation.gestures.FlingBehavior, maxFling: Dp = SHELF_MAX_FLING): Modifier {
    val scope = rememberCoroutineScope()
    return pointerInput(row, fling) {
        val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (row.isScrollInProgress) scope.launch { row.stopScroll() }
            tracker.resetTracking()
            tracker.addPosition(down.uptimeMillis, down.position)
            var start = 0f
            val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                val moved = change.position - down.position
                if (kotlin.math.abs(moved.x) > kotlin.math.abs(moved.y)) { change.consume(); start = over }
            } ?: return@awaitEachGesture
            row.dispatchRawDelta(-start)
            var dragged = start
            horizontalDrag(drag.id) { change ->
                tracker.addPosition(change.uptimeMillis, change.position)
                row.dispatchRawDelta(-change.positionChange().x)
                dragged += change.positionChange().x
                change.consume()
            }
            // A shelf goes about one screen of covers per flick, like YouTube Music's - full
            // long-list momentum threw it 5 to 20 covers, so only a slow drag moved it by one.
            val limit = maxFling.toPx()
            val velocity = (-tracker.calculateVelocity().x).coerceIn(-limit, limit)
            if (kotlin.math.abs(velocity) < SHELF_SLOW_RELEASE.toPx()) {
                // A slow swipe finishes in the direction it was dragged: on to the next cover, or
                // back to show the one on the left in full. Snapping to the nearest cover pulled
                // a short drag back against the finger, which felt like resistance and a bounce.
                val forward = dragged < 0
                val first = row.firstVisibleItemIndex
                val target = if (forward && row.firstVisibleItemScrollOffset > 0) first + 1 else first
                scope.launch { row.animateScrollToItem(target.coerceAtMost(row.layoutInfo.totalItemsCount - 1)) }
            } else {
                scope.launch { row.scroll { with(fling) { performFling(velocity) } } }
            }
        }
    }
}

// The fastest a flick throws a shelf: about one screen of covers before it stops at one.
// Song tables (Quick picks, Long listens, Trending): wide columns, one per flick.
private val SHELF_MAX_FLING = 700.dp
// Cover shelves (albums, mixes, Listen again): a flick carries on across several covers, like
// YouTube Music's; capped so it never races to the end.
private val COVER_MAX_FLING = 2200.dp
// Below this release speed a swipe is a slow one (see shelfDrag).
private val SHELF_SLOW_RELEASE = 350.dp

/** Stops a flung row with an item lined up at its left edge, like YouTube Music (the
 * default centred the item instead). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun rememberStartSnap(row: androidx.compose.foundation.lazy.LazyListState): androidx.compose.foundation.gestures.FlingBehavior {
    val layout = remember(row) {
        androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider(row,
            positionInLayout = androidx.compose.foundation.gestures.snapping.SnapPositionInLayout { _, _, _, _, _ -> 0 })
    }
    return rememberSnapFlingBehavior(layout)
}

/**
 * A row of covers that scrolls freely with momentum and stops at the nearest cover, like
 * YouTube Music's shelves; [rows] covers are stacked in each column ("Listen again": two).
 * Only the covers on screen are built.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CoverRow(count: Int, rows: Int, coverWidth: Dp, content: @Composable (index: Int) -> Unit) {
    if (count == 0) return
    val shelfRow = LocalShelfRow.current!!
    val columns = (count + rows - 1) / rows
    LazyRow(state = shelfRow.state, modifier = Modifier.shelfDrag(shelfRow.state, shelfRow.fling, COVER_MAX_FLING), flingBehavior = shelfRow.fling, userScrollEnabled = false,
        contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(columns, key = { "col_$it" }, contentType = { "cover_column_$rows" }) { column ->
            Column(Modifier.width(coverWidth), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(rows) { r ->
                    val index = column * rows + r
                    if (index < count) content(index)
                }
            }
        }
    }
}

/** "Listen again": covers in two rows that scroll together. Songs play, the rest open. */
@Composable
private fun ListenAgainGrid(
    shelf: com.abn3li.telemusic.data.browse.HomeShelf,
    onPlay: (List<BrowseTrack>, Int) -> Unit,
    onOpen: (BrowseCollection) -> Unit,
    rows: Int = 2
) {
    val play by rememberUpdatedState(onPlay)
    val open by rememberUpdatedState(onOpen)
    val entries = remember(shelf) {
        shelf.tracks.mapIndexed { i, t -> Triple(t.thumbnailUrl, t.title, { play(shelf.tracks, i) }) } +
            shelf.collections.map { c -> Triple(c.thumbnailUrl, c.title, { open(c) }) }
    }
    val coverWidth = 112.dp
    CoverRow(entries.size, rows = rows, coverWidth = coverWidth) { index ->
        val (url, title, onClick) = entries[index]
        Column(Modifier.width(coverWidth).clickable(onClick = onClick)) {
            coil.compose.AsyncImage(model = url, contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.size(coverWidth).clip(RoundedCornerShape(12.dp)).background(LibraryFieldColor))
            Text(title, color = ink.copy(alpha = 0.9f), fontSize = 12.sp, lineHeight = 17.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** A YouTube mix (Supermix, My Mix, Discover): its own cover above a band of colour with its
 * name, like Made for You's tiles. */
@Composable
private fun YouTubeMixTile(item: BrowseCollection, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(156.dp, 196.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(color)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
    ) {
        coil.compose.AsyncImage(
            model = item.thumbnailUrl,
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.size(156.dp)
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(66.dp)
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.4f to color))
                .padding(horizontal = 11.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            Text(item.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            item.subtitle?.let {
                Text(it, color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private data class HomeCoverEntry(val artwork: String?, val title: String, val subtitle: String?, val open: () -> Unit)

/** A mixed YouTube shelf: one row of covers; songs play and collections open in place. */
@Composable
private fun HomeCoverShelf(
    shelf: com.abn3li.telemusic.data.browse.HomeShelf,
    onPlay: (List<BrowseTrack>, Int) -> Unit,
    onOpen: (BrowseCollection) -> Unit
) {
    val play by rememberUpdatedState(onPlay)
    val open by rememberUpdatedState(onOpen)
    val entries = remember(shelf) {
        shelf.tracks.mapIndexed { index, track ->
            HomeCoverEntry(track.thumbnailUrl, track.title, track.artist) { play(shelf.tracks, index) }
        } + shelf.collections.map { collection ->
            HomeCoverEntry(collection.thumbnailUrl, collection.title, collection.subtitle) { open(collection) }
        }
    }
    val coverWidth = 140.dp
    CoverRow(entries.size, rows = 1, coverWidth = coverWidth) { index ->
        val entry = entries[index]
        Column(Modifier.width(coverWidth).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = entry.open)) {
            coil.compose.AsyncImage(model = entry.artwork, contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.size(coverWidth).clip(RoundedCornerShape(12.dp)).background(LibraryFieldColor))
            Spacer(Modifier.height(5.dp))
            Text(entry.title, color = ink.copy(alpha = 0.9f), fontSize = 13.sp, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(entry.subtitle.orEmpty(), color = ink.copy(alpha = 0.55f), fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
