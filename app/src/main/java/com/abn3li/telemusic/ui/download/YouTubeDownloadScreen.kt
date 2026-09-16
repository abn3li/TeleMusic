package com.abn3li.telemusic.ui.download

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.data.browse.BrowseCollection
import com.abn3li.telemusic.data.browse.HomeSection
import com.abn3li.telemusic.data.download.YtDlpArtistResult
import com.abn3li.telemusic.data.download.YtDlpPlaylistResult
import com.abn3li.telemusic.data.download.YtDlpSearchResult
import com.abn3li.telemusic.data.local.SongEntity
import com.abn3li.telemusic.ui.nowplaying.LocalMiniPlayerInset
import kotlinx.coroutines.launch

// Same local dark palette the Library/Settings/Sync redesign uses (see LibraryScreen's own doc
// on why this is hardcoded per-screen rather than routed through MaterialTheme) - kept
// consistent here so this screen doesn't look like a different, unstyled screen bolted on.
private val BgColor = Color(0xFF000000)
private val CardColor = Color(0xFF19191C)
private val TextSecondary = Color(0xFFA9A9A6)
private val TextMuted = Color(0xFF8B8B88)
private val AccentGreen = Color(0xFF1D9E75)
private val ChevronColor = Color(0xFF5F5F5C)
private val DividerColor = Color(0xFF232326)

