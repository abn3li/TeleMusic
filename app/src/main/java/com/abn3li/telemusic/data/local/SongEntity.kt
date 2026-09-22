package com.abn3li.telemusic.data.local

import android.net.Uri
import androidx.core.net.toUri
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.io.File

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
    // Telegram - telegramFileId is meaningless (0) for these. localFilePath points at the app's
    // OWN private copy (see LocalAudioImporter.importToPrivateStorage - the original file the
    // user picked is copied in, never referenced directly), so it's exactly as safe to delete as
    // any other row's file (see MusicRepository.clearSong/clearAllLibrarySongs). Cache eviction
    // and getFreshFileIdForSong() must still NEVER touch these: touching telegramFileId would
    // spam TDLib with lookups for a message that doesn't exist, and cache eviction only ever
    // targets auto-cached (non-explicit, non-import) files in the first place.
    val isLocalImport: Boolean = false,
    // The SAF document Uri of this song's exported copy in the user's chosen shared-storage
    // folder (see MusicRepository.exportToDownloadFolderIfConfigured), if one was ever made -
    // null when no download folder was configured at download time. Kept so "Delete download"
    // can remove that copy too, not just the app-private one - without this, deleting a download
    // in-app left the shared-storage copy behind with nothing pointing back to it.
    val exportedFileUri: String? = null,
    // Stamped every time the song actually starts playing - used to pick which auto-cached
    // (non-explicit-download) files to evict first when the cache size limit is hit.
    val lastPlayedAtMillis: Long = 0L,
    val lyricsPlain: String? = null,
    val lyricsSynced: String? = null,
    val metadataEnriched: Boolean = false,
    val isFavorite: Boolean = false,
    val addedAtMillis: Long = System.currentTimeMillis(),
    // Non-null for a real library row backed by a YouTube video rather than a Telegram message
    // or a local import - telegramFileId is meaningless (0) for these too, same as isLocalImport.
    // UNLIKE isLocalImport, this row often has NO localFilePath: it plays by resolving a fresh
    // stream URL from this video id on demand (see NowPlayingViewModel/MusicService's playback
    // resolution), and can be downloaded for real later via the ordinary Download action - see
    // MusicRepository.downloadExplicitly's own doc. Set once, at import time, and never cleared.
    val youtubeVideoId: String? = null,
    // Which chat this Telegram-sourced song's message actually lives in, once resolved - lets
    // getFreshFileIdForSong() go straight to the right chat on every later play instead of
    // re-running its whole parallel candidate-chat sweep every single time a song from a
    // different chat than the current lastSyncedChatId is played (see that function's own doc).
    // Null until the first successful resolve; a stale/wrong value here just means one sweep to
    // correct it, never worse than not caching at all.
    val resolvedChatId: Long? = null
)

/**
 * The best artwork this song has for a full-size display (Now Playing's big cover, the media
 * notification/lock screen, MiniPlayer, Queue, Lyrics) - prefers [SongEntity.albumArtUrl] (either
 * the full-res 600-1000px online source, or a local import's embedded cover art saved as-is via
 * ThumbnailGenerator.saveFullArtwork - see LocalAudioImporter.extractEmbeddedArtwork) when set,
 * otherwise falls back to [SongEntity.thumbnailPath] (the small 160x160 list-row copy, better
 * than nothing for a song that predates either artwork path having run yet).
 *
 * Telegram-synced songs deliberately do NOT get this treatment - they rely entirely on
 * MusicRepository.enrichMissingMetadata()'s online iTunes/Deezer/MusicBrainz lookup for artwork,
 * same as always. Every artwork-display site should read this instead of albumArtUrl directly -
 * see [displayArtworkUri] for the MediaMetadata (notification/lock screen) equivalent, which
 * needs a real Uri instead of a plain path string.
 *
 * Excludes the literal "none" sentinel [ThumbnailGenerator]/ensureThumbnail's failure path writes
 * to thumbnailPath - that string is not a path, it's a "don't retry" marker.
 */
val SongEntity.displayArtwork: String?
    get() = albumArtUrl?.takeIf { it.isNotBlank() }
        ?: thumbnailPath?.takeIf { it.isNotBlank() && it != "none" }

/**
 * Same source as [displayArtwork], but as a real [Uri] for MediaMetadata (the media notification/
 * lock screen/Android Auto artwork, set via MediaMetadata.Builder.setArtworkUri) rather than
 * Coil's AsyncImage - Coil resolves a bare local file path string fine (proven by the Library
 * list already doing exactly that via thumbnailPath), but MediaMetadata's artwork consumers need
 * an actual scheme. A real https:// URL (the online enrichment path) already has one; a local
 * path (a local import's saved embedded artwork, or thumbnailPath) has none at all, so that case
 * needs wrapping through File(...).toUri() to become a proper file:// Uri instead of being
 * handed to androidx.core.net.toUri() as-is.
 */
val SongEntity.displayArtworkUri: Uri?
    get() {
        val art = displayArtwork ?: return null
        return if (art.startsWith("http://") || art.startsWith("https://") || art.startsWith("content://")) {
            art.toUri()
        } else {
            File(art).toUri()
        }
    }
