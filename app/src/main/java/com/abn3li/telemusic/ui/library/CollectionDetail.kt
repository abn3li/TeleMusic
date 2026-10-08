package com.abn3li.telemusic.ui.library

import com.abn3li.telemusic.ui.download.NoResults
import com.abn3li.telemusic.ui.download.heroTopInset
import com.abn3li.telemusic.ui.theme.SystemBarsState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.runtime.DisposableEffect
import com.abn3li.telemusic.ui.download.ArtistHeroLayout
import com.abn3li.telemusic.ui.theme.LocalPalette
import com.abn3li.telemusic.ui.theme.paper
import com.abn3li.telemusic.ui.theme.ink
import com.abn3li.telemusic.data.local.displayArtwork
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Check
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.PushPin
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.material.icons.rounded.QueuePlayNext
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SortByAlpha
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.data.local.SongEntity
import com.abn3li.telemusic.repository.SortField
import com.abn3li.telemusic.ui.nowplaying.LocalMiniPlayerInset
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Navigation and playback hooks every detail page needs. */
class LibraryCallbacks(
    val onBack: () -> Unit,
    val onPlay: (List<Long>, Int) -> Unit,
    val onPlayCollection: (List<Long>, Boolean) -> Unit,
    val onPlayNext: (Long) -> Unit,
    val onOpenArtist: (String) -> Unit,
    val onOpenAlbum: (String) -> Unit,
    val onOpenArtistSongs: (String) -> Unit,
    // For a page whose own list is the one playing: flip shuffle / play-pause without
    // starting the list over.
    val onToggleShuffle: () -> Unit = {},
    val onTogglePlayPause: () -> Unit = {}
)

/** What's playing, for a collection page's Shuffle and Play buttons: the list the queue was
 * started from (PlaybackQueue.sourceIds), and whether it's playing and shuffled. */
class CollectionPlayback(val sourceIds: List<Long>, val isPlaying: Boolean, val isShuffled: Boolean)

val LocalCollectionPlayback = compositionLocalOf { CollectionPlayback(emptyList(), isPlaying = false, isShuffled = false) }

@Composable
fun AlbumDetailScreen(album: String, viewModel: LibraryViewModel, callbacks: LibraryCallbacks) {
    val repository = (LocalContext.current.applicationContext as TgMusicApp).musicRepository
    var sortField by rememberSaveable { mutableStateOf(SortField.TITLE) }
    var ascending by rememberSaveable { mutableStateOf(true) }
    val songs by remember(album, sortField, ascending) { repository.observeSongsByAlbum(album, sortField, ascending) }
        .collectAsState(initial = null)
    val list = songs.orEmpty()
    val actions = rememberLibrarySongActions(viewModel, callbacks.onPlayNext, callbacks.onOpenArtist, callbacks.onOpenAlbum)
    val albumArtist = list.firstOrNull()?.artist
    val allFavorite = list.isNotEmpty() && list.all { it.isFavorite }

    CollectionDetailPage(
        title = album,
        subtitle = albumArtist,
        onSubtitleClick = albumArtist?.let { artist -> { callbacks.onOpenArtist(artist) } },
        detailLine = songCountLine(list),
        hero = { HeroImage(list.firstNotNullOfOrNull { it.displayArtwork }, Icons.Rounded.Album) },
        songs = list,
        loaded = songs != null,
        callbacks = callbacks,
        favorite = allFavorite,
        onFavorite = {
            list.filter { it.isFavorite == allFavorite }.forEach { viewModel.toggleFavorite(it) }
        },
        menu = { close ->
            LibraryMenuItem("Play Next", Icons.Rounded.QueuePlayNext) { list.forEach { callbacks.onPlayNext(it.telegramMessageId) }; close() }
            LibraryMenuGroupGap()
            LibraryMenuItem("Title", Icons.Rounded.SortByAlpha, selected = sortField == SortField.TITLE) { sortField = SortField.TITLE; close() }
            LibraryMenuDivider()
            LibraryMenuItem("Recently Added", Icons.Rounded.Schedule, selected = sortField == SortField.DATE_ADDED) { sortField = SortField.DATE_ADDED; close() }
            LibraryMenuGroupGap()
            LibraryMenuItem("Ascending", Icons.Rounded.ArrowUpward, selected = ascending) { ascending = true; close() }
            LibraryMenuDivider()
            LibraryMenuItem("Descending", Icons.Rounded.ArrowDownward, selected = !ascending) { ascending = false; close() }
        }
    ) { shown, _ ->
        val ids = shown.map { it.telegramMessageId }
        itemsIndexed(shown, key = { _, s -> s.telegramMessageId }, contentType = { _, _ -> "track" }) { index, song ->
            LibrarySongRow(
                song = song,
                actions = actions,
                onClick = { callbacks.onPlay(ids, index) },
                leading = SongRowLeading.TrackNumber(index + 1),
                subtitle = song.artist.takeIf { it != albumArtist },
                horizontalPadding = 18.dp
            )
            if (index < shown.lastIndex) LibraryDivider(start = 54.dp, end = 18.dp)
        }
    }
}

