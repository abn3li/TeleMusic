package com.abn3li.telemusic.ui.navigation

import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import com.abn3li.telemusic.ui.theme.paper
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.flow.first
import com.abn3li.telemusic.ui.library.HomeScreen
import com.abn3li.telemusic.ui.library.AlertAction
import com.abn3li.telemusic.ui.library.AppAlert
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.data.browse.BrowseTrack
import com.abn3li.telemusic.ui.download.BrowseCollectionScreen
import com.abn3li.telemusic.ui.onboarding.OnboardingScreen
import com.abn3li.telemusic.ui.search.SearchScreen
import com.abn3li.telemusic.ui.library.AppAccent
import com.abn3li.telemusic.ui.library.AlbumDetailScreen
import com.abn3li.telemusic.ui.library.ArtistDetailScreen
import com.abn3li.telemusic.ui.library.ArtistSongsScreen
import com.abn3li.telemusic.ui.library.LibraryAlbumsScreen
import com.abn3li.telemusic.ui.library.LibraryArtistsScreen
import com.abn3li.telemusic.ui.library.LibraryCallbacks
import com.abn3li.telemusic.ui.library.LibraryHomeScreen
import com.abn3li.telemusic.ui.library.LibraryPlaylistsScreen
import com.abn3li.telemusic.ui.library.LibrarySongsScreen
import com.abn3li.telemusic.ui.library.LibraryViewModel
import com.abn3li.telemusic.ui.library.PlaylistDetailScreen
import com.abn3li.telemusic.ui.library.SmartPlaylistDetailScreen
import com.abn3li.telemusic.ui.library.SmartPlaylistKind
import com.abn3li.telemusic.ui.nowplaying.LocalMiniPlayerInset
import com.abn3li.telemusic.ui.nowplaying.LocalPlayNext
import com.abn3li.telemusic.ui.library.CollectionPlayback
import com.abn3li.telemusic.ui.library.LocalCollectionPlayback
import com.abn3li.telemusic.ui.nowplaying.MiniPlayerHeight
import com.abn3li.telemusic.ui.nowplaying.NowPlayingViewModel
import com.abn3li.telemusic.ui.nowplaying.PlayerSheetOverlay
import com.abn3li.telemusic.ui.settings.SettingsScreen
import com.abn3li.telemusic.ui.sync.SyncScreen

private val LibraryRoutes = setOf(
    Routes.LIBRARY, Routes.LIBRARY_SONGS, Routes.LIBRARY_ALBUMS, Routes.LIBRARY_ARTISTS, Routes.LIBRARY_PLAYLISTS,
    Routes.ALBUM, Routes.ARTIST, Routes.ARTIST_SONGS, Routes.PLAYLIST, Routes.SMART_PLAYLIST
)

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val LIBRARY = "library"
    const val LIBRARY_SONGS = "library/songs"
    const val LIBRARY_ALBUMS = "library/albums"
    const val LIBRARY_ARTISTS = "library/artists"
    const val LIBRARY_PLAYLISTS = "library/playlists"
    const val ARTIST_SONGS = "artist_songs/{artist}"
    const val SYNC = "sync"
    const val SETTINGS = "settings"
    const val ALBUM = "album/{album}"
    const val ARTIST = "artist/{artist}"
    const val PLAYLIST = "playlist/{id}/{name}"
    const val SMART_PLAYLIST = "smart_playlist/{kind}"
    const val SEARCH = "search"
    const val YOUTUBE_SIGN_IN = "youtube_sign_in"
    const val SPOTIFY = "spotify"
    const val YOUTUBE_BROWSE = "youtube_browse/{browseId}/{title}/{params}"

    fun album(name: String) = "album/${Uri.encode(name)}"
    fun artist(name: String) = "artist/${Uri.encode(name)}"
    fun artistSongs(name: String) = "artist_songs/${Uri.encode(name)}"
    fun playlist(id: Long, name: String) = "playlist/$id/${Uri.encode(name)}"
    fun smartPlaylist(kind: SmartPlaylistKind) = "smart_playlist/${kind.name}"

    private const val NO_PARAMS = "none"
    fun youtubeBrowse(browseId: String, title: String, params: String?) =
        "youtube_browse/${Uri.encode(browseId)}/${Uri.encode(title)}/${Uri.encode(params ?: NO_PARAMS)}"
}

