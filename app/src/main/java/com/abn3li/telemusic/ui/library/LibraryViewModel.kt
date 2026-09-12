package com.abn3li.telemusic.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abn3li.telemusic.data.local.AlbumSummary
import com.abn3li.telemusic.data.local.ArtistSummary
import com.abn3li.telemusic.data.local.PlaylistEntity
import com.abn3li.telemusic.data.local.SongEntity
import com.abn3li.telemusic.repository.MusicRepository
import com.abn3li.telemusic.repository.SortField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LibraryTab(val label: String) {
    FAVOURITES("Favourites"), PLAYLISTS("Playlists"), TRACKS("Tracks"), ALBUMS("Albums"), ARTISTS("Artists")
}

class LibraryViewModel(private val repository: MusicRepository) : ViewModel() {
    private val _tab = MutableStateFlow(LibraryTab.TRACKS)
    val tab: StateFlow<LibraryTab> = _tab
    private val _sortField = MutableStateFlow(SortField.TITLE)
    val sortField: StateFlow<SortField> = _sortField
    private val _ascending = MutableStateFlow(true)
    val ascending: StateFlow<Boolean> = _ascending

    // Tracks which song IDs currently have an explicit download in progress, for the
    // download-button's own progress spinner.
    private val _downloadingIds = MutableStateFlow<Set<Long>>(emptySet())

    private val sortParams = combine(_sortField, _ascending) { f, a -> f to a }

    val tracks: StateFlow<List<SongEntity>> = sortParams.flatMapLatest { (f, a) -> repository.observeLibrary(f, a) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favorites: StateFlow<List<SongEntity>> = sortParams.flatMapLatest { (f, a) -> repository.observeFavorites(f, a) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Pre-computed @Immutable SongUiModels offloaded to background Dispatchers.Default threads for 120fps UI rendering
    val tracksUiModels: StateFlow<List<SongUiModel>> = combine(tracks, _downloadingIds) { songList, dlIds ->
        withContext(Dispatchers.Default) {
            songList.map { song -> song.toUiModel(dlIds.contains(song.telegramMessageId)) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favoritesUiModels: StateFlow<List<SongUiModel>> = combine(favorites, _downloadingIds) { songList, dlIds ->
        withContext(Dispatchers.Default) {
            songList.map { song -> song.toUiModel(dlIds.contains(song.telegramMessageId)) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val albums: StateFlow<List<AlbumSummary>> = repository.observeAlbums().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val artists: StateFlow<List<ArtistSummary>> = repository.observeArtists().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val playlists: StateFlow<List<PlaylistEntity>> = repository.observePlaylists().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun selectTab(t: LibraryTab) { _tab.value = t }
    fun selectSortField(f: SortField) { _sortField.value = f }
    fun toggleSortDirection() { _ascending.value = !_ascending.value }
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
}