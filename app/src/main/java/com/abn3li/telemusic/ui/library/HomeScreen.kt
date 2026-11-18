package com.abn3li.telemusic.ui.library

import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import com.abn3li.telemusic.ui.download.youtubeTrackActions
import com.abn3li.telemusic.ui.download.youtubeCollectionActions

import coil.imageLoader
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.gestures.ScrollableDefaults
import kotlinx.coroutines.launch
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.produceState
import com.abn3li.telemusic.data.browse.BrowseContent
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import com.abn3li.telemusic.ui.theme.ink
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.border
import com.abn3li.telemusic.data.local.displayArtwork
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import com.abn3li.telemusic.data.browse.BrowseKind
import com.abn3li.telemusic.repository.DiscoveryRepository
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.data.browse.BrowseTrack
import com.abn3li.telemusic.data.browse.BrowseCollection
import com.abn3li.telemusic.data.local.SongEntity
import com.abn3li.telemusic.ui.download.CollectionCard
import com.abn3li.telemusic.ui.download.NewReleasesViewModel

private val MixDaily = Color(0xFF72243E)
private val MixRediscover = Color(0xFF3C3489)

/**
 * The Home tab: shortcuts and the user's library sections from [LibraryViewModel.home] (worked
 * out once per library change), with YouTube Music's New releases shelf last.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun HomeScreen(
    viewModel: LibraryViewModel,
    callbacks: LibraryCallbacks,
    onOpenSettings: () -> Unit,
    onOpenCollection: (BrowseCollection) -> Unit,
    onPlayTracks: (List<BrowseTrack>, Int) -> Unit
) {
    val app = LocalContext.current.applicationContext as TgMusicApp
    val newReleasesViewModel = viewModel<NewReleasesViewModel>(factory = viewModelFactory { initializer {
        NewReleasesViewModel(app.discoveryRepository, app.youtubeAccount, app.settingsStore)
    } })
    val newReleases by newReleasesViewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, newReleasesViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) newReleasesViewModel.onHomeVisible()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val homeFeeds by app.settingsStore.homeFeeds.collectAsState()
    val waitingForFeed = homeFeeds.youtube && newReleases.isLoading &&
        if (newReleases.account.signedIn) newReleases.personal.isNullOrEmpty()
        else newReleases.section?.items.isNullOrEmpty() && newReleases.community?.items.isNullOrEmpty()
    val refresh = rememberPullToRefreshState()
    var refreshing by remember { mutableStateOf(false) }
    val refreshScope = rememberCoroutineScope()
    val home by viewModel.home.collectAsState()
    val artists by viewModel.artists.collectAsState()
    val topArtists = remember(artists) { artists.sortedByDescending { it.songCount }.take(12) }

    // Mood buttons (Relax, Sleep, Sad...): picking one puts that mood's playlists at the top of
    // the page - in a fresh random order on every tap, kept only until the next tap.
    val moods by produceState(app.discoveryRepository.moodsIfLoaded().orEmpty(), homeFeeds.youtube) {
        if (value.isEmpty() && homeFeeds.youtube) value = app.discoveryRepository.moods()
    }
    var moodKey by rememberSaveable { mutableStateOf<String?>(null) }
    var moodTap by rememberSaveable { mutableStateOf(0) }
    val selectedMood = moods.firstOrNull { moodKeyOf(it) == moodKey }?.takeIf { homeFeeds.youtube }
    val moodToken = moodKey?.let { "$it#$moodTap" }
    var moodLists by remember { mutableStateOf(MoodMemory.last) }
    var moodFailed by remember { mutableStateOf<String?>(null) }
    // Mixes already faded in once: scrolling back to their grid doesn't fade it again.
    val moodsShown = remember { mutableSetOf<String>() }
    LaunchedEffect(moodToken, selectedMood) {
        val mood = selectedMood ?: return@LaunchedEffect
        val token = moodToken ?: return@LaunchedEffect
        if (moodLists?.first == token) return@LaunchedEffect
        val lists = loadMoodPlaylists(app.discoveryRepository, mood)
        if (lists.isEmpty()) moodFailed = token
        else (token to lists).let { MoodMemory.last = it; moodLists = it }
    }
    BackHandler(enabled = selectedMood != null) { moodKey = null }
    // The big cards at the top: the first album, mix or playlist of each feed shelf.
    val heroShelves = if (homeFeeds.youtube && newReleases.account.signedIn) newReleases.personal.orEmpty() else emptyList()
    val heroSections = if (homeFeeds.youtube && !newReleases.account.signedIn) listOf(newReleases.section, newReleases.community) else emptyList()
    val heroes = remember(heroShelves, heroSections) { homeHeroes(heroShelves, heroSections) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    Box(Modifier.fillMaxSize().pullToRefresh(refreshing, refresh, enabled = homeFeeds.youtube) {
        refreshing = true
        refreshScope.launch {
            try { newReleasesViewModel.refreshHome() }
            finally { refreshing = false }
        }
    }) {
    LargeTitleList(
        title = "Home",
        listState = listState,
        showTopBar = false,
        overlay = {
            // Clip the indicator's animated entry below the system status area.
            Box(Modifier.fillMaxSize().clipToBounds()) {
                PullToRefreshDefaults.Indicator(state = refresh, isRefreshing = refreshing,
                    containerColor = LibraryFieldColor, color = AppAccent,
                    modifier = Modifier.align(Alignment.TopCenter))
            }
        },
        pinnedButton = { AccountBadge(newReleases.account, null, PinnedAccountSize) },
        onPinnedClick = onOpenSettings,
        pinnedDescription = "Settings"
    ) {
        if (homeFeeds.youtube && moods.isNotEmpty()) item("moods") {
            LazyRow(ShelfOwnLayer.padding(bottom = 14.dp), contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(moods, key = { moodKeyOf(it) }) { mood ->
                    val key = moodKeyOf(mood)
                    val on = key == moodKey
                    FilterPill(if (on) "${mood.title}  ✕" else mood.title, on) {
                        moodFailed = null
                        if (!on) moodTap++
                        moodKey = if (on) null else key
                        refreshScope.launch { listState.scrollToItem(0) }
                    }
                }
            }
        }

        if (selectedMood != null && moodToken != null) {
            val lists = moodLists?.takeIf { it.first == moodToken }?.second
            when {
                lists != null -> item("mood_lists_$moodToken") {
                    MoodPlaylistGrid(lists, onOpenCollection, Modifier.moodFadeIn(moodToken, moodsShown))
                }
                moodFailed == moodToken -> item("mood_failed") {
                    Text("Couldn't load ${selectedMood.title}. Check your connection and tap it again.",
                        color = ink.copy(alpha = 0.55f), modifier = Modifier.padding(horizontal = 18.dp, vertical = 24.dp))
                }
                else -> item("mood_loading") {
                    Box(Modifier.fillMaxWidth().height(144.dp), contentAlignment = Alignment.Center) {
                        CalmSpinner(Modifier.size(28.dp), strokeWidth = 2.dp)
                    }
                }
            }
        } else if (heroes.isNotEmpty()) {
            item("heroes_header") { HomeSectionHeader("Top Picks") }
            item("heroes") { HomeHeroRow(heroes, onOpenCollection, onPlayTracks) }
        }

        // Cached shelves stay visible during refresh; animate only while the feed is missing.
        if (waitingForFeed && !refreshing) item("feed_loading") {
            Box(
                Modifier.fillMaxWidth().height(144.dp).semantics { contentDescription = "Loading feed" },
                contentAlignment = Alignment.Center
            ) {
                CalmSpinner(Modifier.size(28.dp), strokeWidth = 2.dp)
            }
        }

        val personal = if (homeFeeds.youtube && newReleases.account.signedIn) newReleases.personal.orEmpty() else emptyList()
        personal.forEachIndexed { index, shelf -> youTubeShelf(shelf, index, onPlayTracks, onOpenCollection) }


        if (homeFeeds.telegram && home.recentlyPlayed.isNotEmpty()) {
            item("recent_played_header") { HomeSectionHeader("Recently Played") }
            item("recent_played") { SongShelf(home.recentlyPlayed, callbacks) }
        }

        if (homeFeeds.telegram && (home.dailyMix.isNotEmpty() || home.rediscover.isNotEmpty())) {
            item("mixes_header") { HomeSectionHeader("Made for You") }
            item("mixes") {
                LazyRow(ShelfOwnLayer, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (home.dailyMix.isNotEmpty()) item("daily") {
                        MixCard("Daily Mix", "Your most played", MixDaily, home.dailyMix) { callbacks.onPlayCollection(home.dailyMix.map { it.telegramMessageId }, false) }
                    }
                    if (home.rediscover.isNotEmpty()) item("rediscover") {
                        MixCard("Rediscover", "Not played in a while", MixRediscover, home.rediscover) { callbacks.onPlayCollection(home.rediscover.map { it.telegramMessageId }, false) }
                    }
                }
            }
        }

        if (homeFeeds.telegram && home.recentlyAdded.isNotEmpty()) {
            item("recent_added_header") { HomeSectionHeader("Recently Added") }
            item("recent_added") { SongShelf(home.recentlyAdded, callbacks) }
        }

        if (homeFeeds.telegram && topArtists.isNotEmpty()) {
            item("artists_header") { HomeSectionHeader("Top Artists") }
            item("artists") {
                LazyRow(ShelfOwnLayer, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(topArtists, key = { it.artist }) { artist ->
                        Column(
                            Modifier.width(84.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { callbacks.onOpenArtist(artist.artist) },
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            ArtistAvatar(artist.albumArtUrl, 84, Modifier.feedArtworkBorder(CircleShape))
                            Spacer(Modifier.height(6.dp))
                            Text(artist.artist, color = ink.copy(alpha = 0.9f), fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        newReleases.community?.takeIf { homeFeeds.youtube && !newReleases.account.signedIn && it.items.isNotEmpty() }?.let { community ->
            // A few up front; See All opens the rest as a grid.
            val more = community.items.size > COMMUNITY_SHELF_SIZE
            item("community_header") {
                HomeSectionHeader(
                    community.title,
                    onSeeAll = if (more) {
                        { onOpenCollection(BrowseCollection(DiscoveryRepository.COMMUNITY_BROWSE_ID, null, community.title, null, null, BrowseKind.OTHER)) }
                    } else null
                )
            }
            item("community") {
                LazyRow(
                    ShelfOwnLayer,
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(
                        community.items.take(COMMUNITY_SHELF_SIZE),
                        key = { index, item -> "community_${index}_${item.browseId}" }
                    ) { _, item -> CollectionCard(item, artworkBorder = true) { onOpenCollection(item) } }
                }
            }
        }

        newReleases.section?.takeIf { homeFeeds.youtube && !newReleases.account.signedIn && it.items.isNotEmpty() }?.let { releases ->
            item("new_releases_header") { HomeSectionHeader("New releases") }
            item("new_releases") {
                LazyRow(
                    ShelfOwnLayer,
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(
                        releases.items,
                        key = { index, item -> "release_${index}_${item.browseId}_${item.params.orEmpty()}" }
                    ) { _, item -> CollectionCard(item, artworkBorder = true) { onOpenCollection(item) } }
                }
            }
        }

        if (!homeFeeds.telegram && !homeFeeds.youtube) item("feeds_off") {
            Text("Home feeds are turned off. Enable them in Settings.", color = ink.copy(alpha = 0.55f),
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 24.dp))
        }
        item("end") { Spacer(Modifier.height(8.dp)) }
    }
    }
}

private const val COMMUNITY_SHELF_SIZE = 5

private const val MOOD_PAGE_SIZE = 9
private const val HERO_COUNT = 6
// Cards are shown about 260 dp tall: asked at this size from YouTube, they stay sharp.
private const val HERO_ART_PX = 720

/** One Top Picks card: a song to play ([track]) or, signed out, an album or playlist to open
 * ([item]); [label] is the shelf it comes from. */