@Composable
fun ArtistDetailScreen(artist: String, viewModel: LibraryViewModel, callbacks: LibraryCallbacks) {
    val repository = (LocalContext.current.applicationContext as TgMusicApp).musicRepository
    val songs by remember(artist) { repository.observeSongsByArtist(artist, SortField.TITLE, true) }.collectAsState(initial = null)
    val list = songs.orEmpty()
    val albums by viewModel.albums.collectAsState()
    val artistAlbums = remember(albums, artist) { albums.filter { it.artist == artist } }
    val actions = rememberLibrarySongActions(viewModel, callbacks.onPlayNext, callbacks.onOpenArtist, callbacks.onOpenAlbum)

    val ids = remember(list) { list.map { it.telegramMessageId } }
    ArtistHeroLayout(
        name = artist,
        photoUrl = list.firstNotNullOfOrNull { it.displayArtwork },
        canPlay = list.isNotEmpty(),
        onShuffle = { callbacks.onPlayCollection(ids, true) },
        onPlay = { callbacks.onPlayCollection(ids, false) },
        onAllSongs = { callbacks.onOpenArtistSongs(artist) },
        onBack = callbacks.onBack,
        loaded = songs != null,
        searchHint = "Search in $artist",
        menu = { close ->
            LibraryMenuItem("Play Next", Icons.Rounded.QueuePlayNext) { list.forEach { callbacks.onPlayNext(it.telegramMessageId) }; close() }
        }
    ) { searching, query ->
        val shown = when {
            !searching -> list
            query.isBlank() -> list
            else -> list.filter { it.title.contains(query, true) || it.artist.contains(query, true) || it.album.orEmpty().contains(query, true) }
        }
        if (searching && shown.isEmpty() && query.isNotBlank()) item("no_results") { NoResults() }
        if (!searching && list.isNotEmpty()) {
            item("songs_header") { SectionHeader("Songs") { callbacks.onOpenArtistSongs(artist) } }
        }
        val shownIds = shown.map { it.telegramMessageId }
        val visible = if (searching) shown else shown.take(5)
        itemsIndexed(visible, key = { _, s -> s.telegramMessageId }, contentType = { _, _ -> "song" }) { index, song ->
            LibrarySongRow(song = song, actions = actions, onClick = { callbacks.onPlay(shownIds, index) })
            if (index < visible.lastIndex) LibraryDivider(start = 88.dp)
        }
        if (!searching && artistAlbums.isNotEmpty()) {
            item("albums_header") { SectionHeader("Albums", null) }
            item("albums_row") {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp)
                ) {
                    items(artistAlbums, key = { it.album }) { album ->
                        Column(Modifier.width(142.dp).clickable { callbacks.onOpenAlbum(album.album) }) {
                            CoverTile(album.albumArtUrl, Modifier.fillMaxWidth().aspectRatio(1f), corner = 8)
                            Text(
                                album.album,
                                color = ink.copy(alpha = 0.94f),
                                fontSize = 15.sp,
                                lineHeight = 18.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ArtistSongsScreen(artist: String, viewModel: LibraryViewModel, callbacks: LibraryCallbacks) {
    val repository = (LocalContext.current.applicationContext as TgMusicApp).musicRepository
    val songs by remember(artist) { repository.observeSongsByArtist(artist, SortField.TITLE, true) }.collectAsState(initial = emptyList())
    val actions = rememberLibrarySongActions(viewModel, callbacks.onPlayNext, callbacks.onOpenArtist, callbacks.onOpenAlbum)
    SongListPage(
        title = artist,
        songs = songs,
        actions = actions,
        onBack = callbacks.onBack,
        onPlay = callbacks.onPlay,
        onPlayCollection = callbacks.onPlayCollection
    )
}

@Composable
fun PlaylistDetailScreen(playlistId: Long, playlistName: String, viewModel: LibraryViewModel, callbacks: LibraryCallbacks) {
    val repository = (LocalContext.current.applicationContext as TgMusicApp).musicRepository
    val scope = rememberCoroutineScope()
    val songs by remember(playlistId) { repository.observeSongsInPlaylist(playlistId) }.collectAsState(initial = null)
    val hidden by remember(playlistId) { repository.observePlaylistById(playlistId).map { it?.hiddenFromTracks == true } }
        .collectAsState(initial = false)
    var confirmDelete by remember { mutableStateOf(false) }
    val list = songs.orEmpty()
    val pinnedKeys by viewModel.pinnedKeys.collectAsState()
    val pinned = playlistPinKey(playlistId) in pinnedKeys
    val downloadGate = (LocalContext.current.applicationContext as TgMusicApp).downloadGate
    // Only a playlist with YouTube songs (one imported from YouTube) gets Download All.
    val notDownloaded = remember(list) { list.count { it.youtubeVideoId != null && it.localFilePath == null && !it.isExplicitDownload } }
    val downloadProgress = viewModel.playlistDownloads.collectAsState().value[playlistId]

    PlaylistLikePage(
        title = playlistName,
        songs = songs,
        placeholder = Icons.AutoMirrored.Rounded.QueueMusic,
        viewModel = viewModel,
        callbacks = callbacks,
        titleOrderByDefault = false,
        menu = { close ->
            LibraryMenuItem("Play Next", Icons.Rounded.QueuePlayNext) { list.forEach { callbacks.onPlayNext(it.telegramMessageId) }; close() }
            LibraryMenuDivider()
            LibraryMenuItem(
                if (hidden) "Show in Songs" else "Hide from Songs",
                if (hidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff
            ) { scope.launch { repository.setPlaylistHiddenFromTracks(playlistId, !hidden) }; close() }
            LibraryMenuDivider()
            PinMenuItem(pinned) { viewModel.togglePin(playlistPinKey(playlistId)); close() }
            if (downloadProgress != null) {
                LibraryMenuDivider()
                LibraryMenuItem(
                    "Stop All (${downloadProgress.first + 1}/${downloadProgress.second})",
                    Icons.Rounded.Close,
                    destructive = true
                ) { viewModel.stopPlaylistDownload(playlistId); close() }
            } else if (notDownloaded > 0) {
                LibraryMenuDivider()
                LibraryMenuItem("Download All ($notDownloaded)", Icons.Rounded.Download) {
                    downloadGate.run { viewModel.downloadAllInPlaylist(playlistId, list) }; close()
                }
            }
            LibraryMenuGroupGap()
            LibraryMenuItem("Delete Playlist", Icons.Rounded.Delete, destructive = true) { confirmDelete = true; close() }
        }
    )

    if (confirmDelete) {
        AppAlert(
            title = "Delete \"$playlistName\"?",
            message = "The songs stay in your library.",
            onDismiss = { confirmDelete = false },
            actions = listOf(
                AlertAction("Cancel", bold = true) { confirmDelete = false },
                AlertAction("Delete", destructive = true) {
                    confirmDelete = false
                    viewModel.deletePlaylist(playlistId)
                    callbacks.onBack()
                }
            )
        )
    }
}

@Composable
private fun PinMenuItem(pinned: Boolean, onClick: () -> Unit) {
    LibraryMenuItem(
        if (pinned) "Unpin from Home" else "Pin to Home",
        if (pinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin,
        onClick = onClick
    )
}

@Composable
fun SmartPlaylistDetailScreen(kind: SmartPlaylistKind, viewModel: LibraryViewModel, callbacks: LibraryCallbacks) {
    val repository = (LocalContext.current.applicationContext as TgMusicApp).musicRepository
    val scope = rememberCoroutineScope()
    val songs by remember(kind) {
        when (kind) {
            SmartPlaylistKind.LIKED -> repository.observeFavorites(SortField.TITLE, true)
            SmartPlaylistKind.TELEGRAM -> repository.observeTelegramSongs(SortField.TITLE, true)
            SmartPlaylistKind.DOWNLOADED -> repository.observeDownloadedSongs(SortField.TITLE, true)
        }
    }.collectAsState(initial = null)
    val hidden by remember(kind) {
        when (kind) {
            SmartPlaylistKind.LIKED -> repository.observeHideLikedFromTracks()
            SmartPlaylistKind.TELEGRAM -> repository.observeHideTelegramFromTracks()
            SmartPlaylistKind.DOWNLOADED -> repository.observeHideDownloadedFromTracks()
        }
    }.collectAsState()
    val list = songs.orEmpty()
    val pinnedKeys by viewModel.pinnedKeys.collectAsState()
    val pinned = smartPinKey(kind) in pinnedKeys

    PlaylistLikePage(
        title = kind.label,
        songs = songs,
        placeholder = kind.icon(),
        viewModel = viewModel,
        callbacks = callbacks,
        titleOrderByDefault = true,
        menu = { close ->
            LibraryMenuItem("Play Next", Icons.Rounded.QueuePlayNext) { list.forEach { callbacks.onPlayNext(it.telegramMessageId) }; close() }
            LibraryMenuDivider()
            LibraryMenuItem(
                if (hidden) "Show in Songs" else "Hide from Songs",
                if (hidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff
            ) {
                scope.launch {
                    when (kind) {
                        SmartPlaylistKind.LIKED -> repository.setHideLikedFromTracks(!hidden)
                        SmartPlaylistKind.TELEGRAM -> repository.setHideTelegramFromTracks(!hidden)
                        SmartPlaylistKind.DOWNLOADED -> repository.setHideDownloadedFromTracks(!hidden)
                    }
                }
                close()
            }
            LibraryMenuDivider()
            PinMenuItem(pinned) { viewModel.togglePin(smartPinKey(kind)); close() }
        }
    )
}

/**
 * A playlist's page. [songs] arrive in the playlist's own order - newest-added first for a real
 * playlist, by title for the smart ones ([titleOrderByDefault]). The ••• menu can switch between
 * Title and Recently Added, and turn on the A–Z strip (shared with the Songs page), which shows
 * only while sorted by title.
 */
@Composable
private fun PlaylistLikePage(
    title: String,
    songs: List<SongEntity>?,
    placeholder: ImageVector,
    viewModel: LibraryViewModel,
    callbacks: LibraryCallbacks,
    titleOrderByDefault: Boolean,
    menu: @Composable ColumnScope.(close: () -> Unit) -> Unit
) {
    val source = songs.orEmpty()
    var byTitle by rememberSaveable { mutableStateOf(titleOrderByDefault) }
    val list = remember(source, byTitle) {
        when {
            byTitle == titleOrderByDefault -> source
            byTitle -> source.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            else -> source.sortedByDescending { it.addedAtMillis }
        }
    }
    val showIndex by viewModel.showAlphabetIndex.collectAsState()
    val actions = rememberLibrarySongActions(viewModel, callbacks.onPlayNext, callbacks.onOpenArtist, callbacks.onOpenAlbum)
    CollectionDetailPage(
        title = title,
        subtitle = null,
        onSubtitleClick = null,
        detailLine = songCountLine(list),
        hero = { HeroImage(list.firstNotNullOfOrNull { it.displayArtwork }, placeholder) },
        songs = list,
        loaded = songs != null,
        callbacks = callbacks,
        indexKey = if (showIndex && byTitle) { song -> song.title } else null,
        menu = { close ->
            menu(close)
            LibraryMenuGroupGap()
            LibraryMenuItem("Title", Icons.Rounded.SortByAlpha, selected = byTitle) { byTitle = true; close() }
            LibraryMenuDivider()
            LibraryMenuItem("Recently Added", Icons.Rounded.Schedule, selected = !byTitle) { byTitle = false; close() }
            LibraryMenuGroupGap()
            LibraryMenuItem(
                label = "Alphabet Index",
                icon = if (showIndex) Icons.Rounded.Check else null,
                selected = showIndex
            ) { viewModel.setShowAlphabetIndex(!showIndex); close() }
        }
    ) { shown, _ ->
        val ids = shown.map { it.telegramMessageId }
        itemsIndexed(shown, key = { _, s -> s.telegramMessageId }, contentType = { _, _ -> "song" }) { index, song ->
            LibrarySongRow(song = song, actions = actions, onClick = { callbacks.onPlay(ids, index) })
            if (index < shown.lastIndex) LibraryDivider(start = 88.dp)
        }
    }
}

private fun songCountLine(songs: List<SongEntity>): String {
    val minutes = songs.sumOf { it.durationSeconds.toLong() } / 60
    val count = "${songs.size} ${if (songs.size == 1) "Song" else "Songs"}"
    return if (minutes > 0) "$count, about $minutes minutes" else count
}

@Composable
internal fun BoxScope.HeroImage(url: String?, placeholder: ImageVector) {
    Box(Modifier.matchParentSize().background(LocalPalette.current.raised), contentAlignment = Alignment.Center) {
        Icon(placeholder, null, tint = ink.copy(alpha = 0.25f), modifier = Modifier.size(120.dp))
    }
    if (!url.isNullOrEmpty()) {
        AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
    }
}

@Composable
internal fun SectionHeader(title: String, onMore: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(top = 20.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = ink, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (onMore != null) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowForwardIos,
                contentDescription = "See all",
                tint = ink.copy(alpha = 0.7f),
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onMore)
                    .padding(5.dp)
            )
        }
    }
}

/**
 * Album / artist / playlist page: a full-bleed hero (artwork fading into black, title, subtitle,
 * detail line, Shuffle / Play / Search), then the songs. The floating back button and actions
 * gain a solid bar as the hero scrolls away. Search swaps the hero for a search field.
 */
@Composable
private fun CollectionDetailPage(
    title: String,
    subtitle: String?,
    onSubtitleClick: (() -> Unit)?,
    detailLine: String,
    hero: @Composable BoxScope.() -> Unit,
    songs: List<SongEntity>,
    loaded: Boolean,
    callbacks: LibraryCallbacks,
    favorite: Boolean? = null,
    onFavorite: () -> Unit = {},
    menu: (@Composable ColumnScope.(close: () -> Unit) -> Unit)? = null,
    // Set to show the A–Z strip (only for a title-sorted list): which text of a song it indexes.
    indexKey: ((SongEntity) -> String)? = null,
    body: LazyListScope.(shown: List<SongEntity>, searching: Boolean) -> Unit
) = DetailPageScaffold(
    title = title,
    subtitle = subtitle,
    onSubtitleClick = onSubtitleClick,
    detailLine = detailLine,
    hero = hero,
    items = songs,
    loaded = loaded,
    onBack = callbacks.onBack,
    matches = { song, query -> song.title.contains(query, true) || song.artist.contains(query, true) || song.album.orEmpty().contains(query, true) },
    favorite = favorite,
    onFavorite = onFavorite,
    menu = menu,
    indexKey = indexKey,
    heroActions = { shown, openSearch ->
        val ids = remember(shown) { shown.map { it.telegramMessageId } }
        // This page's own list is the one playing (in any order - the page may be sorted
        // differently from when it was started): Shuffle and Play then show and change the
        // current playback instead of starting the list over.
        val playback = LocalCollectionPlayback.current
        val isThisPlaying = remember(playback.sourceIds, ids) {
            ids.isNotEmpty() && playback.sourceIds.size == ids.size && playback.sourceIds.toSet() == ids.toSet()
        }
        val shuffleOn = isThisPlaying && playback.isShuffled
        val playingNow = isThisPlaying && playback.isPlaying
        HeroButtonRow {
            HeroButton(Icons.Rounded.Shuffle, if (shuffleOn) "Shuffle on" else "Shuffle", enabled = songs.isNotEmpty(), active = shuffleOn) {
                if (isThisPlaying) callbacks.onToggleShuffle() else callbacks.onPlayCollection(ids, true)
            }
            HeroButton(
                if (playingNow) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (playingNow) "Pause" else "Play",
                enabled = songs.isNotEmpty(),
                primary = true
            ) {
                if (isThisPlaying) callbacks.onTogglePlayPause() else callbacks.onPlayCollection(ids, false)
            }
            HeroButton(Icons.Rounded.Search, "Search", enabled = songs.isNotEmpty(), onClick = openSearch)
        }
        // Always takes its room, so the title doesn't jump when it appears.
        Box(Modifier.fillMaxWidth().height(30.dp), contentAlignment = Alignment.BottomCenter) {
            if (isThisPlaying) PlaybackStatusLine(playing = playingNow, shuffled = shuffleOn)
        }
    },
    body = body
)

/** The Shuffle / Play / Search row under a detail page's title. */
@Composable
internal fun HeroButtonRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/**
 * The layout every album / artist / playlist page shares, library or YouTube alike: the hero
 * (artwork fading into black, title, [subtitle], [detailLine], then [heroActions] - its buttons),
 * the rows from [body], the top bar that turns solid as the hero scrolls away, and in-page
 * search ([matches] decides which [items] a query keeps; [heroActions] gets openSearch).
 */
@Composable
internal fun <T> DetailPageScaffold(
    title: String,
    subtitle: String?,
    onSubtitleClick: (() -> Unit)?,
    detailLine: String,
    hero: @Composable BoxScope.() -> Unit,
    items: List<T>,
    loaded: Boolean,
    onBack: () -> Unit,
    matches: (T, String) -> Boolean,
    heroActions: @Composable ColumnScope.(shown: List<T>, openSearch: () -> Unit) -> Unit,
    favorite: Boolean? = null,
    onFavorite: () -> Unit = {},
    menu: (@Composable ColumnScope.(close: () -> Unit) -> Unit)? = null,
    indexKey: ((T) -> String)? = null,
    emptyText: String = "No songs here yet",
    // False when the page has other content below (an artist's album shelves): no empty-list
    // message over it. An in-page search with no matches still says "No results".
    showEmptyText: Boolean = true,
    body: LazyListScope.(shown: List<T>, searching: Boolean) -> Unit
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val statusBar = heroTopInset()
    val heroHeight = (configuration.screenHeightDp.dp * 0.6f).coerceIn(380.dp, 560.dp) + statusBar
    val heroHeightPx = with(LocalDensity.current) { heroHeight.toPx() }
    var searching by rememberSaveable { mutableStateOf(false) }
    var searchText by rememberSaveable { mutableStateOf("") }
    val query by produceState(searchText, searchText) {
        if (searchText.isNotBlank()) delay(150)
        value = searchText
    }
    val shown = remember(items, query) {
        if (query.isBlank()) items else items.filter { matches(it, query) }
    }
    val collapse by remember(searching) {
        derivedStateOf {
            when {
                searching -> 1f
                listState.firstVisibleItemIndex > 0 -> 1f
                else -> ((listState.firstVisibleItemScrollOffset - heroHeightPx * 0.55f) / (heroHeightPx * 0.2f)).coerceIn(0f, 1f)
            }
        }
    }
    val closeSearch = {
        searchText = ""
        searching = false
        scope.launch { listState.scrollToItem(0) }
    }
    if (searching) BackHandler { closeSearch() }

    // A–Z strip: letter -> first song under it, rebuilt only when the list changes.
    var barHeightPx by remember { mutableIntStateOf(0) }
    val indexActive = indexKey != null && !searching && shown.isNotEmpty()
    val firstIndexOf = remember(shown, indexKey) {
        if (indexKey == null) emptyMap()
        else HashMap<String, Int>().apply { shown.forEachIndexed { i, song -> putIfAbsent(indexLetterOf(indexKey(song)), i) } }
    }
    var jumpRequest by remember { mutableStateOf<JumpRequest?>(null) }
    LaunchedEffect(listState) {
        // Conflated like the Songs page: a fast slide costs one jump per frame. Item 0 is the
        // hero; the negative offset parks the song just under the solid top bar.
        snapshotFlow { jumpRequest }.collect { request ->
            if (request != null) listState.scrollToItem(1 + request.index, -barHeightPx)
        }
    }
    val showStrip by remember { derivedStateOf { collapse >= 1f } }
    // The status bar goes see-through over the cover: light icons over it, the theme's own once
    // the bar has filled in (flips only then - not every scrolled frame).
    val heroToken = remember { Any() }
    DisposableEffect(Unit) {
        SystemBarsState.heroPages++
        onDispose {
            SystemBarsState.heroPages--
            SystemBarsState.heroesOverPhoto.remove(heroToken)
        }
    }
    LaunchedEffect(searching) { snapshotFlow { collapse < 1f }.collect { over ->
        if (!over) SystemBarsState.heroesOverPhoto.remove(heroToken)
        else if (heroToken !in SystemBarsState.heroesOverPhoto) SystemBarsState.heroesOverPhoto.add(heroToken)
    } }

    // Reaches up under the status bar: the pages sit below it, this one draws behind it.
    Box(
        Modifier
            .fillMaxSize()
            .layout { measurable, constraints ->
                val extra = statusBar.roundToPx()
                val placeable = measurable.measure(
                    constraints.copy(minHeight = constraints.minHeight + extra, maxHeight = constraints.maxHeight + extra)
                )
                layout(placeable.width, constraints.maxHeight) { placeable.place(0, -extra) }
            }
            .background(paper)
    ) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            if (!searching) {
                item("hero") {
                    Box(Modifier.fillMaxWidth().height(heroHeight).clipToBounds()) {
                        Box(
                            Modifier.matchParentSize().graphicsLayer {
                                // Slower than the page: the cover lags behind as it scrolls away.
                                translationY = if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset * 0.45f else 0f
                            }
                        ) { hero() }
                        // Only where the fades show: a shade under the status bar and the fade into
                        // the page. (One full-size overlay also painted the clear middle every frame.)
                        Box(
                            Modifier.align(Alignment.TopCenter).fillMaxWidth().fillMaxHeight(0.18f).background(
                                Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.32f), Color.Transparent))
                            )
                        )
                        Box(
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.55f).background(
                                Brush.verticalGradient(0f to Color.Transparent, 0.64f to paper.copy(alpha = 0.75f), 1f to paper)
                            )
                        )
                        Column(
                            Modifier.fillMaxSize().padding(horizontal = 18.dp).padding(bottom = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Spacer(Modifier.height(88.dp))
                            Spacer(Modifier.weight(1f))
                            val titleSize = when {
                                title.length <= 6 -> 64
                                title.length <= 10 -> 54
                                title.length <= 15 -> 44
                                title.length <= 28 -> 36
                                else -> 30
                            }
                            Text(
                                title,
                                color = ink,
                                fontSize = titleSize.sp,
                                lineHeight = (titleSize * 1.02f).sp,
                                letterSpacing = (-1.2).sp,
                                fontWeight = FontWeight.Black,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (subtitle != null) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    subtitle,
                                    color = ink,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = if (onSubtitleClick != null) Modifier.clickable(onClick = onSubtitleClick) else Modifier
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                detailLine,
                                color = ink.copy(alpha = 0.6f),
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                textAlign = TextAlign.Center,
                                maxLines = 2
                            )
                            Spacer(Modifier.height(22.dp))
                            heroActions(shown) {
                                searching = true
                                scope.launch { listState.scrollToItem(0) }
                            }
                        }
                    }
                }
            } else {
                item("search") {
                    Box(Modifier.fillMaxWidth().padding(top = 64.dp + statusBar)) {
                        LibrarySearchField(searchText, "Search in $title", { searchText = it })
                    }
                }
            }
            if (loaded && shown.isEmpty() && (searching || showEmptyText)) {
                item("empty") {
                    Text(
                        if (searching) "No results" else emptyText,
                        color = ink.copy(alpha = 0.55f),
                        fontSize = 15.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)
                    )
                }
            }
            body(shown, searching)
            item("bottom_inset") { Spacer(Modifier.height(LocalMiniPlayerInset.current + 24.dp)) }
        }

        // Fades in once the cover has scrolled away, so it never sits on top of the artwork.
        if (indexActive) {
            AnimatedVisibility(
                visible = showStrip,
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(150)),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(
                        top = with(LocalDensity.current) { barHeightPx.toDp() } + 8.dp,
                        bottom = LocalMiniPlayerInset.current + 8.dp
                    )
            ) {
                AlphabetIndexBar(
                    letters = IndexLetters,
                    onLetter = { letter ->
                        val at = IndexLetters.indexOf(letter)
                        val index = (IndexLetters.drop(at) + IndexLetters.take(at).reversed()).firstNotNullOfOrNull { firstIndexOf[it] }
                        if (index != null) jumpRequest = JumpRequest(index)
                    }
                )
            }
        }

        DetailTopBar(
            title = title,
            collapse = { collapse },
            onBack = if (searching) { { closeSearch(); Unit } } else onBack,
            favorite = favorite,
            onFavorite = onFavorite,
            menu = menu,
            statusBar = statusBar,
            modifier = Modifier.onSizeChanged { barHeightPx = it.height }
        )
    }
}

