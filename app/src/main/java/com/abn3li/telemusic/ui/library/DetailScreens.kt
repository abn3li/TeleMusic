package com.abn3li.telemusic.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.data.local.PlaylistEntity
import com.abn3li.telemusic.data.local.SongEntity
import com.abn3li.telemusic.ui.nowplaying.LocalMiniPlayerInset
import com.abn3li.telemusic.repository.MusicRepository
import com.abn3li.telemusic.repository.SortField
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** [hiddenFromTracksFlow]/[onToggleHiddenFromTracks] are null for Album/Artist (no "Hide from
 * tracks" button there - see SongListScaffold's own doc) and provided for a real playlist or a
 * smart playlist alike, just sourced differently (a PlaylistEntity's own column vs one of
 * AppSettingsStore's three flags - see MusicRepository's own doc on both). */
private class DetailViewModel(
    private val repository: MusicRepository,
    songsFlow: Flow<List<SongEntity>>,
    hiddenFromTracksFlow: Flow<Boolean>? = null,
    private val onToggleHiddenFromTracks: (suspend (Boolean) -> Unit)? = null
) : ViewModel() {
    val songs: StateFlow<List<SongEntity>> = songsFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val playlists: StateFlow<List<PlaylistEntity>> = repository.observePlaylists().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val downloadingIds = MutableStateFlow<Set<Long>>(emptySet())
    val isHiddenFromTracks: StateFlow<Boolean>? =
        hiddenFromTracksFlow?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun toggleFavorite(song: SongEntity) = viewModelScope.launch { repository.setFavorite(song, !song.isFavorite) }
    fun addToPlaylist(playlistId: Long, song: SongEntity) = viewModelScope.launch { repository.addSongToPlaylist(playlistId, song) }
    fun createPlaylistAndAdd(name: String, song: SongEntity) = viewModelScope.launch {
        val id = repository.createPlaylist(name)
        repository.addSongToPlaylist(id, song)
    }
    fun download(song: SongEntity) = viewModelScope.launch {
        downloadingIds.value = downloadingIds.value + song.telegramMessageId
        runCatching { repository.downloadExplicitly(song) }
        downloadingIds.value = downloadingIds.value - song.telegramMessageId
    }
    fun removeDownload(song: SongEntity) = viewModelScope.launch { repository.removeDownload(song) }
    fun clearSong(song: SongEntity) = viewModelScope.launch { repository.clearSong(song) }
    suspend fun editSongAndFetchArtwork(song: SongEntity, title: String, artist: String): Boolean =
        repository.editSongAndFetchArtwork(song, title, artist)
    fun toggleHiddenFromTracks() {
        val toggle = onToggleHiddenFromTracks ?: return
        val current = isHiddenFromTracks?.value ?: return
        viewModelScope.launch { toggle(!current) }
    }
}

private class DetailHolderViewModel(
    repository: MusicRepository,
    songsFlow: Flow<List<SongEntity>>,
    hiddenFromTracksFlow: Flow<Boolean>? = null,
    onToggleHiddenFromTracks: (suspend (Boolean) -> Unit)? = null
) : ViewModel() {
    val detail = DetailViewModel(repository, songsFlow, hiddenFromTracksFlow, onToggleHiddenFromTracks)
}

@Composable
fun AlbumDetailScreen(album: String, onBack: () -> Unit, onSongClick: (List<Long>, Int) -> Unit) {
    val app = LocalContext.current.applicationContext as TgMusicApp
    val holder = remember { DetailHolderViewModel(app.musicRepository, app.musicRepository.observeSongsByAlbum(album, SortField.TITLE, true)) }
    SongListScaffold(album, onBack, holder.detail, onSongClick)
}

@Composable
fun ArtistDetailScreen(artist: String, onBack: () -> Unit, onSongClick: (List<Long>, Int) -> Unit) {
    val app = LocalContext.current.applicationContext as TgMusicApp
    val holder = remember { DetailHolderViewModel(app.musicRepository, app.musicRepository.observeSongsByArtist(artist, SortField.TITLE, true)) }
    SongListScaffold(artist, onBack, holder.detail, onSongClick)
}

@Composable
fun PlaylistDetailScreen(playlistId: Long, playlistName: String, onBack: () -> Unit, onSongClick: (List<Long>, Int) -> Unit) {
    val app = LocalContext.current.applicationContext as TgMusicApp
    val holder = remember(playlistId) {
        DetailHolderViewModel(
            repository = app.musicRepository,
            songsFlow = app.musicRepository.observeSongsInPlaylist(playlistId),
            hiddenFromTracksFlow = app.musicRepository.observePlaylistById(playlistId).map { it?.hiddenFromTracks == true },
            onToggleHiddenFromTracks = { hidden -> app.musicRepository.setPlaylistHiddenFromTracks(playlistId, hidden) }
        )
    }
    SongListScaffold(playlistName, onBack, holder.detail, onSongClick)
}

@Composable
fun SmartPlaylistDetailScreen(kind: SmartPlaylistKind, onBack: () -> Unit, onSongClick: (List<Long>, Int) -> Unit) {
    val app = LocalContext.current.applicationContext as TgMusicApp
    val holder = remember(kind) {
        val songsFlow = when (kind) {
            SmartPlaylistKind.LIKED -> app.musicRepository.observeFavorites(SortField.TITLE, true)
            SmartPlaylistKind.TELEGRAM -> app.musicRepository.observeTelegramSongs(SortField.TITLE, true)
            SmartPlaylistKind.DOWNLOADED -> app.musicRepository.observeDownloadedSongs(SortField.TITLE, true)
        }
        val hiddenFlow = when (kind) {
            SmartPlaylistKind.LIKED -> app.musicRepository.observeHideLikedFromTracks()
            SmartPlaylistKind.TELEGRAM -> app.musicRepository.observeHideTelegramFromTracks()
            SmartPlaylistKind.DOWNLOADED -> app.musicRepository.observeHideDownloadedFromTracks()
        }
        val onToggle: suspend (Boolean) -> Unit = { hidden ->
            when (kind) {
                SmartPlaylistKind.LIKED -> app.musicRepository.setHideLikedFromTracks(hidden)
                SmartPlaylistKind.TELEGRAM -> app.musicRepository.setHideTelegramFromTracks(hidden)
                SmartPlaylistKind.DOWNLOADED -> app.musicRepository.setHideDownloadedFromTracks(hidden)
            }
        }
        DetailHolderViewModel(app.musicRepository, songsFlow, hiddenFlow, onToggle)
    }
    SongListScaffold(kind.label, onBack, holder.detail, onSongClick)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SongListScaffold(
    title: String,
    onBack: () -> Unit,
    vm: DetailViewModel,
    onSongClick: (List<Long>, Int) -> Unit
) {
    val app = LocalContext.current.applicationContext as TgMusicApp
    val songs by vm.songs.collectAsState()
    val playlists by vm.playlists.collectAsState()
    val downloadingIds by vm.downloadingIds.collectAsState()
    val songIds = remember(songs) { songs.map { it.telegramMessageId } }
    val albumArtUrl = songs.firstOrNull { !it.albumArtUrl.isNullOrEmpty() }?.albumArtUrl

    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val typography = MaterialTheme.typography
    val titleStyle = remember(typography) {
        typography.bodyMedium.copy(fontWeight = FontWeight.Medium, platformStyle = PlatformTextStyle(includeFontPadding = false))
    }
    val subtitleStyle = remember(typography) {
        typography.bodySmall.copy(platformStyle = PlatformTextStyle(includeFontPadding = false))
    }

    AdaptiveScreenBackground {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    // TopAppBar's own container has a fixed height - an unbounded Text here
                    // (no maxLines) could try to wrap a long album/artist/playlist name onto a
                    // 3rd line that the container then just clips mid-glyph instead of growing
                    // for it. Capped at 2 lines with an ellipsis instead, same treatment every
                    // other truncated title in this app already gets.
                    Text(
                        text = title,
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // No card/panel background here on purpose - content sits directly on the ambient
            // background, matching the YouTube playlist screen's look (BrowseCollectionScreen.kt)
            // once that got its own redesign pass. This used to wrap everything in a translucent
            // glass panel clipped to a rounded top edge, which read as a boxed-in "card" sitting
            // on top of the screen instead of being part of it - fine for the Library tabs
            // (Tracks/Albums/Artists/Playlists), which are genuinely scrollable lists sharing a
            // header, but wrong for a single album/artist/playlist's own detail screen.
            if (songs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = "No songs here yet", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp + LocalMiniPlayerInset.current)
                ) {
                    // Header section with rounded album art & Play All / Shuffle buttons
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(160.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(adaptivePanelFill()),
                                contentAlignment = Alignment.Center
                            ) {
                                if (!albumArtUrl.isNullOrEmpty()) {
                                    AsyncImage(
                                        model = albumArtUrl,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.MusicNote,
                                        contentDescription = null,
                                        modifier = Modifier.size(64.dp),
                                        tint = TextMuted
                                    )
                                }
                            }

                            Spacer(Modifier.height(12.dp))

                            Text(
                                text = "${songs.size} ${if (songs.size == 1) "track" else "tracks"}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextMuted
                            )

                            Spacer(Modifier.height(14.dp))

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = { if (songIds.isNotEmpty()) onSongClick(songIds, 0) },
                                    shape = RoundedCornerShape(50),
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = OnAccentGreen),
                                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Play All", fontWeight = FontWeight.Medium)
                                }

                                FilledTonalButton(
                                    onClick = {
                                        if (songIds.isNotEmpty()) {
                                            val randomIndex = (0 until songIds.size).random()
                                            onSongClick(songIds, randomIndex)
                                        }
                                    },
                                    shape = RoundedCornerShape(50),
                                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = DividerColor, contentColor = Color.White),
                                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                                ) {
                                    Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Shuffle", fontWeight = FontWeight.Medium)
                                }
                            }

                            // Only for a real or smart playlist (vm.isHiddenFromTracks is null
                            // for Album/Artist - see DetailViewModel's own doc) - a separate row
                            // below Play/Shuffle, not squeezed into the same one, since this
                            // toggles a standing state rather than doing something immediately.
                            vm.isHiddenFromTracks?.let { hiddenFlow ->
                                val isHidden by hiddenFlow.collectAsState()
                                Spacer(Modifier.height(10.dp))
                                OutlinedButton(
                                    onClick = { vm.toggleHiddenFromTracks() },
                                    shape = RoundedCornerShape(50),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isHidden) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (isHidden) "Hidden from Tracks" else "Hide from Tracks")
                                }
                            }
                        }
                    }

                    itemsIndexed(songs, key = { _, s -> s.telegramMessageId }, contentType = { _, _ -> "song_row" }) { index, song ->
                        val isDownloading = downloadingIds.contains(song.telegramMessageId)
                        val uiModel = remember(song, isDownloading) { song.toUiModel(isDownloading) }

                        SongRow(
                            song = uiModel,
                            playlists = playlists,
                            onClick = { onSongClick(songIds, index) },
                            onToggleFavorite = { vm.toggleFavorite(song) },
                            onDownloadClick = { vm.download(song) },
                            onAddToPlaylist = { id -> vm.addToPlaylist(id, song) },
                            onCreatePlaylistAndAdd = { name -> vm.createPlaylistAndAdd(name, song) },
                            onDeleteDownload = {
                                if (app.playbackQueue.currentSongId() == song.telegramMessageId && app.playbackController.isPlaying()) {
                                    app.playbackController.togglePlayPause()
                                }
                                vm.removeDownload(song)
                            },
                            onClearSong = {
                                if (app.playbackQueue.currentSongId() == song.telegramMessageId && app.playbackController.isPlaying()) {
                                    app.playbackController.togglePlayPause()
                                }
                                vm.clearSong(song)
                            },
                            onEditAndFetchArtwork = { title, artist -> vm.editSongAndFetchArtwork(song, title, artist) },
                            primaryColor = primaryColor,
                            onSurfaceVariant = onSurfaceVariant,
                            titleStyle = titleStyle,
                            subtitleStyle = subtitleStyle
                        )
                        if (index < songs.lastIndex) {
                            HorizontalDivider(thickness = 0.5.dp, color = DividerColor)
                        }
                    }
                }
            }
        }
    }
    }
}