private class HomeHero(val label: String, val track: BrowseTrack? = null, val item: BrowseCollection? = null) {
    val key get() = track?.videoId ?: (item?.browseId + item?.params.orEmpty())
    val title get() = track?.title ?: item?.title.orEmpty()
    val subtitle get() = track?.artist ?: item?.subtitle
    val artwork get() = track?.thumbnailUrl ?: item?.thumbnailUrl
}

/** Songs from the feed's song shelves (Quick picks, Song mixes, Listen again...), taking one
 * from each shelf in turn so the cards mix them. Signed out there are no songs: the first two
 * of New releases and of the community playlists instead. */
private fun homeHeroes(
    shelves: List<com.abn3li.telemusic.data.browse.HomeShelf>,
    sections: List<com.abn3li.telemusic.data.browse.HomeSection?>
): List<HomeHero> {
    val songShelves = shelves.filter { it.tracks.isNotEmpty() }
    val songs = buildList {
        var round = 0
        while (size < HERO_COUNT && songShelves.any { it.tracks.size > round }) {
            songShelves.forEach { shelf -> shelf.tracks.getOrNull(round)?.let { add(HomeHero(shelf.title, track = it)) } }
            round++
        }
    }.distinctBy { it.key }.take(HERO_COUNT)
    if (songs.isNotEmpty()) return songs
    return sections.filterNotNull().flatMap { section ->
        section.items.filter { it.kind != BrowseKind.ARTIST }.take(2).map { HomeHero(section.title, item = it) }
    }.distinctBy { it.key }.take(HERO_COUNT)
}

