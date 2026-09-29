package com.abn3li.telemusic.ui.download

import com.abn3li.telemusic.ui.library.CalmSpinner
import com.abn3li.telemusic.repository.SpotifyImportState
import androidx.compose.ui.text.input.KeyboardType
import com.abn3li.telemusic.ui.library.AppAlert
import com.abn3li.telemusic.ui.library.AlertAction
import com.abn3li.telemusic.ui.library.AlertTextField
import com.abn3li.telemusic.ui.library.AlertNote
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.ui.library.AppAccent
import com.abn3li.telemusic.ui.library.DestructiveRed
import com.abn3li.telemusic.ui.library.GroupLabelColor
import com.abn3li.telemusic.ui.library.SwipeToPlayNext

/** The Search tab's paste-a-link dialog, wired to [viewModel]: a YouTube playlist link is pinned into
 * Discovery, a Spotify link is imported into the Library. */
@Composable
internal fun ImportPlaylistPrompt(viewModel: DiscoveryViewModel, onClose: () -> Unit) {
    val app = LocalContext.current.applicationContext as TgMusicApp
    val state by viewModel.uiState.collectAsState()
    val spotifyState by app.spotifyImporter.state.collectAsState()
    ImportPlaylistDialog(
        errorMessage = state.importPlaylistError,
        spotifyState = spotifyState,
        onDismiss = {
            onClose()
            viewModel.clearImportPlaylistError()
            app.spotifyImporter.acknowledge()
        },
        onImport = { url ->
            if (app.spotifyImporter.isSpotifyLink(url)) app.spotifyImporter.start(url)
            else viewModel.importPlaylist(url) { success -> if (success) onClose() }
        }
    )
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

/** A browse track: artwork, title, artist, Play and Download. */
@Composable
internal fun TrackResultRow(
    title: String,
    artist: String,
    thumbnailUrl: String?,
    isDownloading: Boolean,
    isDownloaded: Boolean,
    onDownloadClick: () -> Unit,
    onPlayClick: () -> Unit,
    onPlayNext: () -> Unit
) {
    // Swipe right to queue it next, like a library song row.
    SwipeToPlayNext(onPlayNext) { swipeModifier ->
    Row(
        swipeModifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onPlayClick)
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
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            when {
                isDownloading -> CalmSpinner(color = AppAccent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
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
}

@Composable
internal fun CenteredSpinner() {
    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
        CalmSpinner(color = AppAccent)
    }
}

@Composable
internal fun CenteredMessage(text: String) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), contentAlignment = Alignment.Center) {
        Text(text, color = GroupLabelColor, fontSize = 15.sp)
    }
}


/** Paste a YouTube playlist link to pin it in Discovery, or a Spotify playlist/album link to
 * import it into the Library as YouTube Music songs (progress shown here while it runs; closing
 * the dialog doesn't stop it). */
@Composable
private fun ImportPlaylistDialog(
    errorMessage: String?,
    spotifyState: SpotifyImportState,
    onDismiss: () -> Unit,
    onImport: (String) -> Unit
) {
    var url by remember { mutableStateOf("") }
    when (spotifyState) {
        is SpotifyImportState.Running -> AppAlert(
            title = "Importing from Spotify",
            message = "Matching \"${spotifyState.name}\" with YouTube Music: ${spotifyState.done} of ${spotifyState.total}. You can close this - it keeps going.",
            onDismiss = onDismiss,
            actions = listOf(AlertAction("Hide", bold = true, onClick = onDismiss))
        )
        is SpotifyImportState.Finished -> AppAlert(
            title = "Imported",
            message = spotifyState.summary + " It's in Library > Playlists.",
            onDismiss = onDismiss,
            actions = listOf(AlertAction("Done", bold = true, onClick = onDismiss))
        )
        else -> AppAlert(
            title = "Import Playlist",
            message = "Paste a YouTube Music playlist link to add it to Discovery, or a Spotify playlist or album link to import its songs into your Library.",
            onDismiss = onDismiss,
            actions = listOf(
                AlertAction("Cancel", onClick = onDismiss),
                AlertAction("Import", bold = true, enabled = url.isNotBlank()) { onImport(url.trim()) }
            )
        ) {
            AlertTextField(url, { url = it }, "YouTube Music or Spotify link", keyboardType = KeyboardType.Uri, isError = errorMessage != null || spotifyState is SpotifyImportState.Failed)
            (errorMessage ?: (spotifyState as? SpotifyImportState.Failed)?.message)?.let { AlertNote(it, color = DestructiveRed) }
        }
    }
}
