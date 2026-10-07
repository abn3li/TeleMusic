package com.abn3li.telemusic.ui.download

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abn3li.telemusic.data.browse.BrowseCollection
import com.abn3li.telemusic.data.browse.BrowseTrack
import com.abn3li.telemusic.data.browse.ArtistPage
import com.abn3li.telemusic.data.browse.CollectionHeader
import com.abn3li.telemusic.data.download.DownloadQuality
import com.abn3li.telemusic.data.download.YtDlpRepository
import com.abn3li.telemusic.data.download.ytDlpStableSongId
import com.abn3li.telemusic.data.local.SongEntity
import com.abn3li.telemusic.data.settings.AppSettingsStore
import com.abn3li.telemusic.repository.DiscoveryRepository
import com.abn3li.telemusic.repository.MusicRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

data class BrowseCollectionUiState(
    val title: String,
    val isLoading: Boolean = true,
    val tracks: List<BrowseTrack> = emptyList(),
    val collections: List<BrowseCollection> = emptyList(),
    // An album's or playlist's own title block, and an artist's whole page - see BrowseContent.
    val header: CollectionHeader? = null,
    val artist: ArtistPage? = null,
    val errorMessage: String? = null,
    val downloadingIds: Set<String> = emptySet(),
    val downloadedIds: Set<String> = emptySet(),
    // Same folder-prompt flow YouTubeDownloadViewModel uses - kept here too since a user could
    // reach a downloadable track from Discovery without ever visiting the search screen first.
    // Non-null while "Import to Library" is running - (done, total) so the button can show real
    // progress instead of just a spinner, since downloading a whole playlist track-by-track can
    // take a while. Set back to null when it finishes (success or failure alike).
    val importProgress: Pair<Int, Int>? = null,
    // The real library playlist this collection became, once imported - lets the button switch
    // to "Open in Library" instead of staying an inert "Imported" label forever.
    val importedPlaylistId: Long? = null,
    // True once the page was saved as an album instead (Library > Albums, no playlist).
    val importedAsAlbum: Boolean = false,
    // A tapped download waiting for a yes: the song is already in the library in better quality.
    val downloadConflict: DownloadConflict? = null
)

/** Backs a single browse destination - a playlist's, chart's, or artist's own page reached by
 * tapping a Discovery card. Shares the exact same folder-prompt flow the search screen uses (see
 * YouTubeDownloadViewModel) rather than a separate copy - that's cheap, local-state-only UI, so
 * reusing it costs nothing extra over what a Browse download already does. */