/** Tall cards side by side at the top of Home, the next one's edge showing: the cover filling the
 * card, and on a dark shade at its bottom the shelf it comes from, the name and the artist.
 * Tapping a song plays it, then the other cards' songs. */
@Composable
private fun HomeHeroRow(heroes: List<HomeHero>, open: (BrowseCollection) -> Unit, play: (List<BrowseTrack>, Int) -> Unit) {
    val row = androidx.compose.foundation.lazy.rememberLazyListState()
    val cardWidth = LocalConfiguration.current.screenWidthDp.dp * 0.64f
    val tracks = remember(heroes) { heroes.mapNotNull { it.track } }
    val playback = LocalCollectionPlayback.current
    LazyRow(ShelfOwnLayer.padding(bottom = 6.dp), state = row, flingBehavior = rememberStartSnap(row),
        contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(heroes, key = { it.key }) { hero ->
            val track = hero.track
            val current = track != null && playback.isCurrent(null, track.videoId)
            HomeHeroCard(hero, current, playback.isPlaying, {
                if (track != null) play(tracks, tracks.indexOf(track).coerceAtLeast(0)) else hero.item?.let(open)
            }, Modifier.width(cardWidth).aspectRatio(0.76f))
        }
    }
}

private val HeroShape = RoundedCornerShape(18.dp)
private val HeroShade = Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f)))

@Composable
private fun HomeHeroCard(hero: HomeHero, current: Boolean, playing: Boolean, onClick: () -> Unit, modifier: Modifier) {
    // Sharp at this size: YouTube Music covers are asked for at the card's size, a video's frame
    // at its largest (falling back to the shelf's own picture when a video has none that large).
    val small = hero.artwork
    val art = remember(small) {
        if (small != null && "googleusercontent.com" in small) com.abn3li.telemusic.data.browse.googleArtworkAtSize(small, HERO_ART_PX)
        else com.abn3li.telemusic.data.browse.fullSizeArtwork(small)
    }
    val actions = when {
        hero.track != null -> Modifier.youtubeTrackActions(hero.track, onClick = onClick)
        hero.item != null -> Modifier.youtubeCollectionActions(hero.item, onClick)
        else -> Modifier
    }
    Box(modifier.clip(HeroShape).background(LibraryFieldColor).then(actions)) {
        coil.compose.AsyncImage(model = art, contentDescription = null, error = coil.compose.rememberAsyncImagePainter(small),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.matchParentSize())
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.55f).align(Alignment.BottomCenter).background(HeroShade))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
            // The shelf it comes from, small above the name ("Quick picks", "Listen again").
            Text(hero.label, color = Color.White.copy(alpha = 0.72f), fontSize = 12.sp, lineHeight = 15.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(hero.title, color = if (current) AppAccent else Color.White, fontSize = 21.sp, lineHeight = 24.sp,
                fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            hero.subtitle?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(3.dp))
                Text(it, color = Color.White.copy(alpha = 0.72f), fontSize = 13.sp, lineHeight = 16.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(Modifier.matchParentSize().border(androidx.compose.foundation.BorderStroke(0.75.dp, MoodEdge), HeroShape))
        if (current) {
            Box(Modifier.matchParentSize().border(2.dp, AppAccent, HeroShape))
            Box(Modifier.align(Alignment.TopEnd).padding(10.dp).size(30.dp)) {
                PlayingMark(playing, Color.White, RoundedCornerShape(8.dp))
            }
        }
    }
}

