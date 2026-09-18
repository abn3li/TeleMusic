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
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import com.abn3li.telemusic.data.browse.BrowseCollection
import com.abn3li.telemusic.data.browse.BrowseTrack
import com.abn3li.telemusic.data.local.SongEntity
import com.abn3li.telemusic.ui.library.AccentGreen
import com.abn3li.telemusic.ui.library.AdaptiveScreenBackground
import com.abn3li.telemusic.ui.library.DividerColor
import com.abn3li.telemusic.ui.library.OnAccentGreen
import com.abn3li.telemusic.ui.library.TextSecondary
import com.abn3li.telemusic.ui.library.adaptivePanelFill
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
    onPlayStream: (SongEntity, Uri, String) -> Unit
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

    AdaptiveScreenBackground {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(state.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.tracks.isNotEmpty() -> {
                    val coverUrl = remember(state.tracks) { state.tracks.firstOrNull { !it.thumbnailUrl.isNullOrEmpty() }?.thumbnailUrl }
                    LazyColumn(contentPadding = PaddingValues(bottom = LocalMiniPlayerInset.current)) {
                        // Same header shape as a real library playlist (see DetailScreens.kt's
                        // SongListScaffold) - cover art + track count + Play/Shuffle, so opening
                        // an imported playlist feels the same as opening one of your own.
                        item(key = "collection_header") {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                androidx.compose.material3.Surface(
                                    shape = RoundedCornerShape(24.dp),
                                    color = adaptivePanelFill(),
                                    shadowElevation = 4.dp,
                                    modifier = Modifier.size(160.dp)
                                ) {
                                    if (!coverUrl.isNullOrEmpty()) {
                                        AsyncImage(model = coverUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                    } else {
                                        Box(
                                            modifier = Modifier.fillMaxSize().background(adaptivePanelFill()),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.MusicNote,
                                                contentDescription = null,
                                                modifier = Modifier.size(64.dp),
                                                tint = AccentGreen.copy(alpha = 0.8f)
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = "${state.tracks.size} ${if (state.tracks.size == 1) "track" else "tracks"}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary
                                )
                                Spacer(Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    androidx.compose.material3.Button(
                                        onClick = { state.tracks.firstOrNull()?.let { viewModel.onPlayClick(it) } },
                                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = OnAccentGreen),
                                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Play All")
                                    }
                                    androidx.compose.material3.FilledTonalButton(
                                        onClick = { state.tracks.randomOrNull()?.let { viewModel.onPlayClick(it) } },
                                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = DividerColor, contentColor = Color.White),
                                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                                    ) {
                                        Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(20.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Shuffle")
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                                // A separate row below Play/Shuffle, not a third pill squeezed
                                // into the same row - this is a slower, heavier action (a real
                                // download per track) and deserves its own visual weight instead
                                // of competing with two one-tap playback buttons.
                                when {
                                    state.importedPlaylistId != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "Imported to your Library playlists",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = AccentGreen
                                        )
                                    }
                                    state.importProgress != null -> {
                                        val (done, total) = state.importProgress!!
                                        androidx.compose.material3.OutlinedButton(
                                            onClick = {},
                                            enabled = false,
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                                        ) {
                                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = AccentGreen)
                                            Spacer(Modifier.width(8.dp))
                                            Text("Importing $done/$total…")
                                        }
                                    }
                                    else -> androidx.compose.material3.OutlinedButton(
                                        onClick = { viewModel.importToLibrary() },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Import to Library")
                                    }
                                }
                            }
                        }
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
                    Text(state.errorMessage ?: "Nothing here", color = TextSecondary)
                }
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
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(adaptivePanelFill()),
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
                Icon(Icons.Default.MusicNote, contentDescription = null, tint = AccentGreen)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(track.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(
                track.artist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
        // Fixed 48dp slots regardless of state (IconButton's own default touch size) - a bare
        // CircularProgressIndicator is narrower than an IconButton, so without this the row
        // visibly shifted left the moment loading started and snapped back once it finished.
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            if (isLoadingStream) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = AccentGreen)
            } else {
                IconButton(onClick = onPlayClick) { Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.White) }
            }
        }
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            when {
                isDownloading -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = AccentGreen)
                isDownloaded -> Icon(Icons.Default.CheckCircle, contentDescription = "Downloaded", tint = AccentGreen)
                else -> IconButton(onClick = onDownloadClick) { Icon(Icons.Default.Download, contentDescription = "Download", tint = Color.White) }
            }
        }
    }
}

@Composable
private fun BrowseCollectionCard(collection: BrowseCollection, onClick: () -> Unit) {
    val context = LocalContext.current
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(10.dp)).background(adaptivePanelFill()),
            contentAlignment = Alignment.Center
        ) {
            if (collection.thumbnailUrl != null) {
                val request = remember(collection.thumbnailUrl) {
                    coil.request.ImageRequest.Builder(context).data(collection.thumbnailUrl).size(300, 300).build()
                }
                AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Default.MusicNote, contentDescription = null, tint = AccentGreen)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(collection.title, color = Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        collection.subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
