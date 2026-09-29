package com.abn3li.telemusic.ui.search

import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.combinedClickable
import android.net.Uri
import com.abn3li.telemusic.data.browse.BrowseTrack
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.data.browse.BrowseCollection
import com.abn3li.telemusic.data.browse.BrowseKind
import com.abn3li.telemusic.data.download.YtDlpSearchResult
import com.abn3li.telemusic.data.local.AlbumSummary
import com.abn3li.telemusic.data.local.ArtistSummary
import com.abn3li.telemusic.data.local.ImportedPlaylistEntity
import com.abn3li.telemusic.data.local.PlaylistSummary
import com.abn3li.telemusic.data.local.SongEntity
import com.abn3li.telemusic.ui.download.CenteredMessage
import com.abn3li.telemusic.ui.download.CenteredSpinner
import com.abn3li.telemusic.ui.download.CollectionCard
import com.abn3li.telemusic.ui.download.DiscoveryUiState
import com.abn3li.telemusic.ui.download.DiscoveryViewModel
import com.abn3li.telemusic.ui.download.ImportPlaylistPrompt
import com.abn3li.telemusic.ui.download.Thumbnail
import com.abn3li.telemusic.ui.download.YouTubeDownloadUiState
import com.abn3li.telemusic.ui.download.YouTubeDownloadViewModel
import com.abn3li.telemusic.ui.library.AppAccent
import com.abn3li.telemusic.ui.library.ArtistAvatar
import com.abn3li.telemusic.ui.library.CalmSpinner
import com.abn3li.telemusic.ui.library.CoverTile
import com.abn3li.telemusic.ui.library.DestructiveRed
import com.abn3li.telemusic.ui.library.GroupLabelColor
import com.abn3li.telemusic.ui.library.LargeTitleList
import com.abn3li.telemusic.ui.library.LibraryCallbacks
import com.abn3li.telemusic.ui.library.LibraryDivider
import com.abn3li.telemusic.ui.library.LibraryFieldColor
import com.abn3li.telemusic.ui.library.LibraryFloatingMenu
import com.abn3li.telemusic.ui.library.LibraryMenuDivider
import com.abn3li.telemusic.ui.library.LibraryMenuItem
import android.widget.Toast
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.material.icons.rounded.QueuePlayNext
import com.abn3li.telemusic.ui.library.SwipeToPlayNext
import com.abn3li.telemusic.ui.library.LibrarySearchField
import com.abn3li.telemusic.ui.library.LibrarySongActions
import com.abn3li.telemusic.ui.library.LibrarySongRow
import com.abn3li.telemusic.ui.library.LibraryViewModel
import com.abn3li.telemusic.ui.library.rememberLibrarySongActions
import com.abn3li.telemusic.ui.nowplaying.LocalMiniPlayerInset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val YOUTUBE_SEARCH_DELAY_MS = 700L
private const val SEARCH_SHELF_PREVIEW_LIMIT = 7
private val ResultsHeaderHeight = 112.dp

private enum class SearchSource { YOUTUBE, LIBRARY }
private enum class ResultSection { OVERVIEW, SONGS, ALBUMS, ARTISTS, PLAYLISTS }

private data class LibraryMatches(
    val songs: List<SongEntity> = emptyList(),
    val albums: List<AlbumSummary> = emptyList(),
    val artists: List<ArtistSummary> = emptyList(),
    val playlists: List<PlaylistSummary> = emptyList()
) { val isEmpty get() = songs.isEmpty() && albums.isEmpty() && artists.isEmpty() && playlists.isEmpty() }

