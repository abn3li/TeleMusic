package com.abn3li.telemusic.ui.download

import androidx.compose.ui.text.input.KeyboardType
import com.abn3li.telemusic.ui.library.AppAlert
import com.abn3li.telemusic.ui.library.AlertAction
import com.abn3li.telemusic.ui.library.AlertTextField
import com.abn3li.telemusic.ui.library.AlertNote
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.data.browse.BrowseCollection
import com.abn3li.telemusic.data.browse.BrowseKind
import com.abn3li.telemusic.data.local.ImportedPlaylistEntity
import com.abn3li.telemusic.data.local.SongEntity
import com.abn3li.telemusic.ui.library.AppAccent
import com.abn3li.telemusic.ui.library.DestructiveRed
import com.abn3li.telemusic.ui.library.FilterPill
import com.abn3li.telemusic.ui.library.GroupLabelColor
import com.abn3li.telemusic.ui.library.LargeTitleList
import com.abn3li.telemusic.ui.library.LibraryDivider
import com.abn3li.telemusic.ui.library.LibrarySearchField

/**
 * Search a song by name and stream (Play) or save (Download) it. With no query, Discovery shows
 * YouTube Music's own home shelves plus any playlists imported by URL.
 */
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

    // Back while showing results returns to Discovery first rather than leaving the screen.
    BackHandler(enabled = state.query.isNotBlank()) { viewModel.onQueryChange("") }

    if (showImportDialog) {
        ImportPlaylistDialog(
            errorMessage = state.importPlaylistError,
            onDismiss = { showImportDialog = false; viewModel.clearImportPlaylistError() },
            onImport = { url -> viewModel.importPlaylist(url) { success -> if (success) showImportDialog = false } }
        )
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.onFolderPicked(uri) else viewModel.skipFolderPrompt()
    }
    if (state.pendingFolderPrompt != null) {
        DownloadFolderDialog(onChoose = { folderPicker.launch(null) }, onSkip = viewModel::skipFolderPrompt)
    }

    LargeTitleList(
        title = "YouTube",
        onBack = onBack,
        stickyContent = {
            LibrarySearchField(state.query, "Search Songs", viewModel::onQueryChange, onSearch = { viewModel.search() })
        }
    ) {
        when {
            state.query.isBlank() -> discovery(
                isLoading = state.isLoadingHome,
                sections = state.homeSections,
                genres = state.genres,
                importedPlaylists = state.importedPlaylists,
                onOpenCollection = onOpenCollection,
                onImportPlaylistClick = { showImportDialog = true },
                onRemoveImportedPlaylist = viewModel::removeImportedPlaylist
            )
            state.isSearching -> item("searching") { CenteredSpinner() }
            state.results.isEmpty() -> item("no_results") { CenteredMessage(state.errorMessage ?: "No songs found") }
            else -> itemsIndexed(state.results, key = { _, r -> r.videoId }) { index, result ->
                TrackResultRow(
                    title = result.title,
                    artist = result.artist,
                    thumbnailUrl = result.thumbnailUrl,
                    isDownloading = result.videoId in state.downloadingIds,
                    isDownloaded = result.videoId in state.downloadedIds,
                    isLoadingStream = result.videoId in state.loadingStreamIds,
                    onDownloadClick = { viewModel.onDownloadIconClick(result) },
                    onPlayClick = { viewModel.onPlayClick(result) }
                )
                if (index < state.results.lastIndex) LibraryDivider(start = 88.dp)
            }
        }
    }
}

private fun LazyListScope.discovery(
    isLoading: Boolean,
    sections: List<com.abn3li.telemusic.data.browse.HomeSection>,
    genres: List<BrowseCollection>,
    importedPlaylists: List<ImportedPlaylistEntity>,
    onOpenCollection: (BrowseCollection) -> Unit,
    onImportPlaylistClick: () -> Unit,
    onRemoveImportedPlaylist: (ImportedPlaylistEntity) -> Unit
) {
    item("imported_header") {
        ShelfHeader("Imported Playlists") {
            Icon(
                Icons.Rounded.Add,
                contentDescription = "Import playlist",
                tint = AppAccent,
                modifier = Modifier.size(28.dp).clickable(onClick = onImportPlaylistClick)
            )
        }
    }
    if (importedPlaylists.isEmpty()) {
        item("imported_empty") {
            Text(
                "Tap + to add a YouTube playlist by its link.",
                color = GroupLabelColor,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 18.dp)
            )
        }
    } else {
        item("imported_row") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = 18.dp)) {
                items(importedPlaylists, key = { it.browseId }) { playlist ->
                    ShelfCard(
                        title = playlist.title,
                        subtitle = playlist.subtitle,
                        thumbnailUrl = playlist.thumbnailUrl,
                        onClick = {
                            onOpenCollection(
                                BrowseCollection(
                                    browseId = playlist.browseId,
                                    params = null,
                                    title = playlist.title,
                                    subtitle = playlist.subtitle,
                                    thumbnailUrl = playlist.thumbnailUrl,
                                    kind = BrowseKind.PLAYLIST
                                )
                            )
                        },
                        onRemove = { onRemoveImportedPlaylist(playlist) }
                    )
                }
            }
        }
    }
    when {
        isLoading -> item("discovery_loading") { CenteredSpinner() }
        sections.isEmpty() && genres.isEmpty() -> item("discovery_error") {
            CenteredMessage("Couldn't load Discovery. Check your connection.")
        }
        else -> {
            if (genres.isNotEmpty()) {
                item("genres_header") { ShelfHeader("Genres") }
                item("genres_row") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 18.dp)) {
                        items(genres, key = { it.title }) { genre ->
                            FilterPill(genre.title, selected = false, onClick = { onOpenCollection(genre) })
                        }
                    }
                }
            }
            // Keyed by title + index: YouTube's feed can contain two shelves with the same title.
            itemsIndexed(sections, key = { index, section -> "${section.title}_$index" }) { _, section ->
                ShelfHeader(section.title)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = 18.dp)) {
                    items(section.items, key = { it.browseId }) { card ->
                        ShelfCard(card.title, card.subtitle, card.thumbnailUrl, onClick = { onOpenCollection(card) })
                    }
                }
            }
        }
    }
}

