package com.abn3li.telemusic.ui.library

import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import com.abn3li.telemusic.ui.nowplaying.LocalMiniPlayerInset
import androidx.compose.material.icons.rounded.Check
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MicExternalOn
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SortByAlpha
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.abn3li.telemusic.data.local.AlbumSummary
import com.abn3li.telemusic.data.local.ArtistSummary
import com.abn3li.telemusic.data.local.SongEntity
import com.abn3li.telemusic.repository.SortField
import kotlinx.coroutines.delay

/** Builds the song-row actions every library page shares. */
@Composable
internal fun rememberLibrarySongActions(
    viewModel: LibraryViewModel,
    onPlayNext: (Long) -> Unit,
    onGoToArtist: (String) -> Unit,
    onGoToAlbum: (String) -> Unit
): LibrarySongActions {
    val playlists by viewModel.playlists.collectAsState()
    val downloadingIds by viewModel.downloadingIds.collectAsState()
    return remember(playlists, downloadingIds, viewModel, onPlayNext, onGoToArtist, onGoToAlbum) {
        LibrarySongActions(
            playlists = playlists,
            downloadingIds = downloadingIds,
            onPlayNext = { onPlayNext(it.telegramMessageId) },
            onToggleFavorite = { viewModel.toggleFavorite(it) },
            onDownload = { viewModel.downloadSong(it) },
            onRemoveDownload = { viewModel.removeDownload(it) },
            onClearSong = { viewModel.clearSong(it) },
            onAddToPlaylist = { id, song -> viewModel.addSongToPlaylist(id, song) },
            onCreatePlaylistAndAdd = { name, song -> viewModel.createPlaylistAndAddSong(name, song) },
            onFindArtwork = { song, title, artist -> viewModel.editSongAndFetchArtwork(song, title, artist) },
            onGoToArtist = onGoToArtist,
            onGoToAlbum = onGoToAlbum
        )
    }
}

/** Debounced search text, so filtering a large library doesn't run on every keystroke. */
@Composable
private fun debounced(text: String): String {
    val value by produceState(text, text) {
        if (text.isNotBlank()) delay(150)
        value = text
    }
    return value
}

@Composable
fun LibraryHomeScreen(
    viewModel: LibraryViewModel,
    onOpenPlaylists: () -> Unit,
    onOpenArtists: () -> Unit,
    onOpenAlbums: () -> Unit,
    onOpenSongs: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPlaylist: (Long, String) -> Unit,
    onOpenSmartPlaylist: (SmartPlaylistKind) -> Unit
) {
    val pinned by viewModel.pinnedPlaylists.collectAsState()
    val pinnedRows = remember(pinned) { pinned.chunked(2) }
    LargeTitleList(
        title = "Library",
        titleTrailing = {
            Icon(
                Icons.Rounded.AccountCircle,
                contentDescription = "Settings",
                tint = AppAccent,
                modifier = Modifier
                    .size(34.dp)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onOpenSettings)
            )
        }
    ) {
        item("links") {
            Column(Modifier.fillMaxWidth()) {
                LibraryLinkRow(Icons.AutoMirrored.Rounded.QueueMusic, "Playlists", onOpenPlaylists)
                LibraryDivider(start = 60.dp)
                LibraryLinkRow(Icons.Rounded.MicExternalOn, "Artists", onOpenArtists)
                LibraryDivider(start = 60.dp)
                LibraryLinkRow(Icons.Rounded.Album, "Albums", onOpenAlbums)
                LibraryDivider(start = 60.dp)
                LibraryLinkRow(Icons.Rounded.MusicNote, "Songs", onOpenSongs)
                LibraryDivider(start = 60.dp)
            }
        }
        if (pinnedRows.isNotEmpty()) {
            item("pinned_header") {
                Text(
                    "Pinned",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 12.dp)
                )
            }
            items(pinnedRows, key = { row -> row.first().key }) { row ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    row.forEachIndexed { column, item ->
                        PinnedTile(
                            item = item,
                            onLeft = column == 0,
                            onOpen = {
                                if (item.smartKind != null) onOpenSmartPlaylist(item.smartKind)
                                else item.playlistId?.let { onOpenPlaylist(it, item.title) }
                            },
                            onUnpin = { viewModel.togglePin(item.key) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** One cover in the Library page's Pinned grid; long-press offers Unpin. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PinnedTile(item: PinnedPlaylist, onLeft: Boolean, onOpen: () -> Unit, onUnpin: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    var tileHeightPx by remember { mutableIntStateOf(0) }
    val gapPx = with(LocalDensity.current) { 6.dp.roundToPx() }
    Box(modifier.onSizeChanged { tileHeightPx = it.height }) {
        Column(
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onOpen,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    }
                )
        ) {
            if (item.smartKind != null) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(7.dp)).background(LibraryTileColor),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(item.smartKind.icon(), contentDescription = null, tint = AppAccent, modifier = Modifier.fillMaxSize(0.42f))
                }
            } else {
                CoverTile(item.artworkUrl, Modifier.fillMaxWidth().aspectRatio(1f), corner = 7, placeholder = Icons.AutoMirrored.Rounded.QueueMusic)
            }
            Spacer(Modifier.height(5.dp))
            Text(item.title, color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Playlist", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, lineHeight = 17.sp, maxLines = 1)
        }
        // Opens just under the tile, growing toward the screen's middle so it never runs off an edge.
        LibraryFloatingMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            alignStart = onLeft,
            offsetYPx = tileHeightPx + gapPx
        ) {
            LibraryMenuItem("Unpin", Icons.Rounded.PushPin) { menuOpen = false; onUnpin() }
        }
    }
}

