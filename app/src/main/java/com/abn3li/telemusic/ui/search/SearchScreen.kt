package com.abn3li.telemusic.ui.search

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.data.browse.BrowseCollection
import com.abn3li.telemusic.data.browse.BrowseKind
import com.abn3li.telemusic.data.download.YtDlpSearchResult
import com.abn3li.telemusic.data.local.AlbumSummary
import com.abn3li.telemusic.data.local.ArtistSummary
import com.abn3li.telemusic.data.local.PlaylistSummary
import com.abn3li.telemusic.data.local.SongEntity
import com.abn3li.telemusic.ui.download.CenteredMessage
import com.abn3li.telemusic.ui.download.CenteredSpinner
import com.abn3li.telemusic.ui.download.CollectionCard
import com.abn3li.telemusic.ui.download.DiscoveryViewModel
import com.abn3li.telemusic.ui.download.ImportPlaylistPrompt
import com.abn3li.telemusic.ui.download.youTubeDiscovery
import com.abn3li.telemusic.ui.download.SearchTab
import com.abn3li.telemusic.ui.download.Thumbnail
import com.abn3li.telemusic.ui.download.TrackResultRow
import com.abn3li.telemusic.ui.download.YouTubeDownloadUiState
import com.abn3li.telemusic.ui.download.YouTubeDownloadViewModel
import com.abn3li.telemusic.ui.library.ArtistAvatar
import com.abn3li.telemusic.ui.library.CoverTile
import com.abn3li.telemusic.ui.library.DestructiveRed
import com.abn3li.telemusic.ui.library.FilterPill
import com.abn3li.telemusic.ui.library.GroupLabelColor
import com.abn3li.telemusic.ui.library.LargeTitleList
import com.abn3li.telemusic.ui.library.LibraryCallbacks
import com.abn3li.telemusic.ui.library.LibraryDivider
import com.abn3li.telemusic.ui.library.LibrarySearchField
import com.abn3li.telemusic.ui.library.LibrarySongActions
import com.abn3li.telemusic.ui.library.LibrarySongRow
import com.abn3li.telemusic.ui.library.LibraryViewModel
import com.abn3li.telemusic.ui.library.SearchStickyHeight
import com.abn3li.telemusic.ui.library.SectionHeader
import com.abn3li.telemusic.ui.library.rememberLibrarySongActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** How long typing has to pause before YouTube is asked - each search is four network calls. */
private const val YOUTUBE_SEARCH_DELAY_MS = 700L

/** The pills' row under the search field. */
private val PillsHeight = 46.dp

/** What the library has for a query - found on the phone, so it's there while you type. */
private data class LibraryMatches(
    val songs: List<SongEntity> = emptyList(),
    val albums: List<AlbumSummary> = emptyList(),
    val artists: List<ArtistSummary> = emptyList(),
    val playlists: List<PlaylistSummary> = emptyList()
) {
    val isEmpty get() = songs.isEmpty() && albums.isEmpty() && artists.isEmpty() && playlists.isEmpty()
}

/**
 * The Search tab. Before typing it shows YouTube Music's feed. Typing searches everything: the
 * library first (live as you type), then YouTube Music below it (asked once typing pauses).
 * YouTube songs the library already has aren't repeated.
 */
