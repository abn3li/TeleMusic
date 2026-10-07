package com.abn3li.telemusic.ui.download

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abn3li.telemusic.data.browse.BrowseCollection
import com.abn3li.telemusic.data.browse.SearchFilter
import com.abn3li.telemusic.data.download.DownloadQuality
import com.abn3li.telemusic.data.download.YtDlpRepository
import com.abn3li.telemusic.data.download.YtDlpSearchResult
import com.abn3li.telemusic.data.download.ytDlpStableSongId
import com.abn3li.telemusic.data.local.SongEntity
import com.abn3li.telemusic.data.settings.AppSettingsStore
import com.abn3li.telemusic.repository.DiscoveryRepository
import com.abn3li.telemusic.repository.MusicRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

data class YouTubeDownloadUiState(
    val query: String = "",
    // The query the results below belong to; differs from [query] while a new search waits to run.
    val searchedQuery: String = "",
    val isSearching: Boolean = false,
    val results: List<YtDlpSearchResult> = emptyList(),
    // The other tabs' results for the same search - pages to open, not songs.
    val albums: List<BrowseCollection> = emptyList(),
    val artists: List<BrowseCollection> = emptyList(),
    val playlists: List<BrowseCollection> = emptyList(),
    val errorMessage: String? = null,
    // Why the last Download failed - kept apart from the search's own message, so it
    // shows in every tab rather than only when the Songs list is empty.
    val actionError: String? = null,
    // Both keyed by videoId - a result mid-download shows a spinner in place of its download
    // icon, and a finished one shows a checkmark instead, without needing a full re-search.
    val downloadingIds: Set<String> = emptySet(),
    val downloadedIds: Set<String> = emptySet(),
    // A tapped download waiting for a yes: the song is already in the library in better quality.
    val downloadConflict: DownloadConflict? = null
)

/** A YouTube download the user is asked about first - see YouTubeDownloadUiState.downloadConflict. */
data class DownloadConflict(val title: String, val higherQuality: Boolean, val confirm: () -> Unit)

/**
 * The YouTube half of the search page: finds songs, albums, artists and playlists, and plays
 * (streams) or downloads a song. Each search is several network calls, so the page asks for one
 * only once typing pauses, not on every key.
 */
class YouTubeDownloadViewModel(
    private val context: Context,
    private val ytDlpRepository: YtDlpRepository,
    private val musicRepository: MusicRepository,
    private val settingsStore: AppSettingsStore,
    private val discoveryRepository: DiscoveryRepository,
    // Downloads run here, not in viewModelScope: they must finish (and land in the library)
    // even when the screen closes and this ViewModel is cleared.
    private val workScope: CoroutineScope
) : ViewModel() {
    private val _uiState = MutableStateFlow(YouTubeDownloadUiState())
    val uiState: StateFlow<YouTubeDownloadUiState> = _uiState

    fun onQueryChange(query: String) {
        if (query.isBlank()) {
            // Cleared: drop the old results, so the next search starts clean.
            searchJob?.cancel()
            _uiState.update {
                it.copy(query = query, searchedQuery = "", isSearching = false, results = emptyList(), albums = emptyList(), artists = emptyList(), playlists = emptyList(), errorMessage = null)
            }
        } else {
            _uiState.update { it.copy(query = query) }
        }
    }

    // The search in flight: a new one cancels it, so an older, slower search can't finish last
    // and put its results under the newer query.
    private var searchJob: Job? = null

    /** One search fills every tab at once - songs, albums, artists and playlists are asked in
     * parallel, so switching tabs afterwards is instant. Songs come from YouTube Music's own
     * Songs tab (one request, square album art); if that finds nothing, the older yt-dlp search
     * is tried instead. The search page calls it on its own once typing
     * pauses, and again ([force]) from the keyboard's Search key; without [force] a query that's
     * already searched (or being searched) isn't asked again. */
    fun search(force: Boolean = false) {
        val query = _uiState.value.query.trim()
        if (query.isEmpty()) return
        if (!force && query == _uiState.value.searchedQuery) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _uiState.update {
                it.copy(searchedQuery = query, isSearching = true, errorMessage = null, results = emptyList(), albums = emptyList(), artists = emptyList(), playlists = emptyList())
            }
            coroutineScope {
                val songs = async {
                    discoveryRepository.searchSongs(query)
                        .map { YtDlpSearchResult(it.videoId, it.title, it.artist, it.durationSeconds, it.thumbnailUrl) }
                        .ifEmpty { ytDlpRepository.search(query) }
                }
                val albums = async { discoveryRepository.searchCollections(query, SearchFilter.ALBUMS) }
                val artists = async { discoveryRepository.searchCollections(query, SearchFilter.ARTISTS) }
                val playlists = async { discoveryRepository.searchCollections(query, SearchFilter.PLAYLISTS) }
                val found = songs.await()
                _uiState.update {
                    it.copy(
                        isSearching = false,
                        results = found,
                        albums = albums.await(),
                        artists = artists.await(),
                        playlists = playlists.await(),
                        errorMessage = if (found.isEmpty()) "No songs found" else null
                    )
                }
            }
        }
    }

    /** Entry point from a row's Download tap (after the app-wide download-location prompt, see
     * DownloadLocationGate) - always grabs the best real audio yt-dlp/YouTube can offer (Opus
     * preferred, see DownloadQuality's own doc). */
    fun onDownloadIconClick(result: YtDlpSearchResult) {
        if (result.videoId in _uiState.value.downloadingIds || result.videoId in _uiState.value.downloadedIds) return
        viewModelScope.launch {
            // Already in the library in better quality: ask before downloading a worse copy.
            val existing = musicRepository.betterCopyInLibrary(result.videoId, result.title, result.artist, result.durationSeconds)
            if (existing != null) {
                _uiState.update {
                    it.copy(downloadConflict = DownloadConflict(result.title, existing.higherQuality) {
                        musicRepository.keepBothCopies(result.videoId)
                        startDownload(result)
                    })
                }
            } else {
                startDownload(result)
            }
        }
    }

    fun dismissDownloadConflict() {
        _uiState.update { it.copy(downloadConflict = null) }
    }


    private fun startDownload(result: YtDlpSearchResult) {
        workScope.launch {
            if (result.videoId in _uiState.value.downloadingIds || result.videoId in _uiState.value.downloadedIds) return@launch
            _uiState.update { it.copy(downloadingIds = it.downloadingIds + result.videoId) }
            try {
                val destDir = File(context.filesDir, "youtube_downloads")
                val songId = ytDlpStableSongId(result.videoId)
                val downloaded = ytDlpRepository.download(result.videoId, destDir, songId.toString(), DownloadQuality.BEST.formatSelector).getOrThrow()
                musicRepository.importDownloadedSong(downloaded, songId, result.videoId)
                _uiState.update { it.copy(downloadedIds = it.downloadedIds + result.videoId) }
                // Turns the real YouTube thumbnail URL already on the row into a cached
                // thumbnailPath - the row is stored pre-enriched (see importDownloadedSong's own
                // doc), so this artwork backfill pass is the only enrichment it still needs.
                musicRepository.backfillThumbnails()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(actionError = "Couldn't save \"${result.title}\": ${e.message}") }
            } finally {
                _uiState.update { it.copy(downloadingIds = it.downloadingIds - result.videoId) }
            }
        }
    }
}
