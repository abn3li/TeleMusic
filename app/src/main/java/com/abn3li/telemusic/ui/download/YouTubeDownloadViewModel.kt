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

/** The search result tabs: everything, only what's in the library, or one kind (library matches
 * of that kind first, then YouTube's). */
enum class SearchTab(val label: String) {
    ALL("All"), LIBRARY("Library"), SONGS("Songs"), ALBUMS("Albums"), ARTISTS("Artists"), PLAYLISTS("Playlists")
}

data class YouTubeDownloadUiState(
    val query: String = "",
    // The query the results below belong to; differs from [query] while a new search waits to run.
    val searchedQuery: String = "",
    val isSearching: Boolean = false,
    val tab: SearchTab = SearchTab.ALL,
    val results: List<YtDlpSearchResult> = emptyList(),
    // The other tabs' results for the same search - pages to open, not songs.
    val albums: List<BrowseCollection> = emptyList(),
    val artists: List<BrowseCollection> = emptyList(),
    val playlists: List<BrowseCollection> = emptyList(),
    val errorMessage: String? = null,
    // Why the last Play or Download failed - kept apart from the search's own message, so it
    // shows in every tab rather than only when the Songs list is empty.
    val actionError: String? = null,
    // Both keyed by videoId - a result mid-download shows a spinner in place of its download
    // icon, and a finished one shows a checkmark instead, without needing a full re-search.
    val downloadingIds: Set<String> = emptySet(),
    val downloadedIds: Set<String> = emptySet(),
    // Set the moment a download is tapped but no folder is saved yet - the screen reacts by
    // launching the system folder picker. The result waiting behind that prompt is kept here
    // rather than re-requested, so picking a folder resumes the exact song the user tapped.
    // A row streams (not downloads) while its videoId is in here - shows a spinner in place of
    // its Play icon, same idea as downloadingIds/downloadedIds above but for onPlayClick.
    val loadingStreamIds: Set<String> = emptySet()
)

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
    // Hands off to the shared NowPlayingViewModel.playEphemeral() - constructed at the nav root
    // (see NavGraph.kt), not something this screen-scoped ViewModel has a reference to itself.
    private val onPlayStream: (SongEntity, Uri, String) -> Unit,
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

    fun selectTab(tab: SearchTab) {
        _uiState.update { it.copy(tab = tab) }
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

    /** Entry point from a row's Play tap - streams straight from a resolved googlevideo.com URL
     * (same Opus quality selector as a real download, see DownloadQuality's own doc) and keeps
     * nothing on disk afterward, unlike the Download button below which saves a real library
     * row. Builds an in-memory-only SongEntity (never inserted into Room - see
     * NowPlayingViewModel.playEphemeral's own doc) so the mini player/Now Playing screen can
     * display it exactly like any other song with zero new UI code. */
    fun onPlayClick(result: YtDlpSearchResult) {
        if (result.videoId in _uiState.value.loadingStreamIds) return
        viewModelScope.launch {
            _uiState.update { it.copy(loadingStreamIds = it.loadingStreamIds + result.videoId, actionError = null) }
            val outcome = ytDlpRepository.resolveStreamUrl(result.videoId, DownloadQuality.BEST.formatSelector)
            val stream = outcome.getOrNull()?.takeIf { it.streamUrl.isNotBlank() }
            if (stream != null) {
                val song = SongEntity(
                    telegramMessageId = ytDlpStableSongId(result.videoId),
                    telegramFileId = 0,
                    title = stream.title,
                    artist = stream.artist,
                    durationSeconds = stream.durationSeconds,
                    // The row's own square album art (YouTube Music search/browse), not the
                    // resolve's thumbnail: the light resolve skips the watch page, so its
                    // thumbnail is the video frame - album art letterboxed with bars.
                    albumArtUrl = com.abn3li.telemusic.data.browse.googleArtworkAtSize(
                        result.thumbnailUrl?.takeIf { it.isNotBlank() } ?: stream.thumbnailUrl,
                        com.abn3li.telemusic.data.browse.SAVED_ARTWORK_SIZE
                    ),
                    isLocalImport = true
                )
                onPlayStream(song, stream.streamUrl.toUri(), result.videoId)
            }
            _uiState.update {
                it.copy(
                    loadingStreamIds = it.loadingStreamIds - result.videoId,
                    actionError = if (stream == null) {
                        "Couldn't play \"${result.title}\": ${outcome.exceptionOrNull()?.message ?: "no stream found"}"
                    } else {
                        it.actionError
                    }
                )
            }
        }
    }

    /** Entry point from a row's Download tap (after the app-wide download-location prompt, see
     * DownloadLocationGate) - always grabs the best real audio yt-dlp/YouTube can offer (Opus
     * preferred, see DownloadQuality's own doc). */
    fun onDownloadIconClick(result: YtDlpSearchResult) {
        if (result.videoId in _uiState.value.downloadingIds || result.videoId in _uiState.value.downloadedIds) return
        startDownload(result)
    }


    private fun startDownload(result: YtDlpSearchResult) {
        workScope.launch {
            _uiState.update { it.copy(downloadingIds = it.downloadingIds + result.videoId) }
            val destDir = File(context.filesDir, "youtube_downloads")
            val songId = ytDlpStableSongId(result.videoId)
            val outcome = ytDlpRepository.download(result.videoId, destDir, songId.toString(), DownloadQuality.BEST.formatSelector)
            outcome.onSuccess { downloaded ->
                musicRepository.importDownloadedSong(downloaded, songId, result.videoId)
                // Turns the real YouTube thumbnail URL already on the row into a cached
                // thumbnailPath - the row is stored pre-enriched (see importDownloadedSong's own
                // doc), so this artwork backfill pass is the only enrichment it still needs.
                musicRepository.backfillThumbnails()
            }
            _uiState.update {
                it.copy(
                    downloadingIds = it.downloadingIds - result.videoId,
                    downloadedIds = if (outcome.isSuccess) it.downloadedIds + result.videoId else it.downloadedIds,
                    actionError = outcome.exceptionOrNull()?.let { e -> "Couldn't download \"${result.title}\": ${e.message}" } ?: it.actionError
                )
            }
        }
    }
}