@Composable
fun SearchScreen(
    libraryViewModel: LibraryViewModel,
    callbacks: LibraryCallbacks,
    onOpenPlaylist: (Long, String) -> Unit,
    onOpenCollection: (BrowseCollection) -> Unit,
    onPlayTracks: (tracks: List<BrowseTrack>, index: Int, shuffle: Boolean) -> Unit
) {
    val app = LocalContext.current.applicationContext as TgMusicApp
    val search = viewModel<YouTubeDownloadViewModel>(factory = viewModelFactory { initializer {
        YouTubeDownloadViewModel(app.applicationContext, app.ytDlpRepository, app.musicRepository,
            app.settingsStore, app.discoveryRepository, app.workScope)
    } })
    val state by search.uiState.collectAsState()
    val songs by libraryViewModel.allSongs.collectAsState()
    val albums by libraryViewModel.albums.collectAsState()
    val artists by libraryViewModel.artists.collectAsState()
    val playlists by libraryViewModel.playlistSummaries.collectAsState()
    val actions = rememberLibrarySongActions(libraryViewModel, callbacks.onPlayNext, callbacks.onOpenArtist, callbacks.onOpenAlbum)
    val query = state.query.trim()
    val library by produceState(LibraryMatches(), query, songs, albums, artists, playlists) {
        if (query.isEmpty()) { value = LibraryMatches(); return@produceState }
        delay(120)
        value = withContext(Dispatchers.Default) {
            LibraryMatches(
                songs.filter { it.title.contains(query, true) || it.artist.contains(query, true) || it.album?.contains(query, true) == true },
                albums.filter { it.album.contains(query, true) || it.artist.contains(query, true) },
                artists.filter { it.artist.contains(query, true) },
                playlists.filter { it.name.contains(query, true) }
            )
        }
    }
    // Saveable: opening a result leaves this screen, and Back should land on the same page
    // (YouTube Music / Your Library) and list (overview / See all) as before.
    var source by rememberSaveable { mutableStateOf(SearchSource.YOUTUBE) }
    var section by rememberSaveable { mutableStateOf(ResultSection.OVERVIEW) }
    // The results field should take over focus only for the Browse -> Results transition. When
    // this destination is recreated after returning from an album/artist/playlist, this starts
    // false, so Search is restored without reopening the keyboard.
    var focusResultsField by remember { mutableStateOf(false) }
    LaunchedEffect(query, source) {
        section = ResultSection.OVERVIEW
        if (query.isNotEmpty() && source == SearchSource.YOUTUBE) {
            delay(YOUTUBE_SEARCH_DELAY_MS); search.search()
        }
    }
    val discovery = viewModel<DiscoveryViewModel>(factory = viewModelFactory { initializer {
        DiscoveryViewModel(app.ytDlpRepository, app.discoveryRepository)
    } })
    val feed by discovery.uiState.collectAsState()
    var showImport by remember { mutableStateOf(false) }
    if (showImport) ImportPlaylistPrompt(discovery) { showImport = false }
    BackHandler(query.isNotEmpty()) {
        if (section != ResultSection.OVERVIEW) section = ResultSection.OVERVIEW else search.onQueryChange("")
    }
    if (query.isEmpty()) {
        BrowseScreen(state.query, { value ->
            if (value.isNotEmpty()) focusResultsField = true
            search.onQueryChange(value)
        }, feed, onOpenCollection, { showImport = true },
            discovery::removeImportedPlaylist, discovery::reload)
    } else {
        ResultsScreen(state, source, section, library, actions, callbacks,
            { source = it }, { section = it }, search::onQueryChange, { search.search(true) },
            { search.onQueryChange("") }, onOpenPlaylist, onOpenCollection,
            // Queues every song result from the tapped one, so Next / Previous walk the results.
            { result ->
                val tracks = state.results.map { BrowseTrack(it.videoId, it.title, it.artist, it.thumbnailUrl, it.durationSeconds) }
                onPlayTracks(tracks, state.results.indexOfFirst { it.videoId == result.videoId }.coerceAtLeast(0), false)
            },
            { result ->
                callbacks.onPlayNext(app.musicRepository.queueIdsForStreams(listOf(
                    BrowseTrack(result.videoId, result.title, result.artist, result.thumbnailUrl, result.durationSeconds))).first())
                Toast.makeText(app, "Playing next", Toast.LENGTH_SHORT).show()
            },
            { result -> app.downloadGate.run { search.onDownloadIconClick(result) } },
            focusResultsField,
            { focusResultsField = false })
    }
}

