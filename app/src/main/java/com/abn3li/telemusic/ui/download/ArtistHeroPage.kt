package com.abn3li.telemusic.ui.download

import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.abn3li.telemusic.ui.library.LibrarySearchField
import com.abn3li.telemusic.ui.library.LibraryFloatingMenu
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.foundation.layout.ColumnScope
import androidx.activity.compose.BackHandler
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.displayCutout
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
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
import com.abn3li.telemusic.data.browse.ArtistPage
import com.abn3li.telemusic.data.browse.BrowseCollection
import com.abn3li.telemusic.data.browse.BrowseKind
import com.abn3li.telemusic.data.browse.BrowseTrack
import com.abn3li.telemusic.ui.library.CalmSpinner
import com.abn3li.telemusic.ui.library.SectionHeader
import com.abn3li.telemusic.ui.nowplaying.LocalMiniPlayerInset
import com.abn3li.telemusic.ui.theme.LocalPalette
import com.abn3li.telemusic.ui.theme.SystemBarsState
import com.abn3li.telemusic.ui.theme.ink
import com.abn3li.telemusic.ui.theme.paper

/**
 * An artist's page, like Apple Music's: their photo fills the top of the screen (behind the
 * status bar) and melts into the theme's page colour, with the big name and Shuffle / Play /
 * All songs over it; then the latest release, Top songs and YouTube's shelves. The photo drifts
 * slower than the page as it scrolls, and the top bar fills in once the name has gone.
 */
