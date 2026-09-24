package com.abn3li.telemusic.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import com.abn3li.telemusic.data.browse.BrowseTrack
import com.abn3li.telemusic.data.local.AlbumSummary
import com.abn3li.telemusic.data.local.ArtistSummary
import com.abn3li.telemusic.data.local.PlaylistDao
import com.abn3li.telemusic.data.local.PlaylistEntity
import com.abn3li.telemusic.data.local.PlaylistSongCrossRef
import com.abn3li.telemusic.data.local.SongDao
import com.abn3li.telemusic.data.local.SongEntity
import com.abn3li.telemusic.data.download.DownloadQuality
import com.abn3li.telemusic.data.download.MediaFolderExporter
import com.abn3li.telemusic.data.download.YtDlpRepository
import com.abn3li.telemusic.data.download.ytDlpStableSongId
import com.abn3li.telemusic.data.settings.AppSettingsStore
import com.abn3li.telemusic.data.telegram.TdlibManager
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
import java.io.File

enum class SortField(val label: String) { TITLE("Name"), ARTIST("Artist"), ALBUM("Album"), DATE_ADDED("Date added") }

// ~10 minutes at the 1s poll interval below - see markStreamedFileCached's own doc.
private const val MAX_CACHE_POLL_ATTEMPTS = 600

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
    context: Context
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
    private fun exportToDownloadFolderIfConfigured(source: File, displayName: String): Uri? {
        val folderUri = settingsStore.downloadFolderUri?.let { android.net.Uri.parse(it) } ?: return null
        return mediaFolderExporter.export(source, folderUri, displayName)
    }
    // ---- Tracks / Favourites, metadata-driven sort ----
    fun observeLibrary(sortField: SortField, ascending: Boolean): Flow<List<SongEntity>> =
        songDao.observeAll().map { it.sortedByField(sortField, ascending) }

    suspend fun getSongById(id: Long): SongEntity? = songDao.getById(id)
    fun search(query: String): Flow<List<SongEntity>> = songDao.search(query)

    /** Android Auto's "Recently Played" browse category - see MusicService's MediaLibrarySession. */
    suspend fun getRecentlyPlayed(limit: Int = 50): List<SongEntity> = songDao.getRecentlyPlayed(limit)

    fun observeFavorites(sortField: SortField, ascending: Boolean): Flow<List<SongEntity>> =
        songDao.observeFavorites().map { it.sortedByField(sortField, ascending) }

    suspend fun setFavorite(song: SongEntity, isFavorite: Boolean) = songDao.setFavorite(song.telegramMessageId, isFavorite)

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
        songDao.upsert(
            SongEntity(
                telegramMessageId = songId,
                telegramFileId = 0,
                title = result.title,
                artist = result.artist,
                durationSeconds = result.durationSeconds,
                albumArtUrl = result.thumbnailUrl,
                localFilePath = result.filePath,
                isExplicitDownload = true,
                metadataEnriched = true,
                // Keeps a lightweight streamable row's own videoId (or the caller's, for a fresh
                // download) so removeDownload() can revert this back to streaming later instead
                // of deleting the row outright - see removeDownload's own doc.
                youtubeVideoId = videoId ?: existing?.youtubeVideoId,
                isFavorite = existing?.isFavorite ?: false,
                addedAtMillis = existing?.addedAtMillis ?: System.currentTimeMillis()
            )
        )
        val extension = File(result.filePath).extension.ifBlank { "m4a" }
        val exportedUri = exportToDownloadFolderIfConfigured(File(result.filePath), sanitizedFileName(result.title, result.artist, extension))
        if (exportedUri != null) {
            songDao.getById(songId)?.let { songDao.update(it.copy(exportedFileUri = exportedUri.toString())) }
        }
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
    suspend fun importPlaylistTrackAsStreamable(track: BrowseTrack): SongEntity {
        val songId = ytDlpStableSongId(track.videoId)
        songDao.getById(songId)?.let { return it }
        val song = SongEntity(
            telegramMessageId = songId,
            telegramFileId = 0,
            title = track.title,
            artist = track.artist,
            durationSeconds = track.durationSeconds,
            albumArtUrl = track.thumbnailUrl,
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
            songDao.update(song.copy(durationSeconds = stream.durationSeconds))
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
    suspend fun resolvePlaybackUri(song: SongEntity): Uri? {
        var localPath = song.localFilePath
        if (localPath != null && !isLocalFileValid(localPath)) {
            clearStaleLocalPath(song.telegramMessageId)
            localPath = null
        }
        return when {
            localPath != null -> localFileToUri(localPath)
            song.isLocalImport -> null
            song.youtubeVideoId != null -> resolveDirectPlaybackUri(song)
            else -> TdlibDataSource.uriFor(getFreshFileIdForSong(song))
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
            songDao.update(song.copy(localFilePath = null))
        }
    }

    // ---- Playlists ----
    fun observePlaylists(): Flow<List<PlaylistEntity>> = playlistDao.observeAll()
    fun observePlaylistSummaries(): Flow<List<com.abn3li.telemusic.data.local.PlaylistSummary>> = playlistDao.observeAllWithArt()
    fun observePlaylistById(playlistId: Long): Flow<PlaylistEntity?> = playlistDao.observeById(playlistId)
    fun observeSongsInPlaylist(playlistId: Long): Flow<List<SongEntity>> = playlistDao.observeSongsInPlaylist(playlistId)
    suspend fun createPlaylist(name: String): Long = playlistDao.insert(PlaylistEntity(name = name))
    suspend fun deletePlaylist(playlistId: Long) = playlistDao.delete(playlistId)
    suspend fun addSongToPlaylist(playlistId: Long, song: SongEntity) = playlistDao.addSong(PlaylistSongCrossRef(playlistId, song.telegramMessageId))
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
        var fromMessageId = 0L
        do {
            val batch = tdlibManager.fetchAudioMessages(chatId, fromMessageId)
            if (batch.isEmpty()) break

            for (msg in batch) {
                val title = msg.title.ifBlank { "Unknown title" }.trim()
                val artist = msg.performer.ifBlank { "Unknown artist" }.trim()

                // 1. Check if song with exact messageId already exists in library
                val existingById = songDao.getById(msg.messageId)
                if (existingById != null) {
                    if (existingById.telegramFileId != msg.fileId) {
                        songDao.update(existingById.copy(telegramFileId = msg.fileId))
                    }
                    continue
                }

                // 2. Check if a song with matching Title & Artist is ALREADY in library
                val existingByTitleArtist = if (title != "Unknown title" && artist != "Unknown artist") {
                    songDao.findByTitleAndArtist(title, artist)
                } else null

                // 3. Check if a song with matching Title & Duration is ALREADY in library
                val existingByTitleDuration = if (existingByTitleArtist == null && title != "Unknown title" && msg.durationSeconds > 0) {
                    songDao.findByTitleAndDuration(title, msg.durationSeconds)
                } else null

                val existingDuplicate = existingByTitleArtist ?: existingByTitleDuration

                if (existingDuplicate != null) {
                    // Song already in library! Update file, message ID, AND resolvedChatId to new chat
                    songDao.update(
                        existingDuplicate.copy(
                            telegramMessageId = msg.messageId,
                            telegramFileId = msg.fileId,
                            resolvedChatId = chatId
                        )
                    )
                } else {
                    // Truly a new song! Insert into library with resolvedChatId set
                    songDao.upsert(
                        SongEntity(
                            telegramMessageId = msg.messageId,
                            telegramFileId = msg.fileId,
                            title = title,
                            artist = artist,
                            durationSeconds = msg.durationSeconds,
                            resolvedChatId = chatId
                        )
                    )
                }
            }
            fromMessageId = batch.last().messageId
        } while (batch.size >= 50)

        removeDuplicates()
    }

    /** Cleans up any existing duplicate entries in the local database. */
    suspend fun removeDuplicates() {
        val all = songDao.observeAll().firstOrNull().orEmpty()
        val grouped = all.groupBy { "${it.title.lowercase().trim()}|${it.artist.lowercase().trim()}" }
        for ((_, songs) in grouped) {
            if (songs.size > 1) {
                val best = songs.maxByOrNull {
                    (if (it.isExplicitDownload) 1000 else 0) +
                        (if (it.localFilePath != null) 500 else 0) +
                        (if (it.metadataEnriched) 100 else 0) +
                        it.addedAtMillis
                } ?: songs.first()

                songs.filter { it.telegramMessageId != best.telegramMessageId }.forEach { duplicate ->
                    songDao.delete(duplicate.telegramMessageId)
                }
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
                songDao.update(song.copy(metadataEnriched = true))
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
            songDao.update(
                song.copy(
                    title = finalTitle,
                    artist = finalArtist,
                    album = song.album ?: enriched?.album,
                    albumArtUrl = finalArtUrl,
                    metadataEnriched = true
                )
            )
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
        songDao.update(
            song.copy(
                title = newTitle,
                artist = newArtist,
                album = enriched?.album ?: song.album,
                albumArtUrl = enriched?.artworkUrl ?: song.albumArtUrl,
                metadataEnriched = true
            )
        )
        val artUrl = enriched?.artworkUrl ?: return false
        ensureThumbnail(song.telegramMessageId, artUrl)
        return true
    }

    /** Generates a small local thumbnail for [songId] from [artUrl] if it doesn't have one yet. */
    private suspend fun ensureThumbnail(songId: Long, artUrl: String) {
        val path = thumbnailGenerator.generate(songId, artUrl)
        if (path != null) {
            songDao.setThumbnailPath(songId, path)
        } else {
            // Mark failed so getSongsMissingThumbnail() never queries this failed URL again!
            songDao.setThumbnailPath(songId, "none")
        }
    }

    /**
     * One-shot pass over every already-enriched song that predates the thumbnail cache.
     * Safe to call repeatedly - only songs missing a thumbnail do any work. Paced with 100ms
     * delays to ensure 0% CPU background impact.
     */
    suspend fun backfillThumbnails() {
        val missing = songDao.getSongsMissingThumbnail()
        if (missing.isEmpty()) return
        for (song in missing) {
            val artUrl = song.albumArtUrl
            if (artUrl.isNullOrBlank()) {
                songDao.setThumbnailPath(song.telegramMessageId, "none")
                continue
            }
            ensureThumbnail(song.telegramMessageId, artUrl)
            delay(100)
        }
    }

    /** Fetches lyrics on-demand for a song - only ever called from the user tapping the Lyrics
     * button in Now Playing, never automatically during sync. See enrichMissingMetadata()
     * above, which deliberately skips lyrics for exactly that reason. */
    suspend fun fetchLyricsForSong(song: SongEntity): LyricsResult? {
        val title = song.title
        val artist = song.artist
        if (title.isBlank() || title == "Unknown title") return null

        val lyrics = lyricsRepository.fetchLyrics(
            title, artist, song.durationSeconds.takeIf { it > 0 }
        )

        if (lyrics != null && (lyrics.plain != null || lyrics.synced != null)) {
            songDao.update(
                song.copy(
                    lyricsPlain = lyrics.plain ?: song.lyricsPlain,
                    lyricsSynced = lyrics.synced ?: song.lyricsSynced
                )
            )
        }
        return lyrics
    }

    /** EXPLICIT download only - triggered by the download button. Sets isExplicitDownload =
     * true, meaning enforceCacheLimit() will never touch this file. A local import is already
     * fully on-device - nothing to download, and there's no real Telegram file behind it to ask
     * TDLib for. A YouTube-sourced streamable row (youtubeVideoId set, no localFilePath yet -
     * see importPlaylistTrackAsStreamable) goes through yt-dlp instead of TDLib, same real
     * download [importDownloadedSong] already does for a search-result row. */
    suspend fun downloadExplicitly(song: SongEntity): String {
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
        songDao.update(
            song.copy(
                telegramFileId = freshFileId, localFilePath = path, isExplicitDownload = true,
                exportedFileUri = exportedUri?.toString() ?: song.exportedFileUri
            )
        )
        return path
    }

    /** Removes a single song from the library entirely - the row's own "Clear song" menu item.
     * Unlike [removeDownload] (which, for a Telegram-sourced download, only strips the local
     * file and reverts the row back to streaming), this always deletes the row itself, plus any
     * local copy it has - an auto-cached stream, an explicit download, or a local import's own
     * private copy - and any exported shared-storage copy, so nothing orphaned is left behind. */
    suspend fun clearSong(song: SongEntity) {
        song.localFilePath?.let(::releaseLocalFile)
        song.exportedFileUri?.let { uri -> runCatching { mediaFolderExporter.delete(android.net.Uri.parse(uri)) } }
        songDao.delete(song.telegramMessageId)
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
        song.localFilePath?.let(::releaseLocalFile)
        song.exportedFileUri?.let { uri -> mediaFolderExporter.delete(android.net.Uri.parse(uri)) }
        if (song.youtubeVideoId != null || song.telegramFileId != 0) {
            songDao.update(song.copy(localFilePath = null, isExplicitDownload = false, exportedFileUri = null))
        } else {
            songDao.delete(song.telegramMessageId)
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
                // Always sync to TDLib's real current path, not just when it was null - a song
                // cancelled+deleted mid-stream (see TdlibManager.cancelDownload's own doc) and
                // later replayed to a fresh completion still had its OLD, now-deleted path
                // sitting in the DB, so the "only if null" version of this check silently left
                // that fresh file's real path unrecorded forever - orphaned on disk, invisible to
                // enforceCacheLimit()'s DB-driven accounting no matter how correctly it ran.
                if (current.localFilePath != progress.local.path) {
                    songDao.update(current.copy(localFilePath = progress.local.path))
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
    data class CacheClearResult(val cachedSongs: Int, val partialFiles: Int, val freedBytes: Long)

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
            songDao.update(song.copy(localFilePath = null))
            tdlibManager.forgetDownload(song.telegramFileId)
            count++
        }
        val (partial, partialBytes) = purgePartialAudioFiles()
        val (ytLeftovers, ytBytes) = purgeYouTubeDownloadLeftovers(everything = false)
        return CacheClearResult(count, partial + ytLeftovers, freed + partialBytes + ytBytes)
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
            song.localFilePath?.let(::releaseLocalFile)
            song.exportedFileUri?.let { uri -> runCatching { mediaFolderExporter.delete(android.net.Uri.parse(uri)) } }
            songDao.delete(song.telegramMessageId)
        }
        val playlists = playlistDao.observeAll().firstOrNull().orEmpty()
        for (pl in playlists) {
            playlistDao.delete(pl.id)
        }
        settingsStore.lastSyncedChatId = 0L
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

    suspend fun stampLastPlayed(song: SongEntity) = songDao.stampLastPlayed(song.telegramMessageId, System.currentTimeMillis())

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
                    songDao.update(song.copy(telegramFileId = freshId))
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
                songDao.update(song.copy(telegramFileId = freshId, resolvedChatId = channelId))
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
            songDao.update(song.copy(telegramFileId = freshId!!, resolvedChatId = chatId))
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
            songDao.update(song.copy(localFilePath = null))
            tdlibManager.forgetDownload(song.telegramFileId)
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