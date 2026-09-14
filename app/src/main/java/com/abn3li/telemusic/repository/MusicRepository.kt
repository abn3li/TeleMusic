package com.abn3li.telemusic.repository

import android.net.Uri
import com.abn3li.telemusic.data.local.AlbumSummary
import com.abn3li.telemusic.data.local.ArtistSummary
import com.abn3li.telemusic.data.local.PlaylistDao
import com.abn3li.telemusic.data.local.PlaylistEntity
import com.abn3li.telemusic.data.local.PlaylistSongCrossRef
import com.abn3li.telemusic.data.local.SongDao
import com.abn3li.telemusic.data.local.SongEntity
import com.abn3li.telemusic.data.download.MediaFolderExporter
import com.abn3li.telemusic.data.settings.AppSettingsStore
import com.abn3li.telemusic.data.telegram.TdlibManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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
    private val mediaFolderExporter: MediaFolderExporter
) {
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
    /** Moves a just-downloaded file (already sitting in its final [YtDlpRepository.download]
     * destination) into a row the rest of the app treats like any other song. Marked as an
     * explicit download (never auto-evicted, shows the "Downloaded" icon) since the whole point
     * of this flow is the user asked for this exact file - see SongEntity.isExplicitDownload's
     * own doc. Left unenriched=false: title/artist already came from yt-dlp's own real metadata,
     * only artwork still needs the usual backfillThumbnails pass to turn albumArtUrl into a
     * cached thumbnailPath. */
    suspend fun importDownloadedSong(result: com.abn3li.telemusic.data.download.YtDlpDownloadResult, songId: Long) {
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
                metadataEnriched = true
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
    fun observeSongsInPlaylist(playlistId: Long): Flow<List<SongEntity>> = playlistDao.observeSongsInPlaylist(playlistId)
    suspend fun createPlaylist(name: String): Long = playlistDao.insert(PlaylistEntity(name = name))
    suspend fun deletePlaylist(playlistId: Long) = playlistDao.delete(playlistId)
    suspend fun addSongToPlaylist(playlistId: Long, song: SongEntity) = playlistDao.addSong(PlaylistSongCrossRef(playlistId, song.telegramMessageId))

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
     * TDLib for. */
    suspend fun downloadExplicitly(song: SongEntity): String {
        if (song.isLocalImport) return song.localFilePath ?: error("Local import ${song.telegramMessageId} has no file path")
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

    /** Removes the local downloaded copy of [song] - only ever called for a song that's actually
     * isExplicitDownload (the row's own "Delete download" menu item only shows up then). Removes
     * BOTH copies: the app-private file AND the exported copy in the user's own shared-storage
     * folder, if one was ever made (see SongEntity.exportedFileUri's own doc) - a Telegram-
     * sourced download then reverts to streaming (the row stays, same as it looked before it was
     * ever downloaded), but a YouTube-sourced download has no other source to fall back to (no
     * live streaming resolver, only download - see data/download's own doc), so removing its
     * only file removes the whole row instead of leaving a dead, unplayable entry behind. */
    suspend fun removeDownload(song: SongEntity) {
        song.localFilePath?.let { path -> runCatching { File(path).delete() } }
        song.exportedFileUri?.let { uri -> mediaFolderExporter.delete(android.net.Uri.parse(uri)) }
        // telegramFileId == 0 marks a row with no real Telegram file behind it - a YouTube
        // download (see importDownloadedSong) is the only isExplicitDownload row that's ever
        // true here, since a local import (the other telegramFileId == 0 case) never becomes an
        // explicit download in the first place - so this check never misfires on one of those.
        if (song.telegramFileId == 0) {
            songDao.delete(song.telegramMessageId)
        } else {
            songDao.update(song.copy(localFilePath = null, isExplicitDownload = false, exportedFileUri = null))
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

    /** Deletes ALL Telegram-synced songs, playlists, and cached audio files from local DB and
     * disk for a 100% fresh sync reset. Local imports are deliberately left untouched - this
     * reset is about Telegram sync state, and their localFilePath is the user's own file on
     * shared storage, never safe to delete. */
    suspend fun clearAllLibrarySongs() {
        val allSongs = songDao.observeAll().firstOrNull().orEmpty()
        for (song in allSongs) {
            if (song.isLocalImport) continue
            val path = song.localFilePath
            if (path != null) {
                val file = File(path)
                if (file.exists()) file.delete()
            }
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
        // No real Telegram message behind a local import - asking TDLib would just burn a
        // lookup per channel for a message id that was never real to begin with.
        if (song.isLocalImport) return song.telegramFileId

        var channelId = settingsStore.lastSyncedChatId
        if (channelId == 0L) {
            val found = tdlibManager.findFirstChannelId()
            if (found != null && found != 0L) {
                channelId = found
                settingsStore.lastSyncedChatId = found
            }
        }

        if (channelId != 0L) {
            val freshId = tdlibManager.getFreshFileId(channelId, song.telegramMessageId)
            if (freshId != null) {
                if (freshId != song.telegramFileId) {
                    songDao.update(song.copy(telegramFileId = freshId))
                }
                return freshId
            }
        }

        val chats = runCatching { tdlibManager.listMyChats().filter { it.isChannel } }.getOrDefault(emptyList())
        for (chat in chats) {
            if (chat.id == channelId) continue
            val freshId = tdlibManager.getFreshFileId(chat.id, song.telegramMessageId)
            if (freshId != null) {
                settingsStore.lastSyncedChatId = chat.id
                if (freshId != song.telegramFileId) {
                    songDao.update(song.copy(telegramFileId = freshId))
                }
                return freshId
            }
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