/** In memory only, for this run: each mood's page (so a second tap needs no request), and the
 * last order shown (so coming back to Home shows the same playlists). */
private object MoodMemory {
    val pages = java.util.concurrent.ConcurrentHashMap<String, BrowseContent>()
    @Volatile var last: Pair<String, List<BrowseCollection>>? = null
}

/** The mood's playlists (and albums) in a new random order. One request the first time, none after. */
private suspend fun loadMoodPlaylists(
    repository: com.abn3li.telemusic.repository.DiscoveryRepository,
    mood: BrowseCollection
): List<BrowseCollection> {
    val key = moodKeyOf(mood)
    val page = MoodMemory.pages[key] ?: repository.browse(mood.browseId, mood.params)
        .also { if (it.collections.isNotEmpty()) MoodMemory.pages[key] = it }
    return page.collections.filter { it.kind != BrowseKind.ARTIST && !it.isPodcast }.shuffled()
}

/** The mood's playlists as pages of 3x3 covers, swiped sideways; only a sliver of the next page
 * shows, so the covers stay large. Every page keeps the first page's height. */
@Composable
private fun MoodPlaylistGrid(lists: List<BrowseCollection>, open: (BrowseCollection) -> Unit, modifier: Modifier = Modifier) {
    val pages = remember(lists) { lists.chunked(MOOD_PAGE_SIZE) }
    val rows = minOf(3, (lists.size + 2) / 3)
    val pager = rememberPagerState { pages.size }
    val pageWidth = LocalConfiguration.current.screenWidthDp.dp - 16.dp - 8.dp - 14.dp
    HorizontalPager(pager, modifier.padding(bottom = 2.dp), contentPadding = PaddingValues(horizontal = 16.dp),
        pageSize = androidx.compose.foundation.pager.PageSize.Fixed(pageWidth), pageSpacing = 8.dp,
        verticalAlignment = Alignment.Top) { index ->
        val page = pages[index]
        Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in 0 until rows) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val cells = page.drop(row * 3).take(3)
                cells.forEach { MoodPlaylistTile(it, { open(it) }, Modifier.weight(1f)) }
                repeat(3 - cells.size) { Spacer(Modifier.weight(1f).aspectRatio(1f)) }
            }
        }
    }
}

private val MoodTileShape = RoundedCornerShape(12.dp)
private val MoodShade = Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)))
private val MoodEdge = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.32f), Color.White.copy(alpha = 0.08f)))

/** A playlist's cover with its name on it, over a soft dark shade at the bottom; a thin edge that
 * is brighter along the top lifts it off the black page. Holding it opens the same menu as every
 * other YouTube card. */
@Composable
private fun MoodPlaylistTile(item: BrowseCollection, onClick: () -> Unit, modifier: Modifier) {
    Box(modifier.aspectRatio(1f).clip(MoodTileShape).background(LibraryFieldColor).youtubeCollectionActions(item, onClick)) {
        coil.compose.AsyncImage(model = item.thumbnailUrl, contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.matchParentSize())
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.5f).align(Alignment.BottomCenter).background(MoodShade))
        Text(item.title, color = Color.White, fontSize = 12.5.sp, lineHeight = 15.sp, fontWeight = FontWeight.Bold,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 9.dp, end = 9.dp, bottom = 8.dp))
        Box(Modifier.matchParentSize().border(androidx.compose.foundation.BorderStroke(0.75.dp, MoodEdge), MoodTileShape))
    }
}

/** Fades a mood's content in the first time it shows (0.2 s); after that it shows at once. */
@Composable
private fun Modifier.moodFadeIn(key: String, shown: MutableSet<String>): Modifier {
    val alpha = remember(key) { Animatable(if (key in shown) 1f else 0f) }
    LaunchedEffect(key) {
        shown += key
        alpha.animateTo(1f, tween(200))
    }
    return graphicsLayer { this.alpha = alpha.value }
}

// Every mood shares one browseId; its params tell them apart.
private fun moodKeyOf(mood: BrowseCollection) = "${mood.browseId}|${mood.params.orEmpty()}"

// Each shelf draws on its own layer: swiping one then changes only that strip of the page, so the
// frosted dock and header (which blur the page behind them) are redone only when something behind
// them really moved - not for every frame of a shelf swipe elsewhere on the page.
private val ShelfOwnLayer = Modifier.graphicsLayer()

/** Cached YouTube songs keep their queue on tap and expose the same remote actions on hold. */
@Composable
private fun homeSongActions(song: SongEntity, onClick: () -> Unit): Modifier {
    val videoId = song.youtubeVideoId?.takeIf { !song.isLocalImport }
    return if (videoId != null) Modifier.youtubeTrackActions(
        BrowseTrack(videoId, song.title, song.artist, song.displayArtwork, song.durationSeconds), onClick)
    else Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
}