@Composable
private fun BrowseScreen(
    query: String, onQueryChange: (String) -> Unit, feed: DiscoveryUiState,
    onOpenCollection: (BrowseCollection) -> Unit, onImport: () -> Unit,
    onRemove: (ImportedPlaylistEntity) -> Unit, onRetry: () -> Unit
) {
    LargeTitleList(title = "Search", stickyContent = {
        LibrarySearchField(query, "Songs, artists, albums", onQueryChange)
    }) {
        item("imported_header") { Header("Your Imported Playlists", action = {
            Icon(Icons.Rounded.Add, "Import playlist", tint = AppAccent,
                modifier = Modifier.size(30.dp).clickable(onClick = onImport))
        }) }
        if (feed.importedPlaylists.isEmpty()) item("imported_empty") {
            Text("Tap + to add a YouTube or Spotify playlist.", color = GroupLabelColor,
                fontSize = 14.sp, modifier = Modifier.padding(horizontal = 18.dp))
        } else item("imported") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 18.dp)) {
                itemsIndexed(feed.importedPlaylists, key = { index, p -> "imported_${index}_${p.browseId}" }) { _, p -> ImportedCard(p, {
                    onOpenCollection(BrowseCollection(p.browseId, null, p.title, p.subtitle, p.thumbnailUrl, BrowseKind.PLAYLIST))
                }, { onRemove(p) }) }
            }
        }
        when {
            feed.isLoading -> item("loading") { CenteredSpinner() }
            feed.genres.isEmpty() -> item("error") {
                Box(Modifier.fillMaxWidth().clickable(onClick = onRetry)) { CenteredMessage("Couldn't load YouTube Music. Tap to try again.") }
            }
            else -> browseContent(feed.genres, onOpenCollection)
        }
    }
}

private fun LazyListScope.browseContent(genres: List<BrowseCollection>, open: (BrowseCollection) -> Unit) {
    if (genres.isNotEmpty()) {
        item("categories_header") { Header("Browse Categories") }
        itemsIndexed(
            genres.chunked(2),
            key = { index, pair -> "genre_row_${index}_${pair.joinToString("|") { it.title }}" }
        ) { _, pair ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { genre -> GenreTile(genre.title, GenreColors[genres.indexOf(genre) % GenreColors.size], Modifier.weight(1f)) { open(genre) } }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultsScreen(
    state: YouTubeDownloadUiState, source: SearchSource, section: ResultSection,
    library: LibraryMatches, actions: LibrarySongActions, callbacks: LibraryCallbacks,
    onSource: (SearchSource) -> Unit, onSection: (ResultSection) -> Unit,
    onQuery: (String) -> Unit, onSearch: () -> Unit, onCancel: () -> Unit,
    openPlaylist: (Long, String) -> Unit, openCollection: (BrowseCollection) -> Unit,
    play: (YtDlpSearchResult) -> Unit, playNext: (YtDlpSearchResult) -> Unit, download: (YtDlpSearchResult) -> Unit,
    requestFocus: Boolean, onFocusHandled: () -> Unit
) {
    val loading = state.isSearching || state.searchedQuery != state.query.trim()
    val pagerState = rememberPagerState(
        initialPage = if (source == SearchSource.YOUTUBE) 0 else 1,
        pageCount = { SearchSource.entries.size }
    )
    val scope = rememberCoroutineScope()
    val pagerSource = SearchSource.entries[pagerState.currentPage]
    LaunchedEffect(pagerState.currentPage) {
        val selected = SearchSource.entries[pagerState.currentPage]
        if (selected != source) onSource(selected)
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = ResultsHeaderHeight, bottom = LocalMiniPlayerInset.current + 24.dp)) {
                if (page == 0) youtubeResults(section, state, loading, onSection, openCollection, play, playNext, download)
                else libraryResults(section, library, actions, callbacks, onSection, openPlaylist)
            }
        }
        ResultsHeader(state.query, pagerSource, onQuery, onSearch, onCancel, { selected ->
            scope.launch { pagerState.animateScrollToPage(selected.ordinal) }
        }, requestFocus, onFocusHandled)
    }
}

