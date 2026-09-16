package com.abn3li.telemusic.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A YouTube/YouTube Music playlist the user pinned into Discovery by pasting its URL - stays
 * until they remove it via its own remove button, independent of any search. [browseId] is the
 * same Innertube browseId convention search-result playlists use (see
 * YouTubeDownloadViewModel.importPlaylist's own doc), so it opens through the exact same
 * BrowseCollectionScreen/DiscoveryRepository.browse() path as any other Discovery card. */
@Entity(tableName = "imported_playlists")
data class ImportedPlaylistEntity(
    @PrimaryKey val browseId: String,
    val title: String,
    val subtitle: String?,
    val thumbnailUrl: String?,
    val addedAtMillis: Long = System.currentTimeMillis()
)