@Composable
internal fun HomeSectionHeader(
    title: String,
    onSeeAll: (() -> Unit)? = null,
    strapline: String? = null,
    // In place of "See all", in the same accent link: "Play all".
    action: String? = null
) {
    // One shape for every section on every page: same title size, same "See all" in the accent
    // colour, same 48 dp row (the link is a comfortable target outside the Home page too).
    Row(
        // The 48 dp minimum sits inside the padding, so a header with "See all" and one without
        // are the same height and the gaps between shelves stay even.
        Modifier.fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, top = 26.dp, bottom = 6.dp)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Under a strapline, centring would put the link between the two lines, so it sits on
        // the title's baseline instead.
        val onTitleLine = if (strapline != null) Modifier.alignBy(LastBaseline) else Modifier
        Column(Modifier.weight(1f).then(onTitleLine)) {
            if (strapline != null) {
                Text(strapline.uppercase(), color = ink.copy(alpha = 0.5f), fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    letterSpacing = 0.3.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(title, color = ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onSeeAll != null) SectionLink(action ?: "See all", "${action ?: "See all"} $title", onSeeAll, onTitleLine)
    }
}

/** A section's link ("See all ›", "Play all ›"): accent text and a chevron, the same on every page. */
@Composable
internal fun SectionLink(text: String, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(start = 12.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = AppAccent, fontSize = 15.sp)
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = AppAccent, modifier = Modifier.size(20.dp).offset(x = 2.dp))
    }
}

/** The account photo's size inside the pinned glass circle. */
internal val PinnedAccountSize = 32.dp