@Composable
private fun ResultsHeader(query: String, source: SearchSource, onQuery: (String) -> Unit,
    onSearch: () -> Unit, onCancel: () -> Unit, onSource: (SearchSource) -> Unit,
    requestFocus: Boolean, onFocusHandled: () -> Unit) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val rootView = LocalView.current
    var fieldValue by remember {
        mutableStateOf(TextFieldValue(query, selection = TextRange(query.length)))
    }
    LaunchedEffect(query) {
        if (query != fieldValue.text) {
            fieldValue = TextFieldValue(query, selection = TextRange(query.length))
        }
    }
    LaunchedEffect(requestFocus) {
        if (requestFocus) {
            focus.requestFocus()
            onFocusHandled()
        }
    }
    // Hiding the keyboard with Back does not normally remove BasicTextField focus. Its blinking
    // cursor would then keep invalidating this screen while it appears idle. Root-window insets
    // are used instead of Compose IME insets because the latter remain consumed/unchanged on
    // some Samsung builds after the keyboard closes.
    DisposableEffect(rootView, focusManager) {
        fun isImeVisible() = ViewCompat.getRootWindowInsets(rootView)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        var imeWasVisible = isImeVisible()
        val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            val visible = isImeVisible()
            if (visible) imeWasVisible = true
            else if (imeWasVisible) {
                imeWasVisible = false
                focusManager.clearFocus()
            }
        }
        rootView.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose { rootView.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
    Column(Modifier.fillMaxWidth().height(ResultsHeaderHeight).background(Color.Black).padding(horizontal = 18.dp).padding(top = 5.dp, bottom = 9.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(10.dp)).background(LibraryFieldColor).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Search, null, tint = Color.White.copy(.55f), modifier = Modifier.size(19.dp)); Spacer(Modifier.width(8.dp))
                BasicTextField(fieldValue, { value ->
                    fieldValue = value
                    onQuery(value.text)
                }, Modifier.weight(1f).focusRequester(focus), textStyle = TextStyle(Color.White, fontSize = 17.sp),
                    cursorBrush = SolidColor(AppAccent), singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        onSearch()
                        focusManager.clearFocus()
                        keyboard?.hide()
                    }))
                Icon(Icons.Rounded.Cancel, "Clear", tint = Color.White.copy(.45f), modifier = Modifier.size(20.dp).clickable { onQuery("") })
            }
            Text("Cancel", color = AppAccent, fontSize = 16.sp, modifier = Modifier.clickable(onClick = onCancel))
        }
        Row(Modifier.fillMaxWidth().height(38.dp).clip(RoundedCornerShape(9.dp)).background(LibraryFieldColor).padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            SearchSource.entries.forEach { item ->
                val selected = item == source
                Box(Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(7.dp))
                    .background(if (selected) Color(0xFF3A3A3E) else Color.Transparent)
                    .clickable(remember { MutableInteractionSource() }, null) { onSource(item) }, contentAlignment = Alignment.Center) {
                    Text(if (item == SearchSource.YOUTUBE) "YouTube Music" else "Your Library",
                        color = if (selected) Color.White else Color.White.copy(.65f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private fun LazyListScope.youtubeResults(section: ResultSection, state: YouTubeDownloadUiState, loading: Boolean,
    show: (ResultSection) -> Unit, open: (BrowseCollection) -> Unit, play: (YtDlpSearchResult) -> Unit, playNext: (YtDlpSearchResult) -> Unit, download: (YtDlpSearchResult) -> Unit) {
    state.actionError?.let { item("action_error") { Text(it, color = DestructiveRed, fontSize = 14.sp, modifier = Modifier.padding(18.dp, 8.dp)) } }
    if (loading) { item("loading") { CenteredSpinner() }; return }
    if (section != ResultSection.OVERVIEW) {
        item("section_title") { Header(section.title(), compact = true) }
        when (section) {
            ResultSection.SONGS -> songRows(state.results, state, play, playNext, download)
            ResultSection.ALBUMS -> collectionRows(state.albums, "No albums found", open)
            ResultSection.ARTISTS -> collectionRows(state.artists, "No artists found", open)
            ResultSection.PLAYLISTS -> collectionRows(state.playlists, "No playlists found", open)
            else -> Unit
        }; return
    }
    val top = buildList<Any> {
        state.artists.firstOrNull()?.let(::add); state.results.firstOrNull()?.let(::add)
        state.albums.firstOrNull()?.let(::add); state.playlists.firstOrNull()?.let(::add)
    }.take(3)
    if (top.isEmpty()) { item("empty") { CenteredMessage(state.errorMessage ?: "Nothing found on YouTube Music") }; return }
    item("top_header") { Header("Top Results", compact = true) }
    itemsIndexed(top) { index, value ->
        when (value) {
            is YtDlpSearchResult -> YoutubeSongRow(value, state, false, "Song · ", play, playNext, download)
            is BrowseCollection -> CollectionRow(value, open)
        }
        if (index < top.lastIndex) LibraryDivider(80.dp)
    }
    if (state.results.isNotEmpty()) {
        item("songs_header") { Header("Songs", { show(ResultSection.SONGS) }) }
        itemsIndexed(state.results.take(4), key = { index, it -> "song_preview_${index}_${it.videoId}" }) { index, song ->
            YoutubeSongRow(song, state, true, "", play, playNext, download); if (index < state.results.take(4).lastIndex) LibraryDivider(80.dp)
        }
    }
    youtubeShelf("Albums", ResultSection.ALBUMS, state.albums, show, open)
    youtubeShelf("Artists", ResultSection.ARTISTS, state.artists, show, open)
    youtubeShelf("Playlists", ResultSection.PLAYLISTS, state.playlists, show, open)
}

private fun LazyListScope.libraryResults(section: ResultSection, data: LibraryMatches, actions: LibrarySongActions,
    callbacks: LibraryCallbacks, show: (ResultSection) -> Unit, openPlaylist: (Long, String) -> Unit) {
    if (data.isEmpty) { item("empty") { CenteredMessage("Nothing in your library matches") }; return }
    if (section != ResultSection.OVERVIEW) {
        item("section_title") { Header(section.title(), compact = true) }
        when (section) {
            ResultSection.SONGS -> librarySongs(data.songs, actions, callbacks)
            ResultSection.ALBUMS -> itemsIndexed(data.albums) { i, a -> CollectionRow(a.album, "Album · ${a.artist}", false, a.albumArtUrl) { callbacks.onOpenAlbum(a.album) }; if (i < data.albums.lastIndex) LibraryDivider(80.dp) }
            ResultSection.ARTISTS -> itemsIndexed(data.artists) { i, a -> CollectionRow(a.artist, "Artist · ${a.songCount} songs", true, a.albumArtUrl) { callbacks.onOpenArtist(a.artist) }; if (i < data.artists.lastIndex) LibraryDivider(80.dp) }
            ResultSection.PLAYLISTS -> itemsIndexed(data.playlists) { i, p -> CollectionRow(p.name, "Playlist", false, p.albumArtUrl) { openPlaylist(p.id, p.name) }; if (i < data.playlists.lastIndex) LibraryDivider(80.dp) }
            else -> Unit
        }; return
    }
    val top = buildList<Any> { data.artists.firstOrNull()?.let(::add); data.songs.firstOrNull()?.let(::add); data.albums.firstOrNull()?.let(::add); data.playlists.firstOrNull()?.let(::add) }.take(3)
    item("top_header") { Header("Top Results", compact = true) }
    itemsIndexed(top) { index, value ->
        when (value) {
            is SongEntity -> LibrarySongRow(value, actions, { callbacks.onPlay(data.songs.map { it.telegramMessageId }, data.songs.indexOf(value)) },
                subtitle = "Song · ${value.artist}", horizontalPadding = 18.dp)
            is AlbumSummary -> CollectionRow(value.album, "Album · ${value.artist}", false, value.albumArtUrl) { callbacks.onOpenAlbum(value.album) }
            is ArtistSummary -> CollectionRow(value.artist, "Artist · ${value.songCount} songs", true, value.albumArtUrl) { callbacks.onOpenArtist(value.artist) }
            is PlaylistSummary -> CollectionRow(value.name, "Playlist", false, value.albumArtUrl) { openPlaylist(value.id, value.name) }
        }; if (index < top.lastIndex) LibraryDivider(80.dp)
    }
    if (data.songs.isNotEmpty()) {
        item("songs_header") { Header("Songs", { show(ResultSection.SONGS) }) }
        val ids = data.songs.map { it.telegramMessageId }
        itemsIndexed(data.songs.take(4), key = { _, it -> it.telegramMessageId }) { i, song ->
            LibrarySongRow(song, actions, { callbacks.onPlay(ids, i) }, horizontalPadding = 18.dp)
            if (i < data.songs.take(4).lastIndex) LibraryDivider(80.dp)
        }
    }
    libraryShelf("Albums", ResultSection.ALBUMS, data.albums, show) { a -> MediaCard(a.album, a.artist, false, a.albumArtUrl) { callbacks.onOpenAlbum(a.album) } }
    libraryShelf("Artists", ResultSection.ARTISTS, data.artists, show) { a -> MediaCard(a.artist, "Artist", true, a.albumArtUrl) { callbacks.onOpenArtist(a.artist) } }
    libraryShelf("Playlists", ResultSection.PLAYLISTS, data.playlists, show) { p -> MediaCard(p.name, "Playlist", false, p.albumArtUrl) { openPlaylist(p.id, p.name) } }
}

private fun LazyListScope.songRows(songs: List<YtDlpSearchResult>, state: YouTubeDownloadUiState,
    play: (YtDlpSearchResult) -> Unit, playNext: (YtDlpSearchResult) -> Unit, download: (YtDlpSearchResult) -> Unit) {
    if (songs.isEmpty()) item("no_songs") { CenteredMessage("No songs found") }
    itemsIndexed(songs, key = { index, it -> "song_${index}_${it.videoId}" }) { i, song -> YoutubeSongRow(song, state, true, "", play, playNext, download); if (i < songs.lastIndex) LibraryDivider(80.dp) }
}

private fun LazyListScope.librarySongs(songs: List<SongEntity>, actions: LibrarySongActions, callbacks: LibraryCallbacks) {
    if (songs.isEmpty()) item("no_songs") { CenteredMessage("No songs found") }
    val ids = songs.map { it.telegramMessageId }
    itemsIndexed(songs, key = { _, it -> it.telegramMessageId }) { i, song ->
        LibrarySongRow(song, actions, { callbacks.onPlay(ids, i) }, horizontalPadding = 18.dp); if (i < songs.lastIndex) LibraryDivider(80.dp)
    }
}

private fun LazyListScope.collectionRows(values: List<BrowseCollection>, empty: String, open: (BrowseCollection) -> Unit) {
    if (values.isEmpty()) item("no_collections") { CenteredMessage(empty) }
    itemsIndexed(values, key = { index, it -> "collection_${index}_${it.browseId}" }) { i, value -> CollectionRow(value, open); if (i < values.lastIndex) LibraryDivider(80.dp) }
}

private fun LazyListScope.youtubeShelf(title: String, section: ResultSection, values: List<BrowseCollection>,
    show: (ResultSection) -> Unit, open: (BrowseCollection) -> Unit) {
    if (values.isEmpty()) return
    item("${section.name}_header") { Header(title, { show(section) }) }
    item("${section.name}_shelf") { CollectionShelf(values.take(SEARCH_SHELF_PREVIEW_LIMIT), open) }
}

private fun <T> LazyListScope.libraryShelf(title: String, section: ResultSection, values: List<T>,
    show: (ResultSection) -> Unit, card: @Composable (T) -> Unit) {
    if (values.isEmpty()) return
    item("${section.name}_header") { Header(title, { show(section) }) }
    item("${section.name}_shelf") { LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 18.dp)) { items(values) { card(it) } } }
}

@Composable private fun CollectionShelf(values: List<BrowseCollection>, open: (BrowseCollection) -> Unit) =
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 18.dp)) {
        itemsIndexed(values, key = { index, it -> "shelf_${index}_${it.browseId}" }) { _, item -> CollectionCard(item) { open(item) } }
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun YoutubeSongRow(result: YtDlpSearchResult, state: YouTubeDownloadUiState, showDownload: Boolean,
    prefix: String, play: (YtDlpSearchResult) -> Unit, playNext: (YtDlpSearchResult) -> Unit, download: (YtDlpSearchResult) -> Unit) {
    val downloading = result.videoId in state.downloadingIds; val downloaded = result.videoId in state.downloadedIds
    var menu by remember(result.videoId) { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    // Tap plays, swipe right queues it next, hold opens the menu - like a library song row.
    SwipeToPlayNext({ playNext(result) }) { swipeModifier ->
    Row(swipeModifier.fillMaxWidth().heightIn(min = 60.dp).combinedClickable(onClick = { play(result) }, onLongClick = {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true }).padding(start = 18.dp, end = 8.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Thumbnail(result.thumbnailUrl, Modifier.size(50.dp), 5, 150)
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 6.dp)) {
            Text(result.title, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(prefix + result.artist, color = Color.White.copy(.56f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (showDownload) Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            when { downloading -> CalmSpinner(Modifier.size(20.dp), AppAccent, 2.dp)
                downloaded -> Icon(Icons.Rounded.Check, "Downloaded", tint = AppAccent, modifier = Modifier.size(22.dp))
                else -> Icon(Icons.Rounded.Download, "Download", tint = AppAccent, modifier = Modifier.size(23.dp).clickable { download(result) }) }
        }
        // Anchors the hold menu at the row's end.
        Box(Modifier.width(10.dp).height(44.dp), contentAlignment = Alignment.Center) {
            LibraryFloatingMenu(menu, { menu = false }) {
                LibraryMenuItem("Play", Icons.Rounded.PlayArrow) { menu = false; play(result) }; LibraryMenuDivider()
                LibraryMenuItem("Play Next", Icons.Rounded.QueuePlayNext) { menu = false; playNext(result) }; LibraryMenuDivider()
                LibraryMenuItem(if (downloaded) "Downloaded" else "Download", if (downloaded) Icons.Rounded.Check else Icons.Rounded.Download) {
                    menu = false; if (!downloaded) download(result)
                }
            }
        }
    }
    }
}

@Composable private fun CollectionRow(value: BrowseCollection, open: (BrowseCollection) -> Unit) =
    CollectionRow(value.title, "${value.kind.label()}${value.subtitle?.let { " · $it" } ?: ""}", value.kind == BrowseKind.ARTIST, value.thumbnailUrl) { open(value) }

@Composable
private fun CollectionRow(title: String, subtitle: String, round: Boolean, art: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(onClick = onClick).padding(start = 18.dp, end = 16.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        if (round) ArtistAvatar(art, 50) else CoverTile(art, Modifier.size(50.dp), 5, Icons.AutoMirrored.Rounded.QueueMusic)
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
            Text(title, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Color.White.copy(.56f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Rounded.ArrowForwardIos, null, tint = Color.White.copy(.35f), modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun MediaCard(title: String, subtitle: String, round: Boolean, art: String?, onClick: () -> Unit) {
    Column(Modifier.width(142.dp).clickable(onClick = onClick), horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start) {
        if (round) ArtistAvatar(art, 142) else CoverTile(art, Modifier.fillMaxWidth().aspectRatio(1f), 8)
        Text(title, color = Color.White.copy(.94f), fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(subtitle, color = Color.White.copy(.56f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ImportedCard(p: ImportedPlaylistEntity, open: () -> Unit, remove: () -> Unit) {
    Column(Modifier.width(142.dp).clickable(onClick = open)) {
        Box { Thumbnail(p.thumbnailUrl, Modifier.fillMaxWidth().aspectRatio(1f), 8, 300)
            Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).clip(CircleShape).background(Color.Black.copy(.62f)).clickable(onClick = remove), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, "Remove", tint = Color.White, modifier = Modifier.size(15.dp))
            }
        }
        Text(p.title, color = Color.White.copy(.94f), fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable private fun GenreTile(title: String, color: Color, modifier: Modifier, click: () -> Unit) =
    Box(modifier.height(96.dp).clip(RoundedCornerShape(10.dp)).background(color).clickable(onClick = click).padding(12.dp), contentAlignment = Alignment.BottomStart) {
        Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }

@Composable
private fun Header(title: String, seeAll: (() -> Unit)? = null, action: (@Composable () -> Unit)? = null, compact: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(top = if (compact) 16.dp else 22.dp, bottom = if (compact) 6.dp else 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        seeAll?.let { Text("See All", color = AppAccent, fontSize = 15.sp, modifier = Modifier.clickable(onClick = it)) }; action?.invoke()
    }
}

private fun ResultSection.title() = name.lowercase().replaceFirstChar { it.uppercase() }
private fun BrowseKind.label() = when (this) { BrowseKind.ARTIST -> "Artist"; BrowseKind.ALBUM -> "Album"; BrowseKind.PLAYLIST -> "Playlist"; BrowseKind.OTHER -> "YouTube Music" }
private val GenreColors = listOf(Color(0xFF8A2846), Color(0xFF3C3489), Color(0xFF6B3D0F), Color(0xFF7A1F1F), Color(0xFF0F5E4A), Color(0xFF1D4F7A), Color(0xFF72243E), Color(0xFF3D4A1C))
