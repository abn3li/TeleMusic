package com.abn3li.telemusic.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abn3li.telemusic.data.local.AlbumSummary
import com.abn3li.telemusic.data.local.ArtistSummary
import com.abn3li.telemusic.data.local.PlaylistEntity
import com.abn3li.telemusic.data.local.PlaylistSummary
import com.abn3li.telemusic.data.local.SongEntity
import com.abn3li.telemusic.repository.MusicRepository
import com.abn3li.telemusic.repository.SortField
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

// The three built-in playlists shown above the user's own ones on the Playlists (home) tab -
// each is just a different filter over the same songs table, not a real row in the playlists
// table, so opening one goes through Routes.SMART_PLAYLIST rather than Routes.PLAYLIST.
enum class SmartPlaylistKind(val label: String) {
    LIKED("Liked Songs"), TELEGRAM("Telegram Songs"), DOWNLOADED("Downloaded Songs")
}

// The Songs page's exclusion filter, gathered from every "Hide from tracks" toggle across the
// app (each real playlist's own, plus the three smart playlists' - see MusicRepository's own
// doc) into one bundle so the LazyColumn only recomputes once per actual change, not once per
// underlying flow.
private data class TrackVisibilityFilter(
    val hiddenPlaylistSongIds: Set<Long>,
    val hideLiked: Boolean,
    val hideTelegram: Boolean,
    val hideDownloaded: Boolean
)

class LibraryViewModel(private val repository: MusicRepository) : ViewModel() {
    private val _sortField = MutableStateFlow(SortField.TITLE)
    val sortField: StateFlow<SortField> = _sortField
    private val _ascending = MutableStateFlow(true)
    val ascending: StateFlow<Boolean> = _ascending

    // Tracks which song IDs currently have an explicit download in progress, for the
    // download-button's own progress spinner.
    private val _downloadingIds = MutableStateFlow<Set<Long>>(emptySet())
    val downloadingIds: StateFlow<Set<Long>> = _downloadingIds

    private val sortParams = combine(_sortField, _ascending) { f, a -> f to a }

    private val visibilityFilter = combine(
        repository.observeHiddenPlaylistSongIds(),
        repository.observeHideLikedFromTracks(),
        repository.observeHideTelegramFromTracks(),
        repository.observeHideDownloadedFromTracks()
    ) { hiddenIds, hideLiked, hideTelegram, hideDownloaded ->
        TrackVisibilityFilter(hiddenIds.toSet(), hideLiked, hideTelegram, hideDownloaded)
    }

    val tracks: StateFlow<List<SongEntity>> = combine(sortParams, visibilityFilter) { params, filter -> params to filter }
        .flatMapLatest { (params, filter) ->
            val (f, a) = params
            repository.observeLibrary(f, a).map { songs ->
                songs.filterNot { song ->
                    song.telegramMessageId in filter.hiddenPlaylistSongIds ||
                        (filter.hideLiked && song.isFavorite) ||
                        (filter.hideTelegram && song.telegramFileId != 0) ||
                        (filter.hideDownloaded && song.isExplicitDownload)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val albums: StateFlow<List<AlbumSummary>> = repository.observeAlbums().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val artists: StateFlow<List<ArtistSummary>> = repository.observeArtists().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val playlists: StateFlow<List<PlaylistEntity>> = repository.observePlaylists().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val playlistSummaries: StateFlow<List<PlaylistSummary>> = repository.observePlaylistSummaries().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _showAlphabetIndex = MutableStateFlow(repository.showSongIndex)
    val showAlphabetIndex: StateFlow<Boolean> = _showAlphabetIndex
    fun setShowAlphabetIndex(show: Boolean) {
        repository.showSongIndex = show
        _showAlphabetIndex.value = show
    }

    fun selectSortField(f: SortField) { _sortField.value = f }
    fun setAscending(ascending: Boolean) { _ascending.value = ascending }
    fun toggleFavorite(song: SongEntity) = viewModelScope.launch { repository.setFavorite(song, !song.isFavorite) }
    fun addSongToPlaylist(playlistId: Long, song: SongEntity) = viewModelScope.launch { repository.addSongToPlaylist(playlistId, song) }
    fun createPlaylistAndAddSong(name: String, song: SongEntity) = viewModelScope.launch {
        val id = repository.createPlaylist(name); repository.addSongToPlaylist(id, song)
    }
    fun createPlaylist(name: String) = viewModelScope.launch { repository.createPlaylist(name) }
    fun deletePlaylist(playlistId: Long) = viewModelScope.launch { repository.deletePlaylist(playlistId) }

    /** The real, explicit download button - only this sets isExplicitDownload = true. */
    fun downloadSong(song: SongEntity) = viewModelScope.launch {
        _downloadingIds.value = _downloadingIds.value + song.telegramMessageId
        runCatching { repository.downloadExplicitly(song) }
        _downloadingIds.value = _downloadingIds.value - song.telegramMessageId
    }

    /** The row menu's "Delete song" item - only ever shown for a song that's actually downloaded. */
    fun removeDownload(song: SongEntity) = viewModelScope.launch { repository.removeDownload(song) }

    /** The row menu's "Clear song" item - removes the song from the library entirely. */
    fun clearSong(song: SongEntity) = viewModelScope.launch { repository.clearSong(song) }

    /** The long-press-on-a-coverless-row dialog's "Fetch artwork" action - see
     * MusicRepository.editSongAndFetchArtwork's own doc. Suspend, not viewModelScope.launch,
     * since the dialog itself needs the found/not-found result to decide whether to dismiss. */
    suspend fun editSongAndFetchArtwork(song: SongEntity, title: String, artist: String): Boolean =
        repository.editSongAndFetchArtwork(song, title, artist)
}