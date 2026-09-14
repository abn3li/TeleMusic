package com.abn3li.telemusic.ui.download

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.data.download.DownloadQuality
import com.abn3li.telemusic.data.download.YtDlpSearchResult

/**
 * "Search a song by name, download it" - the Seal-style flow the user asked for instead of the
 * full YouTube Music browsing experience: type a name, pick the right result, tap Download, pick
 * a quality in the popup. Real playback still only ever happens through the normal library once
 * a song lands there. The quality popup only ever appears from here - Telegram's own download
 * button downloads at whatever quality the source file already is, there's no format to choose.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeDownloadScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as TgMusicApp
    val viewModel = remember {
        YouTubeDownloadViewModel(app.applicationContext, app.ytDlpRepository, app.musicRepository, app.settingsStore)
    }
    val state by viewModel.uiState.collectAsState()

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.onFolderPicked(uri) else viewModel.skipFolderPrompt()
    }

    state.qualityPickerResult?.let { result ->
        QualityPickerDialog(
            result = result,
            initialQuality = state.lastUsedQuality,
            onDismiss = viewModel::dismissQualityPicker,
            onConfirm = { quality -> viewModel.confirmDownload(result, quality) }
        )
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
                state.isSearching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        state.errorMessage ?: "Search for a song to download it",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> LazyColumn {
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

/** Per-download quality/format choice, styled as a grid of selectable boxes rather than a plain
 * dropdown/list - matches what the user asked for, and reads faster than text rows since every
 * option's size/label is visible at once instead of one at a time in a menu. Only ever shown for
 * a YouTube download - see this file's own top-level doc for why. */
@Composable
private fun QualityPickerDialog(
    result: YtDlpSearchResult,
    initialQuality: DownloadQuality,
    onDismiss: () -> Unit,
    onConfirm: (DownloadQuality) -> Unit
) {
    var selected by remember(result.videoId) { mutableStateOf(initialQuality) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(20.dp)
        ) {
            Text("Select quality", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                result.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp, bottom = 16.dp)
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.height(140.dp)
            ) {
                items(DownloadQuality.entries) { quality ->
                    QualityOptionBox(
                        quality = quality,
                        isSelected = quality == selected,
                        onClick = { selected = quality }
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = { onConfirm(selected) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Download (${selected.label})")
            }
        }
    }
}

@Composable
private fun QualityOptionBox(quality: DownloadQuality, isSelected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(2.2f)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainer
            )
            .then(
                if (isSelected) {
                    Modifier.border(BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary), RoundedCornerShape(14.dp))
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            quality.label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(2.dp))
        Text(
            quality.subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DownloadResultRow(
    result: YtDlpSearchResult,
    isDownloading: Boolean,
    isDownloaded: Boolean,
    onDownloadClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            if (result.thumbnailUrl != null) {
                AsyncImage(model = result.thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
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