@Composable
internal fun ArtistHeroPage(
    artist: ArtistPage,
    browseId: String,
    state: BrowseCollectionUiState,
    onBack: () -> Unit,
    onOpenCollection: (BrowseCollection) -> Unit,
    onPlay: (List<BrowseTrack>, Int, Boolean) -> Unit,
    onPlayNext: (BrowseTrack) -> Unit,
    onDownload: (BrowseTrack) -> Unit
) {
    val context = LocalContext.current
    val songs = artist.topSongs
    val allSongs = artist.allSongsBrowseId?.let { id ->
        { onOpenCollection(BrowseCollection(id, artist.allSongsParams, "${artist.name}: Songs", null, null, BrowseKind.PLAYLIST)) }
    }
    val latest = remember(artist.shelves) { latestRelease(artist) }
    ArtistHeroLayout(
        name = artist.name,
        photoUrl = artist.thumbnailUrl,
        canPlay = songs.isNotEmpty(),
        onShuffle = { onPlay(songs, songs.indices.random(), true) },
        onPlay = { onPlay(songs, 0, false) },
        onAllSongs = allSongs,
        onBack = onBack,
        onShare = {
            val link = "https://music.youtube.com/channel/${artist.channelId ?: browseId}"
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
            context.startActivity(Intent.createChooser(send, artist.name))
        },
        searchHint = "Search in ${artist.name}"
    ) { searching, query ->
        if (searching) {
            val found = if (query.isBlank()) songs else songs.filter { it.title.contains(query, true) || it.artist.contains(query, true) }
            if (found.isEmpty() && query.isNotBlank()) item("no_results") { NoResults() }
            trackRows(found, state, onPlay, onPlayNext, onDownload)
            return@ArtistHeroLayout
        }
        if (latest != null) {
            item("latest") { LatestReleaseCard(latest) { onOpenCollection(latest) } }
        }
        if (songs.isNotEmpty()) {
            item("songs_header") { SectionHeader("Top Songs", allSongs) }
        }
        trackRows(songs.take(5), state, onPlay, onPlayNext, onDownload)
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

/**
 * The artist page's frame, shared by YouTube artists and the library's own: the photo under the
 * status bar fading into the page, the big name, Shuffle / Play / All songs, then [body]; the top
 * bar (Back, and Share / Search / [menu] when given) fills in once the name has scrolled away.
 * Search (when [searchHint] is set) swaps the photo for a search field; [body] gets the query.
 */
@Composable
internal fun ArtistHeroLayout(
    name: String,
    photoUrl: String?,
    canPlay: Boolean,
    onShuffle: () -> Unit,
    onPlay: () -> Unit,
    onAllSongs: (() -> Unit)?,
    onBack: () -> Unit,
    onShare: (() -> Unit)? = null,
    // False while the page is still loading: no stand-in picture before the real one.
    loaded: Boolean = true,
    // An album / playlist page: its cover, a line under the title (the artist - tappable when
    // [onSubtitleClick]) and a [detail] line, a wide Play, and [trailing] in place of All songs.
    placeholder: ImageVector = Icons.Rounded.Person,
    subtitle: String? = null,
    onSubtitleClick: (() -> Unit)? = null,
    detail: String? = null,
    pillPlay: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    searchHint: String? = null,
    menu: (@Composable ColumnScope.(close: () -> Unit) -> Unit)? = null,
    body: LazyListScope.(searching: Boolean, query: String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var searching by rememberSaveable { mutableStateOf(false) }
    var searchText by rememberSaveable { mutableStateOf("") }
    val query by produceState(searchText, searchText) {
        if (searchText.isNotBlank()) delay(150)
        value = searchText
    }
    val density = LocalDensity.current
    // The status bar goes see-through over this page: light icons over the photo, the theme's
    // own once the bar has filled in.
    val heroToken = remember { Any() }
    DisposableEffect(Unit) {
        SystemBarsState.heroPages++
        onDispose {
            SystemBarsState.heroPages--
            SystemBarsState.heroesOverPhoto.remove(heroToken)
        }
    }
    val tint = paper
    val pageInk = ink

    val statusBar = heroTopInset()
    val heroHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.6f).coerceIn(380.dp, 560.dp) + statusBar
    val heroPx = with(density) { heroHeight.toPx() }
    val listState = rememberLazyListState()
    val collapse by remember(searching) {
        derivedStateOf {
            when {
                searching -> 1f
                listState.firstVisibleItemIndex > 0 -> 1f
                else -> ((listState.firstVisibleItemScrollOffset - heroPx * 0.55f) / (heroPx * 0.2f)).coerceIn(0f, 1f)
            }
        }
    }
    val closeSearch = {
        searchText = ""
        searching = false
        scope.launch { listState.scrollToItem(0) }
        Unit
    }
    if (searching) BackHandler { closeSearch() }
    // Flips only when the bar fills in or clears - not every scrolled frame. Restarts with the
    // current collapse (it's rebuilt when search opens or closes).
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
            .background(tint)
    ) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            if (searching) item("search") {
                Box(Modifier.fillMaxWidth().padding(top = 64.dp + statusBar)) {
                    LibrarySearchField(searchText, searchHint.orEmpty(), { searchText = it })
                }
            } else item("hero") {
                Box(Modifier.fillMaxWidth().height(heroHeight).clipToBounds()) {
                    Box(
                        Modifier.fillMaxSize().graphicsLayer {
                            // Slower than the page: the photo lags behind as it scrolls away.
                            translationY = if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset * 0.45f else 0f
                        }
                    ) {
                        if (photoUrl.isNullOrEmpty()) {
                            if (loaded) Icon(placeholder, null, tint = pageInk.copy(alpha = 0.25f), modifier = Modifier.size(140.dp).align(Alignment.Center))
                        } else {
                            AsyncImage(model = photoUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                    // Only where the fades show: a shade under the status bar and the fade into the
                    // page. (One full-size overlay also painted the clear middle every frame.)
                    Box(
                        Modifier.align(Alignment.TopCenter).fillMaxWidth().fillMaxHeight(0.18f).background(
                            Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.32f), Color.Transparent))
                        )
                    )
                    Box(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.55f).background(
                            Brush.verticalGradient(0f to Color.Transparent, 0.64f to tint.copy(alpha = 0.75f), 1f to tint)
                        )
                    )
                    Column(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val nameSize = when {
                            name.length <= 6 -> 64
                            name.length <= 10 -> 54
                            name.length <= 15 -> 44
                            name.length <= 28 -> 36
                            else -> 30
                        }
                        Text(
                            name,
                            color = pageInk,
                            fontSize = nameSize.sp,
                            lineHeight = (nameSize * 1.02f).sp,
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
                                color = pageInk,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = if (onSubtitleClick != null) Modifier.clickable(onClick = onSubtitleClick) else Modifier
                            )
                        }
                        if (!detail.isNullOrBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(detail, color = pageInk.copy(alpha = 0.6f), fontSize = 14.sp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.height(22.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (pillPlay) 18.dp else 26.dp)) {
                            RoundGlassButton(Icons.Rounded.Shuffle, "Shuffle", enabled = canPlay, onClick = onShuffle)
                            val haptics = LocalHapticFeedback.current
                            Box(
                                Modifier
                                    .alpha(if (canPlay) 1f else 0.5f)
                                    .then(if (pillPlay) Modifier.width(168.dp).height(56.dp) else Modifier.size(76.dp))
                                    .clip(CircleShape)
                                    .background(pageInk)
                                    .clickable(enabled = canPlay) {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onPlay()
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (pillPlay) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Rounded.PlayArrow, null, tint = tint, modifier = Modifier.size(32.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Play", color = tint, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                                    }
                                } else {
                                    Icon(Icons.Rounded.PlayArrow, "Play", tint = tint, modifier = Modifier.size(46.dp))
                                }
                            }
                            if (trailing != null) trailing()
                            else RoundGlassButton(Icons.AutoMirrored.Rounded.QueueMusic, "All songs", enabled = onAllSongs != null) {
                                onAllSongs?.invoke()
                            }
                        }
                    }
                }
            }
            body(searching, query)
            item("bottom_inset") { Spacer(Modifier.height(LocalMiniPlayerInset.current + 24.dp)) }
        }

        // Back and Share over the photo; fills with the page colour once the name has gone.
        Box(
            Modifier
                .fillMaxWidth()
                .drawBehind { drawRect(tint.copy(alpha = collapse)) }
                .padding(top = statusBar)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text(
                name,
                color = pageInk,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 60.dp).graphicsLayer { alpha = collapse }
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BarButton(Icons.AutoMirrored.Rounded.ArrowBackIos, "Back", { collapse }, iconStart = 5.dp, onClick = if (searching) closeSearch else onBack)
                Spacer(Modifier.weight(1f))
                if (!searching) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (onShare != null) BarButton(Icons.Rounded.Share, "Share", { collapse }, onClick = onShare)
                    if (searchHint != null) BarButton(Icons.Rounded.Search, "Search", { collapse }) {
                        searching = true
                        scope.launch { listState.scrollToItem(0) }
                    }
                    if (menu != null) {
                        var menuOpen by remember { mutableStateOf(false) }
                        Box {
                            BarButton(Icons.Rounded.MoreHoriz, "More", { collapse }) { menuOpen = true }
                            LibraryFloatingMenu(expanded = menuOpen, onDismiss = { menuOpen = false }) {
                                menu { menuOpen = false }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The newest of the artist's first album and first single, when YouTube gives their years. */
private fun latestRelease(artist: ArtistPage): BrowseCollection? {
    fun yearOf(item: BrowseCollection) = item.subtitle?.let { Regex("""\b(19|20)\d{2}\b""").find(it)?.value?.toInt() }
    return artist.shelves
        .filter { it.title.contains("Album", true) || it.title.contains("Single", true) }
        .mapNotNull { shelf -> shelf.items.firstOrNull()?.let { item -> yearOf(item)?.let { item to it } } }
        .maxByOrNull { it.second }?.first
}

@Composable
private fun LatestReleaseCard(item: BrowseCollection, onClick: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .padding(top = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(LocalPalette.current.field)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Thumbnail(item.thumbnailUrl, Modifier.size(92.dp), corner = 12, requestPx = 300)
        Column(Modifier.padding(start = 14.dp)) {
            Text("LATEST RELEASE", color = ink.copy(alpha = 0.55f), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            Spacer(Modifier.height(3.dp))
            Text(item.title, color = ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            item.subtitle?.let {
                Text(it, color = ink.copy(alpha = 0.6f), fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Shuffle / All songs beside Play: a soft disc. */
@Composable
internal fun RoundGlassButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    // A spinner in place of the icon while its work runs; [done]: not tappable, but not dimmed.
    loading: Boolean = false,
    done: Boolean = false,
    onClick: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    Box(
        Modifier
            .alpha(if (enabled || loading || done) 1f else 0.4f)
            .size(54.dp)
            .clip(CircleShape)
            .background(ink.copy(alpha = 0.1f))
            .clickable(enabled = enabled && !loading && !done, interactionSource = remember { MutableInteractionSource() }, indication = null) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        if (loading) CalmSpinner(color = ink, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
        else Icon(icon, description, tint = ink, modifier = Modifier.size(26.dp))
    }
}

/** A top-bar disc: dark over the photo, a light veil once the bar has its colour. */
@Composable
private fun BarButton(
    icon: ImageVector,
    description: String,
    collapse: () -> Float,
    modifier: Modifier = Modifier,
    iconStart: androidx.compose.ui.unit.Dp = 0.dp,
    onClick: () -> Unit
) {
    val barInk = ink
    Box(
        modifier
            .size(38.dp)
            .clip(CircleShape)
            .drawBehind { drawRect(lerp(Color.Black.copy(alpha = 0.32f), barInk.copy(alpha = 0.08f), collapse())) }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        // White over the photo, the theme's ink once the bar is filled.
        val glyph by remember(barInk) { derivedStateOf { if (collapse() < 0.5f) Color.White else barInk } }
        Icon(icon, description, tint = glyph, modifier = Modifier.padding(start = iconStart).size(19.dp))
    }
}

/** How far a page reaches up behind the top system bars: the same gap the pages keep below them
 * (see NavGraph) - the status bar, or the camera cutout where that is taller. */
@Composable
internal fun heroTopInset(): Dp =
    WindowInsets.statusBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top).asPaddingValues().calculateTopPadding()

/** An in-page search that matched nothing. */
@Composable
internal fun NoResults() {
    Text("No results", color = ink.copy(alpha = 0.55f), fontSize = 15.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp))
}
