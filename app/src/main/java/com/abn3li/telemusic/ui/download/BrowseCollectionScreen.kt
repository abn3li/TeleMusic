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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.net.Uri
import coil.compose.AsyncImage
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.data.browse.BrowseCollection
import com.abn3li.telemusic.data.browse.BrowseTrack
import com.abn3li.telemusic.data.local.SongEntity
import com.abn3li.telemusic.ui.nowplaying.LocalMiniPlayerInset

/**
 * A single Discovery destination - a playlist, chart, or artist's own page reached by tapping a
 * card on the Home feed. Fully real: the track list comes from the same browse call the card's
 * own data did, and each Download button feeds the exact same yt-dlp flow the search screen
 * uses - nothing here is a static preview.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseCollectionScreen(
    title: String,
    browseId: String,
    params: String?,
    onBack: () -> Unit,
    onOpenCollection: (BrowseCollection) -> Unit,
    onPlayStream: (SongEntity, Uri) -> Unit
) {
    val app = LocalContext.current.applicationContext as TgMusicApp
    val viewModel = remember(browseId, params) {
        BrowseCollectionViewModel(
            app.applicationContext, title, browseId, params,
            app.discoveryRepository, app.ytDlpRepository, app.musicRepository, app.settingsStore, onPlayStream
        )
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
            text = { Text("Pick a folder in your device's shared storage - every song you download will get a real, visible copy there. You can change this later in Settings.") },
            confirmButton = { TextButton(onClick = { folderPicker.launch(null) }) { Text("Choose folder") } },
            dismissButton = { TextButton(onClick = viewModel::skipFolderPrompt) { Text("Skip for now") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.tracks.isNotEmpty() -> LazyColumn(contentPadding = PaddingValues(bottom = LocalMiniPlayerInset.current)) {
                    items(state.tracks, key = { it.videoId }) { track ->
                        BrowseTrackRow(
                            track = track,
                            isDownloading = track.videoId in state.downloadingIds,
                            isDownloaded = track.videoId in state.downloadedIds,
                            isLoadingStream = track.videoId in state.loadingStreamIds,
                            onDownloadClick = { viewModel.onDownloadClick(track) },
                            onPlayClick = { viewModel.onPlayClick(track) }
                        )
                    }
                }
                state.collections.isNotEmpty() -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + LocalMiniPlayerInset.current),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(state.collections, key = { it.browseId }) { collection ->
                        BrowseCollectionCard(collection = collection, onClick = { onOpenCollection(collection) })
                    }
                }
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(state.errorMessage ?: "Nothing here", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun BrowseTrackRow(
    track: BrowseTrack,
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
            .height(68.dp)
            .clickable(enabled = !isLoadingStream, onClick = onPlayClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            if (track.thumbnailUrl != null) {
                // Requested at the row's own display size, not the source's full resolution
                // (often 500px+) - decoding every thumbnail that large just to shrink it back
                // down in a 52dp box was wasted CPU/memory on every row, magnified by however
                // many rows a playlist has.
                val request = remember(track.thumbnailUrl) {
                    coil.request.ImageRequest.Builder(context).data(track.thumbnailUrl).size(150, 150).build()
                }
                AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Default.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(
                track.artist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (isLoadingStream) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            IconButton(onClick = onPlayClick) { Icon(Icons.Default.PlayArrow, contentDescription = "Play") }
        }
        when {
            isDownloading -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            isDownloaded -> Icon(Icons.Default.CheckCircle, contentDescription = "Downloaded", tint = MaterialTheme.colorScheme.primary)
            else -> IconButton(onClick = onDownloadClick) { Icon(Icons.Default.Download, contentDescription = "Download") }
        }
    }
}

@Composable
private fun BrowseCollectionCard(collection: BrowseCollection, onClick: () -> Unit) {
    val context = LocalContext.current
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            if (collection.thumbnailUrl != null) {
                val request = remember(collection.thumbnailUrl) {
                    coil.request.ImageRequest.Builder(context).data(collection.thumbnailUrl).size(300, 300).build()
                }
                AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Default.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(collection.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        collection.subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