/**
 * "Search a song, playlist, or artist by name" - the Seal-style flow the user asked for instead
 * of the full YouTube Music browsing experience, extended to cover playlists/artists too (not
 * just individual songs). A song can be streamed (Play) or saved permanently (Download); a
 * playlist/artist result opens the exact same real Innertube browse Discovery's own cards use
 * (see YouTubeDownloadViewModel.playlistAsCollection/artistAsCollection).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun YouTubeDownloadScreen(
    onBack: () -> Unit,
    onOpenCollection: (BrowseCollection) -> Unit,
    onPlayStream: (SongEntity, Uri) -> Unit
) {
    val app = LocalContext.current.applicationContext as TgMusicApp
    val viewModel = remember {
        YouTubeDownloadViewModel(app.applicationContext, app.ytDlpRepository, app.musicRepository, app.settingsStore, app.discoveryRepository, onPlayStream)
    }
    val state by viewModel.uiState.collectAsState()
    val pagerState = rememberPagerState(initialPage = state.selectedTab.ordinal) { YouTubeSearchTab.entries.size }
    val pagerScope = rememberCoroutineScope()

    // Swiping the pager is a second way to change tabs alongside tapping a label - both funnel
    // through viewModel.selectTab() so a swipe triggers the same lazy Playlists/Artists fetch a
    // tap does (see selectTab's own doc). selectTab() is idempotent for an already-current tab,
    // so this firing again after an animateScrollToPage a tap already triggered is a no-op.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            viewModel.selectTab(YouTubeSearchTab.entries[page])
        }
    }
    // A fresh search() resets selectedTab back to SONGS in the ViewModel (see its own doc) even
    // if the pager was sitting on Playlists/Artists from the previous query - without this, the
    // pager would keep showing the old page while the state underneath had already moved to
    // Songs. Guarded so it only scrolls on a genuine mismatch, not every recomposition.
    LaunchedEffect(state.selectedTab) {
        if (pagerState.currentPage != state.selectedTab.ordinal) {
            pagerState.animateScrollToPage(state.selectedTab.ordinal)
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.onFolderPicked(uri) else viewModel.skipFolderPrompt()
    }

    if (state.pendingFolderPrompt != null) {
        AlertDialog(
            onDismissRequest = viewModel::skipFolderPrompt,
            icon = { Icon(Icons.Default.Folder, contentDescription = null) },
            title = { Text("Where should downloads be saved?") },
            text = {
                Text("Pick a folder in your device's shared storage - every song you download from YouTube or Telegram will get a real, visible copy there. You can change this later in Settings.")
            },
            confirmButton = {
                TextButton(onClick = { folderPicker.launch(null) }) { Text("Choose folder") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::skipFolderPrompt) { Text("Skip for now") }
            }
        )
    }

    Scaffold(
        containerColor = BgColor,
        topBar = {
            TopAppBar(
                title = { Text("YouTube", color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().background(BgColor).padding(padding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                placeholder = { Text("Song, playlist, or artist", color = TextMuted) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = AccentGreen) },
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = CardColor,
                    unfocusedContainerColor = CardColor,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
            )

            when {
                state.query.isBlank() -> DiscoveryHome(
                    isLoading = state.isLoadingHome,
                    sections = state.homeSections,
                    genres = state.genres,
                    onOpenCollection = onOpenCollection
                )
                // Only Songs is eager (see ViewModel.search's own doc) - Playlists/Artists load
                // lazily on their own tab, so this spinner only ever waits on Songs, not all three.
                state.isSearching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AccentGreen)
                }
                else -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        YouTubeSearchTab.entries.forEach { tab ->
                            val selected = state.selectedTab == tab
                            Text(
                                text = tab.label,
                                color = if (selected) Color.White else TextMuted,
                                fontSize = if (selected) 18.sp else 14.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.clickableNoRipple {
                                    viewModel.selectTab(tab)
                                    pagerScope.launch { pagerState.animateScrollToPage(tab.ordinal) }
                                }
                            )
                        }
                    }

                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize()
                    ) { page ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                                .background(CardColor)
                        ) {
                            when (YouTubeSearchTab.entries[page]) {
                                YouTubeSearchTab.SONGS -> if (state.results.isEmpty()) {
                                    EmptyTabMessage(state.errorMessage ?: "No songs found")
                                } else {
                                    LazyColumn(contentPadding = PaddingValues(bottom = LocalMiniPlayerInset.current)) {
                                        itemsIndexed(state.results, key = { _, r -> r.videoId }) { index, result ->
                                            DownloadResultRow(
                                                result = result,
                                                isDownloading = result.videoId in state.downloadingIds,
                                                isDownloaded = result.videoId in state.downloadedIds,
                                                isLoadingStream = result.videoId in state.loadingStreamIds,
                                                onDownloadClick = { viewModel.onDownloadIconClick(result) },
                                                onPlayClick = { viewModel.onPlayClick(result) }
                                            )
                                            if (index < state.results.lastIndex) HorizontalDividerLine()
                                        }
                                    }
                                }
                                YouTubeSearchTab.PLAYLISTS -> if (state.playlistResults.isEmpty()) {
                                    if (state.isLoadingPlaylists) {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            CircularProgressIndicator(color = AccentGreen)
                                        }
                                    } else {
                                        EmptyTabMessage("No playlists found")
                                    }
                                } else {
                                    LazyColumn(contentPadding = PaddingValues(bottom = LocalMiniPlayerInset.current)) {
                                        itemsIndexed(state.playlistResults, key = { _, p -> p.playlistId }) { index, playlist ->
                                            PlaylistResultRow(playlist = playlist, onClick = { onOpenCollection(viewModel.playlistAsCollection(playlist)) })
                                            if (index < state.playlistResults.lastIndex) HorizontalDividerLine()
                                        }
                                    }
                                }
                                YouTubeSearchTab.ARTISTS -> if (state.artistResults.isEmpty()) {
                                    if (state.isLoadingArtists) {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            CircularProgressIndicator(color = AccentGreen)
                                        }
                                    } else {
                                        EmptyTabMessage("No artists found")
                                    }
                                } else {
                                    LazyColumn(contentPadding = PaddingValues(bottom = LocalMiniPlayerInset.current)) {
                                        itemsIndexed(state.artistResults, key = { _, a -> a.channelId }) { index, artist ->
                                            ArtistResultRow(artist = artist, onClick = { onOpenCollection(viewModel.artistAsCollection(artist)) })
                                            if (index < state.artistResults.lastIndex) HorizontalDividerLine()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val YouTubeSearchTab.label: String
    get() = when (this) {
        YouTubeSearchTab.SONGS -> "Songs"
        YouTubeSearchTab.PLAYLISTS -> "Playlists"
        YouTubeSearchTab.ARTISTS -> "Artists"
    }

@Composable
private fun EmptyTabMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = TextMuted)
    }
}

@Composable
private fun HorizontalDividerLine() {
    androidx.compose.material3.HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
}

// Flat click target without a ripple, matching the rest of this redesign's flat tap style.
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = composed {
    this.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick
    )
}

/** YouTube Music's own real Home feed - shelves of playlists/charts/artists (never a bare music
 * video card - see data/browse/BrowseParser's own doc on the "audio only" filtering this all
 * goes through). Tapping any card is a real browse, not a static preview - see
 * BrowseCollectionScreen's own doc. */
