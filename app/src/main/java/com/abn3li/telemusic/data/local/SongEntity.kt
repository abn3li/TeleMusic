package com.abn3li.telemusic.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey val telegramMessageId: Long,
    val telegramFileId: Int,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationSeconds: Int = 0,
    val albumArtUrl: String? = null,
    // A small (160x160) pre-decoded local copy of albumArtUrl, generated once on first
    // enrichment/backfill - see ThumbnailGenerator. List rows use this instead of albumArtUrl
    // so Coil never has to decode down from a 600-1000px artwork source on every row that
    // scrolls into view; Now Playing's big cover still uses the full-res albumArtUrl.
    val thumbnailPath: String? = null,
    // A file currently exists on disk at this path - true for BOTH a streamed temp cache
    // file and an explicit download. Cache eviction and the download icon must NOT rely on
    // this alone - see isExplicitDownload below.
    val localFilePath: String? = null,
    // True ONLY when the user tapped the dedicated download button. Never set true just
    // from streaming/playing a song. This is what the "Downloaded" icon actually checks,
    // and what cache eviction must NEVER touch.
    val isExplicitDownload: Boolean = false,
    // True for a song imported directly from the device's own storage rather than synced from
    // Telegram - telegramFileId is meaningless (0) for these, and localFilePath points at the
    // user's OWN file on shared storage, not an app-managed cache/download copy. Cache eviction
    // and getFreshFileIdForSong() must NEVER touch these: touching telegramFileId would spam
    // TDLib with lookups for a message that doesn't exist, and touching localFilePath the way
    // cache eviction does for auto-cached songs would delete a file the app doesn't own.
    val isLocalImport: Boolean = false,
    // Stamped every time the song actually starts playing - used to pick which auto-cached
    // (non-explicit-download) files to evict first when the cache size limit is hit.
    val lastPlayedAtMillis: Long = 0L,
    val lyricsPlain: String? = null,
    val lyricsSynced: String? = null,
    val metadataEnriched: Boolean = false,
    val isFavorite: Boolean = false,
    val addedAtMillis: Long = System.currentTimeMillis()
)
