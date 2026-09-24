package com.abn3li.telemusic.ui.download

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.data.browse.BrowseCollection
import com.abn3li.telemusic.data.local.SongEntity
import com.abn3li.telemusic.ui.library.GroupActionRow
import com.abn3li.telemusic.ui.library.GroupCard
import com.abn3li.telemusic.ui.library.GroupLabelColor
import com.abn3li.telemusic.ui.library.LargeTitleGrid
import com.abn3li.telemusic.ui.library.LargeTitleList
import com.abn3li.telemusic.ui.library.LibraryDivider
import com.abn3li.telemusic.ui.library.PlayShuffleButtons

/**
 * One Discovery destination - a playlist, chart or artist page opened from a card. The tracks
 * come from a real browse call, and Download feeds the same yt-dlp flow as search.
 */
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


    if (state.collections.isNotEmpty() && state.tracks.isEmpty()) {
        LargeTitleGrid(title = state.title, onBack = onBack) {
            items(state.collections, key = { it.browseId }) { collection ->
                Column(Modifier.fillMaxWidth().clickable { onOpenCollection(collection) }) {
                    Thumbnail(collection.thumbnailUrl, Modifier.fillMaxWidth().aspectRatio(1f), corner = 7, requestPx = 300)
                    Spacer(Modifier.height(5.dp))
                    Text(collection.title, color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    collection.subtitle?.let {
                        Text(it, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        return
    }

    LargeTitleList(title = state.title, onBack = onBack) {
        when {
            state.isLoading -> item("loading") { CenteredSpinner() }
            state.tracks.isEmpty() -> item("empty") { CenteredMessage(state.errorMessage ?: "Nothing here") }
            else -> {
                item("header") {
                    val coverUrl = remember(state.tracks) { state.tracks.firstOrNull { !it.thumbnailUrl.isNullOrEmpty() }?.thumbnailUrl }
                    Column(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Thumbnail(coverUrl, Modifier.size(200.dp), corner = 8, requestPx = 500)
                        Text(
                            "${state.tracks.size} ${if (state.tracks.size == 1) "Song" else "Songs"}",
                            color = GroupLabelColor,
                            fontSize = 14.5.sp,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                    PlayShuffleButtons(
                        enabled = true,
                        onPlay = { state.tracks.firstOrNull()?.let { viewModel.onPlayClick(it) } },
                        onShuffle = { state.tracks.randomOrNull()?.let { viewModel.onPlayClick(it) } }
                    )
                    GroupCard {
                        val progress = state.importProgress
                        when {
                            state.importedPlaylistId != null -> GroupActionRow("Imported to Library", enabled = false) {}
                            progress != null -> GroupActionRow("Importing ${progress.first}/${progress.second}…", loading = true) {}
                            else -> GroupActionRow("Import to Library") { viewModel.importToLibrary() }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                itemsIndexed(state.tracks, key = { _, t -> t.videoId }) { index, track ->
                    TrackResultRow(
                        title = track.title,
                        artist = track.artist,
                        thumbnailUrl = track.thumbnailUrl,
                        isDownloading = track.videoId in state.downloadingIds,
                        isDownloaded = track.videoId in state.downloadedIds,
                        isLoadingStream = track.videoId in state.loadingStreamIds,
                        onDownloadClick = { app.downloadGate.run { viewModel.onDownloadClick(track) } },
                        onPlayClick = { viewModel.onPlayClick(track) }
                    )
                    if (index < state.tracks.lastIndex) LibraryDivider(start = 88.dp)
                }
            }
        }
    }
}
