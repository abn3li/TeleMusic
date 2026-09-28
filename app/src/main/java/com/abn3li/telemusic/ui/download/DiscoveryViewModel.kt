package com.abn3li.telemusic.ui.download

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abn3li.telemusic.data.browse.BrowseCollection
import com.abn3li.telemusic.data.browse.HomeSection
import com.abn3li.telemusic.data.download.YtDlpRepository
import com.abn3li.telemusic.data.local.ImportedPlaylistEntity
import com.abn3li.telemusic.repository.DiscoveryRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DiscoveryUiState(
    // YouTube Music's own home shelves and genres (cached by DiscoveryRepository, so a second
    // visit is instant).
    val isLoading: Boolean = true,
    val sections: List<HomeSection> = emptyList(),
    val genres: List<BrowseCollection> = emptyList(),
    // The user's pinned-by-URL playlists, straight from Room: an import or remove shows up here
    // on its own.
    val importedPlaylists: List<ImportedPlaylistEntity> = emptyList(),
    val importPlaylistError: String? = null
)

/** The Search tab's YouTube Music feed: its shelves, genres, and playlists imported by link. */
class DiscoveryViewModel(
    private val ytDlpRepository: YtDlpRepository,
    private val discoveryRepository: DiscoveryRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(DiscoveryUiState())
    val uiState: StateFlow<DiscoveryUiState> = _uiState
    // Above init: init starts the first load, and a later initializer would wipe this back to null.
    private var loadJob: Job? = null

    init {
        load()
        viewModelScope.launch {
            discoveryRepository.observeImportedPlaylists().collect { imported ->
                _uiState.update { it.copy(importedPlaylists = imported) }
            }
        }
    }

    /** Fetches the feed and genres. The Search tab keeps this ViewModel while you switch tabs,
     * so a start without a connection is retried from the error message ([reload]). */
    private fun load() {
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            coroutineScope {
                val sections = async { discoveryRepository.homeFeed() }
                val genres = async { discoveryRepository.genres() }
                _uiState.update { it.copy(isLoading = false, sections = sections.await(), genres = genres.await()) }
            }
        }
    }

    fun reload() {
        if (loadJob?.isActive == true) return
        load()
    }

    /** Resolves a pasted playlist URL (via yt-dlp - see fetch_playlist_metadata's own doc) and
     * pins it until the user removes it. [onResult] tells the dialog whether to close (true) or
     * stay open showing [DiscoveryUiState.importPlaylistError] (false). Stored under the same
     * "VL" browseId YouTube Music uses, so it opens through the same BrowseCollectionScreen /
     * DiscoveryRepository.browse() path as any other card. */
    fun importPlaylist(url: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val metadata = ytDlpRepository.fetchPlaylistMetadata(url)
            if (metadata == null) {
                _uiState.update { it.copy(importPlaylistError = "Couldn't recognize that as a playlist link") }
                onResult(false)
                return@launch
            }
            val browseId = if (metadata.playlistId.startsWith("VL")) metadata.playlistId else "VL${metadata.playlistId}"
            discoveryRepository.saveImportedPlaylist(
                ImportedPlaylistEntity(
                    browseId = browseId,
                    title = metadata.title,
                    subtitle = metadata.subtitle,
                    thumbnailUrl = metadata.thumbnailUrl
                )
            )
            _uiState.update { it.copy(importPlaylistError = null) }
            onResult(true)
        }
    }

    fun removeImportedPlaylist(playlist: ImportedPlaylistEntity) {
        viewModelScope.launch { discoveryRepository.removeImportedPlaylist(playlist.browseId) }
    }

    fun clearImportPlaylistError() {
        _uiState.update { it.copy(importPlaylistError = null) }
    }
}
