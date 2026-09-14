package com.abn3li.telemusic.ui.download

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abn3li.telemusic.data.browse.BrowseCollection
import com.abn3li.telemusic.data.browse.BrowseTrack
import com.abn3li.telemusic.data.download.DownloadQuality
import com.abn3li.telemusic.data.download.YtDlpRepository
import com.abn3li.telemusic.data.download.ytDlpStableSongId
import com.abn3li.telemusic.data.settings.AppSettingsStore
import com.abn3li.telemusic.repository.DiscoveryRepository
import com.abn3li.telemusic.repository.MusicRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** A download in progress between the row's Download tap and it actually starting - carries the
 * quality along so it survives a folder-prompt round trip in between (same shape as
 * YouTubeDownloadViewModel's own PendingDownload, just over a BrowseTrack instead of a
 * YtDlpSearchResult). */
data class PendingBrowseDownload(val track: BrowseTrack, val quality: DownloadQuality)

data class BrowseCollectionUiState(
    val title: String,
    val isLoading: Boolean = true,
    val tracks: List<BrowseTrack> = emptyList(),
    val collections: List<BrowseCollection> = emptyList(),
    val errorMessage: String? = null,
    val downloadingIds: Set<String> = emptySet(),
    val downloadedIds: Set<String> = emptySet(),
    val lastUsedQuality: DownloadQuality = DownloadQuality.BEST,
    // The row whose Download icon was just tapped - the screen reacts by showing the same
    // quality picker dialog the search screen uses (see QualityPickerDialog). Null the rest of
    // the time.
    val qualityPickerTrack: BrowseTrack? = null,
    // Same folder-prompt flow YouTubeDownloadViewModel uses - kept here too since a user could
    // reach a downloadable track from Discovery without ever visiting the search screen first.
    val pendingFolderPrompt: PendingBrowseDownload? = null
)

/** Backs a single browse destination - a playlist's, chart's, or artist's own page reached by
 * tapping a Discovery card. Shares the exact same quality-picker dialog and folder-prompt flow
 * the search screen uses (see QualityPickerDialog and YouTubeDownloadViewModel) rather than a
 * separate copy of either - both are cheap, local-state-only UI, so reusing them costs nothing
 * extra over what a Browse download already does. */
class BrowseCollectionViewModel(
    private val context: Context,
    title: String,
    private val browseId: String,
    private val params: String?,
    private val discoveryRepository: DiscoveryRepository,
    private val ytDlpRepository: YtDlpRepository,
    private val musicRepository: MusicRepository,
    private val settingsStore: AppSettingsStore
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        BrowseCollectionUiState(title = title, lastUsedQuality = DownloadQuality.fromStoredName(settingsStore.downloadQuality))
    )
    val uiState: StateFlow<BrowseCollectionUiState> = _uiState

    init {
        viewModelScope.launch {
            val content = discoveryRepository.browse(browseId, params)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    tracks = content.tracks,
                    collections = content.collections,
                    errorMessage = if (content.tracks.isEmpty() && content.collections.isEmpty()) {
                        "Nothing here - check your connection"
                    } else null
                )
            }
        }
    }

    /** Entry point from a row's Download tap - opens the quality picker dialog rather than
     * downloading immediately, matching the search screen's own flow. */
    fun onDownloadClick(track: BrowseTrack) {
        if (track.videoId in _uiState.value.downloadingIds || track.videoId in _uiState.value.downloadedIds) return
        _uiState.update { it.copy(qualityPickerTrack = track) }
    }

    fun dismissQualityPicker() {
        _uiState.update { it.copy(qualityPickerTrack = null) }
    }

    fun confirmDownload(track: BrowseTrack, quality: DownloadQuality) {
        settingsStore.downloadQuality = quality.name
        _uiState.update { it.copy(qualityPickerTrack = null, lastUsedQuality = quality) }
        if (settingsStore.downloadFolderUri == null) {
            _uiState.update { it.copy(pendingFolderPrompt = PendingBrowseDownload(track, quality)) }
        } else {
            startDownload(track, quality)
        }
    }

    fun onFolderPicked(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        settingsStore.downloadFolderUri = uri.toString()
        resumePendingDownload()
    }

    fun skipFolderPrompt() {
        resumePendingDownload()
    }

    private fun resumePendingDownload() {
        val pending = _uiState.value.pendingFolderPrompt ?: return
        _uiState.update { it.copy(pendingFolderPrompt = null) }
        startDownload(pending.track, pending.quality)
    }

    private fun startDownload(track: BrowseTrack, quality: DownloadQuality) {
        viewModelScope.launch {
            _uiState.update { it.copy(downloadingIds = it.downloadingIds + track.videoId) }
            val destDir = File(context.filesDir, "youtube_downloads")
            val songId = ytDlpStableSongId(track.videoId)
            val outcome = ytDlpRepository.download(track.videoId, destDir, songId.toString(), quality.formatSelector)
            outcome.onSuccess { downloaded ->
                musicRepository.importDownloadedSong(downloaded, songId)
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
