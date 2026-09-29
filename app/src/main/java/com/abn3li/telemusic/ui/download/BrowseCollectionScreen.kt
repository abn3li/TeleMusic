package com.abn3li.telemusic.ui.download

import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.abn3li.telemusic.ui.library.AppAlert
import com.abn3li.telemusic.ui.library.AlertAction
import androidx.compose.ui.platform.LocalDensity
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.data.browse.ArtistPage
import com.abn3li.telemusic.data.browse.BrowseCollection
import com.abn3li.telemusic.data.browse.BrowseKind
import com.abn3li.telemusic.data.browse.BrowseTrack
import com.abn3li.telemusic.data.browse.FULL_ARTWORK_SIZE
import com.abn3li.telemusic.data.browse.googleArtworkAtSize
import com.abn3li.telemusic.data.local.SongEntity
import com.abn3li.telemusic.ui.library.CoverTile
import com.abn3li.telemusic.ui.library.DetailPageScaffold
import com.abn3li.telemusic.ui.library.GroupActionRow
import com.abn3li.telemusic.ui.library.GroupCard
import com.abn3li.telemusic.ui.library.HeroButton
import com.abn3li.telemusic.ui.library.HeroButtonRow
import com.abn3li.telemusic.ui.library.HeroImage
import com.abn3li.telemusic.ui.library.LargeTitleGrid
import com.abn3li.telemusic.ui.library.LibraryDivider
import com.abn3li.telemusic.ui.library.SectionHeader

/**
 * One YouTube page - an album, playlist, chart or artist opened from Discovery or search, laid
 * out like the library's own album / playlist / artist pages (see DetailPageScaffold). The
 * tracks come from a real browse call; Play streams, Download feeds the same yt-dlp flow as
 * search, and Import to Library makes the page a library playlist.
 */