@Composable
fun SearchScreen(
    libraryViewModel: LibraryViewModel,
    callbacks: LibraryCallbacks,
    onOpenPlaylist: (Long, String) -> Unit,
    onOpenCollection: (BrowseCollection) -> Unit,
    onPlayStream: (SongEntity, Uri, String) -> Unit
) {
    val app = LocalContext.current.applicationContext as TgMusicApp
    // Scoped to this page's back-stack entry: a YouTube page opened from the results and closed
    // again comes back to the same results, without searching again.
    val viewModel = viewModel<YouTubeDownloadViewModel>(
        factory = viewModelFactory {
            initializer {
                YouTubeDownloadViewModel(app.applicationContext, app.ytDlpRepository, app.musicRepository, app.settingsStore, app.discoveryRepository, onPlayStream, app.workScope)
            }
        }
    )
    val state by viewModel.uiState.collectAsState()
    val songs by libraryViewModel.allSongs.collectAsState()
    val albums by libraryViewModel.albums.collectAsState()
    val artists by libraryViewModel.artists.collectAsState()
    val playlists by libraryViewModel.playlistSummaries.collectAsState()
    val actions = rememberLibrarySongActions(libraryViewModel, callbacks.onPlayNext, callbacks.onOpenArtist, callbacks.onOpenAlbum)

    val query = state.query.trim()
    // Library filtering runs off the main thread and a beat after the last key, so a large
    // library doesn't stutter the typing.
    val library by produceState(LibraryMatches(), query, songs, albums, artists, playlists) {
        if (query.isEmpty()) {
            value = LibraryMatches()
            return@produceState
        }
        delay(120)
        value = withContext(Dispatchers.Default) {
            LibraryMatches(
                songs = songs.filter {
                    it.title.contains(query, true) || it.artist.contains(query, true) || it.album?.contains(query, true) == true
                },
                albums = albums.filter { it.album.contains(query, true) || it.artist.contains(query, true) },
                artists = artists.filter { it.artist.contains(query, true) },
                playlists = playlists.filter { it.name.contains(query, true) }
            )
        }
    }
    // Songs already in the library (by YouTube id, or same title and artist) show up there, not
    // again under YouTube.
    val owned = remember(songs) {
        val ids = HashSet<String>()
        val names = HashSet<String>()
        songs.forEach { song ->
            song.youtubeVideoId?.let(ids::add)
            names += nameKey(song.title, song.artist)
        }
        ids to names
    }
    val youTubeSongs = remember(state.results, owned) {
        state.results.filterNot { it.videoId in owned.first || nameKey(it.title, it.artist) in owned.second }
    }

    // Ask YouTube once typing pauses; a new key restarts the wait.
    LaunchedEffect(query) {
        if (query.isEmpty()) return@LaunchedEffect
        delay(YOUTUBE_SEARCH_DELAY_MS)
        viewModel.search()
    }

    // With nothing typed, the tab is YouTube Music's feed: imported playlists, genres, shelves.
    val discovery = viewModel<DiscoveryViewModel>(
        factory = viewModelFactory { initializer { DiscoveryViewModel(app.ytDlpRepository, app.discoveryRepository) } }
    )
    val feed by discovery.uiState.collectAsState()
    var showImport by remember { mutableStateOf(false) }
    if (showImport) ImportPlaylistPrompt(discovery, onClose = { showImport = false })

    // Back while showing results goes back to the feed first.
    BackHandler(enabled = state.query.isNotEmpty()) { viewModel.onQueryChange("") }

    // YouTube's part is still coming while a search runs, or while typing hasn't paused yet.
    val youTubeLoading = query.isNotEmpty() && (state.isSearching || state.searchedQuery != query)

    LargeTitleList(
        // A bottom-bar tab: no back arrow.
        title = "Search",
        stickyHeight = if (query.isEmpty()) SearchStickyHeight else SearchStickyHeight + PillsHeight,
        stickyContent = {
            Column {
                LibrarySearchField(
                    state.query,
                    "Your music and YouTube",
                    viewModel::onQueryChange,
                    onSearch = { viewModel.search(force = true) }
                )
                if (query.isNotEmpty()) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 18.dp),
                        modifier = Modifier.height(PillsHeight)
                    ) {
                        items(SearchTab.entries.toList(), key = { it.name }) { tab ->
                            FilterPill(tab.label, selected = state.tab == tab, onClick = { viewModel.selectTab(tab) })
                        }
                    }
                }
            }
        }
    ) {
        if (query.isEmpty()) {
            youTubeDiscovery(
                state = feed,
                onOpenCollection = onOpenCollection,
                onImportPlaylistClick = { showImport = true },
                onRemoveImportedPlaylist = discovery::removeImportedPlaylist,
                onRetry = discovery::reload
            )
            return@LargeTitleList
        }
        val tab = state.tab
        libraryResults(tab, library, actions, callbacks, onOpenPlaylist, onSelectTab = viewModel::selectTab)
        if (tab == SearchTab.LIBRARY) {
            if (library.isEmpty) item("library_none") { CenteredMessage("Nothing in your library matches") }
            return@LargeTitleList
        }
        item("yt_title") { SourceHeader("From YouTube") }
        state.actionError?.let { message ->
            item("yt_action_error") {
                Text(message, color = DestructiveRed, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp))
            }
        }
        if (youTubeLoading) {
            item("yt_loading") { CenteredSpinner() }
            return@LargeTitleList
        }
        youTubeResults(
            state = state,
            songs = youTubeSongs,
            songRow = { result ->
                TrackResultRow(
                    title = result.title,
                    artist = result.artist,
                    thumbnailUrl = result.thumbnailUrl,
                    isDownloading = result.videoId in state.downloadingIds,
                    isDownloaded = result.videoId in state.downloadedIds,
                    isLoadingStream = result.videoId in state.loadingStreamIds,
                    onDownloadClick = { app.downloadGate.run { viewModel.onDownloadIconClick(result) } },
                    onPlayClick = { viewModel.onPlayClick(result) }
                )
            },
            onSelectTab = viewModel::selectTab,
            onOpenCollection = onOpenCollection
        )
    }
}