@Composable
private fun DiscoveryHome(
    isLoading: Boolean,
    sections: List<HomeSection>,
    genres: List<BrowseCollection>,
    onOpenCollection: (BrowseCollection) -> Unit
) {
    when {
        isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = AccentGreen) }
        sections.isEmpty() && genres.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Couldn't load Discovery - check your connection", color = TextMuted)
        }
        else -> androidx.compose.foundation.lazy.LazyColumn(
            contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp + LocalMiniPlayerInset.current)
        ) {
            if (genres.isNotEmpty()) {
                item(key = "genres_header") {
                    Text(
                        text = "Genres",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp)
                    )
                }
                item(key = "genres_row") {
                    androidx.compose.foundation.lazy.LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp)
                    ) {
                        items(genres, key = { it.title }) { genre ->
                            GenreChip(genre = genre, onClick = { onOpenCollection(genre) })
                        }
                    }
                }
            }
            // Keyed by title+index, not just title - YouTube's own Home feed can legitimately
            // include a shelf actually titled "New releases" at the same time as the dedicated
            // New Releases section DiscoveryRepository appends, and a bare title-only key
            // crashed LazyColumn with a duplicate-key exception the moment that happened.
            itemsIndexed(sections, key = { index, section -> "${section.title}_$index" }) { _, section ->
                Text(
                    text = section.title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp)
                )
                androidx.compose.foundation.lazy.LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    items(section.items, key = { it.browseId }) { card ->
                        DiscoveryCard(card = card, onClick = { onOpenCollection(card) })
                    }
                }
            }
        }
    }
}

@Composable
private fun GenreChip(genre: BrowseCollection, onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = RoundedCornerShape(100),
        color = CardColor
    ) {
        Text(
            text = genre.title,
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun DiscoveryCard(card: BrowseCollection, onClick: () -> Unit) {
    val context = LocalContext.current
    Column(modifier = Modifier.width(140.dp).clickable(onClick = onClick)) {
        Box(
            modifier = Modifier.size(140.dp).clip(RoundedCornerShape(10.dp)).background(DividerColor),
            contentAlignment = Alignment.Center
        ) {
            if (card.thumbnailUrl != null) {
                val request = remember(card.thumbnailUrl) {
                    coil.request.ImageRequest.Builder(context).data(card.thumbnailUrl).size(300, 300).build()
                }
                AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Default.MusicNote, contentDescription = null, tint = TextMuted)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(card.title, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        card.subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DownloadResultRow(
    result: YtDlpSearchResult,
    isDownloading: Boolean,
    isDownloaded: Boolean,
    isLoadingStream: Boolean,
    onDownloadClick: () -> Unit,
    onPlayClick: () -> Unit
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isLoadingStream, onClick = onPlayClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(DividerColor),
            contentAlignment = Alignment.Center
        ) {
            if (result.thumbnailUrl != null) {
                val request = remember(result.thumbnailUrl) {
                    coil.request.ImageRequest.Builder(context).data(result.thumbnailUrl).size(150, 150).build()
                }
                AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Default.MusicNote, contentDescription = null, tint = TextMuted)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(result.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                result.artist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted
            )
        }
        if (isLoadingStream) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = AccentGreen)
        } else {
            IconButton(onClick = onPlayClick) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.White)
            }
        }
        when {
            isDownloading -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = AccentGreen)
            isDownloaded -> Icon(Icons.Default.CheckCircle, contentDescription = "Downloaded", tint = AccentGreen)
            else -> IconButton(onClick = onDownloadClick) {
                Icon(Icons.Default.Download, contentDescription = "Download", tint = TextSecondary)
            }
        }
    }
}

@Composable
private fun PlaylistResultRow(playlist: YtDlpPlaylistResult, onClick: () -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(DividerColor),
            contentAlignment = Alignment.Center
        ) {
            if (playlist.thumbnailUrl != null) {
                val request = remember(playlist.thumbnailUrl) {
                    coil.request.ImageRequest.Builder(context).data(playlist.thumbnailUrl).size(150, 150).build()
                }
                AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null, tint = TextMuted)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(playlist.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                playlist.subtitle ?: "Playlist",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted
            )
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = ChevronColor)
    }
}

@Composable
private fun ArtistResultRow(artist: YtDlpArtistResult, onClick: () -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(48.dp).clip(CircleShape).background(DividerColor),
            contentAlignment = Alignment.Center
        ) {
            if (artist.thumbnailUrl != null) {
                val request = remember(artist.thumbnailUrl) {
                    coil.request.ImageRequest.Builder(context).data(artist.thumbnailUrl).size(150, 150).build()
                }
                AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Default.Person, contentDescription = null, tint = TextMuted)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(artist.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text("Artist", style = MaterialTheme.typography.bodySmall, color = TextMuted)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = ChevronColor)
    }
}
