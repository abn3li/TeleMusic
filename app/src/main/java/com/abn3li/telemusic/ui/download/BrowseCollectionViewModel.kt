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
    val pendingFolderPrompt: BrowseTrack? = null
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
    private val onPlayStream: (SongEntity, Uri) -> Unit
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
                onPlayStream(song, stream.streamUrl.toUri())
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

    /** Entry point from a row's Download tap - always grabs the best real audio available (see
     * DownloadQuality's own doc), no quality picker any more. Matches the search screen's own
     * folder-prompt flow otherwise. */
    fun onDownloadClick(track: BrowseTrack) {
        if (track.videoId in _uiState.value.downloadingIds || track.videoId in _uiState.value.downloadedIds) return
        if (settingsStore.downloadFolderUri == null) {
            _uiState.update { it.copy(pendingFolderPrompt = track) }
        } else {
            startDownload(track)
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
        startDownload(pending)
    }

    private fun startDownload(track: BrowseTrack) {
        viewModelScope.launch {
            _uiState.update { it.copy(downloadingIds = it.downloadingIds + track.videoId) }
            val destDir = File(context.filesDir, "youtube_downloads")
            val songId = ytDlpStableSongId(track.videoId)
            val outcome = ytDlpRepository.download(track.videoId, destDir, songId.toString(), DownloadQuality.BEST.formatSelector)
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
