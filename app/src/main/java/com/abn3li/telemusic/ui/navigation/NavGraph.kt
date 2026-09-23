package com.abn3li.telemusic.ui.navigation

import android.net.Uri
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.Sync
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.abn3li.telemusic.ui.credentials.CredentialsScreen
import com.abn3li.telemusic.ui.download.BrowseCollectionScreen
import com.abn3li.telemusic.ui.download.YouTubeDownloadScreen
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
    const val CREDENTIALS = "credentials"
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
    const val YOUTUBE_DOWNLOAD = "youtube_download"
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
    val startDestination = if (app.credentialsStore.hasCredentials()) Routes.LIBRARY else Routes.CREDENTIALS

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
            onOpenArtistSongs = { artist -> navController.navigate(Routes.artistSongs(artist)) }
        )
    }

    val playerState by playerViewModel.stableUiState.collectAsState()

    // After Android kills and recreates the app, NavHost restores its saved back stack, which
    // can still hold the first-run Welcome page. The saved credentials are what decide
    // whether the user is signed in, so skip past it.
    LaunchedEffect(Unit) {
        if (navController.currentDestination?.route == Routes.CREDENTIALS && app.credentialsStore.hasCredentials()) {
            navController.navigate(Routes.LIBRARY) { popUpTo(0) { inclusive = true } }
        }
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val inLibrary = currentRoute in LibraryRoutes
    val showBottomBar = inLibrary || currentRoute in listOf(Routes.YOUTUBE_DOWNLOAD, Routes.SYNC, Routes.SETTINGS)

    val miniPlayerBottomMargin = if (showBottomBar) NavBarHeight + 4.dp else 12.dp
    val miniPlayerInset = if (playerState.song != null) {
        if (showBottomBar) NavBarHeight + 4.dp + MiniPlayerHeight + 12.dp else MiniPlayerHeight
    } else if (showBottomBar) NavBarHeight + 12.dp else 0.dp

    Box(Modifier.fillMaxSize()) {
        Scaffold { innerPadding ->
            CompositionLocalProvider(
                LocalMiniPlayerInset provides miniPlayerInset,
                LocalPlayNext provides playNext
            ) {
            NavHost(
                navController = navController,
                startDestination = startDestination,
                modifier = Modifier.padding(innerPadding),
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None }
            ) {
                composable(Routes.CREDENTIALS) {
                    CredentialsScreen(onSaved = {
                        navController.navigate(Routes.LIBRARY) { popUpTo(Routes.CREDENTIALS) { inclusive = true } }
                        navController.navigate(Routes.SYNC)
                    })
                }
                composable(Routes.LIBRARY) {
                    LibraryHomeScreen(
                        viewModel = libraryViewModel,
                        onOpenPlaylists = { navController.navigate(Routes.LIBRARY_PLAYLISTS) },
                        onOpenArtists = { navController.navigate(Routes.LIBRARY_ARTISTS) },
                        onOpenAlbums = { navController.navigate(Routes.LIBRARY_ALBUMS) },
                        onOpenSongs = { navController.navigate(Routes.LIBRARY_SONGS) },
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                        onOpenPlaylist = { id, name -> navController.navigate(Routes.playlist(id, name)) },
                        onOpenSmartPlaylist = { kind -> navController.navigate(Routes.smartPlaylist(kind)) }
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
                composable(Routes.YOUTUBE_DOWNLOAD) {
                    YouTubeDownloadScreen(
                        onBack = { navController.popBackStack() },
                        onOpenCollection = { c -> navController.navigate(Routes.youtubeBrowse(c.browseId, c.title, c.params)) },
                        onPlayStream = { song, uri, videoId -> playerViewModel.playEphemeral(song, uri, videoId) }
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
                        onPlayStream = { song, uri, videoId -> playerViewModel.playEphemeral(song, uri, videoId) }
                    )
                }
                composable(Routes.SYNC) { SyncScreen(onBack = { navController.popBackStack() }) }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        onBack = { navController.popBackStack() },
                        onLoggedOut = { navController.navigate(Routes.CREDENTIALS) { popUpTo(0) { inclusive = true } } }
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
                    if (route == Routes.LIBRARY && inLibrary) {
                        // Library tapped while already inside it: back to the main Library page.
                        navController.popBackStack(Routes.LIBRARY, inclusive = false)
                    } else if (currentRoute != route) {
                        navController.navigate(route) {
                            popUpTo(Routes.LIBRARY) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        PlayerSheetOverlay(
            viewModel = playerViewModel,
            bottomOffset = miniPlayerBottomMargin,
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
            NavigationItem(Routes.LIBRARY, "Library", Icons.Default.LibraryMusic),
            NavigationItem(Routes.YOUTUBE_DOWNLOAD, "YouTube", Icons.Default.Subscriptions),
            NavigationItem(Routes.SYNC, "Sync", Icons.Default.Sync),
            NavigationItem(Routes.SETTINGS, "Settings", Icons.Default.Settings)
        )
    }

    // Docked, container-less bar (Flamingo style): the buttons sit on a fade to black so the
    // page scrolls away underneath instead of being cut off by a solid edge.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(NavBarFadeHeight)
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.3f to Color.Black.copy(alpha = 0.6f),
                    0.55f to Color.Black.copy(alpha = 0.92f),
                    1f to Color.Black
                )
            ),
        contentAlignment = Alignment.BottomCenter
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(NavBarHeight)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { item ->
                val isSelected = currentRoute == item.route
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onNavigate(item.route) }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 2.dp)
                                .size(4.dp)
                                .clip(CircleShape)
                                .background(AppAccent)
                        )
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            tint = if (isSelected) AppAccent else Color(0xFF7B8390),
                            modifier = Modifier.size(26.dp)
                        )
                        Text(
                            text = item.label,
                            color = if (isSelected) AppAccent else Color(0xFF7B8390),
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

private val NavBarHeight = 64.dp
private val NavBarFadeHeight = 96.dp

private data class NavigationItem(
    val route: String,
    val label: String,
    val icon: ImageVector
)