/**
 * A plain song list page (all Songs, or an artist's full list): large title, sticky search,
 * Play/Shuffle, rows. [menu] fills the title bar's "⋯" menu when given.
 */
@Composable
internal fun SongListPage(
    title: String,
    songs: List<SongEntity>,
    actions: LibrarySongActions,
    onBack: () -> Unit,
    onPlay: (List<Long>, Int) -> Unit,
    onPlayCollection: (List<Long>, Boolean) -> Unit,
    menu: (@Composable (close: () -> Unit) -> Unit)? = null,
    // Set to show the A–Z strip: which text of a song it indexes (title/artist/album), and
    // whether the list runs Z→A.
    indexKey: ((SongEntity) -> String)? = null,
    indexDescending: Boolean = false
) {
    var searchText by rememberSaveable { mutableStateOf("") }
    val query = debounced(searchText)
    val shown = remember(songs, query) {
        if (query.isBlank()) songs
        else songs.filter {
            it.title.contains(query, true) || it.artist.contains(query, true) || it.album.orEmpty().contains(query, true)
        }
    }
    val shownIds = remember(shown) { shown.map { it.telegramMessageId } }
    var menuOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val stickyPx = with(LocalDensity.current) { SearchStickyHeight.toPx() }

    // Letter -> position of the first song under it. One pass over the list, only redone when the
    // list or sort changes - never while scrolling.
    val indexActive = indexKey != null && query.isBlank() && shown.isNotEmpty()
    val barLetters = remember(indexDescending) { if (indexDescending) IndexLetters.dropLast(1).reversed() + "#" else IndexLetters }
    val firstIndexOf = remember(shown, indexKey) {
        if (indexKey == null) emptyMap()
        else HashMap<String, Int>().apply {
            shown.forEachIndexed { i, song -> putIfAbsent(indexLetterOf(indexKey(song)), i) }
        }
    }
    // Latest song position the strip asked for. Collected through snapshotFlow, so a fast slide
    // that crosses several letters within one frame costs one jump (to the newest), not several.
    var jumpTarget by remember { mutableIntStateOf(-1) }
    LaunchedEffect(listState) {
        snapshotFlow { jumpTarget }.collect { target ->
            // Items before the songs: large title, sticky search space, Play/Shuffle. The negative
            // offset leaves the song just below the sticky search bar in a single layout pass.
            if (target >= 0) listState.scrollToItem(SONG_LIST_HEADER_ITEMS + target, -stickyPx.toInt())
        }
    }
    fun jumpTo(letter: String) {
        // A letter with no songs lands on the next one that has some (then the previous), like iOS.
        val at = barLetters.indexOf(letter)
        jumpTarget = (barLetters.drop(at) + barLetters.take(at).reversed()).firstNotNullOfOrNull { firstIndexOf[it] } ?: return
    }

    LargeTitleList(
        title = title,
        onBack = onBack,
        listState = listState,
        overlay = if (!indexActive) null else {
            {
                AlphabetIndexBar(
                    letters = barLetters,
                    onLetter = ::jumpTo,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(top = LibraryBarHeight + SearchStickyHeight + 8.dp, bottom = LocalMiniPlayerInset.current + 8.dp)
                )
            }
        },
        barActions = if (menu == null) null else {
            {
                Box {
                    TitleBarCircleButton(contentDescription = "More", onClick = { menuOpen = true })
                    LibraryFloatingMenu(expanded = menuOpen, onDismiss = { menuOpen = false }) {
                        menu { menuOpen = false }
                    }
                }
            }
        },
        stickyContent = { LibrarySearchField(searchText, "Search in $title", { searchText = it }) }
    ) {
        item("play_shuffle") {
            PlayShuffleButtons(
                enabled = shown.isNotEmpty(),
                onPlay = { onPlayCollection(shownIds, false) },
                onShuffle = { onPlayCollection(shownIds, true) }
            )
        }
        if (shown.isEmpty()) {
            item("empty") {
                Text(
                    if (query.isBlank()) "No songs yet" else "No results",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 17.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)
                )
            }
        }
        itemsIndexed(shown, key = { _, song -> song.telegramMessageId }, contentType = { _, _ -> "song" }) { index, song ->
            LibrarySongRow(song = song, actions = actions, onClick = { onPlay(shownIds, index) })
            if (index < shown.lastIndex) LibraryDivider(start = 88.dp)
        }
    }
}

