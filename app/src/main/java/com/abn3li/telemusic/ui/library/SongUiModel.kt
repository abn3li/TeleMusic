package com.abn3li.telemusic.ui.library

import androidx.compose.runtime.Immutable
import com.abn3li.telemusic.data.local.SongEntity

@Immutable
data class SongUiModel(
    val id: Long,
    val title: String,
    val artist: String,
    val subtitle: String,
    // The small pre-decoded local copy - see ThumbnailGenerator. Falls back to the full-res
    // albumArtUrl only until the one-time background generation catches up (freshly-synced
    // songs, or songs synced before this cache existed).
    val listArtworkUrl: String?,
    val isFavorite: Boolean,
    val isExplicitDownload: Boolean,
    val isDownloading: Boolean,
    val isLocalImport: Boolean
)

fun SongEntity.toUiModel(isDownloading: Boolean): SongUiModel = SongUiModel(
    id = telegramMessageId,
    title = title,
    artist = artist,
    subtitle = if (album.isNullOrBlank()) artist else "$artist • $album",
    listArtworkUrl = thumbnailPath ?: albumArtUrl,
    isFavorite = isFavorite,
    isExplicitDownload = isExplicitDownload,
    isDownloading = isDownloading,
    isLocalImport = isLocalImport
)