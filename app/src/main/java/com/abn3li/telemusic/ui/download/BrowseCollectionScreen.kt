package com.abn3li.telemusic.ui.download

import com.abn3li.telemusic.ui.theme.LocalPalette
import com.abn3li.telemusic.ui.theme.paper
import com.abn3li.telemusic.ui.theme.ink
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.LibraryAdd
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
import com.abn3li.telemusic.ui.library.feedArtworkBorder
import com.abn3li.telemusic.ui.library.LargeTitleGrid
import com.abn3li.telemusic.ui.library.LibraryDivider
import com.abn3li.telemusic.ui.library.SectionHeader

/**
 * One YouTube page - an album, playlist, chart or artist opened from Discovery or search, laid
 * out like an artist's page (see ArtistHeroLayout): the cover, title and buttons up top. The
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
    state.downloadConflict?.let { conflict -> DownloadAnywayPrompt(conflict, viewModel::dismissDownloadConflict) }

    state.artist?.let { artist ->
        ArtistHeroPage(artist, browseId, state, onBack, onOpenCollection, onPlayTracks, onPlayNext, onDownload)
        return
    }
    // An artist (a channel id) still loading: the artist page's own frame, not the album one.
    if (state.isLoading && (browseId.startsWith("UC") || browseId.startsWith(com.abn3li.telemusic.repository.DiscoveryRepository.ARTIST_OF_PREFIX))) {
        ArtistHeroLayout(
            name = title, photoUrl = null, loaded = false, canPlay = false,
            onShuffle = {}, onPlay = {}, onAllSongs = null, onBack = onBack
        ) { _, _ -> item("loading") { CenteredSpinner() } }
        return
    }

    // A page of cards and no songs (a genre's playlists, say): a grid, like the library's.
    if (state.collections.isNotEmpty() && state.tracks.isEmpty()) {
        LargeTitleGrid(title = state.title, onBack = onBack) {
            items(state.collections, key = { it.browseId }) { collection ->
                Column(Modifier.fillMaxWidth().youtubeCollectionActions(collection) { onOpenCollection(collection) }) {
                    Thumbnail(collection.thumbnailUrl, Modifier.fillMaxWidth().aspectRatio(1f), corner = 7, requestPx = 300)
                    Spacer(Modifier.height(5.dp))
                    Text(collection.title, color = ink.copy(alpha = 0.9f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    collection.subtitle?.let {
                        Text(it, color = ink.copy(alpha = 0.6f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

    val pageTitle = header?.title ?: state.title
    var chooseImport by remember { mutableStateOf(false) }
    val progress = state.importProgress
    val imported = state.importedPlaylistId != null || state.importedAsAlbum
    ArtistHeroLayout(
        name = pageTitle,
        photoUrl = cover,
        loaded = !state.isLoading,
        placeholder = if (isAlbum) Icons.Rounded.Album else Icons.AutoMirrored.Rounded.QueueMusic,
        subtitle = artistName,
        onSubtitleClick = if (artistName != null && artistId != null) {
            { onOpenCollection(BrowseCollection(artistId, null, artistName, null, null, BrowseKind.ARTIST)) }
        } else null,
        detail = listOfNotNull(header?.subtitle, header?.detail).joinToString(" · ")
            .ifBlank { if (state.isLoading) "" else "${tracks.size} ${if (tracks.size == 1) "Song" else "Songs"}" },
        canPlay = tracks.isNotEmpty(),
        onShuffle = { onPlayTracks(tracks, tracks.indices.random(), true) },
        onPlay = { onPlayTracks(tracks, 0, false) },
        onAllSongs = null,
        onBack = onBack,
        pillPlay = true,
        // Import to Library: a tick once it's in, a spinner while the songs go in. An album can go
        // in as an album (Library > Albums) or as a playlist.
        trailing = {
            RoundGlassButton(
                if (imported) Icons.Rounded.Check else Icons.Rounded.LibraryAdd,
                if (imported) "Imported to Library" else "Import to Library",
                enabled = tracks.isNotEmpty(),
                loading = progress != null,
                done = imported
            ) {
                if (isAlbum) chooseImport = true else viewModel.importToLibrary()
            }
        },
        searchHint = "Search in $pageTitle"
    ) { searching, query ->
        if (searching) {
            val found = if (query.isBlank()) tracks else tracks.filter { it.title.contains(query, true) || it.artist.contains(query, true) }
            if (found.isEmpty() && query.isNotBlank()) item("no_results") { NoResults() }
            trackRows(found, state, onPlayTracks, onPlayNext, onDownload)
            return@ArtistHeroLayout
        }
        if (state.isLoading) item("loading") { CenteredSpinner() }
        if (!state.isLoading && tracks.isEmpty()) {
            item("empty") {
                Text(
                    state.errorMessage ?: "Nothing here",
                    color = ink.copy(alpha = 0.55f),
                    fontSize = 15.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)
                )
            }
        }
        if (progress != null) {
            item("import_progress") {
                Text(
                    "Importing ${progress.first}/${progress.second}…",
                    color = ink.copy(alpha = 0.6f),
                    fontSize = 13.5.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                )
            }
        }
        trackRows(tracks, state, onPlayTracks, onPlayNext, onDownload)
    }
    if (chooseImport) AppAlert(
        title = "Import to Library",
        message = "Save \"$pageTitle\" as an album, or as a playlist.",
        onDismiss = { chooseImport = false },
        actions = listOf(
            AlertAction("As Album", bold = true) { chooseImport = false; viewModel.importToLibrary(asAlbum = true) },
            AlertAction("As Playlist") { chooseImport = false; viewModel.importToLibrary(asAlbum = false) },
            AlertAction("Cancel") { chooseImport = false }
        )
    )
}

internal fun LazyListScope.trackRows(
    tracks: List<BrowseTrack>,
    state: BrowseCollectionUiState,
    onPlay: (List<BrowseTrack>, Int, Boolean) -> Unit,
    onPlayNext: (BrowseTrack) -> Unit,
    onDownload: (BrowseTrack) -> Unit
) {
    itemsIndexed(tracks, key = { _, t -> t.videoId }, contentType = { _, _ -> "track" }) { index, track ->
        TrackResultRow(
            track = track,
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
internal fun CollectionCard(item: BrowseCollection, artworkBorder: Boolean = false, onClick: () -> Unit) {
    val round = item.kind == BrowseKind.ARTIST
    Column(
        Modifier.width(142.dp).youtubeCollectionActions(item, onClick),
        horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start
    ) {
        val artworkShape = if (round) CircleShape else androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
        Box(Modifier.fillMaxWidth().aspectRatio(1f)
            .then(if (artworkBorder) Modifier.feedArtworkBorder(artworkShape) else Modifier)
            .then(if (round) Modifier.clip(CircleShape) else Modifier)) {
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
                color = ink.copy(alpha = 0.94f),
                fontSize = 15.sp,
                lineHeight = CardTitleLineHeight,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (round) TextAlign.Center else TextAlign.Start
            )
            item.subtitle?.let {
                Text(
                    it,
                    color = ink.copy(alpha = 0.6f),
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
