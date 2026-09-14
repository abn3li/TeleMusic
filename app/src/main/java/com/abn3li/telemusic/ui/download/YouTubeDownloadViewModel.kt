package com.abn3li.telemusic.ui.download

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abn3li.telemusic.data.download.DownloadQuality
import com.abn3li.telemusic.data.download.YtDlpRepository
import com.abn3li.telemusic.data.download.YtDlpSearchResult
import com.abn3li.telemusic.data.download.ytDlpStableSongId
import com.abn3li.telemusic.data.settings.AppSettingsStore
import com.abn3li.telemusic.repository.MusicRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** A download in progress between the row's Download tap and it actually starting - carries the
 * quality along so it survives a folder-prompt round trip in between. */
data class PendingDownload(val result: YtDlpSearchResult, val quality: DownloadQuality)

data class YouTubeDownloadUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val results: List<YtDlpSearchResult> = emptyList(),
    val errorMessage: String? = null,
    // Both keyed by videoId - a result mid-download shows a spinner in place of its download
    // icon, and a finished one shows a checkmark instead, without needing a full re-search.
    val downloadingIds: Set<String> = emptySet(),
    val downloadedIds: Set<String> = emptySet(),
    // The quality dialog's own default selection - last quality actually used, not just tapped.
    val lastUsedQuality: DownloadQuality = DownloadQuality.BEST,
    // The row whose Download icon was just tapped - the screen reacts by showing the quality
    // picker dialog for exactly this result. Null the rest of the time.
    val qualityPickerResult: YtDlpSearchResult? = null,
    // Set the moment a download is confirmed but no folder is saved yet - the screen reacts by
    // launching the system folder picker. The result+quality waiting behind that prompt is kept
    // here rather than re-requested, so picking a folder resumes the exact song the user tapped.
    val pendingFolderPrompt: PendingDownload? = null
)

/**
 * Backs the "search a song by name, download it" screen - the Seal-style flow the user asked
 * for, built on real yt-dlp (see data/download/YtDlpService) rather than a hand-rolled Innertube
 * client. A search is a single explicit action (the search action/button), not live-as-you-type -
 * each one is a real network call into yt-dlp, not free to fire on every keystroke.
 */
class YouTubeDownloadViewModel(
    private val context: Context,
    private val ytDlpRepository: YtDlpRepository,
    private val musicRepository: MusicRepository,
    private val settingsStore: AppSettingsStore
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        YouTubeDownloadUiState(lastUsedQuality = DownloadQuality.fromStoredName(settingsStore.downloadQuality))
    )
    val uiState: StateFlow<YouTubeDownloadUiState> = _uiState

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query) }
    }

    fun search() {
        val query = _uiState.value.query.trim()
        if (query.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true, errorMessage = null) }
            val results = ytDlpRepository.search(query)
            _uiState.update {
                it.copy(
                    isSearching = false,
                    results = results,
                    errorMessage = if (results.isEmpty()) "No results - check your connection" else null
                )
            }
        }
    }

    /** Entry point from a row's Download tap - opens the quality picker dialog for that result
     * rather than downloading immediately, so quality is chosen per-download. */
    fun onDownloadIconClick(result: YtDlpSearchResult) {
        if (result.videoId in _uiState.value.downloadingIds || result.videoId in _uiState.value.downloadedIds) return
        _uiState.update { it.copy(qualityPickerResult = result) }
    }

    fun dismissQualityPicker() {
        _uiState.update { it.copy(qualityPickerResult = null) }
    }

    /** The user picked a quality in the dialog and confirmed. The very first download (ever, on
     * this device - no folder saved yet) doesn't start immediately: it parks itself as
     * [YouTubeDownloadUiState.pendingFolderPrompt] so the screen can ask where to save downloads
     * first. Every later confirm - once a folder is saved, or after Skip - goes straight to
     * [startDownload]. */
    fun confirmDownload(result: YtDlpSearchResult, quality: DownloadQuality) {
        settingsStore.downloadQuality = quality.name
        _uiState.update { it.copy(qualityPickerResult = null, lastUsedQuality = quality) }
        if (settingsStore.downloadFolderUri == null) {
            _uiState.update { it.copy(pendingFolderPrompt = PendingDownload(result, quality)) }
        } else {
            startDownload(result, quality)
        }
    }

    /** The user picked a folder from the system picker for [pendingFolderPrompt] (or any later
     * "Change" in Settings, which doesn't touch this ViewModel at all). Persists it with a
     * persistable permission grant - without that grant, the URI stops working the moment this
     * process dies, since a plain SAF grant is only good for the activity result's own lifetime. */
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

    /** User dismissed the folder prompt without picking one - the download still proceeds, it
     * just won't have a visible shared-storage copy until a folder is set (in Settings or by
     * downloading again later). */
    fun skipFolderPrompt() {
        resumePendingDownload()
    }

    private fun resumePendingDownload() {
        val pending = _uiState.value.pendingFolderPrompt ?: return
        _uiState.update { it.copy(pendingFolderPrompt = null) }
        startDownload(pending.result, pending.quality)
    }

    private fun startDownload(result: YtDlpSearchResult, quality: DownloadQuality) {
        viewModelScope.launch {
            _uiState.update { it.copy(downloadingIds = it.downloadingIds + result.videoId) }
            val destDir = File(context.filesDir, "youtube_downloads")
            val songId = ytDlpStableSongId(result.videoId)
            val outcome = ytDlpRepository.download(result.videoId, destDir, songId.toString(), quality.formatSelector)
            outcome.onSuccess { downloaded ->
                musicRepository.importDownloadedSong(downloaded, songId)
                // Turns the real YouTube thumbnail URL already on the row into a cached
                // thumbnailPath - the row is stored pre-enriched (see importDownloadedSong's own
                // doc), so this artwork backfill pass is the only enrichment it still needs.
                musicRepository.backfillThumbnails()
            }
            _uiState.update {
                it.copy(
                    downloadingIds = it.downloadingIds - result.videoId,
                    downloadedIds = if (outcome.isSuccess) it.downloadedIds + result.videoId else it.downloadedIds,
                    errorMessage = outcome.exceptionOrNull()?.let { e -> "Couldn't download \"${result.title}\": ${e.message}" }
                )
            }
        }
    }
}