private const val SONG_LIST_HEADER_ITEMS = 3

@Composable
fun LibrarySongsScreen(
    viewModel: LibraryViewModel,
    onBack: () -> Unit,
    onPlay: (List<Long>, Int) -> Unit,
    onPlayCollection: (List<Long>, Boolean) -> Unit,
    onPlayNext: (Long) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String) -> Unit
) {
    val songs by viewModel.tracks.collectAsState()
    val sortField by viewModel.sortField.collectAsState()
    val ascending by viewModel.ascending.collectAsState()
    val showIndex by viewModel.showAlphabetIndex.collectAsState()
    val actions = rememberLibrarySongActions(viewModel, onPlayNext, onOpenArtist, onOpenAlbum)
    // The strip follows the current sort; "Recently Added" has no letters to index.
    val indexKey: ((SongEntity) -> String)? = if (!showIndex) null else when (sortField) {
        SortField.TITLE -> { song -> song.title }
        SortField.ARTIST -> { song -> song.artist }
        SortField.ALBUM -> { song -> song.album.orEmpty() }
        SortField.DATE_ADDED -> null
    }

    SongListPage(
        title = "Songs",
        songs = songs,
        actions = actions,
        onBack = onBack,
        onPlay = onPlay,
        onPlayCollection = onPlayCollection,
        indexKey = indexKey,
        indexDescending = !ascending,
        menu = { close ->
            SortMenuItem("Title", Icons.Rounded.SortByAlpha, sortField == SortField.TITLE) { viewModel.selectSortField(SortField.TITLE); close() }
            LibraryMenuDivider()
            SortMenuItem("Artist", Icons.Rounded.Person, sortField == SortField.ARTIST) { viewModel.selectSortField(SortField.ARTIST); close() }
            LibraryMenuDivider()
            SortMenuItem("Album", Icons.Rounded.Album, sortField == SortField.ALBUM) { viewModel.selectSortField(SortField.ALBUM); close() }
            LibraryMenuDivider()
            SortMenuItem("Recently Added", Icons.Rounded.Schedule, sortField == SortField.DATE_ADDED) { viewModel.selectSortField(SortField.DATE_ADDED); close() }
            LibraryMenuGroupGap()
            SortMenuItem("Ascending", Icons.Rounded.ArrowUpward, ascending) { viewModel.setAscending(true); close() }
            LibraryMenuDivider()
            SortMenuItem("Descending", Icons.Rounded.ArrowDownward, !ascending) { viewModel.setAscending(false); close() }
            LibraryMenuGroupGap()
            LibraryMenuItem(
                label = "Alphabet Index",
                icon = if (showIndex) Icons.Rounded.Check else null,
                selected = showIndex
            ) { viewModel.setShowAlphabetIndex(!showIndex); close() }
        }
    )
}

