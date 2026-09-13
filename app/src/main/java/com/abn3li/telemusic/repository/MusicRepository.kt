package com.abn3li.telemusic.repository

import android.net.Uri
import com.abn3li.telemusic.data.local.AlbumSummary
import com.abn3li.telemusic.data.local.ArtistSummary
import com.abn3li.telemusic.data.local.PlaylistDao
import com.abn3li.telemusic.data.local.PlaylistEntity
import com.abn3li.telemusic.data.local.PlaylistSongCrossRef
import com.abn3li.telemusic.data.local.SongDao
import com.abn3li.telemusic.data.local.SongEntity
import com.abn3li.telemusic.data.settings.AppSettingsStore
import com.abn3li.telemusic.data.telegram.TdlibManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import java.io.File

enum class SortField(val label: String) { TITLE("Name"), ARTIST("Artist"), ALBUM("Album"), DATE_ADDED("Date added") }

class MusicRepository(
    private val songDao: SongDao,
    private val playlistDao: PlaylistDao,
    private val tdlibManager: TdlibManager,
    private val lyricsRepository: LyricsRepository,
    private val metadataRepository: MetadataRepository,
    private val settingsStore: AppSettingsStore,
    private val thumbnailGenerator: ThumbnailGenerator,
    private val localAudioImporter: LocalAudioImporter
) {
    // ---- Tracks / Favourites, metadata-driven sort ----
    fun observeLibrary(sortField: SortField, ascending: Boolean): Flow<List<SongEntity>> =
        songDao.observeAll().map { it.sortedByField(sortField, ascending) }

    suspend fun getSongById(id: Long): SongEntity? = songDao.getById(id)
    fun search(query: String): Flow<List<SongEntity>> = songDao.search(query)

    fun observeFavorites(sortField: SortField, ascending: Boolean): Flow<List<SongEntity>> =
        songDao.observeFavorites().map { it.sortedByField(sortField, ascending) }

    suspend fun setFavorite(song: SongEntity, isFavorite: Boolean) = songDao.setFavorite(song.telegramMessageId, isFavorite)

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

    // ---- Albums / Artists - grouped straight from real metadata ----
    fun observeAlbums(): Flow<List<AlbumSummary>> = songDao.observeAlbums()
    fun observeSongsByAlbum(album: String, sortField: SortField, ascending: Boolean) =
        songDao.observeSongsByAlbum(album).map { it.sortedByField(sortField, ascending) }

    fun observeArtists(): Flow<List<ArtistSummary>> = songDao.observeArtists()
    fun observeSongsByArtist(artist: String, sortField: SortField, ascending: Boolean) =
        songDao.observeSongsByArtist(artist).map { it.sortedByField(sortField, ascending) }

    // ---- Playlists ----
    fun observePlaylists(): Flow<List<PlaylistEntity>> = playlistDao.observeAll()
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
        songDao.update(song.copy(telegramFileId = freshFileId, localFilePath = path, isExplicitDownload = true))
        return path
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