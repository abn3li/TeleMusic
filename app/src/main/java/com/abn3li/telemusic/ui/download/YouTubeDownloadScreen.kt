package com.abn3li.telemusic.ui.download

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.data.browse.BrowseCollection
import com.abn3li.telemusic.data.browse.HomeSection
import com.abn3li.telemusic.data.download.YtDlpSearchResult
import com.abn3li.telemusic.data.local.ImportedPlaylistEntity
import com.abn3li.telemusic.data.local.SongEntity
import com.abn3li.telemusic.ui.nowplaying.LocalMiniPlayerInset

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
 * "Search a song by name" - the Seal-style flow the user asked for instead of the full YouTube
 * Music browsing experience. A song can be streamed (Play) or saved permanently (Download).
 * Below the search bar, a blank query shows Discovery - YouTube Music's own real home feed, plus
 * any playlists the user has imported by URL (see [ImportPlaylistDialog]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeDownloadScreen(
    onBack: () -> Unit,
    onOpenCollection: (BrowseCollection) -> Unit,
    onPlayStream: (SongEntity, Uri, String) -> Unit
) {
    val app = LocalContext.current.applicationContext as TgMusicApp
    val viewModel = remember {
        YouTubeDownloadViewModel(app.applicationContext, app.ytDlpRepository, app.musicRepository, app.settingsStore, app.discoveryRepository, onPlayStream)
    }
    val state by viewModel.uiState.collectAsState()
    var showImportDialog by remember { mutableStateOf(false) }

    // System/gesture back while showing search results returns to Discovery first, not straight
    // out of this screen - Discovery vs results is just this screen's own query.isBlank() state,
    // not a separate nav destination, so without this the very first back press after a search
    // popped the whole screen back to Library, skipping Discovery entirely. A second back press
    // (query already blank, this handler disabled) falls through to the normal nav pop.
    BackHandler(enabled = state.query.isNotBlank()) { viewModel.onQueryChange("") }

    if (showImportDialog) {
        ImportPlaylistDialog(
            errorMessage = state.importPlaylistError,
            onDismiss = { showImportDialog = false; viewModel.clearImportPlaylistError() },
            onImport = { url ->
                viewModel.importPlaylist(url) { success -> if (success) showImportDialog = false }
            }
        )
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
                placeholder = { Text("Search a song", color = TextMuted) },
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
                    importedPlaylists = state.importedPlaylists,
                    onOpenCollection = onOpenCollection,
                    onImportPlaylistClick = { showImportDialog = true },
                    onRemoveImportedPlaylist = { viewModel.removeImportedPlaylist(it) }
                )
                state.isSearching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AccentGreen)
                }
                else -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                        .background(CardColor)
                ) {
                    if (state.results.isEmpty()) {
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
                }
            }
        }
    }
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

/** Paste a YouTube playlist URL to pin it permanently in Discovery - see
 * YouTubeDownloadViewModel.importPlaylist's own doc for how the URL becomes a browseId. */
@Composable
private fun ImportPlaylistDialog(
    errorMessage: String?,
    onDismiss: () -> Unit,
    onImport: (String) -> Unit
) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import playlist") },
        text = {
            Column {
                Text("Paste a YouTube or YouTube Music playlist link - it'll stay in Discovery until you remove it.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    placeholder = { Text("https://music.youtube.com/playlist?list=...") },
                    singleLine = true,
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let { { Text(it, color = MaterialTheme.colorScheme.error) } }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onImport(url.trim()) }, enabled = url.isNotBlank()) { Text("Import") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
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
    importedPlaylists: List<ImportedPlaylistEntity>,
    onOpenCollection: (BrowseCollection) -> Unit,
    onImportPlaylistClick: () -> Unit,
    onRemoveImportedPlaylist: (ImportedPlaylistEntity) -> Unit
) {
    when {
        isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = AccentGreen) }
        sections.isEmpty() && genres.isEmpty() && importedPlaylists.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Couldn't load Discovery - check your connection", color = TextMuted)
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onImportPlaylistClick) {
                    Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null, tint = AccentGreen)
                    Spacer(Modifier.width(6.dp))
                    Text("Import a playlist", color = AccentGreen)
                }
            }
        }
        else -> androidx.compose.foundation.lazy.LazyColumn(
            contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp + LocalMiniPlayerInset.current)
        ) {
            item(key = "imported_header") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Imported playlists",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onImportPlaylistClick) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = "Import playlist", tint = AccentGreen)
                    }
                }
            }
            if (importedPlaylists.isEmpty()) {
                item(key = "imported_empty") {
                    Text(
                        text = "No playlists imported yet - tap + to add one by URL.",
                        color = TextMuted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            } else {
                item(key = "imported_row") {
                    androidx.compose.foundation.lazy.LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp)
                    ) {
                        items(importedPlaylists, key = { it.browseId }) { playlist ->
                            ImportedPlaylistCard(
                                playlist = playlist,
                                onClick = {
                                    onOpenCollection(
                                        BrowseCollection(
                                            browseId = playlist.browseId,
                                            params = null,
                                            title = playlist.title,
                                            subtitle = playlist.subtitle,
                                            thumbnailUrl = playlist.thumbnailUrl,
                                            kind = com.abn3li.telemusic.data.browse.BrowseKind.PLAYLIST
                                        )
                                    )
                                },
                                onRemove = { onRemoveImportedPlaylist(playlist) }
                            )
                        }
                    }
                }
            }
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
        // Both trailing slots stay a fixed 48dp (IconButton's own default touch size) regardless
        // of which state they're showing - a bare CircularProgressIndicator/Icon is narrower
        // than an IconButton, so without this the slot itself shrank the moment a row started
        // loading, shoving the Download button (and everything to its right, in a wider row) a
        // few dp left and snapping back once loading finished - a visible layout jump on every
        // tap, not just an icon swap in place.
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            if (isLoadingStream) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = AccentGreen)
            } else {
                IconButton(onClick = onPlayClick) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.White)
                }
            }
        }
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            when {
                isDownloading -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = AccentGreen)
                isDownloaded -> Icon(Icons.Default.CheckCircle, contentDescription = "Downloaded", tint = AccentGreen)
                else -> IconButton(onClick = onDownloadClick) {
                    Icon(Icons.Default.Download, contentDescription = "Download", tint = TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun ImportedPlaylistCard(playlist: ImportedPlaylistEntity, onClick: () -> Unit, onRemove: () -> Unit) {
    val context = LocalContext.current
    Column(modifier = Modifier.width(140.dp).clickable(onClick = onClick)) {
        Box(
            modifier = Modifier.size(140.dp).clip(RoundedCornerShape(10.dp)).background(DividerColor),
            contentAlignment = Alignment.Center
        ) {
            if (playlist.thumbnailUrl != null) {
                val request = remember(playlist.thumbnailUrl) {
                    coil.request.ImageRequest.Builder(context).data(playlist.thumbnailUrl).size(300, 300).build()
                }
                AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Default.MusicNote, contentDescription = null, tint = TextMuted)
            }
            IconButton(
                onClick = onRemove,
                modifier = Modifier.align(Alignment.TopEnd).size(28.dp).padding(2.dp)
            ) {
                Box(modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f)).size(22.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Close, contentDescription = "Remove imported playlist", tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(playlist.title, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        playlist.subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