private fun nameKey(title: String, artist: String) = title.trim().lowercase() + "\u0000" + artist.trim().lowercase()

/** "In your library" / "From YouTube": which half of the page this is. */
@Composable
private fun SourceHeader(title: String, detail: String? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(top = 18.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Text(title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        if (detail != null) {
            Text(detail, color = GroupLabelColor, fontSize = 14.sp, modifier = Modifier.padding(start = 10.dp, bottom = 3.dp))
        }
    }
}

/** A small kind label inside the library half ("Songs", "Albums"...). */
@Composable
private fun KindLabel(title: String) {
    Text(
        title.uppercase(),
        color = GroupLabelColor,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 18.dp, top = 14.dp, bottom = 4.dp)
    )
}

/**
 * The library half. All shows a few of each kind (the header's arrow opens the Library tab with
 * everything); Library shows everything; a kind's tab shows only that kind.
 */
private fun LazyListScope.libraryResults(
    tab: SearchTab,
    library: LibraryMatches,
    actions: LibrarySongActions,
    callbacks: LibraryCallbacks,
    onOpenPlaylist: (Long, String) -> Unit,
    onSelectTab: (SearchTab) -> Unit
) {
    val all = tab == SearchTab.ALL
    val songs = when (tab) {
        SearchTab.ALL -> library.songs.take(4)
        SearchTab.LIBRARY, SearchTab.SONGS -> library.songs
        else -> emptyList()
    }
    val albums = when (tab) {
        SearchTab.ALL -> library.albums.take(2)
        SearchTab.LIBRARY, SearchTab.ALBUMS -> library.albums
        else -> emptyList()
    }
    val artists = when (tab) {
        SearchTab.ALL -> library.artists.take(2)
        SearchTab.LIBRARY, SearchTab.ARTISTS -> library.artists
        else -> emptyList()
    }
    val playlists = when (tab) {
        SearchTab.ALL -> library.playlists.take(2)
        SearchTab.LIBRARY, SearchTab.PLAYLISTS -> library.playlists
        else -> emptyList()
    }
    if (songs.isEmpty() && albums.isEmpty() && artists.isEmpty() && playlists.isEmpty()) return
    // Several kinds at once get a label each; a single kind's tab doesn't need one.
    val labelled = tab == SearchTab.ALL || tab == SearchTab.LIBRARY

    item("lib_title") {
        if (all) {
            SectionHeader("In your library") { onSelectTab(SearchTab.LIBRARY) }
        } else {
            SourceHeader("In your library")
        }
    }
    if (songs.isNotEmpty()) {
        if (labelled) item("lib_songs_label") { KindLabel("Songs") }
        // The whole match list is the queue, even when All shows only its first few.
        val ids = library.songs.map { it.telegramMessageId }
        itemsIndexed(songs, key = { _, s -> "lib_song_${s.telegramMessageId}" }, contentType = { _, _ -> "lib_song" }) { index, song ->
            LibrarySongRow(
                song = song,
                actions = actions,
                onClick = { callbacks.onPlay(ids, index) },
                subtitle = listOfNotNull(song.artist, song.album?.takeIf { it.isNotBlank() }).joinToString(" · ")
            )
            if (index < songs.lastIndex) LibraryDivider(start = 88.dp)
        }
    }
    if (albums.isNotEmpty()) {
        if (labelled) item("lib_albums_label") { KindLabel("Albums") }
        itemsIndexed(albums, key = { _, a -> "lib_album_${a.album}\u0000${a.artist}" }, contentType = { _, _ -> "lib_row" }) { index, album ->
            LibraryResultRow(
                title = album.album,
                subtitle = "${album.artist} · ${album.songCount} ${if (album.songCount == 1) "song" else "songs"}",
                artwork = { CoverTile(album.albumArtUrl, Modifier.size(52.dp), corner = 4) },
                onClick = { callbacks.onOpenAlbum(album.album) }
            )
            if (index < albums.lastIndex) LibraryDivider(start = 88.dp)
        }
    }
    if (artists.isNotEmpty()) {
        if (labelled) item("lib_artists_label") { KindLabel("Artists") }
        itemsIndexed(artists, key = { _, a -> "lib_artist_${a.artist}" }, contentType = { _, _ -> "lib_row" }) { index, artist ->
            LibraryResultRow(
                title = artist.artist,
                subtitle = "${artist.songCount} ${if (artist.songCount == 1) "song" else "songs"}",
                artwork = { ArtistAvatar(artist.albumArtUrl, 52) },
                onClick = { callbacks.onOpenArtist(artist.artist) }
            )
            if (index < artists.lastIndex) LibraryDivider(start = 88.dp)
        }
    }
    if (playlists.isNotEmpty()) {
        if (labelled) item("lib_playlists_label") { KindLabel("Playlists") }
        itemsIndexed(playlists, key = { _, p -> "lib_playlist_${p.id}" }, contentType = { _, _ -> "lib_row" }) { index, playlist ->
            LibraryResultRow(
                title = playlist.name,
                subtitle = "Playlist",
                artwork = { CoverTile(playlist.albumArtUrl, Modifier.size(52.dp), corner = 4, placeholder = Icons.AutoMirrored.Rounded.QueueMusic) },
                onClick = { onOpenPlaylist(playlist.id, playlist.name) }
            )
            if (index < playlists.lastIndex) LibraryDivider(start = 88.dp)
        }
    }
}