@Composable
internal fun ShelfHeader(title: String, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(top = 22.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
private fun ShelfCard(title: String, subtitle: String?, thumbnailUrl: String?, onClick: () -> Unit, onRemove: (() -> Unit)? = null) {
    Column(Modifier.width(142.dp).clickable(onClick = onClick)) {
        Box {
            Thumbnail(thumbnailUrl, Modifier.fillMaxWidth().aspectRatio(1f), corner = 8, requestPx = 300)
            if (onRemove != null) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f))
                        .clickable(onClick = onRemove),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(15.dp))
                }
            }
        }
        Text(
            title,
            color = Color.White.copy(alpha = 0.94f),
            fontSize = 15.sp,
            lineHeight = 18.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp)
        )
        if (subtitle != null) {
            Text(subtitle, color = Color.White.copy(alpha = 0.56f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Remote artwork decoded at [requestPx], with a music-note placeholder. */
@Composable
internal fun Thumbnail(url: String?, modifier: Modifier, corner: Int, requestPx: Int) {
    val context = LocalContext.current
    Box(modifier.clip(RoundedCornerShape(corner.dp)).background(Color(0xFF2A2A2E)), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.fillMaxSize(0.4f))
        if (url != null) {
            val request = remember(url) { ImageRequest.Builder(context).data(url).size(requestPx, requestPx).build() }
            AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** A search result / browse track: artwork, title, artist, Play and Download. */
@Composable
internal fun TrackResultRow(
    title: String,
    artist: String,
    thumbnailUrl: String?,
    isDownloading: Boolean,
    isDownloaded: Boolean,
    isLoadingStream: Boolean,
    onDownloadClick: () -> Unit,
    onPlayClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(enabled = !isLoadingStream, onClick = onPlayClick)
            .padding(start = 22.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Thumbnail(thumbnailUrl, Modifier.size(52.dp), corner = 4, requestPx = 150)
        Column(Modifier.weight(1f).padding(start = 16.dp, end = 8.dp)) {
            Text(title, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(artist, color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // Fixed 44dp slots so the row doesn't shift when a button turns into a spinner.
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            if (isLoadingStream) {
                CircularProgressIndicator(color = AppAccent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            } else {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = "Play",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp).clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onPlayClick
                    )
                )
            }
        }
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            when {
                isDownloading -> CircularProgressIndicator(color = AppAccent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                isDownloaded -> Icon(Icons.Rounded.CheckCircle, contentDescription = "Downloaded", tint = AppAccent, modifier = Modifier.size(22.dp))
                else -> Icon(
                    Icons.Rounded.Download,
                    contentDescription = "Download",
                    tint = AppAccent,
                    modifier = Modifier.size(24.dp).clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDownloadClick
                    )
                )
            }
        }
    }
}

@Composable
internal fun CenteredSpinner() {
    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = AppAccent)
    }
}

@Composable
internal fun CenteredMessage(text: String) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), contentAlignment = Alignment.Center) {
        Text(text, color = GroupLabelColor, fontSize = 15.sp)
    }
}

@Composable
internal fun DownloadFolderDialog(onChoose: () -> Unit, onSkip: () -> Unit) {
    AppAlert(
        title = "Where should downloads be saved?",
        message = "Pick a folder in your shared storage - every song you download gets a real, visible copy there. You can change this later in Settings.",
        onDismiss = onSkip,
        actions = listOf(
            AlertAction("Skip for Now", onClick = onSkip),
            AlertAction("Choose Folder", bold = true, onClick = onChoose)
        )
    )
}

/** Paste a YouTube playlist link to pin it in Discovery. */
@Composable
private fun ImportPlaylistDialog(errorMessage: String?, onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    AppAlert(
        title = "Import Playlist",
        message = "Paste a YouTube or YouTube Music playlist link. It stays in Discovery until you remove it.",
        onDismiss = onDismiss,
        actions = listOf(
            AlertAction("Cancel", onClick = onDismiss),
            AlertAction("Import", bold = true, enabled = url.isNotBlank()) { onImport(url.trim()) }
        )
    ) {
        AlertTextField(url, { url = it }, "music.youtube.com/playlist?list=…", keyboardType = KeyboardType.Uri, isError = errorMessage != null)
        errorMessage?.let { AlertNote(it, color = DestructiveRed) }
    }
}