@Composable
private fun SortMenuItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    LibraryMenuItem(label = label, icon = icon, selected = selected, onClick = onClick)
}

@Composable
fun LibraryAlbumsScreen(viewModel: LibraryViewModel, onBack: () -> Unit, onOpenAlbum: (String) -> Unit) {
    val albums by viewModel.albums.collectAsState()
    var searchText by rememberSaveable { mutableStateOf("") }
    val query = debounced(searchText)
    val shown = remember(albums, query) {
        if (query.isBlank()) albums else albums.filter { it.album.contains(query, true) || it.artist.contains(query, true) }
    }
    LargeTitleGrid(
        title = "Albums",
        onBack = onBack,
        stickyContent = { LibrarySearchField(searchText, "Search in Albums", { searchText = it }) }
    ) {
        items(shown, key = { it.album }) { album -> AlbumGridItem(album) { onOpenAlbum(album.album) } }
    }
}

@Composable
internal fun AlbumGridItem(album: AlbumSummary, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
    ) {
        CoverTile(album.albumArtUrl, Modifier.fillMaxWidth().aspectRatio(1f), corner = 7)
        Spacer(Modifier.height(5.dp))
        Text(album.album, color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            "${album.songCount} ${if (album.songCount == 1) "Song" else "Songs"}",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 13.sp,
            lineHeight = 17.sp,
            maxLines = 1
        )
    }
}

