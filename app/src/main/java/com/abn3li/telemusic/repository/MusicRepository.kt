package com.abn3li.telemusic.repository

import kotlinx.coroutines.launch
import coil.imageLoader
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import com.abn3li.telemusic.data.browse.BrowseTrack
import com.abn3li.telemusic.data.local.AlbumSummary
import com.abn3li.telemusic.data.local.ArtistSummary
import com.abn3li.telemusic.data.local.LyricsCacheEntity
import com.abn3li.telemusic.data.local.PlaylistDao
import com.abn3li.telemusic.data.local.PlaylistEntity
import com.abn3li.telemusic.data.local.PlaylistSongCrossRef
import com.abn3li.telemusic.data.local.SongDao
import com.abn3li.telemusic.data.local.SongEntity
import com.abn3li.telemusic.data.local.displayArtwork
import com.abn3li.telemusic.data.download.DownloadQuality
import com.abn3li.telemusic.data.download.MediaFolderExporter
import com.abn3li.telemusic.data.download.YtDlpRepository
import com.abn3li.telemusic.data.download.ytDlpStableSongId
import com.abn3li.telemusic.data.settings.AppSettingsStore
import com.abn3li.telemusic.data.telegram.TdlibManager
import com.abn3li.telemusic.data.telegram.TelegramAudioMessage
import com.abn3li.telemusic.playback.TdlibDataSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.sync.withLock
import java.io.File

enum class SortField(val label: String) { TITLE("Name"), ARTIST("Artist"), ALBUM("Album"), DATE_ADDED("Date added") }

// ~10 minutes at the 1s poll interval below - see markStreamedFileCached's own doc.
private const val MAX_CACHE_POLL_ATTEMPTS = 600

// Stream-only YouTube songs held in memory for the queue - see queueIdsForStreams.
private const val MAX_STREAM_ONLY_SONGS = 3000
private const val MAX_RECENT_STREAMS = 30
private const val STAMP_REPEAT_WINDOW_MS = 10_000L
// Choosing between a Telegram and a YouTube copy of one song (see mergeCrossSourceDuplicates):
// within 10% counts as the same quality.
private const val SAME_QUALITY_MARGIN = 1.10
private const val YOUTUBE_STREAM_KBPS = 130.0
private const val EFFICIENT_CODEC_FACTOR = 1.4
private const val LOSSLESS_KBPS = 100_000.0
private val LOSSLESS_MIME_HINTS = listOf("flac", "alac", "wav", "aiff", "x-ape")

// Thumbnails saved per database transaction - see backfillThumbnails.
private const val THUMBNAIL_BATCH = 60
// This many covers in a row failing to connect means the phone is offline: stop the pass.
private const val THUMBNAIL_OFFLINE_STREAK = 3

/** What playing a song resolves to - see MusicRepository.resolvePlayback. */
sealed interface PlaybackResolution {
    data class Playable(val uri: Uri) : PlaybackResolution
    /** [reason] finishes "Couldn't play …" - e.g. "video may be unavailable". */
    data class Unplayable(val reason: String) : PlaybackResolution
}

// A song with no lyrics anywhere is searched again after this long, in case some appear.
private const val LYRICS_NOT_FOUND_RETRY_MS = 7L * 24 * 60 * 60 * 1000

// Songs read per step when copying existing lyrics into the cache - see backfillLyricsCache.
private const val LYRICS_BACKFILL_PAGE = 200

// Mirrors ytdlp_bridge.py's _ARTIST_SPLIT regex exactly - same separators, same "first segment
// wins" rule, so a collab credit collapses to the same primary artist regardless of which path
// (a fresh download vs this cleanup pass) it went through.
private val ARTIST_SPLIT = Regex("""\s*(?:,|&|/| feat\.?| ft\.?| x )\s*""", RegexOption.IGNORE_CASE)

private fun primaryArtistOf(rawArtist: String): String =
    ARTIST_SPLIT.split(rawArtist, limit = 2).firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: rawArtist