class BrowseCollectionViewModel(
    private val context: Context,
    title: String,
    private val browseId: String,
    private val params: String?,
    private val discoveryRepository: DiscoveryRepository,
    private val ytDlpRepository: YtDlpRepository,
    private val musicRepository: MusicRepository,
    private val settingsStore: AppSettingsStore,
    // Downloads and Import to Library run here, not in viewModelScope: they must finish even
    // when the screen closes and this ViewModel is cleared - never a half-imported playlist.
    private val workScope: CoroutineScope
) : ViewModel() {
    private val _uiState = MutableStateFlow(BrowseCollectionUiState(title = title))
    val uiState: StateFlow<BrowseCollectionUiState> = _uiState

    init {
        viewModelScope.launch {
            val content = discoveryRepository.browse(browseId, params)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    tracks = content.tracks,
                    collections = content.collections,
                    header = content.header,
                    artist = content.artist,
                    // Not "check your connection" - the request almost always succeeds fine
                    // (see DiscoveryRepository.browse's own diagnostic logging); an empty result
                    // here is far more often the page genuinely having nothing playable, or a
                    // page shape this app doesn't parse, than a network failure.
                    errorMessage = if (content.tracks.isEmpty() && content.collections.isEmpty() && content.artist == null) {
                        "This playlist couldn't be loaded - it may be unavailable"
                    } else null
                )
            }
        }
    }

    /** Entry point from a row's Download tap (after the app-wide download-location prompt, see
     * DownloadLocationGate) - always grabs the best real audio available (see DownloadQuality's
     * own doc). */
    fun onDownloadClick(track: BrowseTrack) {
        if (track.videoId in _uiState.value.downloadingIds || track.videoId in _uiState.value.downloadedIds) return
        viewModelScope.launch {
            val existing = musicRepository.betterCopyInLibrary(track.videoId, track.title, track.artist, track.durationSeconds)
            if (existing != null) {
                _uiState.update {
                    it.copy(downloadConflict = DownloadConflict(track.title, existing.higherQuality) {
                        musicRepository.keepBothCopies(track.videoId)
                        startDownload(track)
                    })
                }
            } else {
                startDownload(track)
            }
        }
    }

    fun dismissDownloadConflict() {
        _uiState.update { it.copy(downloadConflict = null) }
    }


    /** "Import to Library" - turns this whole browse collection into a real, permanent library
     * playlist: creates the playlist, then adds every track as a real, lightweight library row
     * (see MusicRepository.importPlaylistTrackAsStreamable's own doc) - NOT a forced download.
     * Each song plays by streaming on demand, exactly like the search screen's own Play button,
     * and can still be downloaded for real later, one at a time, via the ordinary Download
     * action - importing a whole playlist should be near-instant, not a bulk download the user
     * never asked for. Sequential, not parallel, purely to keep [BrowseCollectionUiState.importProgress]
     * a steadily-advancing count rather than a burst of out-of-order DB writes. Already-imported
     * tracks (re-importing the same playlist, or a track shared with another one) are added to
     * the new playlist as-is, untouched. */
    fun importToLibrary(asAlbum: Boolean = false) {
        val current = _uiState.value
        if (current.importProgress != null || current.importedPlaylistId != null || current.importedAsAlbum) return
        val tracks = current.tracks
        if (tracks.isEmpty()) return
        val name = current.header?.title ?: current.title
        _uiState.update { it.copy(importProgress = 0 to tracks.size, errorMessage = null) }
        workScope.launch {
            try {
                if (asAlbum) {
                    // Each song gets this album's name, so they group under Library > Albums.
                    tracks.forEachIndexed { index, track ->
                        musicRepository.importPlaylistTrackAsStreamable(track, name)
                        _uiState.update { it.copy(importProgress = (index + 1) to tracks.size) }
                    }
                    musicRepository.backfillThumbnails()
                    musicRepository.mergeCrossSourceDuplicates()
                    _uiState.update { it.copy(importedAsAlbum = true) }
                    return@launch
                }
                val playlistId = unfinishedPlaylistId?.takeIf { musicRepository.playlistExists(it) }
                    ?: musicRepository.createPlaylist(name).also {
                        unfinishedPlaylistId = it
                        unfinishedPlaylistStartedAt = System.currentTimeMillis()
                    }
                tracks.forEachIndexed { index, track ->
                    val song = musicRepository.importPlaylistTrackAsStreamable(track)
                    musicRepository.addSongToPlaylistAt(playlistId, song.telegramMessageId, unfinishedPlaylistStartedAt - index)
                    _uiState.update { it.copy(importProgress = (index + 1) to tracks.size) }
                }
                musicRepository.backfillThumbnails()
                musicRepository.mergeCrossSourceDuplicates()
                _uiState.update { it.copy(importedPlaylistId = playlistId) }
                unfinishedPlaylistId = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Couldn't finish importing: ${e.message}") }
            } finally {
                _uiState.update { it.copy(importProgress = null) }
            }
        }
    }

    // Retry a partly imported playlist rather than creating another copy in the same screen.
    private var unfinishedPlaylistId: Long? = null
    private var unfinishedPlaylistStartedAt = 0L

    private fun startDownload(track: BrowseTrack) {
        workScope.launch {
            if (track.videoId in _uiState.value.downloadingIds || track.videoId in _uiState.value.downloadedIds) return@launch
            _uiState.update { it.copy(downloadingIds = it.downloadingIds + track.videoId) }
            try {
                val destDir = File(context.filesDir, "youtube_downloads")
                val songId = ytDlpStableSongId(track.videoId)
                val downloaded = ytDlpRepository.download(track.videoId, destDir, songId.toString(), DownloadQuality.BEST.formatSelector).getOrThrow()
                musicRepository.importDownloadedSong(downloaded, songId, track.videoId)
                _uiState.update { it.copy(downloadedIds = it.downloadedIds + track.videoId) }
                musicRepository.backfillThumbnails()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Couldn't save \"${track.title}\": ${e.message}") }
            } finally {
                _uiState.update { it.copy(downloadingIds = it.downloadingIds - track.videoId) }
            }
        }
    }
}
