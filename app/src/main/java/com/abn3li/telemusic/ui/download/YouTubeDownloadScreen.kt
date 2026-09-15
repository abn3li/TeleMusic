package com.abn3li.telemusic.ui.download

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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.abn3li.telemusic.ui.nowplaying.LocalMiniPlayerInset

/**
 * "Search a song by name, download it" - the Seal-style flow the user asked for instead of the
 * full YouTube Music browsing experience: type a name, pick the right result, tap Download.
 * Always grabs the best real audio available - no quality picker any more (see DownloadQuality's
 * own doc). Real playback still only ever happens through the normal library once a song lands
 * there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeDownloadScreen(onBack: () -> Unit, onOpenCollection: (BrowseCollection) -> Unit) {
    val app = LocalContext.current.applicationContext as TgMusicApp
    val viewModel = remember {
        YouTubeDownloadViewModel(app.applicationContext, app.ytDlpRepository, app.musicRepository, app.settingsStore, app.discoveryRepository)
    }
    val state by viewModel.uiState.collectAsState()

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
        topBar = {
            TopAppBar(
                title = { Text("Download from YouTube") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(16.dp)),
                placeholder = { Text("Song name or artist - song") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = TextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.search() })
            )

            when {
                state.query.isBlank() -> DiscoveryHome(
                    isLoading = state.isLoadingHome,
                    sections = state.homeSections,
                    genres = state.genres,
                    onOpenCollection = onOpenCollection
                )
                state.isSearching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        state.errorMessage ?: "Search for a song to download it",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> LazyColumn(contentPadding = PaddingValues(bottom = LocalMiniPlayerInset.current)) {
                    items(state.results, key = { it.videoId }) { result ->
                        DownloadResultRow(
                            result = result,
                            isDownloading = result.videoId in state.downloadingIds,
                            isDownloaded = result.videoId in state.downloadedIds,
                            onDownloadClick = { viewModel.onDownloadIconClick(result) }
                        )
                    }
                }
            }
        }
    }
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
        isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        sections.isEmpty() && genres.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Couldn't load Discovery - check your connection", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> androidx.compose.foundation.lazy.LazyColumn(
            contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp + LocalMiniPlayerInset.current)
        ) {
            if (genres.isNotEmpty()) {
                item(key = "genres_header") {
                    Text(
                        text = "Genres",
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
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(100),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Text(
            text = genre.title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun DiscoveryCard(card: BrowseCollection, onClick: () -> Unit) {
    val context = LocalContext.current
    Column(modifier = Modifier.width(140.dp).clickable(onClick = onClick)) {
        Box(
            modifier = Modifier.size(140.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            if (card.thumbnailUrl != null) {
                val request = remember(card.thumbnailUrl) {
                    coil.request.ImageRequest.Builder(context).data(card.thumbnailUrl).size(300, 300).build()
                }
                AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Default.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(card.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        card.subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DownloadResultRow(
    result: YtDlpSearchResult,
    isDownloading: Boolean,
    isDownloaded: Boolean,
    onDownloadClick: () -> Unit
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            if (result.thumbnailUrl != null) {
                val request = remember(result.thumbnailUrl) {
                    coil.request.ImageRequest.Builder(context).data(result.thumbnailUrl).size(150, 150).build()
                }
                AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Default.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(result.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                result.artist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        when {
            isDownloading -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            isDownloaded -> Icon(Icons.Default.CheckCircle, contentDescription = "Downloaded", tint = MaterialTheme.colorScheme.primary)
            else -> IconButton(onClick = onDownloadClick) {
                Icon(Icons.Default.Download, contentDescription = "Download")
            }
        }
    }
}