/** Square artwork with a soft grey music-note placeholder behind it. */
@Composable
internal fun CoverTile(url: String?, modifier: Modifier, corner: Int, placeholder: ImageVector = Icons.Rounded.MusicNote) {
    Box(modifier.clip(RoundedCornerShape(corner.dp)).background(LibraryTileColor), contentAlignment = Alignment.Center) {
        Icon(placeholder, null, tint = Color(0xFFC7C7CC), modifier = Modifier.fillMaxSize(0.42f))
        if (!url.isNullOrEmpty()) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
fun LibraryArtistsScreen(viewModel: LibraryViewModel, onBack: () -> Unit, onOpenArtist: (String) -> Unit) {
    val artists by viewModel.artists.collectAsState()
    var searchText by rememberSaveable { mutableStateOf("") }
    val query = debounced(searchText)
    val shown = remember(artists, query) {
        if (query.isBlank()) artists else artists.filter { it.artist.contains(query, true) }
    }
    LargeTitleList(
        title = "Artists",
        onBack = onBack,
        stickyContent = { LibrarySearchField(searchText, "Search in Artists", { searchText = it }) }
    ) {
        itemsIndexed(shown, key = { _, artist -> artist.artist }) { index, artist ->
            ArtistRow(artist) { onOpenArtist(artist.artist) }
            if (index < shown.lastIndex) LibraryDivider(start = 81.dp)
        }
    }
}

@Composable
private fun ArtistRow(artist: ArtistSummary, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(62.dp)
            .clickable(onClick = onClick)
            .padding(start = 18.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ArtistAvatar(artist.albumArtUrl, 48)
        Text(
            artist.artist,
            color = Color.White,
            fontSize = 16.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 15.dp)
        )
        ChevronIcon()
    }
}

@Composable
internal fun ArtistAvatar(url: String?, sizeDp: Int) {
    Box(Modifier.size(sizeDp.dp).clip(CircleShape).background(Color(0xFF8E8E93)), contentAlignment = Alignment.BottomCenter) {
        Icon(Icons.Rounded.Person, null, tint = Color(0xFFE5E5EA), modifier = Modifier.fillMaxSize(0.8f))
        if (!url.isNullOrEmpty()) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
fun LibraryPlaylistsScreen(
    viewModel: LibraryViewModel,
    onBack: () -> Unit,
    onOpenPlaylist: (Long, String) -> Unit,
    onOpenSmartPlaylist: (SmartPlaylistKind) -> Unit
) {
    val playlists by viewModel.playlistSummaries.collectAsState()
    var searchText by rememberSaveable { mutableStateOf("") }
    val query = debounced(searchText)
    var showCreate by remember { mutableStateOf(false) }
    val smart = remember(query) {
        SmartPlaylistKind.entries.filter { query.isBlank() || it.label.contains(query, true) }
    }
    val shown = remember(playlists, query) {
        if (query.isBlank()) playlists else playlists.filter { it.name.contains(query, true) }
    }

    LargeTitleList(
        title = "Playlists",
        onBack = onBack,
        stickyContent = { LibrarySearchField(searchText, "Search in Playlists", { searchText = it }) }
    ) {
        item("create") {
            PlaylistRow(label = "Create New Playlist…", tile = { IconTile(Icons.Rounded.Add) }) { showCreate = true }
            LibraryDivider(start = 96.dp)
        }
        smart.forEach { kind ->
            item("smart_${kind.name}") {
                PlaylistRow(label = kind.label, tile = { IconTile(kind.icon()) }) { onOpenSmartPlaylist(kind) }
                LibraryDivider(start = 96.dp)
            }
        }
        itemsIndexed(shown, key = { _, p -> p.id }) { index, playlist ->
            PlaylistRow(
                label = playlist.name,
                tile = { CoverTile(playlist.albumArtUrl, Modifier.size(60.dp), corner = 4, placeholder = Icons.AutoMirrored.Rounded.QueueMusic) }
            ) { onOpenPlaylist(playlist.id, playlist.name) }
            if (index < shown.lastIndex) LibraryDivider(start = 96.dp)
        }
    }

    if (showCreate) {
        NewPlaylistDialog(
            onCreate = { name -> viewModel.createPlaylist(name); showCreate = false },
            onDismiss = { showCreate = false }
        )
    }
}

internal fun SmartPlaylistKind.icon(): ImageVector = when (this) {
    SmartPlaylistKind.LIKED -> Icons.Rounded.Star
    SmartPlaylistKind.DOWNLOADED -> Icons.Rounded.Download
    SmartPlaylistKind.TELEGRAM -> Icons.AutoMirrored.Rounded.Send
}

@Composable
private fun PlaylistRow(label: String, tile: @Composable () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(76.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tile()
        Text(
            label,
            color = Color.White,
            fontSize = 17.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 16.dp, end = 12.dp)
        )
        ChevronIcon()
    }
}

@Composable
internal fun IconTile(icon: ImageVector, sizeDp: Int = 60) {
    Box(
        Modifier.size(sizeDp.dp).clip(RoundedCornerShape(4.dp)).background(LibraryTileColor),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = AppAccent, modifier = Modifier.size((sizeDp * 0.5f).dp))
    }
}

@Composable
internal fun NewPlaylistDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AppAlert(
        title = "New Playlist",
        message = "Enter a name for this playlist.",
        onDismiss = onDismiss,
        actions = listOf(
            AlertAction("Cancel", onClick = onDismiss),
            AlertAction("Create", bold = true, enabled = name.isNotBlank()) { onCreate(name.trim()) }
        )
    ) {
        AlertTextField(name, { name = it }, "Playlist name")
    }
}