@Composable
fun BrowseCollectionScreen(
    title: String,
    browseId: String,
    params: String?,
    onBack: () -> Unit,
    onOpenCollection: (BrowseCollection) -> Unit,
    // Plays [tracks] as the queue from [index] (shuffled when [shuffle]) - see NavGraph.
    onPlayTracks: (tracks: List<BrowseTrack>, index: Int, shuffle: Boolean) -> Unit,
    onPlayNext: (BrowseTrack) -> Unit
) {
    val app = LocalContext.current.applicationContext as TgMusicApp
    // viewModel(), not remember{}: cleared with this screen's back-stack entry, and kept across
    // rotation.
    val viewModel = viewModel<BrowseCollectionViewModel>(
        key = "browse:$browseId:$params",
        factory = viewModelFactory {
            initializer {
                BrowseCollectionViewModel(
                    app.applicationContext, title, browseId, params,
                    app.discoveryRepository, app.ytDlpRepository, app.musicRepository, app.settingsStore, app.workScope
                )
            }
        }
    )
    val state by viewModel.uiState.collectAsState()
    val onDownload: (BrowseTrack) -> Unit = { track -> app.downloadGate.run { viewModel.onDownloadClick(track) } }

    state.artist?.let { artist ->
        ArtistPageContent(artist, state, onBack, onOpenCollection, onPlayTracks, onPlayNext, onDownload)
        return
    }

    // A page of cards and no songs (a genre's playlists, say): a grid, like the library's.
    if (state.collections.isNotEmpty() && state.tracks.isEmpty()) {
        LargeTitleGrid(title = state.title, onBack = onBack) {
            items(state.collections, key = { it.browseId }) { collection ->
                Column(Modifier.fillMaxWidth().clickable { onOpenCollection(collection) }) {
                    Thumbnail(collection.thumbnailUrl, Modifier.fillMaxWidth().aspectRatio(1f), corner = 7, requestPx = 300)
                    Spacer(Modifier.height(5.dp))
                    Text(collection.title, color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    collection.subtitle?.let {
                        Text(it, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        return
    }

    val header = state.header
    val isAlbum = header?.subtitle?.let { it.startsWith("Album") || it.startsWith("Single") || it.startsWith("EP") } == true
    val tracks = state.tracks
    val cover = header?.thumbnailUrl
        ?: googleArtworkAtSize(tracks.firstOrNull { !it.thumbnailUrl.isNullOrEmpty() }?.thumbnailUrl, FULL_ARTWORK_SIZE)
    val artistName = header?.artist
    val artistId = header?.artistBrowseId

    DetailPageScaffold(
        title = header?.title ?: state.title,
        subtitle = artistName,
        onSubtitleClick = if (artistName != null && artistId != null) {
            { onOpenCollection(BrowseCollection(artistId, null, artistName, null, null, BrowseKind.ARTIST)) }
        } else null,
        detailLine = listOfNotNull(header?.subtitle, header?.detail).joinToString(" · ")
            .ifBlank { if (state.isLoading) "" else "${tracks.size} ${if (tracks.size == 1) "Song" else "Songs"}" },
        hero = { HeroImage(cover, if (isAlbum) Icons.Rounded.Album else Icons.AutoMirrored.Rounded.QueueMusic) },
        items = tracks,
        loaded = !state.isLoading,
        onBack = onBack,
        matches = { track, query -> track.title.contains(query, true) || track.artist.contains(query, true) },
        emptyText = state.errorMessage ?: "Nothing here",
        heroActions = { shown, openSearch ->
            PlayShuffleSearch(shown, onPlayTracks, openSearch)
        }
    ) { shown, searching ->
        if (state.isLoading) item("loading") { CenteredSpinner() }
        if (!searching && tracks.isNotEmpty()) {
            item("import") {
                GroupCard {
                    val progress = state.importProgress
                    var chooseImport by remember { mutableStateOf(false) }
                    when {
                        state.importedPlaylistId != null -> GroupActionRow("Imported to Library", enabled = false) {}
                        state.importedAsAlbum -> GroupActionRow("Imported to Library Albums", enabled = false) {}
                        progress != null -> GroupActionRow("Importing ${progress.first}/${progress.second}…", loading = true) {}
                        // An album can go in as an album (Library > Albums) or as a playlist.
                        isAlbum -> GroupActionRow("Import to Library") { chooseImport = true }
                        else -> GroupActionRow("Import to Library") { viewModel.importToLibrary() }
                    }
                    if (chooseImport) AppAlert(
                        title = "Import to Library",
                        message = "Save \"${header?.title ?: state.title}\" as an album, or as a playlist.",
                        onDismiss = { chooseImport = false },
                        actions = listOf(
                            AlertAction("As Album", bold = true) { chooseImport = false; viewModel.importToLibrary(asAlbum = true) },
                            AlertAction("As Playlist") { chooseImport = false; viewModel.importToLibrary(asAlbum = false) },
                            AlertAction("Cancel") { chooseImport = false }
                        )
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        trackRows(shown, state, onPlayTracks, onPlayNext, onDownload)
    }
}

/**
 * An artist's page: their picture and name, then Top songs (the arrow opens the full list),
 * then each shelf YouTube has for them - Albums, Singles & EPs, Playlists, similar artists.
 */
@Composable
private fun ArtistPageContent(
    artist: ArtistPage,
    state: BrowseCollectionUiState,
    onBack: () -> Unit,
    onOpenCollection: (BrowseCollection) -> Unit,
    onPlay: (List<BrowseTrack>, Int, Boolean) -> Unit,
    onPlayNext: (BrowseTrack) -> Unit,
    onDownload: (BrowseTrack) -> Unit
) {
    DetailPageScaffold(
        title = artist.name,
        subtitle = null,
        onSubtitleClick = null,
        detailLine = artist.subtitle.orEmpty(),
        hero = { HeroImage(artist.thumbnailUrl, Icons.Rounded.Person) },
        items = artist.topSongs,
        loaded = true,
        // Some artists have no song shelf, only albums: no "nothing here" above those.
        showEmptyText = artist.shelves.isEmpty(),
        onBack = onBack,
        matches = { track, query -> track.title.contains(query, true) || track.artist.contains(query, true) },
        emptyText = "Nothing here",
        heroActions = { shown, openSearch -> PlayShuffleSearch(shown, onPlay, openSearch) }
    ) { shown, searching ->
        if (!searching && shown.isNotEmpty()) {
            item("songs_header") {
                val allSongs = artist.allSongsBrowseId
                SectionHeader(
                    "Top songs",
                    allSongs?.let { id -> { onOpenCollection(BrowseCollection(id, artist.allSongsParams, "${artist.name}: Songs", null, null, BrowseKind.PLAYLIST)) } }
                )
            }
        }
        trackRows(if (searching) shown else shown.take(5), state, onPlay, onPlayNext, onDownload)
        if (!searching) {
            artist.shelves.forEachIndexed { index, shelf ->
                item("shelf_title_$index") { SectionHeader(shelf.title, null) }
                item("shelf_$index") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = 18.dp)) {
                        items(shelf.items, key = { it.browseId }) { card -> CollectionCard(card) { onOpenCollection(card) } }
                    }
                }
            }
        }
    }
}

/** Shuffle / Play / Search under a YouTube page's title: the page's songs become the queue, in
 * order from the top, or shuffled. */
@Composable
private fun PlayShuffleSearch(shown: List<BrowseTrack>, onPlay: (List<BrowseTrack>, Int, Boolean) -> Unit, openSearch: () -> Unit) {
    HeroButtonRow {
        HeroButton(Icons.Rounded.Shuffle, "Shuffle", enabled = shown.isNotEmpty()) { onPlay(shown, shown.indices.random(), true) }
        HeroButton(Icons.Rounded.PlayArrow, "Play", enabled = shown.isNotEmpty()) { onPlay(shown, 0, false) }
        HeroButton(Icons.Rounded.Search, "Search", enabled = shown.isNotEmpty(), onClick = openSearch)
    }
    Spacer(Modifier.height(30.dp))
}

private fun LazyListScope.trackRows(
    tracks: List<BrowseTrack>,
    state: BrowseCollectionUiState,
    onPlay: (List<BrowseTrack>, Int, Boolean) -> Unit,
    onPlayNext: (BrowseTrack) -> Unit,
    onDownload: (BrowseTrack) -> Unit
) {
    itemsIndexed(tracks, key = { _, t -> t.videoId }, contentType = { _, _ -> "track" }) { index, track ->
        TrackResultRow(
            title = track.title,
            artist = track.artist,
            thumbnailUrl = track.thumbnailUrl,
            isDownloading = track.videoId in state.downloadingIds,
            isDownloaded = track.videoId in state.downloadedIds,
            onDownloadClick = { onDownload(track) },
            // The whole list is the queue, starting here: Next/Previous work as in the library.
            onPlayClick = { onPlay(tracks, index, false) },
            onPlayNext = { onPlayNext(track) }
        )
        if (index < tracks.lastIndex) LibraryDivider(start = 88.dp)
    }
}

/** An album / playlist / artist card in a shelf, sized like the library artist page's album
 * cards; an artist's picture is round. */
@Composable
internal fun CollectionCard(item: BrowseCollection, onClick: () -> Unit) {
    val round = item.kind == BrowseKind.ARTIST
    Column(
        Modifier.width(142.dp).clickable(onClick = onClick),
        horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).then(if (round) Modifier.clip(CircleShape) else Modifier)) {
            CoverTile(
                item.thumbnailUrl,
                Modifier.fillMaxWidth().aspectRatio(1f),
                corner = if (round) 0 else 8,
                placeholder = if (round) Icons.Rounded.Person else Icons.Rounded.Album
            )
        }
        // Every card's text takes the same height - room for a two-line title and a subtitle
        // line - so a sideways row of them never changes height as cards with longer or shorter
        // titles scroll in (that made everything below the row jump). Short titles sit at the top.
        val textHeight = with(LocalDensity.current) { CardTitleLineHeight.toDp() * 2 + CardSubtitleLineHeight.toDp() } + 8.dp
        Column(
            Modifier.fillMaxWidth().height(textHeight).padding(top = 8.dp),
            horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start
        ) {
            Text(
                item.title,
                color = Color.White.copy(alpha = 0.94f),
                fontSize = 15.sp,
                lineHeight = CardTitleLineHeight,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (round) TextAlign.Center else TextAlign.Start
            )
            item.subtitle?.let {
                Text(
                    it,
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                    lineHeight = CardSubtitleLineHeight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = if (round) TextAlign.Center else TextAlign.Start
                )
            }
        }
    }
}

private val CardTitleLineHeight = 18.sp
private val CardSubtitleLineHeight = 17.sp
