package com.abn3li.telemusic.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.abn3li.telemusic.data.local.PlaylistEntity

// Recomputing this per row, per recomposition, added up across hundreds of rows during a
// fling - RoundedCornerShape at a fixed dp doesn't depend on composition/theme at all, so it's
// hoisted to a one-time allocation instead of one per SongRow call.
private val ThumbnailShape = RoundedCornerShape(12.dp)

/**
 * A plain clickable circle standing in for Material3's [IconButton] - that composable wraps its
 * content in its own [Surface], enforces a minimum touch target, and provides a content color,
 * none of which a fixed 36dp row icon needs. With three of these per row, that extra machinery
 * showed up directly in `adb shell dumpsys gfxinfo` as CPU-bound (not GPU-bound) frame time
 * during a fling through a large library - this trims it back to just a ripple and a click.
 */
@Composable
private fun RowIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
        content = { content() }
    )
}

@Composable
fun SongRow(
    song: SongUiModel,
    playlists: List<PlaylistEntity>,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDownloadClick: () -> Unit,
    onAddToPlaylist: (Long) -> Unit,
    onCreatePlaylistAndAdd: (String) -> Unit,
    modifier: Modifier = Modifier,
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    onSurfaceVariant: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    // Same story as the shapes above: .copy() allocates a new TextStyle every time it runs, so
    // callers that render many rows (SongList, SongListScaffold) hoist this once and pass it
    // down instead of paying for it on every row on every recomposition.
    titleStyle: TextStyle = MaterialTheme.typography.bodyMedium.copy(
        fontWeight = FontWeight.Medium,
        platformStyle = PlatformTextStyle(includeFontPadding = false)
    ),
    subtitleStyle: TextStyle = MaterialTheme.typography.bodySmall.copy(
        platformStyle = PlatformTextStyle(includeFontPadding = false)
    )
) {
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }

    val imageRequest = remember(song.listArtworkUrl, context) {
        ImageRequest.Builder(context)
            .data(song.listArtworkUrl)
            .allowHardware(true)
            .size(120, 120)
            .build()
    }

    // Flat list item row (0 card wrappers, 0 outer boxes, no per-row clip/highlight shape -
    // rows are visually separated by a plain divider between them instead, like a plain list).
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(68.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(ThumbnailShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            if (!song.listArtworkUrl.isNullOrEmpty()) {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = primaryColor
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        // Title & Subtitle Column
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = song.title,
                style = titleStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = song.subtitle,
                style = subtitleStyle,
                color = onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            RowIconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (song.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = null,
                    tint = if (song.isFavorite) primaryColor else onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            if (song.isDownloading) {
                Box(modifier = Modifier.padding(6.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = primaryColor
                    )
                }
            } else {
                RowIconButton(onClick = { if (!song.isExplicitDownload) onDownloadClick() }) {
                    Icon(
                        imageVector = if (song.isExplicitDownload) Icons.Default.CloudDone else Icons.Default.CloudDownload,
                        contentDescription = null,
                        tint = if (song.isExplicitDownload) primaryColor else onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Box {
                RowIconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Options",
                        tint = onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                if (menuExpanded) {
                    DropdownMenu(expanded = true, onDismissRequest = { menuExpanded = false }) {
                        if (playlists.isNotEmpty()) {
                            Text(
                                text = "Add to playlist",
                                style = MaterialTheme.typography.labelSmall,
                                color = primaryColor,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                            playlists.forEach { playlist ->
                                DropdownMenuItem(
                                    text = { Text(playlist.name) },
                                    onClick = {
                                        onAddToPlaylist(playlist.id)
                                        menuExpanded = false
                                    }
                                )
                            }
                            HorizontalDivider()
                        }
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                            text = { Text("New playlist...") },
                            onClick = {
                                menuExpanded = false
                                showCreateDialog = true
                            }
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("New playlist") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Playlist name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (name.isNotBlank()) onCreatePlaylistAndAdd(name.trim())
                        showCreateDialog = false
                    }
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}