package com.abn3li.telemusic.repository

import android.content.Context
import android.net.Uri
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

    // Live mirrors of the three smart playlists' own "Hide from tracks" flags (see
    // AppSettingsStore's own doc - they're settings, not DB rows, so they need their own
    // reactive holder here) - this repository is a single app-wide instance, so a toggle made
    // from a Smart Playlist detail screen is immediately visible to the Tracks tab's own
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

    /** Copies each file into the app's own private storage (never leaves it depending on the
     * picked folder's URI staying valid) and upserts it as a song - see LocalAudioFile's own
     * stableSongId() doc for why re-picking the same file resolves to the same row rather than
     * duplicating. */
    suspend fun importLocalSongs(files: List<LocalAudioFile>) {
        for (file in files) {
            val songId = file.stableSongId()
            val copiedPath = localAudioImporter.importToPrivateStorage(file, songId) ?: continue
            songDao.upsert(
                SongEntity(
                    telegramMessageId = songId,
                    telegramFileId = 0,
                    title = file.title,
                    artist = file.artist,
                    album = file.album,
                    durationSeconds = file.durationSeconds,
                    localFilePath = copiedPath,
                    isLocalImport = true,
                    // Left unenriched on purpose - it already has a real title/artist from the
                    // device's own tags, so enrichMissingMetadata() won't touch those, but it
                    // still has no artwork (nothing extracts embedded cover art from an imported
                    // file), so it still needs that pass to fetch one. Call
                    // enrichMissingMetadata() after this import to fetch it right away rather
                    // than waiting for a Telegram sync that may never come.
                    metadataEnriched = false
                )
            )
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
        return stream.streamUrl.toUri()
    }

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
                    // Song already in library! Update file & message ID without creating duplicate entry
                    songDao.update(
                        existingDuplicate.copy(
                            telegramMessageId = msg.messageId,
                            telegramFileId = msg.fileId
                        )
                    )
                } else {
                    // Truly a new song! Insert into library
                    songDao.upsert(
                        SongEntity(
                            telegramMessageId = msg.messageId,
                            telegramFileId = msg.fileId,
                            title = title,
                            artist = artist,
                            durationSeconds = msg.durationSeconds
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
        for (song in songDao.getUnenriched()) {
            val originalTitle = song.title
            val originalArtist = song.artist

            onProgress("Fetching info for $originalTitle...")

            // A local import always has a real title/artist from its own tags, so the first two
            // checks rarely fire for one - but it never has artwork either (nothing extracts
            // embedded cover art from an imported file), so without that third check a local
            // import that happens to already carry an album tag would never get a lookup at all
            // and would silently stay coverless forever.
            val needsMetadataGuess = originalTitle == "Unknown title" || song.album == null || song.albumArtUrl == null
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

    /** Generates a small local thumbnail for [songId] from [artUrl] if it doesn't have one yet. */
    private suspend fun ensureThumbnail(songId: Long, artUrl: String) {
        thumbnailGenerator.generate(songId, artUrl)?.let { path -> songDao.setThumbnailPath(songId, path) }
    }

    /**
     * One-shot pass over every already-enriched song that predates the thumbnail cache (or
     * whose earlier generation attempt failed). Safe to call repeatedly - only songs missing a
     * thumbnail do any work. Called once from the app's own background scope at startup rather
     * than blocking any particular screen's load.
     */
    suspend fun backfillThumbnails() {
        for (song in songDao.getSongsMissingThumbnail()) {
            val artUrl = song.albumArtUrl ?: continue
            ensureThumbnail(song.telegramMessageId, artUrl)
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
        song.localFilePath?.let { path -> runCatching { File(path).delete() } }
        song.exportedFileUri?.let { uri -> runCatching { mediaFolderExporter.delete(android.net.Uri.parse(uri)) } }
        songDao.delete(song.telegramMessageId)
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
        song.localFilePath?.let { path -> runCatching { File(path).delete() } }
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
        while (true) {
            val progress = tdlibManager.getCachedFileProgress(song.telegramFileId)
            if (progress?.local?.isDownloadingCompleted == true) {
                val current = songDao.getById(song.telegramMessageId) ?: return
                if (current.localFilePath == null) {
                    songDao.update(current.copy(localFilePath = progress.local.path))
                }
                enforceCacheLimit(excludeSongId = song.telegramMessageId)
                return
            }
            delay(1000)
        }
    }

    /** Deletes all auto-cached streaming audio files from disk and resets localFilePath in DB. */
    suspend fun clearStreamingCache(): Int {
        val autoCached = songDao.getAutoCachedSongsOldestFirst()
        var count = 0
        for (song in autoCached) {
            val path = song.localFilePath
            if (path != null) {
                val file = File(path)
                if (file.exists()) file.delete()
                songDao.update(song.copy(localFilePath = null))
                count++
            }
        }
        return count
    }

    /** Deletes EVERY song - Telegram-synced, YouTube-downloaded, and local imports alike - plus
     * playlists and every cached/downloaded/exported audio file on disk, for a 100% fresh start.
     * A local import's localFilePath is the app's own private COPY (see importLocalSongs - the
     * original file the user picked is never touched, it's copied into app storage on import),
     * so deleting it here is exactly as safe as deleting any other row's file. */
    suspend fun clearAllLibrarySongs() {
        val allSongs = songDao.observeAll().firstOrNull().orEmpty()
        for (song in allSongs) {
            val path = song.localFilePath
            if (path != null) {
                val file = File(path)
                if (file.exists()) file.delete()
            }
            song.exportedFileUri?.let { uri -> runCatching { mediaFolderExporter.delete(android.net.Uri.parse(uri)) } }
            songDao.delete(song.telegramMessageId)
        }
        val playlists = playlistDao.observeAll().firstOrNull().orEmpty()
        for (pl in playlists) {
            playlistDao.delete(pl.id)
        }
        settingsStore.lastSyncedChatId = 0L
    }

    suspend fun stampLastPlayed(song: SongEntity) = songDao.stampLastPlayed(song.telegramMessageId, System.currentTimeMillis())

    suspend fun getFreshFileIdForSong(song: SongEntity): Int {
        // No real Telegram message behind a local import OR a YouTube download - telegramFileId
        // == 0 is this app's own general marker for "not really a Telegram row" (see
        // SongEntity's own doc, and the same check at line ~400 in this file). Checking only
        // isLocalImport here missed YouTube downloads, so opening one in Now Playing used to
        // sweep EVERY channel the user is in (2+ TDLib round-trips each) hunting for a message
        // id that could never exist, stalling this song's state update the whole time.
        if (song.isLocalImport || song.telegramFileId == 0) return song.telegramFileId

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
            totalSize -= size
        }
    }
}