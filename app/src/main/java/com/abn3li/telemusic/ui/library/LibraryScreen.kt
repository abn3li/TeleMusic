package com.abn3li.telemusic.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.data.local.AlbumSummary
import com.abn3li.telemusic.data.local.ArtistSummary
import com.abn3li.telemusic.data.local.PlaylistEntity
import com.abn3li.telemusic.data.local.PlaylistSummary
import com.abn3li.telemusic.data.local.SongEntity
import com.abn3li.telemusic.data.telegram.TelegramConnectionState
import com.abn3li.telemusic.ui.nowplaying.LocalMiniPlayerInset
import com.abn3li.telemusic.repository.SortField
import com.abn3li.telemusic.sync.SyncService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// The redesign's own dark palette - deliberately local to this screen (hardcoded, not routed
// through MaterialTheme) so it matches the pasted mockup exactly rather than approximating it
// with the app's existing MonochromeDarkColorScheme tokens.
private val BgColor = Color(0xFF000000)
private val CardColor = Color(0xFF19191C)
private val TextSecondary = Color(0xFFA9A9A6)
private val TextMuted = Color(0xFF8B8B88)
private val AccentGreen = Color(0xFF1D9E75)
private val OnAccentGreen = Color(0xFF04342C)
private val ChevronColor = Color(0xFF5F5F5C)
private val DividerColor = Color(0xFF232326)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    onSongClick: (List<Long>, Int) -> Unit, // full ordered list + tapped index, for the playback queue
    onSyncClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onPlaylistClick: (Long, String) -> Unit,
    onSmartPlaylistClick: (SmartPlaylistKind) -> Unit,
    onYouTubeDownloadClick: () -> Unit,
    // Constructed once at the nav graph root (same pattern as NowPlayingViewModel) and passed
    // down, rather than a local `remember` here - NavHost disposes this whole composable's
    // composition every time you navigate to Artist/Album/Playlist and come back, so a locally
    // `remember`-ed ViewModel was silently recreated on every return trip: selected tab, sort
    // field, and search state all reset to their defaults, which is what read as "goes back to
    // Tracks with a broken layout" (an empty-to-populated content jump as the fresh instance's
    // flows started from empty and re-subscribed to the DB). Defaults to a fresh local instance
    // so this composable still works with no caller wiring (e.g. previews).
    viewModel: LibraryViewModel? = null
) {
    val app = LocalContext.current.applicationContext as TgMusicApp
    val resolvedViewModel = viewModel ?: remember { LibraryViewModel(app.musicRepository) }
    val coroutineScope = rememberCoroutineScope()

    val tab by resolvedViewModel.tab.collectAsState()
    val sortField by resolvedViewModel.sortField.collectAsState()
    val tracks by resolvedViewModel.tracks.collectAsState()
    val tracksUiModels by resolvedViewModel.tracksUiModels.collectAsState()
    val albums by resolvedViewModel.albums.collectAsState()
    val artists by resolvedViewModel.artists.collectAsState()
    val playlists by resolvedViewModel.playlists.collectAsState()
    val playlistSummaries by resolvedViewModel.playlistSummaries.collectAsState()

    // Search state
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }

    // The text field itself stays bound to searchQuery directly so typing never lags - only the
    // (potentially expensive, on a large library) re-filtering below waits for a short pause in
    // typing, instead of re-scanning all four lists on the main thread after every keystroke.
    val debouncedSearchQuery by produceState(initialValue = searchQuery, searchQuery) {
        delay(250)
        value = searchQuery
    }

    val filteredTracksUi = remember(tracksUiModels, debouncedSearchQuery) {
        if (debouncedSearchQuery.isBlank()) tracksUiModels
        else tracksUiModels.filter { song ->
            song.title.contains(debouncedSearchQuery, ignoreCase = true) ||
            song.subtitle.contains(debouncedSearchQuery, ignoreCase = true)
        }
    }

    val filteredAlbums = remember(albums, debouncedSearchQuery) {
        if (debouncedSearchQuery.isBlank()) albums
        else albums.filter { album ->
            album.album.contains(debouncedSearchQuery, ignoreCase = true) ||
            album.artist.contains(debouncedSearchQuery, ignoreCase = true)
        }
    }

    val filteredArtists = remember(artists, debouncedSearchQuery) {
        if (debouncedSearchQuery.isBlank()) artists
        else artists.filter { artist ->
            artist.artist.contains(debouncedSearchQuery, ignoreCase = true)
        }
    }

    // Cheaply derived from the SAME already-subscribed `tracks` flow - no extra DB queries just
    // for a row subtitle count.
    val likedCount = remember(tracks) { tracks.count { it.isFavorite } }
    val telegramCount = remember(tracks) { tracks.count { it.telegramFileId != 0 } }
    val downloadedCount = remember(tracks) { tracks.count { it.isExplicitDownload } }

    val pagerState = rememberPagerState(
        initialPage = tab.ordinal,
        pageCount = { LibraryTab.entries.size }
    )

    val filterChipsListState = rememberLazyListState()

    LaunchedEffect(filterChipsListState) {
        snapshotFlow { pagerState.currentPage + pagerState.currentPageOffsetFraction }
            .collect { rawIndex ->
                val liveIndex = rawIndex.coerceIn(0f, (LibraryTab.entries.size - 1).toFloat())
                val base = liveIndex.toInt()
                val info = filterChipsListState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == base }
                if (info != null) {
                    val nextInfo = filterChipsListState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == base + 1 }
                    val itemWidth = nextInfo?.let { it.offset - info.offset } ?: info.size
                    val offset = ((liveIndex - base) * itemWidth).toInt()
                    filterChipsListState.scrollToItem(base, offset)
                } else {
                    filterChipsListState.scrollToItem(base)
                }
            }
    }

    LaunchedEffect(pagerState.settledPage) {
        resolvedViewModel.selectTab(LibraryTab.entries[pagerState.settledPage])
    }

    LaunchedEffect(tab) {
        if (pagerState.currentPage != tab.ordinal && !pagerState.isScrollInProgress) {
            pagerState.animateScrollToPage(tab.ordinal)
        }
    }

    val connectionState by app.tdlibManager.connectionState.collectAsState()
    val isSyncing by SyncService.isRunning.collectAsState()
    val syncProgressMessage by SyncService.progress.collectAsState()

    var showReconnectButton by remember { mutableStateOf(false) }

    LaunchedEffect(connectionState) {
        if (connectionState == TelegramConnectionState.CONNECTED) {
            showReconnectButton = false
        } else {
            if (!showReconnectButton) {
                delay(5000)
                if (connectionState != TelegramConnectionState.CONNECTED) {
                    showReconnectButton = true
                }
            }
        }
    }

    // Only spins up while a sync is running - see this animation's own doc history: an
    // unconditional infiniteRepeatable here competes with every scroll frame's vsync callback
    // for the whole time this screen is on screen, synced or not.
    val syncRotationAngle: State<Float> = if (isSyncing) {
        val infiniteTransition = rememberInfiniteTransition(label = "syncRotation")
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(animation = tween(1200, easing = LinearEasing)),
            label = "rotation"
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }

    val (statusColor, statusText) = remember(connectionState) {
        when (connectionState) {
            TelegramConnectionState.CONNECTED -> Color(0xFF4CAF50) to "Telegram Connected"
            TelegramConnectionState.CONNECTING -> Color(0xFFFF9800) to "Connecting to Telegram..."
            TelegramConnectionState.CONNECTING_TO_PROXY -> Color(0xFF00E5FF) to "Connecting to Proxy..."
            TelegramConnectionState.WAITING_FOR_NETWORK -> Color(0xFFFFC107) to "Waiting for Network..."
            TelegramConnectionState.UPDATING -> Color(0xFF2196F3) to "Updating Telegram..."
            TelegramConnectionState.DISCONNECTED -> Color(0xFFF44336) to "Telegram Disconnected"
        }
    }

    Scaffold(
        containerColor = BgColor,
        topBar = {
            Surface(color = BgColor) {
                Column {
                    Column(modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "Library", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Medium)
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { isSearchActive = !isSearchActive }, modifier = Modifier.size(24.dp)) {
                                    Icon(
                                        Icons.Default.Search,
                                        contentDescription = "Search songs",
                                        tint = if (isSearchActive) AccentGreen else TextSecondary
                                    )
                                }
                                IconButton(onClick = onSyncClick, modifier = Modifier.size(24.dp)) {
                                    Icon(
                                        Icons.Default.Sync,
                                        contentDescription = "Sync from channel",
                                        tint = if (isSyncing) AccentGreen else TextSecondary,
                                        modifier = Modifier.graphicsLayer {
                                            rotationZ = if (isSyncing) syncRotationAngle.value else 0f
                                        }
                                    )
                                }
                                IconButton(onClick = onYouTubeDownloadClick, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Download, contentDescription = "Download from YouTube", tint = TextSecondary)
                                }
                                IconButton(onClick = onSettingsClick, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = TextSecondary)
                                }
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(top = 6.dp)
                        ) {
                            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(statusColor))
                            Text(text = statusText, color = TextSecondary, fontSize = 13.sp)
                        }
                    }

                    AnimatedVisibility(visible = isSearchActive, enter = expandVertically(), exit = shrinkVertically()) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search songs, artists, albums...") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = AccentGreen) },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear search", tint = TextSecondary)
                                    }
                                } else {
                                    IconButton(onClick = { isSearchActive = false; searchQuery = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = "Close search", tint = TextSecondary)
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(24.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = CardColor,
                                unfocusedContainerColor = CardColor,
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }

                    AnimatedVisibility(visible = showReconnectButton, enter = expandVertically(), exit = shrinkVertically()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Connecting taking time...", color = TextSecondary, fontSize = 12.sp)
                            FilledTonalButton(
                                onClick = { app.tdlibManager.reconnect(app.settingsStore.proxySettings) },
                                colors = ButtonDefaults.filledTonalButtonColors(containerColor = CardColor, contentColor = AccentGreen),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Reconnect", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    AnimatedVisibility(visible = isSyncing, enter = expandVertically(), exit = shrinkVertically()) {
                        Column(modifier = Modifier.fillMaxWidth().background(CardColor)) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = AccentGreen,
                                trackColor = DividerColor
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Send,
                                    contentDescription = null,
                                    tint = AccentGreen,
                                    modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = syncRotationAngle.value }
                                )
                                Text(
                                    text = syncProgressMessage.ifBlank { "Syncing songs from Telegram..." },
                                    color = TextSecondary,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().background(BgColor)) {
            // Samsung Music-style tabs: no pill/chip background at all - the selected tab is
            // just bigger, bolder, and brighter than the rest, which read/unread size contrast
            // alone is what carries the selection state.
            LazyRow(
                state = filterChipsListState,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                itemsIndexed(LibraryTab.entries) { index, t ->
                    val liveIndex = (pagerState.currentPage + pagerState.currentPageOffsetFraction).roundToInt()
                    val selected = index == liveIndex
                    Text(
                        text = t.label,
                        color = if (selected) Color.White else TextMuted,
                        fontSize = if (selected) 22.sp else 16.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.clickableNoRipple {
                            coroutineScope.launch { pagerState.animateScrollToPage(t.ordinal) }
                        }
                    )
                }
            }

            HorizontalPager(
                state = pagerState,
                beyondBoundsPageCount = 0,
                pageSpacing = 12.dp,
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) { page ->
                key(page) {
                    when (LibraryTab.entries[page]) {
                        LibraryTab.TRACKS -> SongList(filteredTracksUi, tracks, playlists, onSongClick, resolvedViewModel, sortField)
                        LibraryTab.ALBUMS -> AlbumsList(filteredAlbums, onAlbumClick)
                        LibraryTab.ARTISTS -> ArtistsList(filteredArtists, onArtistClick)
                        LibraryTab.PLAYLISTS -> PlaylistsList(
                            playlists = playlistSummaries,
                            likedCount = likedCount,
                            telegramCount = telegramCount,
                            downloadedCount = downloadedCount,
                            onPlaylistClick = onPlaylistClick,
                            onSmartPlaylistClick = onSmartPlaylistClick,
                            viewModel = resolvedViewModel
                        )
                    }
                }
            }
        }
    }
}

