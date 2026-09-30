package com.abn3li.telemusic.ui.download

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abn3li.telemusic.data.browse.BrowseCollection
import com.abn3li.telemusic.data.browse.HomeSection
import com.abn3li.telemusic.data.download.YtDlpRepository
import com.abn3li.telemusic.data.local.ImportedPlaylistEntity
import com.abn3li.telemusic.repository.DiscoveryRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DiscoveryUiState(
    // Search browse content (cached by DiscoveryRepository, so a second visit is instant).
    val isLoading: Boolean = true,
    val genres: List<BrowseCollection> = emptyList(),
    // The user's pinned-by-URL playlists, straight from Room: an import or remove shows up here
    // on its own.
    val importedPlaylists: List<ImportedPlaylistEntity> = emptyList(),
    val importPlaylistError: String? = null
)

/** The Search tab's categories and playlists imported by link. */
class DiscoveryViewModel(
    private val ytDlpRepository: YtDlpRepository,
    private val discoveryRepository: DiscoveryRepository
) : ViewModel() {
    // Starts with the categories when they're already in memory (they are, from app start on any
    // phone that has loaded them once), so opening Search shows them at once - no spinner.
    private val _uiState = MutableStateFlow(
        discoveryRepository.genresIfLoaded().let { DiscoveryUiState(isLoading = it == null, genres = it.orEmpty()) }
    )
    val uiState: StateFlow<DiscoveryUiState> = _uiState
    // Above init: init starts the first load, and a later initializer would wipe this back to null.
    private var loadJob: Job? = null

    init {
        if (_uiState.value.isLoading) load()
        viewModelScope.launch {
            discoveryRepository.observeImportedPlaylists().collect { imported ->
                _uiState.update { it.copy(importedPlaylists = imported) }
            }
        }
    }

    /** Fetches genres. The Search tab keeps this ViewModel while you switch tabs,
     * so a start without a connection is retried from the error message ([reload]). */
    private fun load() {
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val genres = discoveryRepository.genres()
            _uiState.update { it.copy(isLoading = false, genres = genres) }
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

data class NewReleasesUiState(
    val isLoading: Boolean = true,
    val section: HomeSection? = null,
    // Listener-made playlists for Home; null until read, empty when the region has none.
    val community: HomeSection? = null
)

/** Owns the remote shelves displayed on Home: New releases and community playlists. */
class NewReleasesViewModel(
    private val discoveryRepository: DiscoveryRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(NewReleasesUiState())
    val uiState: StateFlow<NewReleasesUiState> = _uiState

    private var loadJob: Job? = null
    private var communityJob: Job? = null

    init { retryIfMissing() }

    /** Loads each shelf that isn't there yet - called on each visit to Home, so one that failed
     * (offline start) shows up once the connection is back. Nothing runs once both have loaded. */
    fun retryIfMissing() {
        if (_uiState.value.section == null && loadJob?.isActive != true) {
            loadJob = viewModelScope.launch {
                val section = discoveryRepository.newReleases()
                _uiState.update { it.copy(isLoading = false, section = section) }
            }
        }
        if (_uiState.value.community == null && communityJob?.isActive != true) {
            communityJob = viewModelScope.launch {
                val community = discoveryRepository.communityPlaylists()
                _uiState.update { it.copy(community = community) }
            }
        }
    }
}