class MusicRepository(
    private val songDao: SongDao,
    private val playlistDao: PlaylistDao,
    private val tdlibManager: TdlibManager,
    private val lyricsRepository: LyricsRepository,
    private val metadataRepository: MetadataRepository,
    private val settingsStore: AppSettingsStore,
    private val thumbnailGenerator: ThumbnailGenerator,
    private val localAudioImporter: LocalAudioImporter,
    private val mediaFolderExporter: MediaFolderExporter,
    private val ytDlpRepository: YtDlpRepository,
    private val database: com.abn3li.telemusic.data.local.AppDatabase,
    context: Context,
    // Songs playing or waiting in the queue: never removed from under the player (see
    // mergeCrossSourceDuplicates).
    private val songsInUse: suspend () -> Set<Long> = { emptySet() }
) {
    private val appContext = context.applicationContext

    // Song ids already confirmed to have a working telegramFileId THIS app run - see
    // getFreshFileIdForSong's own doc for why this exists: without it, EVERY play of a Telegram
    // song paid a real TDLib round trip (OpenChat + GetMessage) to re-verify a file id that, in
    // the common case (replaying a song, or the queue auto-advancing through recently-played
    // ones), was already confirmed fresh moments ago. In-memory only, not persisted - cleared on
    // every fresh sync (see syncFromChannel), since that's the one thing that's actually shown to
    // rotate a song's real file id out from under it.
    private val verifiedFreshFileIdThisRun = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    // Live mirrors of the three smart playlists' own "Hide from tracks" flags (see
    // AppSettingsStore's own doc - they're settings, not DB rows, so they need their own
    // reactive holder here) - this repository is a single app-wide instance, so a toggle made
    // from a Smart Playlist detail screen is immediately visible to the Songs page's own
    // LibraryViewModel instance without either one needing to know about the other directly.
    private val _hideLiked = MutableStateFlow(settingsStore.hideLikedFromTracks)
    private val _hideTelegram = MutableStateFlow(settingsStore.hideTelegramFromTracks)
    private val _hideDownloaded = MutableStateFlow(settingsStore.hideDownloadedFromTracks)

    fun observeHideLikedFromTracks(): StateFlow<Boolean> = _hideLiked
    fun observeHideTelegramFromTracks(): StateFlow<Boolean> = _hideTelegram
    fun observeHideDownloadedFromTracks(): StateFlow<Boolean> = _hideDownloaded

    fun setHideLikedFromTracks(hidden: Boolean) { settingsStore.hideLikedFromTracks = hidden; _hideLiked.value = hidden }
    fun setHideTelegramFromTracks(hidden: Boolean) { settingsStore.hideTelegramFromTracks = hidden; _hideTelegram.value = hidden }
    fun setHideDownloadedFromTracks(hidden: Boolean) { settingsStore.hideDownloadedFromTracks = hidden; _hideDownloaded.value = hidden }

    /** Best-effort copy of [source] into the user's chosen shared-storage download folder, if
     * they've picked one (AppSettingsStore.downloadFolderUri) - a no-op (returns null) otherwise,
     * so a song downloads exactly as it did before anyone touches that setting. [displayName]
     * should already include a real extension (e.g. "Title.m4a"), not the internal songId
     * filename - this copy is the one the user actually sees in their file manager. The returned
     * Uri is persisted onto the song's own row (exportedFileUri) so "Delete download" can find
     * and remove this exact copy later, not just the app-private one. */
    private suspend fun exportToDownloadFolderIfConfigured(source: File, displayName: String): Uri? {
        val folderUri = settingsStore.downloadFolderUri?.let { android.net.Uri.parse(it) } ?: return null
        // A whole-file copy (tens of MB for lossless): never on the caller's (often main) thread.
        return withContext(Dispatchers.IO) { mediaFolderExporter.export(source, folderUri, displayName) }
    }
    // ---- Tracks / Favourites, metadata-driven sort ----
    fun observeLibrary(sortField: SortField, ascending: Boolean): Flow<List<SongEntity>> =
        songDao.observeAll().map { it.sortedByField(sortField, ascending) }

    suspend fun getSongById(id: Long): SongEntity? =
        songDao.getById(id) ?: streamOnlySongs[id] ?: recentStreams().firstOrNull { it.telegramMessageId == id }

    // YouTube songs played from search or a YouTube album/playlist/artist page that aren't in the
    // library: kept in memory so they can sit in the normal queue (Next/Previous, the
    // notification, auto-advance) like any library song. They're saved to the library only when
    // they're Liked, added to a playlist or downloaded (see [saveIfStreamOnly]).
    private val streamOnlySongs = java.util.concurrent.ConcurrentHashMap<Long, SongEntity>()

    /** The queue ids for [tracks], in order - a song already in the library is played from its
     * row (so a downloaded one plays from the file); the rest are held as stream-only songs. */
    fun queueIdsForStreams(tracks: List<BrowseTrack>): List<Long> {
        // Bounded: a very long session drops the oldest held songs, never the ones being queued.
        if (streamOnlySongs.size > MAX_STREAM_ONLY_SONGS) streamOnlySongs.clear()
        return tracks.map { track ->
            val id = ytDlpStableSongId(track.videoId)
            streamOnlySongs.getOrPut(id) {
                SongEntity(
                    telegramMessageId = id,
                    telegramFileId = 0,
                    title = track.title,
                    artist = track.artist,
                    durationSeconds = track.durationSeconds,
                    albumArtUrl = com.abn3li.telemusic.data.browse.googleArtworkAtSize(track.thumbnailUrl, com.abn3li.telemusic.data.browse.SAVED_ARTWORK_SIZE),
                    youtubeVideoId = track.videoId,
                    metadataEnriched = true
                )
            }
            id
        }
    }

    // The last YouTube songs played that aren't in the library, newest first, for Home's
    // Recently Played. Kept in a small file (not the songs table, so they stay out of the
    // library); read once, then written only when a stream-only song starts playing.
    private val recentStreamsFile = File(appContext.filesDir, "recent_streams.json")
    private val recentStreamsState = MutableStateFlow<List<SongEntity>?>(null)
    private val recentStreamsLock = kotlinx.coroutines.sync.Mutex()

    private suspend fun recentStreams(): List<SongEntity> {
        recentStreamsState.value?.let { return it }
        return recentStreamsLock.withLock {
            recentStreamsState.value ?: withContext(Dispatchers.IO) { readRecentStreams() }.also { recentStreamsState.value = it }
        }
    }

    /** Stream-only YouTube songs played lately, newest first (see [recentStreamsFile]). */
    fun observeRecentStreams(): Flow<List<SongEntity>> = kotlinx.coroutines.flow.flow {
        recentStreams()
        emitAll(recentStreamsState.filterNotNull())
    }

    private suspend fun recordStreamPlayed(song: SongEntity, at: Long) {
        if (song.youtubeVideoId == null) return
        recentStreamsLock.withLock {
            val current = recentStreamsState.value ?: withContext(Dispatchers.IO) { readRecentStreams() }
            // The player and the playback service both stamp a song as it starts: record it once.
            val newest = current.firstOrNull()
            if (newest?.telegramMessageId == song.telegramMessageId && at - newest.lastPlayedAtMillis < 30_000) return@withLock
            val updated = (listOf(song.copy(lastPlayedAtMillis = at)) + current.filter { it.telegramMessageId != song.telegramMessageId })
                .take(MAX_RECENT_STREAMS)
            recentStreamsState.value = updated
            withContext(Dispatchers.IO) { writeRecentStreams(updated) }
        }
    }

    private fun readRecentStreams(): List<SongEntity> = runCatching {
        if (!recentStreamsFile.exists()) return emptyList()
        val array = org.json.JSONArray(recentStreamsFile.readText())
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val videoId = o.optString("videoId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SongEntity(
                telegramMessageId = ytDlpStableSongId(videoId),
                telegramFileId = 0,
                title = o.optString("title"),
                artist = o.optString("artist"),
                album = o.optString("album").takeIf { it.isNotBlank() },
                durationSeconds = o.optInt("duration"),
                albumArtUrl = o.optString("art").takeIf { it.isNotBlank() },
                youtubeVideoId = videoId,
                metadataEnriched = true,
                lastPlayedAtMillis = o.optLong("playedAt")
            )
        }
    }.getOrElse { e -> Log.w("MusicRepository", "recent streams unreadable", e); emptyList() }

    private fun writeRecentStreams(songs: List<SongEntity>) {
        val array = org.json.JSONArray()
        songs.forEach { s ->
            array.put(org.json.JSONObject()
                .put("videoId", s.youtubeVideoId).put("title", s.title).put("artist", s.artist)
                .put("album", s.album ?: "").put("duration", s.durationSeconds)
                .put("art", s.albumArtUrl ?: "").put("playedAt", s.lastPlayedAtMillis))
        }
        runCatching {
            val tmp = File(recentStreamsFile.path + ".tmp")
            tmp.writeText(array.toString())
            tmp.renameTo(recentStreamsFile) || run { recentStreamsFile.writeText(array.toString()); true }
        }.onFailure { Log.w("MusicRepository", "recent streams not saved", it) }
    }

    /** A stream-only song gets a real library row first, so a Like or playlist entry has
     * something to attach to. A no-op for songs already in the library. */
    private suspend fun saveIfStreamOnly(songId: Long) {
        if (songDao.getById(songId) != null) return
        streamOnlySongs[songId]?.let { songDao.upsert(it) }
    }

    /** Sets [songId]'s album only if it has none yet (Spotify imports - see SpotifyImporter). */
    suspend fun setAlbumIfMissing(songId: Long, album: String) = songDao.setAlbumIfMissing(songId, album)

    fun search(query: String): Flow<List<SongEntity>> = songDao.search(query)

    /** Android Auto's "Recently Played" browse category - see MusicService's MediaLibrarySession. */
    suspend fun getRecentlyPlayed(limit: Int = 50): List<SongEntity> =
        (songDao.getRecentlyPlayed(limit) + recentStreams())
            .sortedByDescending { it.lastPlayedAtMillis }.distinctBy { it.telegramMessageId }.take(limit)

    fun observeFavorites(sortField: SortField, ascending: Boolean): Flow<List<SongEntity>> =
        songDao.observeFavorites().map { it.sortedByField(sortField, ascending) }

    suspend fun setFavorite(song: SongEntity, isFavorite: Boolean) {
        if (isFavorite) saveIfStreamOnly(song.telegramMessageId)
        songDao.setFavorite(song.telegramMessageId, isFavorite)
        // The large widget shows the Like star (a no-op when no widget is placed).
        com.abn3li.telemusic.widget.MusicWidgets.refresh(appContext)
    }

    // ---- Smart (built-in) playlists: Liked/Telegram/Downloaded - not real rows in the
    // playlists table, just a different filter over the same songs table. ----
    fun observeTelegramSongs(sortField: SortField, ascending: Boolean): Flow<List<SongEntity>> =
        songDao.observeTelegramSongs().map { it.sortedByField(sortField, ascending) }

    fun observeDownloadedSongs(sortField: SortField, ascending: Boolean): Flow<List<SongEntity>> =
        songDao.observeDownloaded().map { it.sortedByField(sortField, ascending) }

    // ---- Import from local storage ----
    /** [treeUri] is a folder the user picked via the system file explorer (SAF) - no storage
     * permission needed, that grant is independent of READ_MEDIA_AUDIO/READ_EXTERNAL_STORAGE. */
    fun scanLocalFolder(treeUri: Uri): List<LocalAudioFile> = localAudioImporter.scanFolder(treeUri)

    /** Which local imports (by their stableSongId()) are already in the library - lets the
     * picker sheet show them as checked/disabled instead of the user re-importing blind. */
    suspend fun getLocalImportSongIds(): Set<Long> = songDao.getLocalImportSongIds().toSet()

    /** References each file's own content:// URI directly, playing straight from it forever
     * instead of copying its bytes - the same approach most real media players (VLC, Poweramp)
     * use. Works because the picked folder's TREE URI already had its read permission persisted
     * the moment the user picked it (see SettingsScreen's importFolderLauncher); that grant
     * covers every file inside the tree, so nothing further needs to be requested per file - just
     * confirmed readable (see LocalAudioImporter.isReadable's own doc for why NOT calling
     * takePersistableUriPermission again per file matters here). Falls back to an actual copy
     * into the app's own private storage only if a file isn't readable at all (rare - a genuinely
     * broken provider). Upserts each as a song either way - see LocalAudioFile's own
     * stableSongId() doc for why re-picking the same file resolves to the same row rather than
     * duplicating. */
    suspend fun importLocalSongs(files: List<LocalAudioFile>) {
        for (file in files) {
            val songId = file.stableSongId()
            val localPath = if (localAudioImporter.isReadable(file)) {
                file.uri.toString()
            } else {
                localAudioImporter.importToPrivateStorage(file, songId) ?: continue
            }
            songDao.upsert(
                SongEntity(
                    telegramMessageId = songId,
                    telegramFileId = 0,
                    title = file.title,
                    artist = file.artist,
                    album = file.album,
                    durationSeconds = file.durationSeconds,
                    localFilePath = localPath,
                    isLocalImport = true,
                    // Left unenriched on purpose - metadataEnriched only flips to true once
                    // enrichMissingMetadata() (called right below via the embedded-artwork check,
                    // or by the caller afterward) confirms this song has both real title/artist
                    // AND real artwork - see that function's hasArtwork check.
                    metadataEnriched = false
                )
            )

            // Pull cover art straight from the file's own tags first - real, instant, no network
            // call - and only fall back to enrichMissingMetadata()'s online iTunes/Deezer/
            // MusicBrainz lookup for files that genuinely have none embedded. thumbnailGenerator
            // caches by songId and returns the existing path if already generated, so this is
            // safe to call even if the same file gets re-imported later.
            val embeddedArtwork = localAudioImporter.extractEmbeddedArtwork(file)
            if (embeddedArtwork != null) {
                // Full-size copy first for Now Playing/the media notification (displayArtwork
                // prefers albumArtUrl) - the small thumbnailPath copy alone looked visibly
                // blurry stretched across a full-screen backdrop; see saveFullArtwork's own doc.
                thumbnailGenerator.saveFullArtwork(songId, embeddedArtwork)?.let { path ->
                    songDao.setAlbumArtUrl(songId, path)
                }
                thumbnailGenerator.generateFromBytes(songId, embeddedArtwork)?.let { path ->
                    songDao.setThumbnailPath(songId, path)
                }
            }
        }
    }

    // ---- Downloaded from YouTube (via yt-dlp - see data/download) ----
    /** Moves a just-downloaded file (already sitting in its final destination from
     * [com.abn3li.telemusic.data.download.YtDlpRepository.download]) into a row the rest of the
     * app treats like any other song. Marked as an
     * explicit download (never auto-evicted, shows the "Downloaded" icon) since the whole point
     * of this flow is the user asked for this exact file - see SongEntity.isExplicitDownload's
     * own doc. Left unenriched=false: title/artist already came from yt-dlp's own real metadata,
     * only artwork still needs the usual backfillThumbnails pass to turn albumArtUrl into a
     * cached thumbnailPath. */
    suspend fun importDownloadedSong(result: com.abn3li.telemusic.data.download.YtDlpDownloadResult, songId: Long, videoId: String? = null) {
        val existing = songDao.getById(songId)
        val artwork = com.abn3li.telemusic.data.browse.googleArtworkAtSize(result.thumbnailUrl, com.abn3li.telemusic.data.browse.SAVED_ARTWORK_SIZE)
        // A song already in the library (a streamable YouTube row) keeps everything it has -
        // title, lyrics, play history, album, Like, artwork; only its file and download state
        // change. Rebuilding the row from scratch wiped all of that.
        val song = existing?.copy(
            durationSeconds = result.durationSeconds.takeIf { it > 0 } ?: existing.durationSeconds,
            albumArtUrl = existing.albumArtUrl?.takeIf { it.isNotBlank() } ?: artwork,
            localFilePath = result.filePath,
            isExplicitDownload = true,
            metadataEnriched = true,
            youtubeVideoId = videoId ?: existing.youtubeVideoId
        ) ?: SongEntity(
            telegramMessageId = songId,
            telegramFileId = 0,
            title = result.title,
            artist = result.artist,
            durationSeconds = result.durationSeconds,
            albumArtUrl = artwork,
            localFilePath = result.filePath,
            isExplicitDownload = true,
            metadataEnriched = true,
            // Keeps the caller's videoId so removeDownload() can revert this back to streaming
            // later instead of deleting the row outright - see removeDownload's own doc.
            youtubeVideoId = videoId
        )
        songDao.upsert(song)
        keepArtworkForOffline(song.albumArtUrl)
        val extension = File(result.filePath).extension.ifBlank { "m4a" }
        val exportedUri = exportToDownloadFolderIfConfigured(File(result.filePath), sanitizedFileName(result.title, result.artist, extension))
        if (exportedUri != null) {
            // One copy only: the song now lives in (and plays from) the user's folder.
            songDao.getById(songId)?.let { songDao.update(it.copy(localFilePath = exportedUri.toString(), exportedFileUri = exportedUri.toString())) }
            withContext(Dispatchers.IO) { runCatching { File(result.filePath).delete() } }
        }
    }

    /**
     * One-time cleanup for downloads made before single-copy downloads: each one that also has a
     * copy in the download folder keeps only that copy. The private file is deleted only after
     * the folder copy is confirmed readable. A single bounded pass, flag-guarded so it never runs
     * again.
     */
    suspend fun migrateDownloadsToSingleCopy() {
        if (settingsStore.singleCopyMigrationDone) return
        for (song in songDao.observeAll().firstOrNull().orEmpty()) {
            val exported = song.exportedFileUri ?: continue
            val local = song.localFilePath ?: continue
            if (!song.isExplicitDownload || local.startsWith("content://")) continue
            if (!isLocalFileValid(exported)) continue
            songDao.setLocalFilePath(song.telegramMessageId, exported)
            if (song.telegramFileId != 0) tdlibManager.deleteDownloadedFile(song.telegramFileId)
            else withContext(Dispatchers.IO) { runCatching { File(local).delete() } }
        }
        settingsStore.singleCopyMigrationDone = true
    }

    /**
     * One-time upgrade for YouTube songs saved before artwork was stored sharp: their 120 px
     * googleusercontent links become the 544 px version of the same image. Only the link changes
     * (list thumbnails already cached on disk stay); flag-guarded so it never runs again.
     */
    suspend fun upgradeYouTubeArtwork() {
        if (settingsStore.youTubeArtworkUpgraded) return
        for (song in songDao.observeAll().firstOrNull().orEmpty()) {
            val url = song.albumArtUrl ?: continue
            val upgraded = com.abn3li.telemusic.data.browse.googleArtworkAtSize(url, com.abn3li.telemusic.data.browse.SAVED_ARTWORK_SIZE)
            if (upgraded != null && upgraded != url) songDao.setAlbumArtUrl(song.telegramMessageId, upgraded)
        }
        settingsStore.youTubeArtworkUpgraded = true
    }

    /** A safe, readable filename for the exported copy - real filesystems reject a handful of
     * characters a song title/artist can legitimately contain (a "/" in a title, for instance). */
    private fun sanitizedFileName(title: String, artist: String, extension: String): String {
        val base = "$artist - $title".replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifBlank { "Untitled" }
        return "$base.$extension"
    }

    /** "Import to Library"'s real per-track step - adds [track] as a genuine library row WITHOUT
     * downloading anything, unlike [importDownloadedSong]. Plays by resolving a fresh stream URL
     * from [SongEntity.youtubeVideoId] on demand (see [resolveDirectPlaybackUri]), and can be
     * downloaded for real later via the ordinary Download action ([downloadExplicitly]) - exactly
     * the same played-or-downloaded shape a Telegram-synced song already has, just backed by a
     * YouTube video instead of a Telegram message. Idempotent: re-importing a playlist that
     * shares a track with one already in the library returns the existing row untouched instead
     * of overwriting it (which would have wiped a real download back down to a bare stream). */
    suspend fun importPlaylistTrackAsStreamable(track: BrowseTrack, album: String? = null): SongEntity {
        val songId = ytDlpStableSongId(track.videoId)
        songDao.getById(songId)?.let { existing ->
            if (!album.isNullOrBlank() && existing.album.isNullOrBlank()) {
                songDao.setAlbumIfMissing(songId, album)
                return existing.copy(album = album)
            }
            return existing
        }
        val song = SongEntity(
            telegramMessageId = songId,
            telegramFileId = 0,
            title = track.title,
            artist = track.artist,
            album = album?.takeIf { it.isNotBlank() },
            durationSeconds = track.durationSeconds,
            albumArtUrl = com.abn3li.telemusic.data.browse.googleArtworkAtSize(track.thumbnailUrl, com.abn3li.telemusic.data.browse.SAVED_ARTWORK_SIZE),
            youtubeVideoId = track.videoId,
            metadataEnriched = true
        )
        songDao.upsert(song)
        return song
    }

    /** Resolves a playable URI for [song] WITHOUT touching disk - the streaming counterpart to
     * [downloadExplicitly], for a [SongEntity.youtubeVideoId] row that hasn't been (or isn't)
     * downloaded. Null for anything else (already has a localFilePath, or isn't YouTube-sourced
     * at all), so callers can try this first and fall back to their existing local/TDLib logic
     * unchanged - see NowPlayingViewModel/MusicService's own playback resolution. */
    suspend fun resolveDirectPlaybackUri(song: SongEntity): Uri? {
        val videoId = song.youtubeVideoId ?: return null
        if (song.localFilePath != null) return null
        val outcome = ytDlpRepository.resolveStreamUrl(videoId, DownloadQuality.BEST.formatSelector)
        val stream = outcome.getOrNull()?.takeIf { it.streamUrl.isNotBlank() } ?: return null
        if (stream.durationSeconds > 0 && song.durationSeconds != stream.durationSeconds) {
            songDao.setDuration(song.telegramMessageId, stream.durationSeconds)
        }
        return stream.streamUrl.toUri()
    }

    fun invalidateStreamCache(videoId: String) {
        ytDlpRepository.invalidateStreamCache(videoId)
    }

    /**
     * The one true "how do I actually play this song" resolution, shared by MusicService's
     * next/previous handling and the Android Auto browse tree (MusicService's
     * MediaLibrarySession.Callback) - previously duplicated only in MusicService.playAdjacentSong,
     * which is exactly the kind of drift that made local-file staleness or YouTube-stream
     * resolution behave differently for one caller than the other. Local file first (clearing a
     * stale path if the file's gone missing since last recorded), then a YouTube stream resolve,
     * then falling back to a fresh TDLib file id. Null only when nothing could resolve at all (a
     * dead YouTube stream with no local copy, or a local import whose referenced content:// URI
     * permission is no longer valid - see SongEntity.isLocalImport's own doc for why localFilePath
     * can be either a real path or a content:// reference, and why only the latter has nowhere
     * else to fall back to).
     */
    suspend fun resolvePlaybackUri(song: SongEntity): Uri? =
        (resolvePlayback(song) as? PlaybackResolution.Playable)?.uri

    /** The same resolution, with the reason when there's nothing to play - each reason decided
     * by the very branch that found nothing, so the message can't drift from the logic. */
    suspend fun resolvePlayback(song: SongEntity): PlaybackResolution {
        var current = song
        val localPath = song.localFilePath
        if (localPath != null && !isLocalFileValid(localPath)) {
            clearStaleLocalPath(song.telegramMessageId)
            // Carry on with the row as it is now: resolveDirectPlaybackUri skips any song that
            // still has a local path, so the stale one would stop a deleted download streaming.
            current = song.copy(localFilePath = null)
        }
        return when {
            current.localFilePath != null -> PlaybackResolution.Playable(localFileToUri(current.localFilePath!!))
            current.isLocalImport -> PlaybackResolution.Unplayable("file may have been moved or deleted")
            current.youtubeVideoId != null -> resolveDirectPlaybackUri(current)?.let { PlaybackResolution.Playable(it) }
                ?: PlaybackResolution.Unplayable("video may be unavailable")
            // A Telegram song with no copy on the phone needs Telegram (not set up, or logged out).
            !tdlibManager.isStarted -> PlaybackResolution.Unplayable("set up Telegram in the Sync tab to play it")
            else -> PlaybackResolution.Playable(TdlibDataSource.uriFor(getFreshFileIdForSong(current)))
        }
    }

    private fun isLocalFileValid(path: String): Boolean =
        if (path.startsWith("content://")) {
            runCatching {
                appContext.contentResolver.openInputStream(Uri.parse(path))?.use { true } ?: false
            }.getOrDefault(false)
        } else {
            File(path).exists() && File(path).length() > 0
        }

    private fun localFileToUri(path: String): Uri =
        if (path.startsWith("content://")) Uri.parse(path) else File(path).toURI().toString().toUri()

    // ---- One-time cleanup: collapse collab credits into their primary artist ----
    /** A track credited "The Weeknd, Daft Punk" used to become its own separate Artists-tab
     * entry next to "The Weeknd"'s solo tracks - same underlying issue as
     * ytdlp_bridge.py's _primary_artist() (that fix only prevents NEW rows from fragmenting;
     * this collapses rows already stored that way before that fix existed, e.g. from Telegram
     * sync's own ID3 tags carrying a collab credit, or downloads made before this cleanup was
     * added). Idempotent and cheap when there's nothing to fix - only touches rows whose artist
     * string actually contains a separator. Run once at startup, same as backfillThumbnails(). */
    suspend fun normalizeArtistCredits() {
        for (rawArtist in songDao.getDistinctArtists()) {
            val primary = primaryArtistOf(rawArtist)
            if (primary != rawArtist) songDao.renameArtist(rawArtist, primary)
        }
    }

    // ---- Albums / Artists - grouped straight from real metadata ----
    fun observeAlbums(): Flow<List<AlbumSummary>> = songDao.observeAlbums()
    fun observeSongsByAlbum(album: String, sortField: SortField, ascending: Boolean) =
        songDao.observeSongsByAlbum(album).map { it.sortedByField(sortField, ascending) }

    fun observeArtists(): Flow<List<ArtistSummary>> = songDao.observeArtists()
    fun observeSongsByArtist(artist: String, sortField: SortField, ascending: Boolean) =
        songDao.observeSongsByArtist(artist).map { it.sortedByField(sortField, ascending) }

    suspend fun clearStaleLocalPath(songId: Long) {
        songDao.getById(songId)?.let { song ->
            // A download whose only copy (in the user's folder) was deleted or moved is no longer
            // a download - it goes back to streaming instead of still showing as downloaded.
            val wasFolderCopy = song.localFilePath != null && song.localFilePath == song.exportedFileUri
            songDao.update(
                song.copy(
                    localFilePath = null,
                    isExplicitDownload = if (wasFolderCopy) false else song.isExplicitDownload,
                    exportedFileUri = if (wasFolderCopy) null else song.exportedFileUri
                )
            )
        }
    }

    // ---- Playlists ----
    fun observePlaylists(): Flow<List<PlaylistEntity>> = playlistDao.observeAll()
    fun observePlaylistSummaries(): Flow<List<com.abn3li.telemusic.data.local.PlaylistSummary>> = playlistDao.observeAllWithArt()
    fun observePlaylistById(playlistId: Long): Flow<PlaylistEntity?> = playlistDao.observeById(playlistId)
    fun observeSongsInPlaylist(playlistId: Long): Flow<List<SongEntity>> = playlistDao.observeSongsInPlaylist(playlistId)
    suspend fun createPlaylist(name: String): Long = playlistDao.insert(PlaylistEntity(name = name))
    suspend fun deletePlaylist(playlistId: Long) = playlistDao.delete(playlistId)
    suspend fun addSongToPlaylist(playlistId: Long, song: SongEntity) {
        saveIfStreamOnly(song.telegramMessageId)
        playlistDao.addSong(PlaylistSongCrossRef(playlistId, song.telegramMessageId))
    }
    /** Adds with an explicit sort time - playlists list newest first, so an import gives its first
     * song the latest time to keep the source's order. */
    suspend fun addSongToPlaylistAt(playlistId: Long, songId: Long, addedAtMillis: Long) =
        playlistDao.addSong(PlaylistSongCrossRef(playlistId, songId, addedAtMillis))
    suspend fun playlistExists(playlistId: Long): Boolean = playlistDao.countById(playlistId) > 0
    suspend fun songIdsInPlaylist(playlistId: Long): List<Long> = playlistDao.songIdsInPlaylist(playlistId)
    suspend fun setPlaylistHiddenFromTracks(playlistId: Long, hidden: Boolean) = playlistDao.setHiddenFromTracks(playlistId, hidden)
    // Every song id belonging to a playlist that's had its own "Hide from tracks" turned on -
    // see PlaylistDao.observeSongIdsInHiddenPlaylists' own doc.
    fun observeHiddenPlaylistSongIds(): Flow<List<Long>> = playlistDao.observeSongIdsInHiddenPlaylists()

    private fun List<SongEntity>.sortedByField(field: SortField, ascending: Boolean): List<SongEntity> {
        val comparator = when (field) {
            SortField.TITLE -> compareBy<SongEntity> { it.title.lowercase() }
            SortField.ARTIST -> compareBy { it.artist.lowercase() }
            SortField.ALBUM -> compareBy { (it.album ?: "Unknown Album").lowercase() }
            SortField.DATE_ADDED -> compareBy { it.addedAtMillis }
        }
        val sorted = sortedWith(comparator)
        return if (ascending) sorted else sorted.reversed()
    }

    // ---- Sync from Telegram ----
    suspend fun syncFromChannel(chatId: Long) {
        settingsStore.lastSyncedChatId = chatId
        // A fresh sync is the one thing that's actually shown to rotate a song's real file id
        // out from under it (see verifiedFreshFileIdThisRun's own doc) - every song this sync
        // touches gets its telegramFileId written fresh below anyway, so clearing this just means
        // the NEXT play of each song trusts that freshly-synced value instead of a stale "already
        // verified" flag from before this sync ran.
        verifiedFreshFileIdThisRun.clear()
        // Audio messages first, then audio files sent as documents - each search pages on its
        // own cursor (see TdlibManager.fetchAudioPage).
        for (documents in listOf(false, true)) {
            var fromMessageId = 0L
            while (true) {
                val page = tdlibManager.fetchAudioPage(chatId, fromMessageId, documents)
                for (msg in page.songs) addSyncedSong(chatId, msg)
                // 0 = no more pages. A cursor that didn't move would fetch the same page forever.
                if (page.nextFromMessageId == 0L || page.nextFromMessageId == fromMessageId) break
                fromMessageId = page.nextFromMessageId
            }
        }

        removeDuplicates()
        mergeCrossSourceDuplicates()
    }

    // ---- One song from both Telegram and YouTube ----

    private val crossSourceLock = kotlinx.coroutines.sync.Mutex()
    // Telegram copies being downloaded to replace a YouTube download: started once, in the
    // background, so a sync or an import never waits for them.
    private val upgradeScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val upgradesInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    /** A copy of a song already in the library, found before a YouTube download. */
    data class ExistingCopy(val song: SongEntity, val higherQuality: Boolean)

    /**
     * Where the library has one song twice - from Telegram and from YouTube (same title and
     * artist, lengths within a few seconds) - keeps the better copy: the higher quality (see
     * [qualityOf]); about the same, the downloaded one; neither downloaded, the Telegram one.
     * The copy kept takes the other's Like, playlist places, last play and Spotify link, then
     * the other is removed from the library (never from Telegram). A downloaded copy beaten by
     * one that isn't downloaded is replaced only once the better one has downloaded, so the
     * song never stops working offline; if that download fails, both stay. A song playing or
     * in the queue is left for the next run. Runs after a sync and after an import; nothing
     * runs while the app is idle.
     */
    suspend fun mergeCrossSourceDuplicates() = withContext(Dispatchers.IO) {
        // Without Telegram signed in, its songs can't play - nothing to compare against.
        if (!tdlibManager.isStarted) return@withContext
        crossSourceLock.withLock {
            val all = songDao.observeAll().firstOrNull().orEmpty()
            val telegram = all.filter { it.telegramFileId != 0 && it.youtubeVideoId == null && !it.isLocalImport }
                .groupBy { recordingKey(it) }
            // Downloads made on purpose despite a better copy stay, with that copy.
            val keepBoth = settingsStore.keepBothSongIds
            val youtube = all.filter { it.youtubeVideoId != null && it.telegramMessageId !in keepBoth }
            // (better copy, the copy it replaces once downloaded)
            val toUpgrade = mutableListOf<Pair<SongEntity, SongEntity>>()
            val removed = HashSet<Long>()
            val inUse = songsInUse()
            database.withTransaction {
                for (yt in youtube) {
                    val tg = telegram[recordingKey(yt)]?.firstOrNull { it.telegramMessageId !in removed && sameRecording(it, yt) } ?: continue
                    // Quality not known yet (synced before sizes were recorded): decided after the next sync.
                    val keep = betterCopy(tg, yt) ?: continue
                    val drop = if (keep === yt) tg else yt
                    if (drop.telegramMessageId in inUse) continue
                    removed += drop.telegramMessageId
                    if (drop.isExplicitDownload && !keep.isExplicitDownload) toUpgrade += keep to drop
                    else moveOnto(drop, keep)
                }
            }
            for ((keep, drop) in toUpgrade) {
                if (!upgradesInFlight.add(keep.telegramMessageId)) continue
                upgradeScope.launch {
                    try {
                        // Downloaded first; only then is the other download replaced.
                        downloadExplicitly(keep)
                        crossSourceLock.withLock {
                            // Read again: a Like or a play during the download must carry over.
                            val freshKeep = songDao.getById(keep.telegramMessageId) ?: return@withLock
                            val freshDrop = songDao.getById(drop.telegramMessageId) ?: return@withLock
                            if (freshDrop.telegramMessageId in songsInUse()) return@withLock
                            database.withTransaction { moveOnto(freshDrop, freshKeep) }
                        }
                    } catch (e: Exception) {
                        Log.w("MusicRepository", "Better copy of '${keep.title}' didn't download; keeping both", e)
                    } finally {
                        upgradesInFlight.remove(keep.telegramMessageId)
                    }
                }
            }
        }
    }

    /** Before downloading a YouTube song: the copy already in the library that's as good or
     * better (from Telegram), if any - so the user can be asked first. Null when there's none,
     * or its quality isn't known yet. */
    suspend fun betterCopyInLibrary(videoId: String, title: String, artist: String, durationSeconds: Int): ExistingCopy? =
        withContext(Dispatchers.IO) {
            val candidate = SongEntity(
                telegramMessageId = ytDlpStableSongId(videoId), telegramFileId = 0,
                title = title, artist = artist, durationSeconds = durationSeconds, youtubeVideoId = videoId
            )
            val copy = songDao.findTelegramCopies(title, artist)
                .firstOrNull { sameRecording(it, candidate) && betterCopy(it, candidate) === it }
                ?: return@withContext null
            val higher = (qualityOf(copy) ?: 0.0) >= (qualityOf(candidate) ?: 0.0) * SAME_QUALITY_MARGIN
            ExistingCopy(copy, higher)
        }

    /** The user downloaded [videoId] anyway: that download stays alongside the better copy. */
    fun keepBothCopies(videoId: String) {
        settingsStore.keepBothSongIds = settingsStore.keepBothSongIds + ytDlpStableSongId(videoId)
    }

    private fun recordingKey(song: SongEntity) = "${song.title.trim().lowercase()}|${song.artist.trim().lowercase()}"

    /** Same title and artist, and lengths within 3 seconds (a live or extended version of a song
     * shares its name but not its length). Songs without a real title/artist never match. */
    private fun sameRecording(a: SongEntity, b: SongEntity): Boolean {
        if (a.title.isBlank() || a.title == "Unknown title" || a.artist.isBlank() || a.artist == "Unknown artist") return false
        if (recordingKey(a) != recordingKey(b)) return false
        return a.durationSeconds > 0 && b.durationSeconds > 0 && kotlin.math.abs(a.durationSeconds - b.durationSeconds) <= 3
    }

    /** [telegram] or [youtube], whichever copy of one song to keep - see
     * [mergeCrossSourceDuplicates]. Null while either copy's quality is unknown: nothing is
     * removed on a guess. */
    private fun betterCopy(telegram: SongEntity, youtube: SongEntity): SongEntity? {
        val tq = qualityOf(telegram) ?: return null
        val yq = qualityOf(youtube) ?: return null
        if (tq >= yq * SAME_QUALITY_MARGIN) return telegram
        if (yq >= tq * SAME_QUALITY_MARGIN) return youtube
        if (youtube.isExplicitDownload && !telegram.isExplicitDownload) return youtube
        return telegram
    }

    /**
     * A copy's quality, as an MP3-equivalent bitrate in kbps (Opus and AAC sound about as good as
     * MP3 at ~1.4x their bitrate); lossless files score above any lossy one. Null when unknown.
     */
    private fun qualityOf(song: SongEntity): Double? {
        val duration = song.durationSeconds.takeIf { it > 0 } ?: return null
        if (song.youtubeVideoId != null) {
            // Streamed from YouTube: its usual audio, ~130 kbps Opus. Downloaded: the file itself.
            val path = song.localFilePath ?: return YOUTUBE_STREAM_KBPS * EFFICIENT_CODEC_FACTOR
            val bytes = fileSizeOf(path)?.takeIf { it > 0 } ?: return YOUTUBE_STREAM_KBPS * EFFICIENT_CODEC_FACTOR
            val factor = if (path.substringAfterLast('.', "").lowercase() == "mp3") 1.0 else EFFICIENT_CODEC_FACTOR
            return bytes * 8.0 / duration / 1000.0 * factor
        }
        val mime = song.sourceMime.orEmpty().lowercase()
        if (LOSSLESS_MIME_HINTS.any { it in mime }) return LOSSLESS_KBPS
        val bytes = song.sourceSizeBytes.takeIf { it > 0 } ?: return null
        val factor = if ("mpeg" in mime || "mp3" in mime || mime.isBlank()) 1.0 else EFFICIENT_CODEC_FACTOR
        return bytes * 8.0 / duration / 1000.0 * factor
    }

    /** Size of a real file or of a content:// copy in the user's download folder. */
    private fun fileSizeOf(path: String): Long? = runCatching {
        if (path.startsWith("content://")) {
            appContext.contentResolver.openFileDescriptor(Uri.parse(path), "r")?.use { it.statSize }
        } else File(path).takeIf { it.exists() }?.length()
    }.getOrNull()

    /** [from]'s Like, playlist places, last play and Spotify link go to [to]; then [from] leaves
     * the library, its downloaded or cached file with it (never anything in Telegram). */
    private suspend fun moveOnto(from: SongEntity, to: SongEntity) {
        for (entry in playlistDao.getEntriesForSong(from.telegramMessageId)) {
            playlistDao.addSong(entry.copy(songId = to.telegramMessageId))
        }
        playlistDao.removeEntriesForSong(from.telegramMessageId)
        if (from.isFavorite && !to.isFavorite) songDao.setFavorite(to.telegramMessageId, true)
        if (from.lastPlayedAtMillis > to.lastPlayedAtMillis) songDao.stampLastPlayed(to.telegramMessageId, from.lastPlayedAtMillis)
        if (!from.album.isNullOrBlank()) songDao.setAlbumIfMissing(to.telegramMessageId, from.album)
        database.spotifyDao().remapSong(from.telegramMessageId, to.telegramMessageId)
        clearSong(from)
    }

    private suspend fun addSyncedSong(chatId: Long, msg: TelegramAudioMessage) {
        val title = msg.title.ifBlank { "Unknown title" }.trim()
        val artist = msg.performer.ifBlank { "Unknown artist" }.trim()

        // 1. This message is already in the library: refresh its file id. Message ids are only
        // unique inside one chat (and YouTube/local rows have ids of their own), so a row that
        // isn't this chat's Telegram song is a different song sharing the id - left alone.
        val existingById = songDao.getById(msg.messageId)
        if (existingById != null) {
            val sameMessage = existingById.telegramFileId != 0 &&
                (existingById.resolvedChatId == null || existingById.resolvedChatId == chatId)
            if (sameMessage && existingById.telegramFileId != msg.fileId) {
                songDao.setTelegramFileId(existingById.telegramMessageId, msg.fileId)
            }
            // Songs synced before sizes were recorded get theirs now.
            if (sameMessage && existingById.sourceSizeBytes == 0L && msg.sizeBytes > 0) {
                songDao.setSourceInfo(existingById.telegramMessageId, msg.sizeBytes, msg.mimeType.ifBlank { null })
            }
            return
        }

        // 2./3. The same song is already synced from another message (another chat, or posted
        // twice): that row stays and keeps playing from its own message. Only Telegram rows
        // count - a YouTube or local copy of the song doesn't keep the Telegram one out.
        val titleKnown = title != "Unknown title"
        val duplicate = (if (titleKnown && artist != "Unknown artist") songDao.findTelegramByTitleAndArtist(title, artist) else null)
            ?: (if (titleKnown && msg.durationSeconds > 0) songDao.findTelegramByTitleAndDuration(title, msg.durationSeconds) else null)
        if (duplicate != null) return

        val song = SongEntity(
            telegramMessageId = msg.messageId,
            telegramFileId = msg.fileId,
            title = title,
            artist = artist,
            durationSeconds = msg.durationSeconds,
            resolvedChatId = chatId,
            sourceSizeBytes = msg.sizeBytes,
            sourceMime = msg.mimeType.ifBlank { null }
        )
        // 4. The library already has this song from YouTube in the same or better quality: the
        // Telegram copy isn't added (it would only be merged away again after every sync).
        if (titleKnown && artist != "Unknown artist" &&
            songDao.findYoutubeByTitleAndArtist(title, artist).any { sameRecording(song, it) && betterCopy(song, it) === it }
        ) return

        songDao.upsert(song)
    }

    /**
     * Collapses Telegram songs synced more than once (same title and artist) into one row. Only
     * Telegram rows are compared - a YouTube or local copy of the same song is another source and
     * stays - and songs without a real title/artist are never grouped (two "Unknown title" songs,
     * or two files named "01 Intro", aren't the same song). The row kept is the downloaded one, else the one with a file, else
     * the enriched one, else the oldest; it takes over the others' playlist entries and Like.
     * A downloaded duplicate is never deleted. A removed row's cached stream file is cleared by
     * reconcileOrphanedTdlibFiles at the next launch (unless the kept row uses the same file).
     */
    suspend fun removeDuplicates() {
        val telegramSongs = songDao.observeAll().firstOrNull().orEmpty().filter {
            it.telegramFileId != 0 && !it.isLocalImport && it.youtubeVideoId == null &&
                it.title.isNotBlank() && it.title != "Unknown title" &&
                it.artist.isNotBlank() && it.artist != "Unknown artist" && it.artist != "Telegram Document"
        }
        val groups = telegramSongs.groupBy { "${it.title.lowercase().trim()}|${it.artist.lowercase().trim()}" }
        for (songs in groups.values) {
            if (songs.size < 2) continue
            val best = songs.sortedWith(
                compareByDescending<SongEntity> { it.isExplicitDownload }
                    .thenByDescending { it.localFilePath != null }
                    .thenByDescending { it.metadataEnriched }
                    .thenBy { it.addedAtMillis }
            ).first()
            for (duplicate in songs) {
                if (duplicate.telegramMessageId == best.telegramMessageId || duplicate.isExplicitDownload) continue
                for (entry in playlistDao.getEntriesForSong(duplicate.telegramMessageId)) {
                    playlistDao.addSong(entry.copy(songId = best.telegramMessageId))
                }
                playlistDao.removeEntriesForSong(duplicate.telegramMessageId)
                if (duplicate.isFavorite && !best.isFavorite) songDao.setFavorite(best.telegramMessageId, true)
                songDao.delete(duplicate.telegramMessageId)
            }
        }
    }

    /** [onProgress] fires per song, displaying live song title status. Keeps original song titles! */
    suspend fun enrichMissingMetadata(onProgress: (String) -> Unit = {}) {
        songDao.markCompleteSongsEnriched()
        val unenriched = songDao.getUnenriched()
        Log.d("MusicRepo", "enrichMissingMetadata: ${unenriched.size} songs remaining to enrich")
        if (unenriched.isEmpty()) return

        for (song in unenriched) {
            val originalTitle = song.title
            val originalArtist = song.artist
            // Artwork already in hand - either from an online lookup (albumArtUrl) or pulled
            // straight from the source during import/sync (thumbnailPath, no URL involved) -
            // counts the same here. Gating only on albumArtUrl would send every local-import/
            // Telegram-cover song that already has real art back through an online lookup on
            // every single call, forever - exactly the repeated-work loop this check exists to
            // prevent.
            val hasArtwork = !song.albumArtUrl.isNullOrBlank() || !song.thumbnailPath.isNullOrBlank()

            val alreadyHasGoodInfo = originalTitle.isNotBlank() && originalTitle != "Unknown title"
                    && originalArtist.isNotBlank() && originalArtist != "Unknown artist"
                    && hasArtwork

            if (alreadyHasGoodInfo) {
                songDao.markEnriched(song.telegramMessageId)
                continue
            }

            onProgress("Fetching info for $originalTitle...")

            // A local import/Telegram sync always has a real title/artist from its own tags, so
            // the first two checks rarely fire - but only needs a lookup for artwork when it
            // truly has none of its own (see hasArtwork above; LocalAudioImporter and
            // TdlibManager's sync path now extract embedded/Telegram-provided cover art directly
            // into thumbnailPath before this ever runs).
            val needsMetadataGuess = originalTitle == "Unknown title" || song.album == null || !hasArtwork
            val enriched = if (needsMetadataGuess) {
                metadataRepository.enrich("$originalTitle $originalArtist".trim())
            } else null

            if (enriched != null) {
                onProgress("Fetching art for $originalTitle...")
            }

            // Always RESPECT the original song title if it wasn't "Unknown title"!
            val finalTitle = if (originalTitle != "Unknown title" && originalTitle.isNotBlank()) {
                originalTitle
            } else {
                enriched?.title ?: originalTitle
            }

            val finalArtist = if (originalArtist != "Unknown artist" && originalArtist.isNotBlank()) {
                originalArtist
            } else {
                enriched?.artist ?: originalArtist
            }

            // Lyrics are deliberately NOT fetched here. This runs for every unenriched song
            // during library sync - fetching lyrics for the whole library automatically would
            // mean dozens/hundreds of network calls the user never asked for. Lyrics are only
            // ever fetched on-demand, via fetchLyricsForSong() below, when the user taps the
            // Lyrics button for a specific song.
            val finalArtUrl = song.albumArtUrl ?: enriched?.artworkUrl
            songDao.setSongInfo(song.telegramMessageId, finalTitle, finalArtist, song.album ?: enriched?.album, finalArtUrl)
            if (finalArtUrl != null) ensureThumbnail(song.telegramMessageId, finalArtUrl)
        }
    }

    /** The long-press-on-a-coverless-song flow: lets the user correct a mis-tagged title/artist
     * (common for Telegram audio with garbage or missing tags) and re-runs the same
     * iTunes -> Deezer -> MusicBrainz lookup enrichMissingMetadata uses, keyed off the user's own
     * correction instead of a guess. Unlike that bulk pass, this ALWAYS overwrites title/artist -
     * correcting them is the whole point of this flow, not a fallback for "Unknown title". Returns
     * whether artwork was actually found; the title/artist correction is saved either way. */
    suspend fun editSongAndFetchArtwork(song: SongEntity, newTitle: String, newArtist: String): Boolean {
        val enriched = metadataRepository.enrich("$newTitle $newArtist".trim())
        songDao.setSongInfo(song.telegramMessageId, newTitle, newArtist, enriched?.album ?: song.album, enriched?.artworkUrl ?: song.albumArtUrl)
        val artUrl = enriched?.artworkUrl ?: return false
        ensureThumbnail(song.telegramMessageId, artUrl)
        return true
    }

    /** Generates a small local thumbnail for [songId] from [artUrl] if it doesn't have one yet. */
    private suspend fun ensureThumbnail(songId: Long, artUrl: String) {
        val result = thumbnailGenerator.generate(songId, artUrl)
        if (result.path != null) {
            songDao.setThumbnailPath(songId, result.path)
        } else if (!result.retryLater) {
            // Mark failed so getSongsMissingThumbnail() never queries this failed URL again!
            // (Not when it only failed for lack of a connection - that one is retried.)
            songDao.setThumbnailPath(songId, "none")
        }
    }

    /**
     * One-shot pass over every already-enriched song that predates the thumbnail cache.
     * Safe to call repeatedly - only songs missing a thumbnail do any work. Paced with 100ms
     * delays to ensure 0% CPU background impact.
     */
    /** Runs [block]'s database writes as one transaction - open screens reload once, not per write. */
    suspend fun <T> inTransaction(block: suspend () -> T): T = database.withTransaction(block)

    suspend fun backfillThumbnails() {
        // Once: thumbnails used to be marked failed for good even when the phone was just
        // offline, leaving those songs without artwork offline forever. Give them one retry.
        if (!settingsStore.failedThumbnailsRetried) {
            songDao.clearFailedThumbnails()
            settingsStore.failedThumbnailsRetried = true
        }
        val missing = songDao.getSongsMissingThumbnail()
        if (missing.isEmpty()) return
        // Saved in batches, not one by one: every write to the songs table makes each open
        // screen reload the whole library, so 700 single writes (a big Spotify import) meant 700
        // full reloads - 25-40% CPU for minutes. One transaction per batch = one reload.
        var unreachable = 0
        for (batch in missing.chunked(THUMBNAIL_BATCH)) {
            val paths = ArrayList<Pair<Long, String>>(batch.size)
            for (song in batch) {
                val artUrl = song.albumArtUrl ?: continue
                val result = thumbnailGenerator.generate(song.telegramMessageId, artUrl)
                if (result.retryLater) {
                    // Couldn't be reached: left for the next run, not marked failed. One such
                    // song (a cover file that's gone, a dead host) is just skipped - only
                    // several in a row mean there's no connection, which ends the pass rather
                    // than failing the same way a few hundred more times.
                    if (++unreachable >= THUMBNAIL_OFFLINE_STREAK) break
                    continue
                }
                unreachable = 0
                delay(100)
                paths += song.telegramMessageId to (result.path ?: "none") // "none" = dead link, never retried
            }
            if (paths.isNotEmpty()) database.withTransaction { paths.forEach { (id, path) -> songDao.setThumbnailPath(id, path) } }
            if (unreachable >= THUMBNAIL_OFFLINE_STREAK) return
        }
    }

    /** Saves a downloaded song's covers into the image cache (the saved size used around the
     * app, and the large one Now Playing shows), so they're there offline even if the song was
     * never opened while online. Once per download; a no-op for local files. */
    private fun keepArtworkForOffline(artUrl: String?) {
        if (artUrl.isNullOrBlank() || !artUrl.startsWith("http")) return
        val full = com.abn3li.telemusic.data.browse.fullSizeArtwork(artUrl)
        setOfNotNull(artUrl, full).forEach { url ->
            appContext.imageLoader.enqueue(
                coil.request.ImageRequest.Builder(appContext)
                    .data(url)
                    .diskCacheKey(url)
                    .memoryCachePolicy(coil.request.CachePolicy.DISABLED)
                    .size(64) // only the file on disk matters here, not a full-size decode
                    .build()
            )
        }
    }

    private val lyricsCache = database.lyricsCacheDao()

    /**
     * Lyrics for [song], asked for on demand (opening Lyrics, Retry, a custom search) - never
     * during sync. The lyrics cache answers first: found lyrics, or "none anywhere" searched
     * within [LYRICS_NOT_FOUND_RETRY_MS], cost no network at all. Otherwise one search as
     * [query]'s title/artist (a custom search passes its own), saved to the song and the cache
     * under both the song's own name and the query's. [force] skips the cache (Retry, custom
     * search). Null when nothing was found.
     */
    suspend fun fetchLyricsForSong(song: SongEntity, query: SongEntity = song, force: Boolean = false): LyricsResult? {
        if (query.title.isBlank() || query.title == "Unknown title") return null
        val songKey = lyricsRepository.cacheKey(song.title, song.artist)
        val queryKey = lyricsRepository.cacheKey(query.title, query.artist)

        if (!force) {
            val cached = lyricsCache.get(songKey)
            if (cached != null) {
                if (cached.plain != null || cached.synced != null) {
                    songDao.setLyrics(song.telegramMessageId, cached.plain, cached.synced)
                    return LyricsResult(cached.plain, cached.synced, LyricsProvider.fromName(cached.provider))
                }
                if (System.currentTimeMillis() - cached.fetchedAtMillis < LYRICS_NOT_FOUND_RETRY_MS) return null
            }
        }

        val search = lyricsRepository.fetchLyrics(query.title, query.artist, query.durationSeconds.takeIf { it > 0 })
        val lyrics = search.result?.takeIf { it.plain != null || it.synced != null }
        when {
            // Found: kept under the song's own name and whatever was searched.
            lyrics != null -> saveLyrics(song, listOf(songKey, queryKey).distinct(), lyrics)
            // "None anywhere" only when every source really answered (not offline or blocked),
            // and only for the name actually searched - a custom search with a typo says
            // nothing about the song's real title.
            search.allAnswered -> saveLyrics(song, listOf(queryKey), null)
        }
        return lyrics
    }

    /** The Lyrics source picker: asks only [provider]. A find replaces the song's lyrics (and its
     * cache entry); nothing found leaves what's there. */
    suspend fun fetchLyricsFrom(song: SongEntity, provider: LyricsProvider): LyricsSearch {
        val search = lyricsRepository.fetchFrom(provider, song.title, song.artist, song.durationSeconds.takeIf { it > 0 })
        search.result?.let { saveLyrics(song, listOf(lyricsRepository.cacheKey(song.title, song.artist)), it) }
        return search
    }

    /** Which source the song's current lyrics came from, if the cache knows. */
    suspend fun lyricsProviderFor(song: SongEntity): LyricsProvider? =
        LyricsProvider.fromName(lyricsCache.get(lyricsRepository.cacheKey(song.title, song.artist))?.provider)

    private suspend fun saveLyrics(song: SongEntity, keys: List<String>, lyrics: LyricsResult?) {
        val now = System.currentTimeMillis()
        // Only the lyrics columns: the song may have changed since it was read.
        if (lyrics != null) songDao.setLyrics(song.telegramMessageId, lyrics.plain, lyrics.synced)
        for (key in keys) {
            // A "not found" never replaces lyrics already cached under that name.
            if (lyrics == null && lyricsCache.get(key)?.let { it.plain != null || it.synced != null } == true) continue
            lyricsCache.put(LyricsCacheEntity(key, lyrics?.plain, lyrics?.synced, lyrics?.provider?.name, now))
        }
    }

    /** One-time: copies lyrics songs already had into the lyrics cache, so they carry over to
     * the same song from another source and survive a library reset. Runs at startup, a page at
     * a time (by song id, so songs deleted meanwhile can't make it skip any) so a big library
     * never holds all its lyrics in memory at once. A test build keyed the cache too loosely
     * (see LyricsRepository.cacheKey), so what it cached is dropped first - only entries older
     * than this run, never a lookup made while it runs - and rebuilt from the songs. */
    suspend fun backfillLyricsCache() {
        if (settingsStore.lyricsCacheBackfilled) return
        val now = System.currentTimeMillis()
        lyricsCache.deleteOlderThan(now)
        var afterId = Long.MIN_VALUE
        while (true) {
            val page = songDao.getLyricsToBackfill(afterId, LYRICS_BACKFILL_PAGE)
            if (page.isEmpty()) break
            afterId = page.last().telegramMessageId
            val entries = page.mapNotNull { row ->
                val plain = row.lyricsPlain?.takeIf { it.isNotBlank() }
                val synced = row.lyricsSynced?.takeIf { it.isNotBlank() }
                // Blank text isn't lyrics - it must not become a "none anywhere" entry.
                if (plain == null && synced == null) return@mapNotNull null
                LyricsCacheEntity(lyricsRepository.cacheKey(row.title, row.artist), plain, synced, null, now)
            }
            if (entries.isNotEmpty()) lyricsCache.putIfMissing(entries)
            if (page.size < LYRICS_BACKFILL_PAGE) break
        }
        settingsStore.lyricsCacheBackfilled = true
    }

    /** EXPLICIT download only - triggered by the download button. Sets isExplicitDownload =
     * true, meaning enforceCacheLimit() will never touch this file. A local import is already
     * fully on-device - nothing to download, and there's no real Telegram file behind it to ask
     * TDLib for. A YouTube-sourced streamable row (youtubeVideoId set, no localFilePath yet -
     * see importPlaylistTrackAsStreamable) goes through yt-dlp instead of TDLib, same real
     * download [importDownloadedSong] already does for a search-result row. */
    suspend fun downloadExplicitly(song: SongEntity): String {
        val flac = (appContext.applicationContext as com.abn3li.telemusic.TgMusicApp)
            .flacUpgradeStore.acquireDownload(song.telegramMessageId.toString())
        if (flac != null) try { return withContext(Dispatchers.IO) {
            val (buffer, lease) = flac
            val destination = File(appContext.filesDir, "flac_downloads").apply { mkdirs() }
            val saved = File(destination, "${song.telegramMessageId}-${java.util.UUID.randomUUID()}.flac")
            var exported: Uri? = null
            var committed = false
            val wasInLibrary = songDao.getById(song.telegramMessageId) != null
            try {
                // Completion is signalled by the writer; a partial stream is never saved as a download.
                buffer.awaitComplete()
                check(buffer.file.length() == buffer.totalSize) { "FLAC download is incomplete" }
                buffer.file.copyTo(saved)
                exported = exportToDownloadFolderIfConfigured(saved, sanitizedFileName(song.title, song.artist, "flac"))
                val finalPath = exported?.toString() ?: saved.absolutePath
                database.withTransaction {
                    if (songDao.getById(song.telegramMessageId) == null && !wasInLibrary) {
                        // Playing a search result need not create a library row until Download.
                        songDao.upsert(song.copy(localFilePath = finalPath, isExplicitDownload = true,
                            exportedFileUri = exported?.toString(), sourceSizeBytes = buffer.totalSize, sourceMime = "audio/flac"))
                    } else {
                        check(songDao.setFlacDownloaded(song.telegramMessageId, finalPath, exported?.toString(), buffer.totalSize) == 1) {
                            "Song was removed while downloading"
                        }
                    }
                }
                committed = true
                if (exported != null) saved.delete()
                keepArtworkForOffline(song.displayArtwork)
                finalPath
            } finally {
                lease.close()
                if (!committed) {
                    saved.delete()
                    exported?.let { runCatching { mediaFolderExporter.delete(it) } }
                }
            }
        } } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { flac.second.close() }
        }
        if (song.isLocalImport) return song.localFilePath ?: error("Local import ${song.telegramMessageId} has no file path")
        if (song.youtubeVideoId != null && song.localFilePath == null) {
            val destDir = File(appContext.filesDir, "youtube_downloads")
            val outcome = ytDlpRepository.download(song.youtubeVideoId, destDir, song.telegramMessageId.toString(), DownloadQuality.BEST.formatSelector)
            val downloaded = outcome.getOrThrow()
            importDownloadedSong(downloaded, song.telegramMessageId, song.youtubeVideoId)
            return downloaded.filePath
        }
        val freshFileId = getFreshFileIdForSong(song)
        val path = tdlibManager.downloadFile(freshFileId)
        check(File(path).let { it.exists() && it.length() > 0 }) { "Download completed but file missing/empty at $path" }
        val extension = File(path).extension.ifBlank { "mp3" }
        val exportedUri = exportToDownloadFolderIfConfigured(File(path), sanitizedFileName(song.title, song.artist, extension))
        // With a download folder, the folder copy is the only copy: it's what plays, and TDLib's
        // own file is removed (and forgotten) so the song isn't stored twice.
        val finalPath = exportedUri?.toString() ?: path
        // Only the download's own columns: lyrics or a Like saved while it downloaded must stay.
        songDao.setDownloaded(song.telegramMessageId, freshFileId, finalPath, exportedUri?.toString())
        if (exportedUri != null) {
            // Through TDLib, not File.delete(): TDLib must know the file is gone, or a later
            // re-download (after "Delete Download") gets told it's already complete.
            tdlibManager.deleteDownloadedFile(freshFileId)
        }
        return finalPath
    }

    /** Removes a single song from the library entirely - the row's own "Clear song" menu item.
     * Unlike [removeDownload] (which, for a Telegram-sourced download, only strips the local
     * file and reverts the row back to streaming), this always deletes the row itself, plus any
     * local copy it has - an auto-cached stream, an explicit download, or a local import's own
     * private copy - and any exported shared-storage copy, so nothing orphaned is left behind. */
    suspend fun clearSong(song: SongEntity) {
        song.localFilePath?.takeIf { it != song.exportedFileUri }?.let(::releaseLocalFile)
        song.exportedFileUri?.let { uri -> runCatching { mediaFolderExporter.delete(android.net.Uri.parse(uri)) } }
        songDao.delete(song.telegramMessageId)
        forgetKeepBoth(song.telegramMessageId)
    }

    /** A song gone from the library no longer needs its "keep both copies" mark. */
    private fun forgetKeepBoth(id: Long) {
        val keepBoth = settingsStore.keepBothSongIds
        if (id in keepBoth) settingsStore.keepBothSongIds = keepBoth - id
    }

    /** Releases whatever [path] actually represents - deletes it if it's a real file this app
     * owns (an auto-cached stream, an explicit download, or a copied local import), or just
     * releases the persisted read grant if it's a referenced local import's own content:// URI
     * (see SongEntity.isLocalImport's own doc) - that file is the user's own original, never
     * this app's to delete. */
    private fun releaseLocalFile(path: String) {
        if (path.startsWith("content://")) {
            runCatching {
                appContext.contentResolver.releasePersistableUriPermission(Uri.parse(path), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            runCatching { File(path).delete() }
        }
    }

    /** Removes the local downloaded copy of [song] - the row's own "Delete song" menu item,
     * shown for an isExplicitDownload OR a local import (both have a real file to remove).
     * Removes BOTH copies: the app-private file AND the exported copy in the user's own shared-
     * storage folder, if one was ever made (see SongEntity.exportedFileUri's own doc). A
     * Telegram-sourced OR YouTube-streamable (youtubeVideoId set - see
     * importPlaylistTrackAsStreamable) download reverts to streaming, the row stays, same as it
     * looked before it was ever downloaded - only a local import, or a legacy YouTube download
     * from before youtubeVideoId existed, has nowhere to fall back to, so removing its only file
     * removes the whole row instead of leaving a dead, unplayable entry behind. */
    suspend fun removeDownload(song: SongEntity) {
        song.localFilePath?.takeIf { it != song.exportedFileUri }?.let(::releaseLocalFile)
        song.exportedFileUri?.let { uri -> mediaFolderExporter.delete(android.net.Uri.parse(uri)) }
        if (song.youtubeVideoId != null || song.telegramFileId != 0) {
            songDao.clearDownload(song.telegramMessageId)
        } else {
            songDao.delete(song.telegramMessageId)
            forgetKeepBoth(song.telegramMessageId)
        }
    }

    /** Records that a streamed file finished downloading in the background - this is the
     * AUTO-CACHE path. isExplicitDownload is deliberately left untouched (false unless it was
     * already true from a prior explicit download), so the download icon and cache eviction
     * both treat this correctly as "not a real download." */
    suspend fun markStreamedFileCached(song: SongEntity) {
        // Bounded so a download that genuinely never finishes (paused indefinitely, network
        // dies, or - before NowPlayingViewModel started cancelling the previous song's download
        // on skip - simply deprioritized behind newer requests) can't poll forever. The caller
        // cancelling the old download on skip is the real fix for that case; this is just a
        // safety net so this loop can never become one of the unbounded background coroutines
        // that caused the storage/CPU leak in the first place, even if some future caller forgets
        // to cancel.
        repeat(MAX_CACHE_POLL_ATTEMPTS) {
            val progress = tdlibManager.getCachedFileProgress(song.telegramFileId)
            if (progress?.local?.isDownloadingCompleted == true) {
                val current = songDao.getById(song.telegramMessageId) ?: return
                // An original-source cache completion must never replace a saved FLAC.
                if (current.isExplicitDownload) return
                // Always sync to TDLib's real current path, not just when it was null - a song
                // cancelled+deleted mid-stream (see TdlibManager.cancelDownload's own doc) and
                // later replayed to a fresh completion still had its OLD, now-deleted path
                // sitting in the DB, so the "only if null" version of this check silently left
                // that fresh file's real path unrecorded forever - orphaned on disk, invisible to
                // enforceCacheLimit()'s DB-driven accounting no matter how correctly it ran.
                if (current.localFilePath != progress.local.path) {
                    songDao.setCachedFilePath(current.telegramMessageId, progress.local.path)
                }
                enforceCacheLimit(excludeSongId = song.telegramMessageId)
                return
            }
            delay(1000)
        }
    }

    /** Tells TDLib to actually stop downloading [fileId] - see TdlibManager.cancelDownload's own
     * doc for why this matters: cancelling our own markStreamedFileCached polling coroutine (a
     * structured child of NowPlayingViewModel's loadJob, so it's already cancelled automatically
     * when the user skips to a new song) does NOT stop TDLib's independent background download
     * of the song being skipped away from - without this, every streamed song kept silently
     * downloading to completion regardless of whether it was still playing, consuming disk space
     * enforceCacheLimit() has no way to know about until each one finishes. */
    suspend fun cancelStreamingDownload(fileId: Int) = tdlibManager.cancelDownload(fileId)

    /** What a cache wipe removed: fully cached songs, and half-streamed leftover files. */
    data class CacheClearResult(val cachedSongs: Int, val partialFiles: Int, val freedBytes: Long, val lyricsCleared: Int)

    /**
     * Deletes every auto-cached song file (explicit downloads and local imports are never
     * touched) AND every partially streamed file TDLib left behind, so the cache really ends
     * up empty. [keepSongId] - the song playing right now - keeps its file, as does whatever
     * TDLib is still streaming, so playback isn't cut off mid-song.
     */
    suspend fun clearStreamingCache(keepSongId: Long? = null): CacheClearResult {
        var count = 0
        var freed = 0L
        for (song in songDao.getAutoCachedSongsOldestFirst()) {
            if (song.telegramMessageId == keepSongId) continue
            val path = song.localFilePath ?: continue
            val file = File(path)
            if (file.exists()) {
                freed += file.length()
                file.delete()
            }
            songDao.setLocalFilePath(song.telegramMessageId, null)
            tdlibManager.deleteDownloadedFile(song.telegramFileId)
            count++
        }
        val (partial, partialBytes) = purgePartialAudioFiles()
        val (ytLeftovers, ytBytes) = purgeYouTubeDownloadLeftovers(everything = false)
        // YouTube streams play straight from the internet - nothing of them is on disk; only the
        // links already looked up are remembered (in memory), and those go too.
        ytDlpRepository.clearStreamCache()
        val lyrics = clearAllLyrics()
        return CacheClearResult(count, partial + ytLeftovers, freed + partialBytes + ytBytes, lyrics)
    }

    /** Every saved lyric - on the songs and in the lookup cache. Opening a song's lyrics (or the
     * open player) searches again. Returns how many songs had lyrics. */
    private suspend fun clearAllLyrics(): Int = database.withTransaction {
        lyricsCache.deleteAll()
        songDao.clearAllLyrics()
    }

    /**
     * Removes files in TDLib's music folder that no song points at - songs streamed only part
     * way, then skipped. Keeps anything a song row references and anything being streamed right
     * now. Returns (files deleted, bytes freed).
     */
    private suspend fun purgePartialAudioFiles(): Pair<Int, Long> {
        val files = File(appContext.filesDir, "tdlib/music").listFiles() ?: return 0 to 0L
        val keep = songDao.getAllReferencedLocalFilePaths()
            .mapNotNullTo(HashSet()) { runCatching { File(it).canonicalPath }.getOrNull() }
        keep += tdlibManager.activeDownloadPaths()
        var deleted = 0
        var freed = 0L
        for (file in files) {
            val canonical = runCatching { file.canonicalPath }.getOrNull() ?: continue
            if (canonical in keep) continue
            val size = file.length()
            if (file.delete()) {
                deleted++
                freed += size
            }
        }
        return deleted to freed
    }

    /**
     * Cleans the YouTube downloads folder. yt-dlp writes "<stem>.<ext>.part" (plus ".ytdl" for
     * fragmented streams) and renames on completion, so an interrupted download leaves those
     * behind forever. [everything] = false (Clear Cache) removes those leftovers and any file no
     * song row points at, keeping finished downloads; true (Reset Library) removes all of it.
     * Either way a download that's still running is skipped. Returns (files deleted, bytes freed).
     */
    private suspend fun purgeYouTubeDownloadLeftovers(everything: Boolean): Pair<Int, Long> {
        val files = File(appContext.filesDir, "youtube_downloads").listFiles() ?: return 0 to 0L
        val active = ytDlpRepository.activeDownloadStems()
        val referenced = if (everything) emptySet() else songDao.getAllReferencedLocalFilePaths()
            .mapNotNullTo(HashSet()) { runCatching { File(it).canonicalPath }.getOrNull() }
        var deleted = 0
        var freed = 0L
        for (file in files) {
            if (!file.isFile || file.name.substringBefore('.') in active) continue
            val canonical = runCatching { file.canonicalPath }.getOrNull() ?: continue
            if (canonical in referenced) continue
            val size = file.length()
            if (file.delete()) {
                deleted++
                freed += size
            }
        }
        return deleted to freed
    }

    /** Deletes EVERY song - Telegram-synced, YouTube-downloaded, and local imports alike - plus
     * playlists and every cached/downloaded/exported audio file on disk, for a 100% fresh start.
     * See [releaseLocalFile] for why a local import's localFilePath isn't always safe to delete
     * outright - a referenced (not copied) one is the user's own original file. */
    suspend fun clearAllLibrarySongs() {
        val allSongs = songDao.observeAll().firstOrNull().orEmpty()
        for (song in allSongs) {
            song.localFilePath?.takeIf { it != song.exportedFileUri }?.let(::releaseLocalFile)
            song.exportedFileUri?.let { uri -> runCatching { mediaFolderExporter.delete(android.net.Uri.parse(uri)) } }
            songDao.delete(song.telegramMessageId)
        }
        settingsStore.keepBothSongIds = emptySet()
        val playlists = playlistDao.observeAll().firstOrNull().orEmpty()
        for (pl in playlists) {
            playlistDao.delete(pl.id)
        }
        settingsStore.lastSyncedChatId = 0L
        settingsStore.pinnedPlaylists = emptyList()
        // Everything else the library left behind: YouTube songs played without being added
        // (Home's Recently Played), saved lyrics, YouTube and Spotify import links, covers.
        recentStreamsLock.withLock {
            withContext(Dispatchers.IO) { recentStreamsFile.delete() }
            recentStreamsState.value = emptyList()
        }
        lyricsCache.deleteAll()
        database.importedPlaylistDao().deleteAll()
        database.spotifyDao().deleteAllLinks()
        database.spotifyDao().deleteAllMatches()
        ytDlpRepository.clearStreamCache()
        withContext(Dispatchers.IO) {
            for (dir in listOf("thumbnails", "artwork", "local_imports", "flac_downloads")) {
                File(appContext.filesDir, dir).listFiles()?.forEach { it.deleteRecursively() }
            }
        }
        appContext.imageLoader.memoryCache?.clear()
        appContext.imageLoader.diskCache?.clear()
        // Every row is gone, so any file still in TDLib's music folder is a half-streamed
        // leftover, and anything left in the YouTube downloads folder is an orphan or an
        // interrupted download - wipe those too for a truly fresh start.
        purgePartialAudioFiles()
        purgeYouTubeDownloadLeftovers(everything = true)
    }

    var pinnedPlaylists: List<String>
        get() = settingsStore.pinnedPlaylists
        set(value) { settingsStore.pinnedPlaylists = value }

    var showSongIndex: Boolean
        get() = settingsStore.showSongIndex
        set(value) { settingsStore.showSongIndex = value }

    // The player and the playback service both stamp a song as it starts. Each stamp is a write
    // to the songs table, and every such write makes the open screens re-read the whole library
    // and Home recompute - so the second stamp of the same start is dropped.
    @Volatile private var lastStampedSongId = 0L
    @Volatile private var lastStampedAt = 0L

    suspend fun stampLastPlayed(song: SongEntity) {
        val now = System.currentTimeMillis()
        if (song.telegramMessageId == lastStampedSongId && now - lastStampedAt < STAMP_REPEAT_WINDOW_MS) return
        lastStampedSongId = song.telegramMessageId
        lastStampedAt = now
        // No library row to stamp: a YouTube song played from search or an album page.
        if (songDao.stampLastPlayed(song.telegramMessageId, now) == 0) recordStreamPlayed(song, now)
    }

    suspend fun getFreshFileIdForSong(song: SongEntity): Int {
        // No real Telegram message behind a local import OR a YouTube download - telegramFileId
        // == 0 is this app's own general marker for "not really a Telegram row" (see
        // SongEntity's own doc, and the same check at line ~400 in this file). Checking only
        // isLocalImport here missed YouTube downloads, so opening one in Now Playing used to
        // sweep EVERY channel the user is in (2+ TDLib round-trips each) hunting for a message
        // id that could never exist, stalling this song's state update the whole time.
        if (song.isLocalImport || song.telegramFileId == 0) return song.telegramFileId

        // Already confirmed working THIS run - skip the TDLib round trip entirely rather than
        // re-verifying a file id that was just proven fresh moments ago (see
        // verifiedFreshFileIdThisRun's own doc). This is the fast path for the common case:
        // replaying a song, or the queue auto-advancing through recently-played ones.
        if (song.telegramMessageId in verifiedFreshFileIdThisRun) return song.telegramFileId

        // This song's OWN remembered chat, from a previous resolve (see SongEntity.resolvedChatId's
        // own doc) - tried FIRST, ahead of the app-wide lastSyncedChatId, since that single global
        // value only ever reflects whichever chat was resolved MOST RECENTLY across every song,
        // not this specific one. Without this, a library with songs from several different chats
        // re-ran the full candidate sweep below on every single play of any song that wasn't from
        // the one chat lastSyncedChatId currently happens to point at - not just once after a
        // channel switch, but forever, every time. A resolved chat only ever needs the sweep
        // again if the message truly moves/disappears from it, which the fallthrough below still
        // handles.
        song.resolvedChatId?.let { resolvedChatId ->
            val freshId = tdlibManager.getFreshFileId(resolvedChatId, song.telegramMessageId)
            if (freshId != null) {
                if (freshId != song.telegramFileId) {
                    songDao.setTelegramFileId(song.telegramMessageId, freshId)
                }
                verifiedFreshFileIdThisRun.add(song.telegramMessageId)
                return freshId
            }
        }

        var channelId = settingsStore.lastSyncedChatId
        if (channelId == 0L) {
            val found = tdlibManager.findFirstChannelId()
            if (found != null && found != 0L) {
                channelId = found
                settingsStore.lastSyncedChatId = found
            }
        }

        if (channelId != 0L && channelId != song.resolvedChatId) {
            val freshId = tdlibManager.getFreshFileId(channelId, song.telegramMessageId)
            if (freshId != null) {
                songDao.setTelegramFile(song.telegramMessageId, freshId, channelId)
                verifiedFreshFileIdThisRun.add(song.telegramMessageId)
                return freshId
            }
        }

        // Not filtered to isChannel-only - sync can also come from Saved Messages (a private
        // chat, never a "channel"), so a song synced from there would otherwise never be
        // re-resolvable once lastSyncedChatId moved on to a real channel: this whole fallback
        // exists BECAUSE the source chat can change between syncs, and Saved Messages is exactly
        // as valid a source as any channel is.
        val channels = runCatching { tdlibManager.listMyChats(resolvePublicStatus = false) }.getOrDefault(emptyList())
        val savedMessages = runCatching { tdlibManager.getSavedMessagesChat() }.getOrNull()
        val candidates = (channels + listOfNotNull(savedMessages)).distinctBy { it.id }
            .filter { it.id != channelId && it.id != song.resolvedChatId }

        // Fired in parallel, not one chat after another - a real account with a couple dozen
        // chats meant a sequential sweep could take 20-30+ seconds (each lookup is its own
        // network round trip), which is exactly what read as "the song just doesn't play for
        // half a minute". These are all independent, network-bound lookups against different
        // chats, same tradeoff listMyChats' own per-chat GetChat fetch already makes - the whole
        // sweep now takes about as long as its single slowest lookup instead of their sum. This
        // sweep only has to run once per song now (see resolvedChatId above), not on every play.
        val found = coroutineScope {
            candidates
                .map { chat -> async { chat.id to tdlibManager.getFreshFileId(chat.id, song.telegramMessageId) } }
                .awaitAll()
                .firstOrNull { it.second != null }
        }
        if (found != null) {
            val (chatId, freshId) = found
            settingsStore.lastSyncedChatId = chatId
            songDao.setTelegramFile(song.telegramMessageId, freshId!!, chatId)
            verifiedFreshFileIdThisRun.add(song.telegramMessageId)
            return freshId!!
        }

        return song.telegramFileId
    }

    /** Evicts least-recently-played AUTO-CACHED files (isExplicitDownload == false) once their
     * total size exceeds the configured limit. Explicit downloads are NEVER touched, NEVER
     * counted toward the limit, and persist until the user manually removes them. The
     * currently-playing song is always excluded. */
    suspend fun enforceCacheLimit(excludeSongId: Long? = null) {
        val limit = settingsStore.maxCacheSizeBytes
        if (limit == AppSettingsStore.UNLIMITED) return

        val autoCached = songDao.getAutoCachedSongsOldestFirst().filter { it.telegramMessageId != excludeSongId }
        var totalSize = autoCached.sumOf { File(it.localFilePath!!).length() }
        if (totalSize <= limit) return

        for (song in autoCached) {
            if (totalSize <= limit) break
            val file = File(song.localFilePath!!)
            val size = file.length()
            if (file.exists()) file.delete()
            songDao.setLocalFilePath(song.telegramMessageId, null)
            tdlibManager.deleteDownloadedFile(song.telegramFileId)
            totalSize -= size
        }
    }

    /**
     * One-time-per-launch cleanup for files left behind by a since-fixed bug: a streamed song
     * that was cancelled mid-download and later replayed to a fresh completion could end up with
     * TDLib's real current file on disk while the DB still pointed at its old, already-deleted
     * path - making the fresh file permanently invisible to enforceCacheLimit()'s DB-driven
     * accounting (confirmed on-device: it tracked ~99MB while files/tdlib/music actually held
     * 424MB). That root cause is fixed (see markStreamedFileCached's own doc), but files it
     * already orphaned before the fix don't get found by any future scan since nothing in the DB
     * ever pointed at them - this reconciles disk against the DB once to clear that backlog.
     *
     * A single flat listing + set diff over one directory, called once from TgMusicApp's startup
     * (same place backfillThumbnails/normalizeArtistCredits already run) - no loop, no retry, no
     * repeated re-scan of files it already handled.
     */
    suspend fun reconcileOrphanedTdlibFiles() {
        val musicDir = File(appContext.filesDir, "tdlib/music")
        val files = musicDir.listFiles() ?: return
        // canonicalPath, not absolutePath: Context.filesDir resolves to /data/user/0/<pkg>/files
        // on this device while TDLib records its own paths as /data/data/<pkg>/files/... - the
        // same real directory via a symlink, but different STRINGS, so a plain absolutePath
        // comparison here treated every file as unreferenced regardless of the DB - confirmed
        // on-device: it deleted 28 files including several just-cached songs still actively
        // pointed at by the DB, not just the intended pre-existing orphans. canonicalPath
        // resolves the symlink on both sides so this can't happen again.
        val referenced = songDao.getAllReferencedLocalFilePaths()
            .mapNotNullTo(HashSet()) { runCatching { File(it).canonicalPath }.getOrNull() }
        var deleted = 0
        var freedBytes = 0L
        for (file in files) {
            val canonical = runCatching { file.canonicalPath }.getOrNull() ?: continue
            if (canonical !in referenced) {
                freedBytes += file.length()
                if (file.delete()) deleted++
            }
        }
        if (deleted > 0) {
            Log.d("CacheDebug", "reconcileOrphanedTdlibFiles: deleted=$deleted freedBytes=$freedBytes")
        }
    }
}