/** The sort field + shuffle/play controls row, same spot the Samsung Music reference has its
 * "Name" sort chip and its two round shuffle/play buttons. */
@Composable
private fun SongListHeaderRow(
    sortField: SortField,
    onFieldSelected: (SortField) -> Unit,
    onShuffle: () -> Unit,
    onPlayAll: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickableNoRipple { menuExpanded = true }
            ) {
                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(sortField.label, color = TextSecondary, fontSize = 16.dp.value.sp)
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }, modifier = Modifier.background(CardColor)) {
                SortField.entries.forEach { field ->
                    DropdownMenuItem(
                        text = { Text(field.label, color = Color.White) },
                        onClick = { onFieldSelected(field); menuExpanded = false }
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(44.dp).clip(CircleShape).background(DividerColor).clickableNoRipple(onShuffle),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Shuffle, contentDescription = "Shuffle", tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Box(
                modifier = Modifier.size(44.dp).clip(CircleShape).background(AccentGreen).clickableNoRipple(onPlayAll),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Play all", tint = OnAccentGreen, modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun SongList(
    songsUi: List<SongUiModel>,
    songsEntities: List<SongEntity>,
    playlists: List<PlaylistEntity>,
    onSongClick: (List<Long>, Int) -> Unit,
    viewModel: LibraryViewModel,
    sortField: SortField
) {
    val app = LocalContext.current.applicationContext as TgMusicApp
    val entitiesById = remember(songsEntities) { songsEntities.associateBy { it.telegramMessageId } }
    val songIds = remember(songsUi) { songsUi.map { it.id } }

    val onFieldSelected = remember(viewModel) { { f: SortField -> viewModel.selectSortField(f) } }

    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    // Computed once for the whole list rather than per SongRow per recomposition - see SongRow's
    // titleStyle/subtitleStyle doc for why.
    val typography = MaterialTheme.typography
    val titleStyle = remember(typography) {
        typography.bodyMedium.copy(fontWeight = FontWeight.Medium, platformStyle = PlatformTextStyle(includeFontPadding = false))
    }
    val subtitleStyle = remember(typography) {
        typography.bodySmall.copy(platformStyle = PlatformTextStyle(includeFontPadding = false))
    }

    val onShuffle = remember(songIds) {
        {
            if (songIds.isNotEmpty()) onSongClick(songIds, (0 until songIds.size).random())
            Unit
        }
    }
    val onPlayAll = remember(songIds) {
        { if (songIds.isNotEmpty()) onSongClick(songIds, 0); Unit }
    }

    // The whole list sits inside one big rounded-top card - the Samsung Music reference this
    // was copied from has the sort/shuffle/play row and every song row inside a single
    // container, not a plain background behind separate per-row cards.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(CardColor)
    ) {
        if (songsUi.isEmpty()) {
            Column(Modifier.fillMaxSize()) {
                SongListHeaderRow(sortField, onFieldSelected, onShuffle, onPlayAll)
                Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = "No songs found", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = LocalMiniPlayerInset.current)
        ) {
            item(key = "sort_bar", contentType = "sort_bar") {
                SongListHeaderRow(sortField, onFieldSelected, onShuffle, onPlayAll)
            }

            itemsIndexed(
                items = songsUi,
                key = { _, s -> s.id },
                contentType = { _, _ -> "song_row" }
            ) { index, uiModel ->
                val songEntity = entitiesById[uiModel.id] ?: return@itemsIndexed

                SongRow(
                    song = uiModel,
                    playlists = playlists,
                    onClick = remember(songIds, index) { { onSongClick(songIds, index) } },
                    onToggleFavorite = remember(songEntity) { { viewModel.toggleFavorite(songEntity) } },
                    onDownloadClick = remember(songEntity) { { viewModel.downloadSong(songEntity) } },
                    onAddToPlaylist = remember(songEntity) { { id -> viewModel.addSongToPlaylist(id, songEntity) } },
                    onCreatePlaylistAndAdd = remember(songEntity) { { name -> viewModel.createPlaylistAndAddSong(name, songEntity) } },
                    onDeleteDownload = remember(songEntity, app) {
                        {
                            // Deleting the file out from under an actively playing/buffering
                            // ExoPlayer instance is fragile (it may keep playing already-buffered
                            // audio fine, or fail on the next read/seek) - pausing first avoids
                            // that race instead of leaving it to chance.
                            if (app.playbackQueue.currentSongId() == songEntity.telegramMessageId && app.playbackController.isPlaying()) {
                                app.playbackController.togglePlayPause()
                            }
                            viewModel.removeDownload(songEntity)
                        }
                    },
                    onClearSong = remember(songEntity, app) {
                        {
                            if (app.playbackQueue.currentSongId() == songEntity.telegramMessageId && app.playbackController.isPlaying()) {
                                app.playbackController.togglePlayPause()
                            }
                            viewModel.clearSong(songEntity)
                        }
                    },
                    onEditAndFetchArtwork = remember(songEntity) {
                        { title, artist -> viewModel.editSongAndFetchArtwork(songEntity, title, artist) }
                    },
                    primaryColor = primaryColor,
                    onSurfaceVariant = onSurfaceVariant,
                    titleStyle = titleStyle,
                    subtitleStyle = subtitleStyle
                )
                if (index < songsUi.lastIndex) {
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
        }
    }
}

@Composable
private fun AlbumsList(albums: List<AlbumSummary>, onAlbumClick: (String) -> Unit) {
    val context = LocalContext.current
    // Same big rounded-top card every other tab uses now (see SongList's own doc) - consistent
    // across Playlists/Tracks/Albums/Artists rather than just the one tab.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(CardColor)
    ) {
    if (albums.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = "No albums found", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + LocalMiniPlayerInset.current),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            items(items = albums, key = { it.album }, contentType = { "album_card" }) { album ->
                val imageRequest = remember(album.albumArtUrl, context) {
                    ImageRequest.Builder(context).data(album.albumArtUrl).allowHardware(true).size(300, 300).build()
                }
                val albumPainter = rememberAsyncImagePainter(imageRequest)
                val albumSubtitle = remember(album.artist, album.songCount) {
                    "${album.artist} • ${album.songCount} ${if (album.songCount == 1) "track" else "tracks"}"
                }
                val onAlbumClicked = remember(album.album, onAlbumClick) { { onAlbumClick(album.album) } }

                Column(modifier = Modifier.clickableNoRipple(onAlbumClicked)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(18.dp))
                            .background(CardColor),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!album.albumArtUrl.isNullOrEmpty()) {
                            Image(
                                painter = albumPainter,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Album,
                                contentDescription = null,
                                tint = TextMuted,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = album.album,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = albumSubtitle,
                        color = TextMuted,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun ArtistsList(artists: List<ArtistSummary>, onArtistClick: (String) -> Unit) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(CardColor)
    ) {
    if (artists.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = "No artists found", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + LocalMiniPlayerInset.current),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            items(items = artists, key = { it.artist }, contentType = { "artist_card" }) { artist ->
                val artistSubtitle = remember(artist.songCount) {
                    "${artist.songCount} ${if (artist.songCount == 1) "track" else "tracks"}"
                }
                val onArtistClicked = remember(artist.artist, onArtistClick) { { onArtistClick(artist.artist) } }
                val artistImageRequest = remember(artist.albumArtUrl, context) {
                    ImageRequest.Builder(context).data(artist.albumArtUrl).allowHardware(true).size(300, 300).build()
                }
                val artistPainter = rememberAsyncImagePainter(artistImageRequest)

                Column(modifier = Modifier.clickableNoRipple(onArtistClicked)) {
                    // Square, not circular - matches the same rounded-square treatment every
                    // other card in this redesign uses (albums, playlists), rather than the
                    // circular avatar convention most music apps default to for artists.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(18.dp))
                            .background(CardColor),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!artist.albumArtUrl.isNullOrEmpty()) {
                            Image(
                                painter = artistPainter,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                tint = TextMuted,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = artist.artist,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = artistSubtitle,
                        color = TextMuted,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun SmartPlaylistRow(
    label: String,
    subtitle: String,
    icon: ImageVector,
    iconTint: Color,
    iconBg: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DividerColor)
            .clickableNoRipple(onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(iconBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(text = subtitle, color = TextMuted, fontSize = 13.sp)
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
            contentDescription = null,
            tint = ChevronColor,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun PlaylistsList(
    playlists: List<PlaylistSummary>,
    likedCount: Int,
    telegramCount: Int,
    downloadedCount: Int,
    onPlaylistClick: (Long, String) -> Unit,
    onSmartPlaylistClick: (SmartPlaylistKind) -> Unit,
    viewModel: LibraryViewModel
) {
    val context = LocalContext.current
    var showCreateDialog by remember { mutableStateOf(false) }

    fun trackWord(count: Int) = "$count ${if (count == 1) "track" else "tracks"}"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(CardColor)
    ) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 12.dp + LocalMiniPlayerInset.current),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "new_playlist_button") {
            Button(
                onClick = { showCreateDialog = true },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = OnAccentGreen)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(text = "New playlist", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }

        item(key = "smart_liked") {
            SmartPlaylistRow(
                label = SmartPlaylistKind.LIKED.label,
                subtitle = trackWord(likedCount),
                icon = Icons.Default.Favorite,
                iconTint = Color(0xFFF0997B),
                iconBg = Color(0xFF4A1B0C),
                onClick = remember(onSmartPlaylistClick) { { onSmartPlaylistClick(SmartPlaylistKind.LIKED) } }
            )
        }
        item(key = "smart_telegram") {
            SmartPlaylistRow(
                label = SmartPlaylistKind.TELEGRAM.label,
                subtitle = trackWord(telegramCount),
                icon = Icons.Default.Send,
                iconTint = Color(0xFF85B7EB),
                iconBg = Color(0xFF042C53),
                onClick = remember(onSmartPlaylistClick) { { onSmartPlaylistClick(SmartPlaylistKind.TELEGRAM) } }
            )
        }
        item(key = "smart_downloaded") {
            SmartPlaylistRow(
                label = SmartPlaylistKind.DOWNLOADED.label,
                subtitle = trackWord(downloadedCount),
                icon = Icons.Default.Download,
                iconTint = Color(0xFF97C459),
                iconBg = Color(0xFF173404),
                onClick = remember(onSmartPlaylistClick) { { onSmartPlaylistClick(SmartPlaylistKind.DOWNLOADED) } }
            )
        }

        if (playlists.isNotEmpty()) {
            item(key = "your_playlists_header") {
                Text(
                    text = "Your playlists",
                    color = TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp, start = 4.dp)
                )
            }
        }

        items(items = playlists, key = { it.id }, contentType = { "playlist_row" }) { playlist ->
            val onPlaylistClicked = remember(playlist.id, playlist.name, onPlaylistClick) {
                { onPlaylistClick(playlist.id, playlist.name) }
            }
            val onDeleteClicked = remember(playlist.id, viewModel) {
                { viewModel.deletePlaylist(playlist.id); Unit }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(DividerColor)
                    .clickableNoRipple(onPlaylistClicked)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(BgColor),
                    contentAlignment = Alignment.Center
                ) {
                    if (!playlist.albumArtUrl.isNullOrEmpty()) {
                        val request = remember(playlist.albumArtUrl) {
                            ImageRequest.Builder(context).data(playlist.albumArtUrl).size(150, 150).build()
                        }
                        Image(
                            painter = rememberAsyncImagePainter(request),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Text(
                    text = playlist.name,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDeleteClicked) {
                    Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete playlist", tint = Color(0xFFE0716A))
                }
            }
        }
    }
    }

    if (showCreateDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("New Playlist") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Playlist name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (name.isNotBlank()) viewModel.createPlaylist(name.trim())
                        showCreateDialog = false
                    }
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// Simple click modifier without a ripple, matching the flat redesign's style.
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = composed {
    this.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick
    )
}
