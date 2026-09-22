package com.abn3li.telemusic.ui.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.abn3li.telemusic.data.local.SongEntity
import com.abn3li.telemusic.data.local.displayArtwork
import kotlinx.coroutines.launch

private val SheetColor = Color(0xFF1C1C1E)

/**
 * The Now Playing "..." menu. [song] is a snapshot taken when the sheet opened, so the header
 * doesn't change under the user if the track advances while it's open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NowPlayingOverflowSheet(
    song: SongEntity,
    isDownloading: Boolean,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onSongInfo: () -> Unit,
    onSearchLyrics: () -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    fun closeThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            action()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SheetColor,
        contentColor = Color.White,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.3f)) }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp)
                .padding(bottom = 18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(alpha = 0.35f), modifier = Modifier.size(28.dp))
                    if (!song.displayArtwork.isNullOrEmpty()) {
                        AsyncImage(
                            model = song.displayArtwork,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        song.title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        song.artist,
                        fontSize = 13.5.sp,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(top = 3.dp)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                closeThen { onOpenArtist(song.artist) }
                            }
                    )
                    val album = song.album?.takeIf { it.isNotBlank() }
                    if (album != null) {
                        Text(
                            album,
                            fontSize = 12.5.sp,
                            color = Color.White.copy(alpha = 0.5f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                    closeThen { onOpenAlbum(album) }
                                }
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.07f))
            ) {
                // A real local import is already on the device; a YouTube "Play" stream (local
                // import flag but no file yet) can still be downloaded for real.
                val isRealLocalImport = song.isLocalImport && song.localFilePath != null
                if (!isRealLocalImport) {
                    val downloaded = song.isExplicitDownload
                    SheetAction(
                        icon = if (downloaded) Icons.Rounded.CloudDone else Icons.Rounded.CloudDownload,
                        label = when {
                            downloaded -> "Downloaded"
                            isDownloading -> "Downloading…"
                            else -> "Download"
                        },
                        enabled = !downloaded && !isDownloading,
                        busy = isDownloading,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            closeThen(onDownload)
                        }
                    )
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(start = 52.dp))
                }
                SheetAction(icon = Icons.Rounded.Info, label = "Song info", onClick = { closeThen(onSongInfo) })
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(start = 52.dp))
                SheetAction(icon = Icons.Rounded.Lyrics, label = "Search lyrics", onClick = { closeThen(onSearchLyrics) })
            }
        }
    }
}

@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    busy: Boolean = false
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp)
            .alpha(if (enabled || busy) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            if (busy) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            } else {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Text(label, fontSize = 16.sp, color = Color.White)
    }
}
