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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.data.local.AlbumSummary
import com.abn3li.telemusic.data.local.ArtistSummary
import com.abn3li.telemusic.data.local.PlaylistEntity
import com.abn3li.telemusic.data.local.PlaylistSummary
import com.abn3li.telemusic.data.local.SongEntity
import com.abn3li.telemusic.data.telegram.TelegramConnectionState
import com.abn3li.telemusic.repository.SortField
import com.abn3li.telemusic.sync.SyncService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
    val ascending by resolvedViewModel.ascending.collectAsState()
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

    // Real-time filtering based on search query
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

    // Smooth 60/120fps Horizontal Pager state with 1-page pre-rendering for lag-free swiping
    val pagerState = rememberPagerState(
        initialPage = tab.ordinal,
        pageCount = { LibraryTab.entries.size }
    )

    val filterChipsListState = rememberLazyListState()

    // Keeps the chip row's scroll position AND which chip is highlighted tracking the pager's
    // live drag position (not just where it settles) - this needs to react every frame of the
    // drag, which is exactly what's needed for the chip row to actually follow your finger
    // instead of jumping only once the swipe finishes, and for a chip past the edge of the
    // screen (Artists, the last tab) to actually scroll into view instead of the row staying put
    // while the page underneath changes. A single snapshotFlow collector (rather than a
    // LaunchedEffect keyed on the continuously-changing offset fraction) avoids cancelling and
    // relaunching a coroutine on every single drag frame.
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

    // Sync Pager page settlement -> ViewModel selected tab
    LaunchedEffect(pagerState.settledPage) {
        resolvedViewModel.selectTab(LibraryTab.entries[pagerState.settledPage])
    }

    // Sync ViewModel selected tab -> Pager page (Safely guarded against mid-swipe gesture hijacking)
    LaunchedEffect(tab) {
        if (pagerState.currentPage != tab.ordinal && !pagerState.isScrollInProgress) {
            pagerState.animateScrollToPage(tab.ordinal)
        }
    }

    // Live Telegram Connection State & Sync Progress
    val connectionState by app.tdlibManager.connectionState.collectAsState()
    val isSyncing by SyncService.isRunning.collectAsState()
    val syncProgressMessage by SyncService.progress.collectAsState()

    // Reconnect Button visibility logic: shows after 5s of connecting and remains until CONNECTED
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

    // GPU-deferred rotation angle: kept as a State<Float> (no `by` delegate) so reading it
    // only happens inside the graphicsLayer draw lambdas below, instead of every animation
    // frame forcing this whole composable (pager + lists included) to recompose.
    //
    // Only actually spins up while a sync is running - an infiniteRepeatable animation
    // registers a Choreographer callback on every vsync for as long as it's composed, so an
    // unconditional one here was quietly competing with every scroll frame's own vsync
    // callback for the entire time this screen (Pager + lists included) was on screen, synced
    // or not - not just while the icon was actually spinning.
    val syncRotationAngle: State<Float> = if (isSyncing) {
        val infiniteTransition = rememberInfiniteTransition(label = "syncRotation")
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = LinearEasing)
            ),
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
        topBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Column {
                    Column(modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Library",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                IconButton(onClick = { isSearchActive = !isSearchActive }, modifier = Modifier.size(24.dp)) {
                                    Icon(
                                        Icons.Default.Search,
                                        contentDescription = "Search songs",
                                        tint = if (isSearchActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                IconButton(onClick = onSyncClick, modifier = Modifier.size(24.dp)) {
                                    Icon(
                                        Icons.Default.Sync,
                                        contentDescription = "Sync from channel",
                                        tint = if (isSyncing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.graphicsLayer {
                                            rotationZ = if (isSyncing) syncRotationAngle.value else 0f
                                        }
                                    )
                                }
                                IconButton(onClick = onYouTubeDownloadClick, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Download, contentDescription = "Download from YouTube")
                                }
                                IconButton(onClick = onSettingsClick, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                                }
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(statusColor)
                            )
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Expandable Animated Search Bar
                    AnimatedVisibility(
                        visible = isSearchActive,
                        enter = expandVertically(),
                        exit = shrinkVertically()
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search songs, artists, albums...") },
                            leadingIcon = {
                                Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear search")
                                    }
                                } else {
                                    IconButton(onClick = { isSearchActive = false; searchQuery = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = "Close search")
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(24.dp),
                            // Card-like: filled with the same background as the app's other
                            // cards (surfaceVariant), no visible outline - instead of the
                            // previous bordered/transparent look.
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }

                    // Reconnect Banner Bar (Remains visible even when clicked until CONNECTED)
                    AnimatedVisibility(
                        visible = showReconnectButton,
                        enter = expandVertically(),
                        exit = shrinkVertically()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Connecting taking time...",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FilledTonalButton(
                                onClick = {
                                    app.tdlibManager.reconnect(app.settingsStore.proxySettings)
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Reconnect",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // Sync Progress Indicator & Status Message Banner
                    AnimatedVisibility(
                        visible = isSyncing,
                        enter = expandVertically(),
                        exit = shrinkVertically()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
                        ) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Sync,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .size(16.dp)
                                        .graphicsLayer {
                                            rotationZ = if (isSyncing) syncRotationAngle.value else 0f
                                        }
                                )
                                Text(
                                    text = syncProgressMessage.ifBlank { "Syncing songs from Telegram..." },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
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
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            // Pill-shaped filter chips - filled primary color when selected, plain transparent
            // otherwise (no outline). "Selected" here means whichever tab is CLOSEST to the
            // pager's live drag position, not the settled tab - see the LaunchedEffect above.
            LazyRow(
                state = filterChipsListState,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(LibraryTab.entries) { index, t ->
                    val liveIndex = (pagerState.currentPage + pagerState.currentPageOffsetFraction).roundToInt()
                    val selected = index == liveIndex
                    Surface(
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(t.ordinal)
                            }
                        },
                        shape = RoundedCornerShape(100),
                        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
                    ) {
                        Text(
                            text = t.label,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp)
                        )
                    }
                }
            }

            // GPU Hardware Texture Layer Page Transformer - renders 120fps smooth scaling/fading during touch swipes
            HorizontalPager(
                state = pagerState,
                beyondBoundsPageCount = 0,
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) { page ->
                key(page) {
                    when (LibraryTab.entries[page]) {
                        LibraryTab.TRACKS -> SongList(filteredTracksUi, tracks, playlists, onSongClick, resolvedViewModel, sortField, ascending)
                        LibraryTab.ALBUMS -> AlbumsList(filteredAlbums, onAlbumClick)
                        LibraryTab.ARTISTS -> ArtistsList(filteredArtists, onArtistClick)
                        LibraryTab.PLAYLISTS -> PlaylistsList(playlistSummaries, onPlaylistClick, onSmartPlaylistClick, resolvedViewModel)
                    }
                }
            }
        }
    }
}

