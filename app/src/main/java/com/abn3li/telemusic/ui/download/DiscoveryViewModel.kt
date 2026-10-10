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
    // Discover's categories (cached by DiscoveryRepository, so a second visit is instant).
    val isLoading: Boolean = true,
    val genres: List<BrowseCollection> = emptyList(),
    // The user's pinned-by-URL playlists, straight from Room: an import or remove shows up here
    // on its own.
    val importedPlaylists: List<ImportedPlaylistEntity> = emptyList(),
    val importPlaylistError: String? = null
)

/** Discover's categories and playlists imported by link. */
class DiscoveryViewModel(
    private val ytDlpRepository: YtDlpRepository,
    private val discoveryRepository: DiscoveryRepository
) : ViewModel() {
    // Starts with the categories when they're already in memory (they are, from app start on any
    // phone that has loaded them once), so opening Discover shows them at once - no spinner.
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

    /** Fetches genres. Discover keeps this ViewModel while you switch tabs,
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
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val section: HomeSection? = null,
    // Listener-made playlists for Home; null until read, empty when the region has none.
    val community: HomeSection? = null,
    // Signed in to YouTube Music: that account's own Home shelves (null until read).
    val personal: List<com.abn3li.telemusic.data.browse.HomeShelf>? = null,
    val account: com.abn3li.telemusic.data.youtube.YouTubeAccountState = com.abn3li.telemusic.data.youtube.YouTubeAccountState()
)

/** Owns the remote shelves displayed on Home: New releases and community playlists, or - signed
 * in to YouTube Music - that account's own shelves. */
class NewReleasesViewModel(
    private val discoveryRepository: DiscoveryRepository,
    private val account: com.abn3li.telemusic.data.youtube.YouTubeAccount,
    private val settings: com.abn3li.telemusic.data.settings.AppSettingsStore
) : ViewModel() {
    private val _uiState = MutableStateFlow(NewReleasesUiState(
        account = account.state.value,
        personal = if (account.state.value.signedIn) discoveryRepository.personalHomeIfLoaded() else null
    ))
    val uiState: StateFlow<NewReleasesUiState> = _uiState

    private var loadJob: Job? = null
    private var communityJob: Job? = null
    private var personalJob: Job? = null
    private var activeFeedLoads = 0
    private var feedEnabled = settings.homeFeeds.value.youtube

    init {
        retryIfMissing()
        viewModelScope.launch {
            settings.homeFeeds.collect { feeds ->
                feedEnabled = feeds.youtube
                if (feedEnabled) retryIfMissing()
                else {
                    loadJob?.cancel()
                    communityJob?.cancel()
                    personalJob?.cancel()
                }
            }
        }
        // Signing in or out swaps Home's YouTube shelves: what was read for the old account goes.
        viewModelScope.launch {
            var previousSession = _uiState.value.account.sessionId
            var firstEmission = true
            account.state.collect { state ->
                _uiState.update { it.copy(account = state) }
                if (previousSession != state.sessionId) {
                    personalJob?.cancel()
                    communityJob?.cancel()
                    loadJob?.cancel()
                    _uiState.update { it.copy(personal = null, community = null, section = null, isRefreshing = false) }
                }
                if (state.signedIn && state.name == null && (firstEmission || previousSession != state.sessionId)) loadProfile()
                firstEmission = false
                previousSession = state.sessionId
                retryIfMissing()
            }
        }
    }

    private fun loadProfile() {
        viewModelScope.launch {
            val session = account.state.value.sessionId
            val (name, photo) = discoveryRepository.accountProfile()
            if (name != null || photo != null) account.setProfile(name, photo, session)
        }
    }

    /** Called only at entry/resume or when feeds are enabled; never starts a background timer. */
    fun onHomeVisible() = retryIfMissing()

    /** Pull refresh bypasses the cache and waits for active requests, keeping existing shelves. */
    suspend fun refreshHome() {
        if (!feedEnabled) return
        retryIfMissing(force = true)
        personalJob?.join()
        loadJob?.join()
        communityJob?.join()
    }

    // Public Home loads two shelves independently. Keep the indicator until both requests
    // finish, and clear it on cancellation or failure as well as success.
    private fun launchFeedLoad(load: suspend () -> Unit): Job = viewModelScope.launch {
        activeFeedLoads++
        _uiState.update { it.copy(isLoading = true) }
        try {
            load()
        } finally {
            activeFeedLoads--
            _uiState.update { it.copy(isLoading = activeFeedLoads > 0) }
        }
    }

    fun retryIfMissing(force: Boolean = false) {
        if (!feedEnabled) return
        if (_uiState.value.account.signedIn &&
            (force || _uiState.value.personal == null || discoveryRepository.personalHomeIsStale()) &&
            personalJob?.isActive != true) {
            personalJob = launchFeedLoad {
                val session = account.state.value.sessionId
                _uiState.update { it.copy(isRefreshing = true) }
                try {
                    val shelves = discoveryRepository.personalHome(force)
                    if (session == account.state.value.sessionId && shelves != null) {
                        _uiState.update { it.copy(personal = shelves) }
                    }
                } finally {
                    if (session == account.state.value.sessionId) _uiState.update { it.copy(isRefreshing = false) }
                }
            }
        }
        // Signed-in Home uses only the account's own shelves; public defaults return on sign-out.
        if (_uiState.value.account.signedIn) return
        if ((force || _uiState.value.section == null) && loadJob?.isActive != true) {
            loadJob = launchFeedLoad {
                val session = account.state.value.sessionId
                val section = discoveryRepository.newReleases(force)
                if (session == account.state.value.sessionId) {
                    _uiState.update { it.copy(section = section ?: it.section) }
                }
            }
        }
        if ((force || _uiState.value.community == null) && communityJob?.isActive != true) {
            communityJob = launchFeedLoad {
                val session = account.state.value.sessionId
                val community = discoveryRepository.communityPlaylists(force)
                if (session == account.state.value.sessionId) _uiState.update { it.copy(community = community ?: it.community) }
            }
        }
    }
}
