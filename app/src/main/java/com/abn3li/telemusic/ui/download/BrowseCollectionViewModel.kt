package com.abn3li.telemusic.ui.download

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abn3li.telemusic.data.browse.BrowseCollection
import com.abn3li.telemusic.data.browse.BrowseTrack
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
import kotlinx.coroutines.launch
import java.io.File

data class BrowseCollectionUiState(
    val title: String,
    val isLoading: Boolean = true,
    val tracks: List<BrowseTrack> = emptyList(),
    val collections: List<BrowseCollection> = emptyList(),
    val errorMessage: String? = null,
    val downloadingIds: Set<String> = emptySet(),
    val downloadedIds: Set<String> = emptySet(),
    // Same idea as downloadingIds - a row streams (not downloads) while its videoId is in here.
    val loadingStreamIds: Set<String> = emptySet(),
    // Same folder-prompt flow YouTubeDownloadViewModel uses - kept here too since a user could
    // reach a downloadable track from Discovery without ever visiting the search screen first.
    // Non-null while "Import to Library" is running - (done, total) so the button can show real
    // progress instead of just a spinner, since downloading a whole playlist track-by-track can
    // take a while. Set back to null when it finishes (success or failure alike).
    val importProgress: Pair<Int, Int>? = null,
    // The real library playlist this collection became, once imported - lets the button switch
    // to "Open in Library" instead of staying an inert "Imported" label forever.
    val importedPlaylistId: Long? = null
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
    private val onPlayStream: (SongEntity, Uri, String) -> Unit
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
                    // Not "check your connection" - the request almost always succeeds fine
                    // (see DiscoveryRepository.browse's own diagnostic logging); an empty result
                    // here is far more often the page genuinely having nothing playable, or a
                    // page shape this app doesn't parse, than a network failure.
                    errorMessage = if (content.tracks.isEmpty() && content.collections.isEmpty()) {
                        "This playlist couldn't be loaded - it may be unavailable"
                    } else null
                )
            }
        }
    }

    /** Entry point from a row's Play tap - see YouTubeDownloadViewModel.onPlayClick's own doc,
     * this is the identical flow for a Discovery/playlist track instead of a search result. */
    fun onPlayClick(track: BrowseTrack) {
        if (track.videoId in _uiState.value.loadingStreamIds) return
        viewModelScope.launch {
            _uiState.update { it.copy(loadingStreamIds = it.loadingStreamIds + track.videoId, errorMessage = null) }
            val outcome = ytDlpRepository.resolveStreamUrl(track.videoId, DownloadQuality.BEST.formatSelector)
            val stream = outcome.getOrNull()?.takeIf { it.streamUrl.isNotBlank() }
            if (stream != null) {
                val song = SongEntity(
                    telegramMessageId = ytDlpStableSongId(track.videoId),
                    telegramFileId = 0,
                    title = stream.title,
                    artist = stream.artist,
                    durationSeconds = stream.durationSeconds,
                    albumArtUrl = stream.thumbnailUrl,
                    isLocalImport = true
                )
                onPlayStream(song, stream.streamUrl.toUri(), track.videoId)
            }
            _uiState.update {
                it.copy(
                    loadingStreamIds = it.loadingStreamIds - track.videoId,
                    errorMessage = if (stream == null) {
                        "Couldn't play \"${track.title}\": ${outcome.exceptionOrNull()?.message ?: "no stream found"}"
                    } else {
                        it.errorMessage
                    }
                )
            }
        }
    }

    /** Entry point from a row's Download tap (after the app-wide download-location prompt, see
     * DownloadLocationGate) - always grabs the best real audio available (see DownloadQuality's
     * own doc). */
    fun onDownloadClick(track: BrowseTrack) {
        if (track.videoId in _uiState.value.downloadingIds || track.videoId in _uiState.value.downloadedIds) return
        startDownload(track)
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
    fun importToLibrary() {
        if (_uiState.value.importProgress != null || _uiState.value.importedPlaylistId != null) return
        val tracks = _uiState.value.tracks
        if (tracks.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(importProgress = 0 to tracks.size) }
            val playlistId = musicRepository.createPlaylist(_uiState.value.title)
            tracks.forEachIndexed { index, track ->
                val song = musicRepository.importPlaylistTrackAsStreamable(track)
                musicRepository.addSongToPlaylist(playlistId, song)
                _uiState.update { it.copy(importProgress = (index + 1) to tracks.size) }
            }
            musicRepository.backfillThumbnails()
            _uiState.update { it.copy(importProgress = null, importedPlaylistId = playlistId) }
        }
    }

    private fun startDownload(track: BrowseTrack) {
        viewModelScope.launch {
            _uiState.update { it.copy(downloadingIds = it.downloadingIds + track.videoId) }
            val destDir = File(context.filesDir, "youtube_downloads")
            val songId = ytDlpStableSongId(track.videoId)
            val outcome = ytDlpRepository.download(track.videoId, destDir, songId.toString(), DownloadQuality.BEST.formatSelector)
            outcome.onSuccess { downloaded ->
                musicRepository.importDownloadedSong(downloaded, songId, track.videoId)
                musicRepository.backfillThumbnails()
            }
            _uiState.update {
                it.copy(
                    downloadingIds = it.downloadingIds - track.videoId,
                    downloadedIds = if (outcome.isSuccess) it.downloadedIds + track.videoId else it.downloadedIds,
                    errorMessage = outcome.exceptionOrNull()?.let { e -> "Couldn't download \"${track.title}\": ${e.message}" }
                )
            }
        }
    }
}