@Composable
private fun SortBar(
    sortField: SortField,
    ascending: Boolean,
    onFieldSelected: (SortField) -> Unit,
    onToggleDirection: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 2.dp, end = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Box {
            FilledTonalButton(
                onClick = { menuExpanded = true },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Sort: ${sortField.label}", style = MaterialTheme.typography.labelMedium)
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                SortField.entries.forEach { field ->
                    DropdownMenuItem(
                        text = { Text(field.label) },
                        onClick = {
                            onFieldSelected(field)
                            menuExpanded = false
                        }
                    )
                }
            }
        }
        IconButton(onClick = onToggleDirection) {
            Icon(
                imageVector = if (ascending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                contentDescription = "Toggle sort direction",
                tint = MaterialTheme.colorScheme.primary
            )
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
    sortField: SortField,
    ascending: Boolean
) {
    val app = LocalContext.current.applicationContext as TgMusicApp
    val entitiesById = remember(songsEntities) { songsEntities.associateBy { it.telegramMessageId } }
    val songIds = remember(songsUi) { songsUi.map { it.id } }

    val onFieldSelected = remember(viewModel) { { f: SortField -> viewModel.selectSortField(f) } }
    val onToggleDir = remember(viewModel) { { viewModel.toggleSortDirection() } }

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

    if (songsUi.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            SortBar(sortField, ascending, onFieldSelected, onToggleDir)
            Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No songs found",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        // Clean flat LazyColumn - rows are separated by a plain thin divider rather than a
        // gap, matching a plain list feel instead of a card-per-row one.
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp)
        ) {
            item(key = "sort_bar", contentType = "sort_bar") {
                SortBar(sortField, ascending, onFieldSelected, onToggleDir)
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

@Composable
private fun AlbumsList(albums: List<AlbumSummary>, onAlbumClick: (String) -> Unit) {
    val context = LocalContext.current
    if (albums.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "No albums found",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 2.dp, end = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(
                items = albums,
                key = { it.album },
                contentType = { "album_row" }
            ) { album ->
                val imageRequest = remember(album.albumArtUrl, context) {
                    ImageRequest.Builder(context)
                        .data(album.albumArtUrl)
                        .allowHardware(true)
                        .size(150, 150)
                        .build()
                }

                val albumPainter = rememberAsyncImagePainter(imageRequest)

                val albumSubtitle = remember(album.artist, album.songCount) {
                    "${album.artist} • ${album.songCount} ${if (album.songCount == 1) "track" else "tracks"}"
                }

                val onAlbumClicked = remember(album.album, onAlbumClick) {
                    { onAlbumClick(album.album) }
                }

                // Flat list item row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(68.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(onClick = onAlbumClicked)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
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
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                            )
                        }
                    }

                    Spacer(Modifier.width(12.dp))

                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = album.album,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.SemiBold,
                                platformStyle = PlatformTextStyle(includeFontPadding = false)
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = albumSubtitle,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                platformStyle = PlatformTextStyle(includeFontPadding = false)
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
    if (artists.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "No artists found",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 2.dp, end = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(
                items = artists,
                key = { it.artist },
                contentType = { "artist_row" }
            ) { artist ->
                val artistSubtitle = remember(artist.songCount) {
                    "${artist.songCount} ${if (artist.songCount == 1) "track" else "tracks"}"
                }

                val onArtistClicked = remember(artist.artist, onArtistClick) {
                    { onArtistClick(artist.artist) }
                }

                val artistImageRequest = remember(artist.albumArtUrl, context) {
                    ImageRequest.Builder(context)
                        .data(artist.albumArtUrl)
                        .allowHardware(true)
                        .size(150, 150)
                        .build()
                }
                val artistPainter = rememberAsyncImagePainter(artistImageRequest)

                // Flat list item row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(68.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(onClick = onArtistClicked)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
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
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    Spacer(Modifier.width(12.dp))

                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = artist.artist,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.SemiBold,
                                platformStyle = PlatformTextStyle(includeFontPadding = false)
                            )
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = artistSubtitle,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                platformStyle = PlatformTextStyle(includeFontPadding = false)
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(52.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                ),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun PlaylistsList(
    playlists: List<PlaylistSummary>,
    onPlaylistClick: (Long, String) -> Unit,
    onSmartPlaylistClick: (SmartPlaylistKind) -> Unit,
    viewModel: LibraryViewModel
) {
    val context = LocalContext.current
    var showCreateDialog by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            Button(
                onClick = { showCreateDialog = true },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("New Playlist")
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, top = 2.dp, end = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item(key = "smart_liked", contentType = "smart_playlist_row") {
                SmartPlaylistRow(
                    label = SmartPlaylistKind.LIKED.label,
                    icon = Icons.Default.Favorite,
                    onClick = remember(onSmartPlaylistClick) { { onSmartPlaylistClick(SmartPlaylistKind.LIKED) } }
                )
            }
            item(key = "smart_telegram", contentType = "smart_playlist_row") {
                SmartPlaylistRow(
                    label = SmartPlaylistKind.TELEGRAM.label,
                    icon = Icons.Default.Send,
                    onClick = remember(onSmartPlaylistClick) { { onSmartPlaylistClick(SmartPlaylistKind.TELEGRAM) } }
                )
            }
            item(key = "smart_downloaded", contentType = "smart_playlist_row") {
                SmartPlaylistRow(
                    label = SmartPlaylistKind.DOWNLOADED.label,
                    icon = Icons.Default.Download,
                    onClick = remember(onSmartPlaylistClick) { { onSmartPlaylistClick(SmartPlaylistKind.DOWNLOADED) } }
                )
            }

            if (playlists.isNotEmpty()) {
                item(key = "your_playlists_header", contentType = "header") {
                    Text(
                        text = "Your playlists",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 4.dp)
                    )
                }
            }

            items(
                items = playlists,
                key = { it.id },
                contentType = { "playlist_row" }
            ) { playlist ->
                val onPlaylistClicked = remember(playlist.id, playlist.name, onPlaylistClick) {
                    { onPlaylistClick(playlist.id, playlist.name) }
                }

                val onDeleteClicked = remember(playlist.id, viewModel) {
                    { viewModel.deletePlaylist(playlist.id); Unit }
                }

                Surface(
                    onClick = onPlaylistClicked,
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer),
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
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }

                        Spacer(Modifier.width(12.dp))

                        Text(
                            text = playlist.name,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.SemiBold,
                                platformStyle = PlatformTextStyle(includeFontPadding = false)
                            ),
                            modifier = Modifier.weight(1f)
                        )

                        IconButton(onClick = onDeleteClicked) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete playlist",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
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