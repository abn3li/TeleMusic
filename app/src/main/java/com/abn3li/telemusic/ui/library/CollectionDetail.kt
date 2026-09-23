package com.abn3li.telemusic.ui.library

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
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
    val onOpenArtistSongs: (String) -> Unit
)

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
        hero = { HeroImage(list.firstNotNullOfOrNull { it.albumArtUrl ?: it.thumbnailPath }, Icons.Rounded.Album) },
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

    CollectionDetailPage(
        title = artist,
        subtitle = null,
        onSubtitleClick = null,
        detailLine = "${list.size} ${if (list.size == 1) "Song" else "Songs"}",
        hero = { HeroImage(list.firstNotNullOfOrNull { it.albumArtUrl ?: it.thumbnailPath }, Icons.Rounded.Album) },
        songs = list,
        loaded = songs != null,
        callbacks = callbacks,
        menu = { close ->
            LibraryMenuItem("Play Next", Icons.Rounded.QueuePlayNext) { list.forEach { callbacks.onPlayNext(it.telegramMessageId) }; close() }
        }
    ) { shown, searching ->
        val ids = shown.map { it.telegramMessageId }
        if (!searching) {
            item("songs_header") { SectionHeader("Songs") { callbacks.onOpenArtistSongs(artist) } }
        }
        val visible = if (searching) shown else shown.take(5)
        itemsIndexed(visible, key = { _, s -> s.telegramMessageId }, contentType = { _, _ -> "song" }) { index, song ->
            LibrarySongRow(song = song, actions = actions, onClick = { callbacks.onPlay(ids, index) })
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
                                color = Color.White.copy(alpha = 0.94f),
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

    PlaylistLikePage(
        title = playlistName,
        songs = songs,
        placeholder = Icons.AutoMirrored.Rounded.QueueMusic,
        viewModel = viewModel,
        callbacks = callbacks,
        menu = { close ->
            LibraryMenuItem("Play Next", Icons.Rounded.QueuePlayNext) { list.forEach { callbacks.onPlayNext(it.telegramMessageId) }; close() }
            LibraryMenuDivider()
            LibraryMenuItem(
                if (hidden) "Show in Songs" else "Hide from Songs",
                if (hidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff
            ) { scope.launch { repository.setPlaylistHiddenFromTracks(playlistId, !hidden) }; close() }
            LibraryMenuDivider()
            PinMenuItem(pinned) { viewModel.togglePin(playlistPinKey(playlistId)); close() }
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
        if (pinned) "Unpin from Library" else "Pin to Library",
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

@Composable
private fun PlaylistLikePage(
    title: String,
    songs: List<SongEntity>?,
    placeholder: ImageVector,
    viewModel: LibraryViewModel,
    callbacks: LibraryCallbacks,
    menu: @Composable ColumnScope.(close: () -> Unit) -> Unit
) {
    val list = songs.orEmpty()
    val actions = rememberLibrarySongActions(viewModel, callbacks.onPlayNext, callbacks.onOpenArtist, callbacks.onOpenAlbum)
    CollectionDetailPage(
        title = title,
        subtitle = null,
        onSubtitleClick = null,
        detailLine = songCountLine(list),
        hero = { HeroImage(list.firstNotNullOfOrNull { it.albumArtUrl ?: it.thumbnailPath }, placeholder) },
        songs = list,
        loaded = songs != null,
        callbacks = callbacks,
        menu = menu
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
private fun BoxScope.HeroImage(url: String?, placeholder: ImageVector) {
    Box(Modifier.matchParentSize().background(Color(0xFF2A2A2E)), contentAlignment = Alignment.Center) {
        Icon(placeholder, null, tint = Color.White.copy(alpha = 0.25f), modifier = Modifier.size(120.dp))
    }
    if (!url.isNullOrEmpty()) {
        AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
    }
}

@Composable
private fun SectionHeader(title: String, onMore: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(top = 20.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (onMore != null) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowForwardIos,
                contentDescription = "See all",
                tint = Color.White.copy(alpha = 0.7f),
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
    body: LazyListScope.(shown: List<SongEntity>, searching: Boolean) -> Unit
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val heroHeight = (configuration.screenHeightDp.dp * 0.52f).coerceIn(320.dp, 468.dp)
    val heroHeightPx = with(LocalDensity.current) { heroHeight.toPx() }
    var searching by rememberSaveable { mutableStateOf(false) }
    var searchText by rememberSaveable { mutableStateOf("") }
    val query by produceState(searchText, searchText) {
        if (searchText.isNotBlank()) delay(150)
        value = searchText
    }
    val shown = remember(songs, query) {
        if (query.isBlank()) songs
        else songs.filter { it.title.contains(query, true) || it.artist.contains(query, true) || it.album.orEmpty().contains(query, true) }
    }
    val ids = remember(shown) { shown.map { it.telegramMessageId } }
    val collapse by remember(searching) {
        derivedStateOf {
            when {
                searching -> 1f
                listState.firstVisibleItemIndex > 0 -> 1f
                else -> (listState.firstVisibleItemScrollOffset / (heroHeightPx * 0.62f)).coerceIn(0f, 1f)
            }
        }
    }
    val closeSearch = {
        searchText = ""
        searching = false
        scope.launch { listState.scrollToItem(0) }
    }
    if (searching) BackHandler { closeSearch() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            if (!searching) {
                item("hero") {
                    Box(Modifier.fillMaxWidth().height(heroHeight)) {
                        hero()
                        Box(
                            Modifier.matchParentSize().background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.Black.copy(alpha = 0.22f),
                                        Color.Black.copy(alpha = 0.46f),
                                        Color.Black.copy(alpha = 0.92f),
                                        Color.Black
                                    )
                                )
                            )
                        )
                        Column(
                            Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Spacer(Modifier.height(88.dp))
                            Spacer(Modifier.weight(1f))
                            Text(
                                title,
                                color = Color.White,
                                fontSize = 31.sp,
                                lineHeight = 36.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (subtitle != null) {
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    subtitle,
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = if (onSubtitleClick != null) Modifier.clickable(onClick = onSubtitleClick) else Modifier
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                detailLine,
                                color = Color.White.copy(alpha = 0.72f),
                                fontSize = 14.5.sp,
                                lineHeight = 20.sp,
                                textAlign = TextAlign.Center,
                                maxLines = 2
                            )
                            Spacer(Modifier.height(28.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                HeroButton(Icons.Rounded.Shuffle, "Shuffle", enabled = songs.isNotEmpty()) { callbacks.onPlayCollection(ids, true) }
                                HeroButton(Icons.Rounded.PlayArrow, "Play", enabled = songs.isNotEmpty()) { callbacks.onPlayCollection(ids, false) }
                                HeroButton(Icons.Rounded.Search, "Search", enabled = songs.isNotEmpty()) {
                                    searching = true
                                    scope.launch { listState.scrollToItem(0) }
                                }
                            }
                        }
                    }
                }
            } else {
                item("search") {
                    Box(Modifier.fillMaxWidth().padding(top = 64.dp)) {
                        LibrarySearchField(searchText, "Search in $title", { searchText = it })
                    }
                }
            }
            if (loaded && shown.isEmpty()) {
                item("empty") {
                    Text(
                        if (searching) "No results" else "No songs here yet",
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 15.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)
                    )
                }
            }
            body(shown, searching)
            item("bottom_inset") { Spacer(Modifier.height(LocalMiniPlayerInset.current + 24.dp)) }
        }

        DetailTopBar(
            title = title,
            collapse = { collapse },
            onBack = if (searching) { { closeSearch(); Unit } } else callbacks.onBack,
            favorite = favorite,
            onFavorite = onFavorite,
            menu = menu
        )
    }
}

@Composable
private fun HeroButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Box(
        Modifier
            .alpha(if (enabled) 1f else 0.42f)
            .size(56.dp)
            .clip(CircleShape)
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
        Icon(icon, description, tint = Color.White, modifier = Modifier.size(36.dp))
    }
}

@Composable
private fun DetailTopBar(
    title: String,
    collapse: () -> Float,
    onBack: () -> Unit,
    favorite: Boolean?,
    onFavorite: () -> Unit,
    menu: (@Composable ColumnScope.(close: () -> Unit) -> Unit)?
) {
    val progress = collapse()
    val surface = lerp(Color.Black.copy(alpha = 0.34f), Color.White.copy(alpha = 0.08f), progress)
    var menuOpen by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = progress * 0.96f))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            title,
            color = Color.White,
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
                Icon(Icons.AutoMirrored.Rounded.ArrowBackIos, "Back", tint = Color.White, modifier = Modifier.padding(start = 5.dp).size(18.dp))
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
                            tint = if (favorite) AppAccent else Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    if (menu != null) {
                        Spacer(Modifier.width(1.dp).height(18.dp).background(Color.White.copy(alpha = 0.25f)))
                    }
                }
                if (menu != null) {
                    Box {
                        Box(
                            Modifier.size(34.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { menuOpen = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.MoreHoriz, "More", tint = Color.White, modifier = Modifier.size(26.dp))
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