/**
 * Main application Navigation Graph with fixed Frosted Glass Bottom Bar and Floating MiniPlayer.
 */
@Composable
fun TgMusicNavGraph(navController: NavHostController = rememberNavController()) {
    val app = LocalContext.current.applicationContext as TgMusicApp
    // The hello screens on the very first start, then always Home. Telegram's API ID/hash are
    // asked for in Settings > Telegram, only when you use it.
    // Decided once: finishing the hello screens must not change the start page mid-session,
    // which would rebuild the whole navigation graph.
    val startDestination = remember { if (app.needsOnboarding()) Routes.ONBOARDING else Routes.HOME }

    // viewModel() (not remember{}) so this survives a config change (rotation) via the Activity's
    // own ViewModelStore - TgMusicNavGraph is composed directly in MainActivity's setContent, so
    // LocalViewModelStoreOwner resolves to the Activity itself. A plain remember{} block is torn
    // down and rebuilt on every Activity recreation (rotation included, unless the manifest
    // handles the config change itself, which this app doesn't), which silently reset song=null
    // and made the MiniPlayer disappear on rotate even though the song was still audibly playing
    // - see NowPlayingViewModel.init{}'s own doc for the other half of this fix (recovering the
    // already-playing song for the still-separate case of a fresh process after being cleared
    // from recents, which a ViewModelStore can't help with since the whole process was killed).
    val playerViewModel = viewModel<NowPlayingViewModel>(
        factory = viewModelFactory {
            initializer {
                NowPlayingViewModel(app.musicRepository, app.playbackController, app.playbackQueue, app.ytDlpRepository, app.applicationContext)
            }
        }
    )

    val libraryViewModel = remember { LibraryViewModel(app.musicRepository) }

    val playNext: (Long) -> Unit = remember(playerViewModel) { { id -> playerViewModel.playNext(id) } }
    val libraryCallbacks = remember(navController, playerViewModel) {
        LibraryCallbacks(
            onBack = { navController.popBackStack() },
            onPlay = { ids, index -> playerViewModel.playFromQueue(ids, index) },
            onPlayCollection = { ids, shuffle -> playerViewModel.playCollection(ids, shuffle) },
            onPlayNext = { id -> playerViewModel.playNext(id) },
            onOpenArtist = { artist -> navController.navigate(Routes.artist(artist)) },
            onOpenAlbum = { album -> navController.navigate(Routes.album(album)) },
            onOpenArtistSongs = { artist -> navController.navigate(Routes.artistSongs(artist)) },
            onToggleShuffle = { playerViewModel.toggleShuffle() },
            onTogglePlayPause = { playerViewModel.togglePlayPause() }
        )
    }

    // YouTube songs (search results, albums, playlists) play through the normal queue as
    // stream-only songs, so Next / Previous move through the whole list.
    val playScope = rememberCoroutineScope()
    val playStreams: (List<BrowseTrack>, Int, Boolean) -> Unit = remember(playerViewModel) {
        { tracks, index, shuffle ->
            // The queue entries are built off the main thread: a playlist of hundreds of songs
            // would otherwise hold up the tap.
            playScope.launch {
                val ids = withContext(Dispatchers.Default) { app.musicRepository.queueIdsForStreams(tracks) }
                if (shuffle) playerViewModel.playCollection(ids, true) else playerViewModel.playFromQueue(ids, index)
            }
        }
    }

    val playerState by playerViewModel.stableUiState.collectAsState()
    val playingFrom by playerViewModel.playingFrom.collectAsState()
    val collectionPlayback = remember(playingFrom, playerState.song, playerState.isPlaying, playerState.isShuffleEnabled) {
        CollectionPlayback(
            sourceIds = if (playerState.song != null) playingFrom else emptyList(),
            isPlaying = playerState.isPlaying,
            isShuffled = playerState.isShuffleEnabled
        )
    }

    // Lets the loading screen go once Home's library has been read (not while Home would still
    // flash empty); the hello screens need nothing. Waits for one value, then stops listening.
    LaunchedEffect(Unit) {
        if (startDestination == Routes.HOME) libraryViewModel.home.first { it.loaded }
        app.markFirstScreenReady()
    }

    // "Import and Download All" from Spotify: once the import finishes, queue its songs through
    // the same Download All (and download-location prompt) as the playlist menu.
    val pendingSpotifyDownload by app.spotifyImporter.pendingDownload.collectAsState()
    LaunchedEffect(pendingSpotifyDownload) {
        val playlistId = pendingSpotifyDownload ?: return@LaunchedEffect
        app.spotifyImporter.downloadStarted()
        val songs = app.musicRepository.observeSongsInPlaylist(playlistId).first()
        app.downloadGate.run { libraryViewModel.downloadAllInPlaylist(playlistId, songs) }
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val inLibrary = currentRoute in LibraryRoutes
    val showBottomBar = inLibrary || currentRoute in listOf(Routes.HOME, Routes.SEARCH, Routes.SYNC, Routes.SETTINGS)

    val miniPlayerBottomMargin = if (showBottomBar) NavBarHeight else 12.dp
    val miniPlayerInset = if (playerState.song != null) {
        if (showBottomBar) NavBarHeight + MiniPlayerHeight + 12.dp else MiniPlayerHeight
    } else if (showBottomBar) NavBarHeight + 12.dp else 0.dp

    // The pages and the tab bar stay clear of the system bars; only the player (below) draws
    // behind them.
    val navBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(Modifier.fillMaxSize()) {
      // The status bar's gap is kept on the pages themselves (below), not here: the Scaffold clips
      // to its own bounds, and an artist / album / playlist page draws its artwork up behind
      // the status bar.
      val barsInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
      Box(Modifier.fillMaxSize().windowInsetsPadding(barsInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))) {
        Scaffold(contentWindowInsets = WindowInsets(0)) { innerPadding ->
            CompositionLocalProvider(
                LocalMiniPlayerInset provides miniPlayerInset,
                LocalPlayNext provides playNext,
                LocalCollectionPlayback provides collectionPlayback
            ) {
            NavHost(
                navController = navController,
                startDestination = startDestination,
                modifier = Modifier.padding(innerPadding).windowInsetsPadding(barsInsets.only(WindowInsetsSides.Top)).then(rememberVerticalBounce(currentRoute)),
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None }
            ) {
                composable(Routes.ONBOARDING) {
                    OnboardingScreen(onFinished = {
                        navController.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                    })
                }
                composable(Routes.HOME) {
                    HomeScreen(
                        viewModel = libraryViewModel,
                        callbacks = libraryCallbacks,
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                        onOpenPlaylist = { id, name -> navController.navigate(Routes.playlist(id, name)) },
                        onOpenSmartPlaylist = { kind -> navController.navigate(Routes.smartPlaylist(kind)) },
                        onOpenCollection = { c -> navController.navigate(Routes.youtubeBrowse(c.browseId, c.title, c.params)) },
                        onPlayTracks = { tracks, index -> playStreams(tracks, index, false) },
                        nowPlayingId = playerState.song?.telegramMessageId,
                        isPlaying = playerState.isPlaying
                    )
                }
                composable(Routes.SEARCH) {
                    SearchScreen(
                        libraryViewModel = libraryViewModel,
                        callbacks = libraryCallbacks,
                        onOpenPlaylist = { id, name -> navController.navigate(Routes.playlist(id, name)) },
                        onOpenCollection = { c -> navController.navigate(Routes.youtubeBrowse(c.browseId, c.title, c.params)) },
                        onPlayTracks = playStreams
                    )
                }
                composable(Routes.SPOTIFY) {
                    com.abn3li.telemusic.ui.spotify.SpotifyLibraryScreen(
                        onBack = { navController.popBackStack() },
                        onOpenPlaylist = { id, name -> navController.navigate(Routes.playlist(id, name)) }
                    )
                }
                composable(Routes.LIBRARY) {
                    LibraryHomeScreen(
                        onOpenPlaylists = { navController.navigate(Routes.LIBRARY_PLAYLISTS) },
                        onOpenArtists = { navController.navigate(Routes.LIBRARY_ARTISTS) },
                        onOpenAlbums = { navController.navigate(Routes.LIBRARY_ALBUMS) },
                        onOpenSongs = { navController.navigate(Routes.LIBRARY_SONGS) },
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) }
                    )
                }
                composable(Routes.LIBRARY_SONGS) {
                    LibrarySongsScreen(
                        viewModel = libraryViewModel,
                        onBack = libraryCallbacks.onBack,
                        onPlay = libraryCallbacks.onPlay,
                        onPlayCollection = libraryCallbacks.onPlayCollection,
                        onPlayNext = libraryCallbacks.onPlayNext,
                        onOpenArtist = libraryCallbacks.onOpenArtist,
                        onOpenAlbum = libraryCallbacks.onOpenAlbum
                    )
                }
                composable(Routes.LIBRARY_ALBUMS) {
                    LibraryAlbumsScreen(libraryViewModel, libraryCallbacks.onBack, libraryCallbacks.onOpenAlbum)
                }
                composable(Routes.LIBRARY_ARTISTS) {
                    LibraryArtistsScreen(libraryViewModel, libraryCallbacks.onBack, libraryCallbacks.onOpenArtist)
                }
                composable(Routes.LIBRARY_PLAYLISTS) {
                    LibraryPlaylistsScreen(
                        viewModel = libraryViewModel,
                        onBack = libraryCallbacks.onBack,
                        onOpenPlaylist = { id, name -> navController.navigate(Routes.playlist(id, name)) },
                        onOpenSmartPlaylist = { kind -> navController.navigate(Routes.smartPlaylist(kind)) }
                    )
                }
                composable(Routes.YOUTUBE_BROWSE) { backStackEntry ->
                    val browseId = backStackEntry.arguments?.getString("browseId")?.let { Uri.decode(it) } ?: ""
                    val title = backStackEntry.arguments?.getString("title")?.let { Uri.decode(it) } ?: ""
                    val params = backStackEntry.arguments?.getString("params")?.let { Uri.decode(it) }?.takeIf { it.isNotBlank() && it != "none" }
                    BrowseCollectionScreen(
                        title = title,
                        browseId = browseId,
                        params = params,
                        onBack = { navController.popBackStack() },
                        onOpenCollection = { c -> navController.navigate(Routes.youtubeBrowse(c.browseId, c.title, c.params)) },
                        onPlayTracks = playStreams,
                        onPlayNext = { track ->
                            playerViewModel.playNext(app.musicRepository.queueIdsForStreams(listOf(track)).first())
                            Toast.makeText(app, "Playing next", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
                composable(Routes.YOUTUBE_SIGN_IN) {
                    com.abn3li.telemusic.ui.youtube.YouTubeSignInScreen(onDone = { navController.popBackStack() })
                }
                composable(Routes.SYNC) { SyncScreen(onBack = { navController.popBackStack() }) }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        onOpenTelegram = { navController.navigate(Routes.SYNC) },
                        onYouTubeSignIn = { navController.navigate(Routes.YOUTUBE_SIGN_IN) },
                        onBack = { navController.popBackStack() },
                        onOpenSpotify = { navController.navigate(Routes.SPOTIFY) },
                        onLoggedOut = {
                            navController.navigate(Routes.HOME) { popUpTo(0) { inclusive = true } }
                        }
                    )
                }
                composable(Routes.ALBUM) { backStackEntry ->
                    val album = backStackEntry.arguments?.getString("album")?.let { Uri.decode(it) } ?: ""
                    AlbumDetailScreen(album, libraryViewModel, libraryCallbacks)
                }
                composable(Routes.ARTIST) { backStackEntry ->
                    val artist = backStackEntry.arguments?.getString("artist")?.let { Uri.decode(it) } ?: ""
                    ArtistDetailScreen(artist, libraryViewModel, libraryCallbacks)
                }
                composable(Routes.ARTIST_SONGS) { backStackEntry ->
                    val artist = backStackEntry.arguments?.getString("artist")?.let { Uri.decode(it) } ?: ""
                    ArtistSongsScreen(artist, libraryViewModel, libraryCallbacks)
                }
                composable(Routes.PLAYLIST) { backStackEntry ->
                    val id = backStackEntry.arguments?.getString("id")?.toLongOrNull() ?: 0L
                    val name = backStackEntry.arguments?.getString("name")?.let { Uri.decode(it) } ?: "Playlist"
                    PlaylistDetailScreen(id, name, libraryViewModel, libraryCallbacks)
                }
                composable(Routes.SMART_PLAYLIST) { backStackEntry ->
                    val kind = backStackEntry.arguments?.getString("kind")
                        ?.let { runCatching { SmartPlaylistKind.valueOf(it) }.getOrNull() } ?: SmartPlaylistKind.LIKED
                    SmartPlaylistDetailScreen(kind, libraryViewModel, libraryCallbacks)
                }
            }
            }
        }

        if (showBottomBar) {
            AppBottomNavBar(
                currentRoute = if (inLibrary) Routes.LIBRARY else currentRoute,
                onNavigate = { route ->
                    when {
                        // Home tapped: back to the Home page (a playlist/artist opened from Home
                        // sits on top of it).
                        route == Routes.HOME -> {
                            if (!navController.popBackStack(Routes.HOME, inclusive = false)) {
                                navController.navigate(Routes.HOME) { launchSingleTop = true }
                            }
                        }
                        // Library tapped while inside a Library page: back to the main Library
                        // page - or open it, when the page came from Home and Library isn't open.
                        route == Routes.LIBRARY && inLibrary -> {
                            if (!navController.popBackStack(Routes.LIBRARY, inclusive = false)) {
                                navController.navigate(Routes.LIBRARY) {
                                    popUpTo(Routes.HOME)
                                    launchSingleTop = true
                                }
                            }
                        }
                        currentRoute != route -> navController.navigate(route) {
                            popUpTo(Routes.HOME) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        DownloadLocationPrompt(app)
      }

        PlayerSheetOverlay(
            viewModel = playerViewModel,
            bottomOffset = miniPlayerBottomMargin + navBarInset,
            onOpenArtist = { artist -> navController.navigate(Routes.artist(artist)) },
            onOpenAlbum = { album -> navController.navigate(Routes.album(album)) },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun AppBottomNavBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val items = remember {
        listOf(
            NavigationItem(Routes.HOME, "Home", NavIcons.Home),
            NavigationItem(Routes.SEARCH, "Search", NavIcons.Search),
            NavigationItem(Routes.LIBRARY, "Library", NavIcons.Library)
        )
    }

    // The parent already clears the system bars. Adding their inset here again lifts the tabs
    // above their intended position and crowds the mini player.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(NavBarHeight)
            .background(paper)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(NavBarRowHeight),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { item ->
                NavBarItem(
                    item = item,
                    selected = currentRoute == item.route,
                    onClick = { onNavigate(item.route) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** Keep the touch target full size while the icon and label shrink together during a press. */
@Composable
private fun NavBarItem(item: NavigationItem, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.93f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "navPressScale"
    )
    val color by animateColorAsState(
        targetValue = if (selected) AppAccent else NavInactive,
        label = "navColor"
    )
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                onClick = onClick
            )
    ) {
        Column(
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.label,
                tint = color,
                modifier = Modifier.size(NavIconSize)
            )
            Text(
                text = item.label,
                color = color,
                fontSize = 12.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}

private val NavBarHeight = 64.dp
private val NavBarRowHeight = 62.dp
private val NavIconSize = 30.dp
private val NavInactive = Color.Gray

private data class NavigationItem(
    val route: String,
    val label: String,
    val icon: ImageVector
)

/** The one-time "where should downloads go?" question, shown the first time any download is
 * started (see DownloadLocationGate). Nothing here runs until a download is actually waiting. */
@Composable
private fun DownloadLocationPrompt(app: TgMusicApp) {
    val gate = app.downloadGate
    val pending by gate.pending.collectAsState()
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri == null) {
            gate.cancel()
        } else {
            gate.useFolder(app.contentResolver, treeUri)
        }
    }
    if (pending != null) {
        AppAlert(
            title = "Where should downloads go?",
            message = "A folder keeps one copy of each song where your file manager and other music apps can see it. App Storage keeps them private to TeleMusic. You can change this in Settings.",
            onDismiss = gate::cancel,
            actions = listOf(
                AlertAction("Choose Folder", bold = true) { folderPicker.launch(null) },
                AlertAction("App Storage", onClick = gate::chooseAppStorage),
                AlertAction("Cancel", onClick = gate::cancel)
            )
        )
    }
}