/** A library album / artist / playlist: artwork, name, a detail line, and an arrow. */
@Composable
private fun LibraryResultRow(title: String, subtitle: String, artwork: @Composable () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick)
            .padding(start = 22.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        artwork()
        Column(Modifier.weight(1f).padding(start = 16.dp, end = 8.dp)) {
            Text(title, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        RowArrow()
    }
}

@Composable
private fun RowArrow() {
    Icon(
        Icons.AutoMirrored.Rounded.ArrowForwardIos,
        contentDescription = null,
        tint = Color.White.copy(alpha = 0.35f),
        modifier = Modifier.size(14.dp)
    )
}

/**
 * The YouTube half for the chosen tab. All shows a few songs, then a shelf each of albums,
 * artists and playlists (the arrow opens that tab); the other tabs list one kind in full.
 */
private fun LazyListScope.youTubeResults(
    state: YouTubeDownloadUiState,
    songs: List<YtDlpSearchResult>,
    songRow: @Composable (YtDlpSearchResult) -> Unit,
    onSelectTab: (SearchTab) -> Unit,
    onOpenCollection: (BrowseCollection) -> Unit
) {
    fun songList(list: List<YtDlpSearchResult>) {
        itemsIndexed(list, key = { _, r -> "yt_song_${r.videoId}" }, contentType = { _, _ -> "track" }) { index, result ->
            songRow(result)
            if (index < list.lastIndex) LibraryDivider(start = 88.dp)
        }
    }
    fun rows(list: List<BrowseCollection>, empty: String) {
        if (list.isEmpty()) item("yt_empty_rows") { CenteredMessage(empty) }
        itemsIndexed(list, key = { _, c -> "yt_row_${c.browseId}" }, contentType = { _, _ -> "collection" }) { index, collection ->
            CollectionResultRow(collection) { onOpenCollection(collection) }
            if (index < list.lastIndex) LibraryDivider(start = 88.dp)
        }
    }
    fun shelf(title: String, tab: SearchTab, list: List<BrowseCollection>) {
        if (list.isEmpty()) return
        item("yt_shelf_title_${tab.name}") { SectionHeader(title) { onSelectTab(tab) } }
        item("yt_shelf_${tab.name}") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = 18.dp)) {
                items(list, key = { it.browseId }) { card -> CollectionCard(card) { onOpenCollection(card) } }
            }
        }
    }

    when (state.tab) {
        SearchTab.ALL -> {
            if (songs.isEmpty() && state.albums.isEmpty() && state.artists.isEmpty() && state.playlists.isEmpty()) {
                item("yt_nothing") { CenteredMessage("Nothing found on YouTube") }
                return
            }
            if (songs.isNotEmpty()) {
                item("yt_songs_title") { SectionHeader("Songs") { onSelectTab(SearchTab.SONGS) } }
                songList(songs.take(4))
            }
            shelf("Albums", SearchTab.ALBUMS, state.albums)
            shelf("Artists", SearchTab.ARTISTS, state.artists)
            shelf("Playlists", SearchTab.PLAYLISTS, state.playlists)
        }
        SearchTab.SONGS -> {
            if (songs.isEmpty()) {
                // Everything YouTube found is already in the library: say so rather than "nothing".
                val message = if (state.results.isNotEmpty()) "Already in your library" else state.errorMessage ?: "No songs found"
                item("yt_no_songs") { CenteredMessage(message) }
            }
            songList(songs)
        }
        SearchTab.ALBUMS -> rows(state.albums, "No albums found")
        SearchTab.ARTISTS -> rows(state.artists, "No artists found")
        SearchTab.PLAYLISTS -> rows(state.playlists, "No playlists found")
        SearchTab.LIBRARY -> Unit
    }
}

/** An album / artist / playlist on YouTube: artwork (round for an artist), title, what it is,
 * and an arrow - it opens a page rather than playing. */
@Composable
private fun CollectionResultRow(collection: BrowseCollection, onClick: () -> Unit) {
    val round = collection.kind == BrowseKind.ARTIST
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick)
            .padding(start = 22.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Thumbnail(collection.thumbnailUrl, Modifier.size(52.dp), corner = if (round) 26 else 4, requestPx = 150)
        Column(Modifier.weight(1f).padding(start = 16.dp, end = 8.dp)) {
            Text(collection.title, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // "Artist • 12M monthly audience" in the Artists tab: the tab already says what it is.
            collection.subtitle?.removePrefix("Artist • ")?.let {
                Text(it, color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        RowArrow()
    }
}