/** Your account: its photo, else your first letter on the accent, else a plain person. Opens Settings. */
@Composable
internal fun AccountBadge(account: com.abn3li.telemusic.data.youtube.YouTubeAccountState, onClick: (() -> Unit)?, size: androidx.compose.ui.unit.Dp = 34.dp) {
    val initial = account.name?.trim()?.firstOrNull()?.uppercaseChar()
    Box(
        Modifier.size(size).clip(CircleShape)
            .semantics { contentDescription = "Settings" }
            .then(if (onClick != null) Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick) else Modifier)
            .then(if (initial != null) Modifier.background(Brush.linearGradient(listOf(AppAccent, AppAccent.copy(red = AppAccent.red * 0.7f, green = AppAccent.green * 0.6f, blue = AppAccent.blue * 0.75f)))) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        when {
            initial != null -> Text(initial.toString(), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            else -> Icon(Icons.Rounded.AccountCircle, null, tint = AppAccent, modifier = Modifier.fillMaxSize())
        }
        // The photo covers the letter once it has loaded.
        if (account.photoUrl != null) coil.compose.AsyncImage(account.photoUrl, null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/** A sideways row of song covers; tapping one plays the row from that song. */
@Composable
private fun SongShelf(songs: List<SongEntity>, callbacks: LibraryCallbacks) {
    val ids = remember(songs) { songs.map { it.telegramMessageId } }
    val rowState = rememberLazyListState()
    // A newly played/added song is inserted at the front; a keyed row stays anchored on the old
    // first item, leaving the new ones hidden off-screen to the left. Snap back to the start when
    // the front changes - unless the user scrolled along the row. "Scrolled" is judged only when
    // a scroll ends, so the row being pushed along by new songs (several can arrive while Home
    // isn't showing) doesn't count as the user having moved it.
    var atStart by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(rowState) {
        snapshotFlow { rowState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) atStart = rowState.firstVisibleItemIndex == 0
        }
    }
    LaunchedEffect(songs.firstOrNull()?.telegramMessageId) {
        if (atStart) rowState.scrollToItem(0)
    }
    val playback = LocalCollectionPlayback.current
    LazyRow(state = rowState, modifier = ShelfOwnLayer, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(songs, key = { _, s -> s.telegramMessageId }) { index, song ->
            val current = playback.isCurrent(song.telegramMessageId, null)
            Column(Modifier.width(128.dp).then(homeSongActions(song) { callbacks.onPlay(ids, index) })) {
                Box {
                    SongArtwork(song, 128.dp, 6.dp, Modifier.feedArtworkBorder(RoundedCornerShape(6.dp)))
                    if (current) PlayingMark(playback.isPlaying, Color.White, RoundedCornerShape(6.dp))
                }
                Spacer(Modifier.height(5.dp))
                Text(song.title, color = if (current) AppAccent else ink.copy(alpha = 0.9f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, color = if (current) AppAccent.copy(alpha = 0.75f) else ink.copy(alpha = 0.55f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A mix: four of its songs' covers in a 2x2 grid, its name on a band of its colour below. */
@Composable
private fun MixCard(title: String, subtitle: String, color: Color, songs: List<SongEntity>, onClick: () -> Unit) {
    val covers = remember(songs) { songs.distinctBy { it.displayArtwork }.take(4) }
    Box(
        Modifier
            .size(156.dp, 196.dp)
            .feedArtworkBorder(RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(color)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
    ) {
        Column {
            for (row in 0 until 2) {
                Row {
                    for (column in 0 until 2) {
                        val song = covers.getOrNull(row * 2 + column)
                        if (song != null) SongArtwork(song, 78.dp, 0.dp) else Spacer(Modifier.size(78.dp))
                    }
                }
            }
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(66.dp)
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.4f to color))
                .padding(horizontal = 11.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            Text(subtitle, color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.youTubeShelf(
    shelf: com.abn3li.telemusic.data.browse.HomeShelf,
    index: Int,
    onPlayTracks: (List<BrowseTrack>, Int) -> Unit,
    onOpenCollection: (BrowseCollection) -> Unit
) {
    item("yt_header_$index", contentType = "yt_header") {
        val showAll: () -> Unit = {
            onOpenCollection(
                BrowseCollection(
                    shelf.moreBrowseId ?: (DiscoveryRepository.SHELF_BROWSE_PREFIX + shelf.title),
                    if (shelf.moreBrowseId != null) shelf.moreParams else null,
                    shelf.title, null, null, BrowseKind.OTHER
                )
            )
        }
        when {
            shelf.listRows && shelf.tracks.size > 1 && !shelf.isForgottenFavorites() ->
                HomeSectionHeader(shelf.title, strapline = shelf.strapline, action = "Play all", onSeeAll = { onPlayTracks(shelf.tracks, 0) })
            else -> HomeSectionHeader(shelf.title, strapline = shelf.strapline, onSeeAll = showAll)
        }
    }
    item("yt_shelf_$index", contentType = shelf.layoutKind()) {
        // Each shelf's row owns its scrolling (see shelfDrag); the title row above doesn't move it.
        val row = rememberLazyListState()
        val snapToItem = shelf.layoutKind() in listOf("yt_rows", "yt_grid")
        val fling = if (snapToItem) rememberStartSnap(row) else ScrollableDefaults.flingBehavior()
        androidx.compose.runtime.CompositionLocalProvider(LocalShelfRow provides ShelfRow(row, fling, snapToItem)) {
            YouTubeShelf(shelf, index, onPlayTracks, onOpenCollection)
        }
    }
}

/** The row a shelf's covers scroll in, handed to whichever layout the shelf uses. */
private class ShelfRow(
    val state: androidx.compose.foundation.lazy.LazyListState,
    val fling: androidx.compose.foundation.gestures.FlingBehavior,
    val snapToItem: Boolean
)
private val LocalShelfRow = androidx.compose.runtime.staticCompositionLocalOf<ShelfRow?> { null }

/** Which layout a shelf gets (see YouTubeShelf) - shelves of one kind reuse each other's build. */
private fun com.abn3li.telemusic.data.browse.HomeShelf.layoutKind(): String {
    val name = title.lowercase()
    return when {
        isForgottenFavorites() -> "yt_covers"
        listRows -> "yt_rows"
        "listen again" in name -> "yt_grid"
        "mix" in name && collections.isNotEmpty() && tracks.isEmpty() -> "yt_mixes"
        else -> "yt_covers"
    }
}

private fun com.abn3li.telemusic.data.browse.HomeShelf.isForgottenFavorites(): Boolean {
    val name = title.lowercase()
    return "forgot" in name && ("favor" in name || "favour" in name)
}

private val MixColors = listOf(Color(0xFF72243E), Color(0xFF3C3489), Color(0xFF0F5E5A), Color(0xFF7A4A0E), Color(0xFF2B4C7E), Color(0xFF5B2A6E))

/**
 * One shelf of the signed-in YouTube Music feed, laid out by kind: Quick picks (list rows) four
 * songs to a column; "Listen again" as a two-row grid of covers; mixes as colour tiles; other
 * songs as a row of covers; albums, playlists and artists as cards (artists round).
 */
@Composable
private fun YouTubeShelf(
    shelf: com.abn3li.telemusic.data.browse.HomeShelf,
    index: Int,
    onPlayTracks: (List<BrowseTrack>, Int) -> Unit,
    onOpenCollection: (BrowseCollection) -> Unit
) {
    val title = shelf.title.lowercase()
    when {
        shelf.isForgottenFavorites() -> HomeCoverShelf(shelf, onPlayTracks, onOpenCollection)
        shelf.listRows -> QuickPicks(shelf.tracks, onPlayTracks)
        "listen again" in title -> ListenAgainGrid(shelf, onPlayTracks, onOpenCollection)
        "mix" in title && shelf.collections.isNotEmpty() && shelf.tracks.isEmpty() ->
            LazyRow(state = LocalShelfRow.current!!.state, modifier = Modifier.shelfDrag(LocalShelfRow.current!!.state, LocalShelfRow.current!!.fling, COVER_MAX_FLING, LocalShelfRow.current!!.snapToItem).then(ShelfOwnLayer),
                flingBehavior = LocalShelfRow.current!!.fling, userScrollEnabled = false,
                contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(shelf.collections, key = { i, item -> "mix_${index}_${i}_${item.browseId}" }) { i, item ->
                    YouTubeMixTile(item, MixColors[i % MixColors.size]) { onOpenCollection(item) }
                }
            }
        else -> HomeCoverShelf(shelf, onPlayTracks, onOpenCollection)
    }
}

/** Quick picks, like YouTube Music: columns of four songs in a row that scrolls freely and
 * stops at the nearest column, the next one peeking in from the edge. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickPicks(tracks: List<BrowseTrack>, onPlay: (List<BrowseTrack>, Int) -> Unit) {
    if (tracks.isEmpty()) return
    val play by rememberUpdatedState(onPlay)
    val columns = remember(tracks) { tracks.withIndex().chunked(4) }
    // Every cover is fetched and decoded at its row size up front (a few small images), so a
    // column sliding in draws covers that are ready instead of preparing them on that frame.
    val context = LocalContext.current
    val coverPx = with(LocalDensity.current) { 46.dp.roundToPx() }
    LaunchedEffect(tracks) {
        tracks.forEach { track ->
            track.thumbnailUrl?.let { url ->
                context.imageLoader.enqueue(coil.request.ImageRequest.Builder(context).data(url).size(coverPx).build())
            }
        }
    }
    // A column is most of the screen, so the next one shows at the edge.
    val columnWidth = LocalConfiguration.current.screenWidthDp.dp * 0.86f
    val shelfRow = LocalShelfRow.current!!
    val playback = LocalCollectionPlayback.current
    LazyRow(state = shelfRow.state, modifier = Modifier.shelfDrag(shelfRow.state, shelfRow.fling).then(ShelfOwnLayer), flingBehavior = shelfRow.fling, userScrollEnabled = false,
        contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(columns.size, key = { "qp_$it" }, contentType = { "qp_column" }) { column ->
            Column(Modifier.width(columnWidth).clip(RoundedCornerShape(20.dp))
                .background(LibraryFieldColor).border(1.dp, ink.copy(alpha = 0.06f), RoundedCornerShape(20.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp).heightIn(min = 256.dp)) {
                columns[column].forEachIndexed { rowIndex, (i, track) ->
                    val current = playback.isCurrent(null, track.videoId)
                    Row(Modifier.fillMaxWidth().heightIn(min = 62.dp)
                        .youtubeTrackActions(track, onClick = { play(tracks, i) }),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            coil.compose.AsyncImage(model = track.thumbnailUrl, contentDescription = null,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier.size(46.dp).feedArtworkBorder(RoundedCornerShape(9.dp)).clip(RoundedCornerShape(9.dp)).background(LibraryFieldColor))
                            if (current) PlayingMark(playback.isPlaying, Color.White, RoundedCornerShape(9.dp))
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(track.title, color = if (current) AppAccent else ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(track.artist, color = if (current) AppAccent.copy(alpha = 0.75f) else ink.copy(alpha = 0.55f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (rowIndex < columns[column].lastIndex) Box(Modifier.fillMaxWidth().padding(start = 58.dp)
                        .height(1.dp).background(ink.copy(alpha = 0.07f)))
                }
            }
        }
    }
}

/**
 * How a shelf follows a finger, like YouTube Music's: a touch stops a gliding shelf; a move that
 * is clearly sideways (more sideways than up/down) drags it and flings it with the finger's speed.
 * Normal cover rows stop freely; song tables and grids still line up their columns. Anything
 * else is left to the page, which scrolls. (The row's own
 * scrolling grabbed any touch on a moving shelf at once, whatever its direction, so a scroll up
 * or down right after a flick shook the shelf instead of moving the page.)
 */
@Composable
internal fun Modifier.shelfDrag(row: androidx.compose.foundation.lazy.LazyListState,
    fling: androidx.compose.foundation.gestures.FlingBehavior, maxFling: Dp = SHELF_MAX_FLING,
    snapToItem: Boolean = true): Modifier {
    val scope = rememberCoroutineScope()
    return pointerInput(row, fling, maxFling, snapToItem) {
        val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (row.isScrollInProgress) scope.launch { row.stopScroll() }
            tracker.resetTracking()
            tracker.addPosition(down.uptimeMillis, down.position)
            var start = 0f
            val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                val moved = change.position - down.position
                if (kotlin.math.abs(moved.x) > kotlin.math.abs(moved.y)) { change.consume(); start = over }
            } ?: return@awaitEachGesture
            row.dispatchRawDelta(-start)
            var dragged = start
            val released = horizontalDrag(drag.id) { change ->
                tracker.addPosition(change.uptimeMillis, change.position)
                row.dispatchRawDelta(-change.positionChange().x)
                dragged += change.positionChange().x
                change.consume()
            }
            // A cancelled touch (another gesture took over, or the page went away) is not a
            // release. Starting a fling here moved the shelf after the finger had stopped.
            if (!released) return@awaitEachGesture
            // A shelf goes about one screen of covers per flick, like YouTube Music's - full
            // long-list momentum threw it 5 to 20 covers, so only a slow drag moved it by one.
            val limit = maxFling.toPx()
            val velocity = (-tracker.calculateVelocity().x).coerceIn(-limit, limit)
            if (snapToItem && kotlin.math.abs(velocity) < SHELF_SLOW_RELEASE.toPx()) {
                // Wide song columns finish in the drag's direction. Ordinary cover rows keep
                // their exact release position instead of being forced onto another image.
                val forward = dragged < 0
                val first = row.firstVisibleItemIndex
                val target = if (forward && row.firstVisibleItemScrollOffset > 0) first + 1 else first
                scope.launch { row.animateScrollToItem(target.coerceAtMost(row.layoutInfo.totalItemsCount - 1)) }
            } else {
                scope.launch { row.scroll { with(fling) { performFling(velocity) } } }
            }
        }
    }
}

// The fastest a flick throws a song table: about one column before it lines up.
// Song tables (Quick picks, Long listens, Trending): wide columns, one per flick.
private val SHELF_MAX_FLING = 700.dp
// Cover shelves (albums, mixes, Listen again): a flick carries on across several covers;
// capped so it never races to the end.
private val COVER_MAX_FLING = 2200.dp
// Below this release speed a swipe is a slow one (see shelfDrag).
private val SHELF_SLOW_RELEASE = 350.dp

/** Lines up song tables and grids at the left edge instead of centring a column. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun rememberStartSnap(row: androidx.compose.foundation.lazy.LazyListState): androidx.compose.foundation.gestures.FlingBehavior {
    val layout = remember(row) {
        androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider(row,
            androidx.compose.foundation.gestures.snapping.SnapPosition.Start)
    }
    return rememberSnapFlingBehavior(layout)
}

/**
 * Covers scroll with momentum; only table/grid shelves line up when released.
 * [rows] covers are stacked in each column ("Listen again": two).
 * Only the covers on screen are built.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CoverRow(count: Int, rows: Int, coverWidth: Dp, content: @Composable (index: Int) -> Unit) {
    if (count == 0) return
    val shelfRow = LocalShelfRow.current!!
    val columns = (count + rows - 1) / rows
    LazyRow(state = shelfRow.state, modifier = Modifier.shelfDrag(shelfRow.state, shelfRow.fling, COVER_MAX_FLING, shelfRow.snapToItem).then(ShelfOwnLayer), flingBehavior = shelfRow.fling, userScrollEnabled = false,
        contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        items(columns, key = { "col_$it" }, contentType = { "cover_column_$rows" }) { column ->
            Column(Modifier.width(coverWidth), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(rows) { r ->
                    val index = column * rows + r
                    if (index < count) content(index)
                }
            }
        }
    }
}

/** "Listen again": covers in two rows that scroll together. Songs play, the rest open. */
@Composable
private fun ListenAgainGrid(
    shelf: com.abn3li.telemusic.data.browse.HomeShelf,
    onPlay: (List<BrowseTrack>, Int) -> Unit,
    onOpen: (BrowseCollection) -> Unit,
    rows: Int = 2
) {
    val play by rememberUpdatedState(onPlay)
    val open by rememberUpdatedState(onOpen)
    val entries = remember(shelf) {
        shelf.tracks.mapIndexed { i, t -> HomeCoverEntry(t.thumbnailUrl, t.title, t.artist) { play(shelf.tracks, i) } } +
            shelf.collections.map { c -> HomeCoverEntry(c.thumbnailUrl, c.title, c.subtitle) { open(c) } }
    }
    val coverWidth = 112.dp
    val playback = LocalCollectionPlayback.current
    CoverRow(entries.size, rows = rows, coverWidth = coverWidth) { index ->
        val entry = entries[index]
        val track = shelf.tracks.getOrNull(index)
        val actions = if (track != null) Modifier.youtubeTrackActions(track, entry.open)
        else Modifier.youtubeCollectionActions(shelf.collections[index - shelf.tracks.size], entry.open)
        val current = track != null && playback.isCurrent(null, track.videoId)
        Column(Modifier.width(coverWidth).then(actions)) {
            Box {
                coil.compose.AsyncImage(model = entry.artwork, contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.size(coverWidth).feedArtworkBorder(RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).background(LibraryFieldColor))
                if (current) PlayingMark(playback.isPlaying, Color.White, RoundedCornerShape(12.dp))
            }
            CoverCaption(entry, current, top = 6.dp)
        }
    }
}

/** A YouTube mix (Supermix, My Mix, Discover): its own cover above a band of colour with its
 * name, like Made for You's tiles. */
@Composable
private fun YouTubeMixTile(item: BrowseCollection, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(156.dp, 196.dp)
            .feedArtworkBorder(RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(color)
            .youtubeCollectionActions(item, onClick)
    ) {
        coil.compose.AsyncImage(
            model = item.thumbnailUrl,
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.size(156.dp)
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(66.dp)
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.4f to color))
                .padding(horizontal = 11.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            Text(item.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            item.subtitle?.let {
                Text(it, color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private data class HomeCoverEntry(val artwork: String?, val title: String, val subtitle: String?, val open: () -> Unit)

/** A cover's name and, in grey below, its artist (or what it is); in the accent while it plays. */
@Composable
private fun CoverCaption(entry: HomeCoverEntry, current: Boolean, top: Dp) {
    Text(entry.title, color = if (current) AppAccent else ink.copy(alpha = 0.9f), fontSize = 13.sp, lineHeight = 17.sp,
        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = top))
    if (!entry.subtitle.isNullOrBlank()) Text(entry.subtitle, color = if (current) AppAccent.copy(alpha = 0.75f) else ink.copy(alpha = 0.55f),
        fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** A mixed YouTube shelf: one row of covers; songs play and collections open in place. */
@Composable
private fun HomeCoverShelf(
    shelf: com.abn3li.telemusic.data.browse.HomeShelf,
    onPlay: (List<BrowseTrack>, Int) -> Unit,
    onOpen: (BrowseCollection) -> Unit
) {
    val play by rememberUpdatedState(onPlay)
    val open by rememberUpdatedState(onOpen)
    val entries = remember(shelf) {
        shelf.tracks.mapIndexed { index, track ->
            HomeCoverEntry(track.thumbnailUrl, track.title, track.artist) { play(shelf.tracks, index) }
        } + shelf.collections.map { collection ->
            HomeCoverEntry(collection.thumbnailUrl, collection.title, collection.subtitle) { open(collection) }
        }
    }
    val coverWidth = 140.dp
    val playback = LocalCollectionPlayback.current
    CoverRow(entries.size, rows = 1, coverWidth = coverWidth) { index ->
        val entry = entries[index]
        val actions = if (index < shelf.tracks.size)
            Modifier.youtubeTrackActions(shelf.tracks[index], entry.open)
        else Modifier.youtubeCollectionActions(shelf.collections[index - shelf.tracks.size], entry.open)
        val track = shelf.tracks.getOrNull(index)
        val current = track != null && playback.isCurrent(null, track.videoId)
        Column(Modifier.width(coverWidth).then(actions)) {
            Box {
                coil.compose.AsyncImage(model = entry.artwork, contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.size(coverWidth).feedArtworkBorder(RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).background(LibraryFieldColor))
                if (current) PlayingMark(playback.isPlaying, Color.White, RoundedCornerShape(12.dp))
            }
            CoverCaption(entry, current, top = 8.dp)
        }
    }
}
