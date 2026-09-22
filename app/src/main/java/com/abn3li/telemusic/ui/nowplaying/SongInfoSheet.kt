package com.abn3li.telemusic.ui.nowplaying

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.abn3li.telemusic.data.local.SongEntity
import com.abn3li.telemusic.data.local.displayArtwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private data class FileDetails(val format: String, val bitrate: String, val size: String)

/** "Song info" from the Now Playing menu, styled like the menu sheet itself. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SongInfoSheet(song: SongEntity, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // Reads the file's header and size - kept off the main thread.
    val details by produceState<FileDetails?>(null, song.telegramMessageId, song.localFilePath) {
        value = withContext(Dispatchers.IO) {
            val (format, bitrate) = detectAudioFormat(context, song.localFilePath, song.durationSeconds)
            val bytes = song.localFilePath?.let { localFileSizeBytes(context, it) } ?: 0L
            val size = if (bytes > 0) String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0)) else "Streaming"
            FileDetails(format, bitrate, size)
        }
    }
    val source = when {
        // A YouTube "Play" stream is also flagged isLocalImport; only a real import has a file.
        song.isLocalImport && song.localFilePath != null -> "Imported from device"
        song.isExplicitDownload -> "Downloaded"
        song.localFilePath != null -> "Cached"
        song.isLocalImport || song.youtubeVideoId != null -> "Streaming from YouTube"
        else -> "Streaming from Telegram"
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF1C1C1E),
        contentColor = Color.White,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.3f)) }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp)
                .padding(bottom = 22.dp)
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
                    Text(song.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        song.artist,
                        fontSize = 13.5.sp,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
            }

            InfoSection("Track") {
                InfoRow("Album", song.album?.takeIf { it.isNotBlank() } ?: "Unknown")
                InfoDivider()
                InfoRow("Duration", formatMs(song.durationSeconds * 1000L))
            }
            InfoSection("Audio") {
                InfoRow("Format", details?.format ?: "…")
                InfoDivider()
                InfoRow("Quality", details?.bitrate?.ifBlank { "Unknown" } ?: "…")
            }
            InfoSection("File") {
                InfoRow("Size", details?.size ?: "…")
                InfoDivider()
                InfoRow("Source", source)
            }
        }
    }
}

@Composable
private fun InfoSection(title: String, content: @Composable () -> Unit) {
    Text(
        title.uppercase(),
        color = Color.White.copy(alpha = 0.45f),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp)
    )
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.07f))
    ) {
        content()
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp)
        Spacer(Modifier.width(16.dp))
        Text(
            value,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun InfoDivider() {
    HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(start = 16.dp))
}