@Composable
internal fun HeroButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    active: Boolean = false,
    // The wide Play in the middle: filled with the ink, its icon and word in the page colour.
    primary: Boolean = false,
    onClick: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    // [active] (Shuffle while it's on): filled with the accent, so the state reads at a glance.
    val fill by animateColorAsState(
        when {
            primary -> ink
            active -> AppAccent
            else -> ink.copy(alpha = 0.1f)
        },
        tween(200),
        label = "heroButtonFill"
    )
    Box(
        Modifier
            .alpha(if (enabled) 1f else 0.42f)
            .then(if (primary) Modifier.width(168.dp).height(56.dp) else Modifier.size(54.dp))
            .clip(CircleShape)
            .background(fill)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        if (primary) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = paper, modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(6.dp))
                Text(description, color = paper, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            Icon(icon, description, tint = if (active) Color.White else ink, modifier = Modifier.size(26.dp))
        }
    }
}

/** "Playing · Shuffled" under the buttons of the list that's playing - pink while shuffled. A
 * still icon, not moving bars: an endless animation would keep the screen redrawing. */
@Composable
private fun PlaybackStatusLine(playing: Boolean, shuffled: Boolean) {
    val color = if (shuffled) AppAccent else ink.copy(alpha = 0.75f)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Text(
            (if (playing) "Playing" else "Paused") + (if (shuffled) " · Shuffled" else " · In order"),
            color = color,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun DetailTopBar(
    title: String,
    collapse: () -> Float,
    onBack: () -> Unit,
    favorite: Boolean?,
    onFavorite: () -> Unit,
    menu: (@Composable ColumnScope.(close: () -> Unit) -> Unit)?,
    statusBar: Dp,
    modifier: Modifier = Modifier
) {
    val progress = collapse()
    // Over the cover the buttons are white on a dark disc; once the bar has filled in with the
    // page colour they take the theme's own ink.
    val surface = lerp(Color.Black.copy(alpha = 0.34f), ink.copy(alpha = 0.08f), progress)
    val barInk = lerp(Color.White, ink, progress)
    var menuOpen by remember { mutableStateOf(false) }
    Box(
        modifier
            .fillMaxWidth()
            .background(paper.copy(alpha = progress * 0.96f))
            .padding(top = statusBar)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            title,
            color = ink,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 110.dp)
                .graphicsLayer { alpha = ((collapse() - 0.85f) / 0.15f).coerceIn(0f, 1f) }
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(surface)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBackIos, "Back", tint = barInk, modifier = Modifier.padding(start = 5.dp).size(18.dp))
            }
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.height(38.dp).clip(RoundedCornerShape(19.dp)).background(surface).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (favorite != null) {
                    Box(
                        Modifier.size(34.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onFavorite),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                            contentDescription = "Favorite",
                            tint = if (favorite) AppAccent else barInk,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    if (menu != null) {
                        Spacer(Modifier.width(1.dp).height(18.dp).background(barInk.copy(alpha = 0.25f)))
                    }
                }
                if (menu != null) {
                    Box {
                        Box(
                            Modifier.size(34.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { menuOpen = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.MoreHoriz, "More", tint = barInk, modifier = Modifier.size(26.dp))
                        }
                        LibraryFloatingMenu(expanded = menuOpen, onDismiss = { menuOpen = false }) {
                            menu { menuOpen = false }
                        }
                    }
                }
            }
        }
    }
}
