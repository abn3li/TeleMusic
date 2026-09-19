package com.abn3li.telemusic.ui.navigation

import android.net.Uri
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.ui.credentials.CredentialsScreen
import com.abn3li.telemusic.ui.download.BrowseCollectionScreen
import com.abn3li.telemusic.ui.download.YouTubeDownloadScreen
import com.abn3li.telemusic.ui.library.AccentGreen
import com.abn3li.telemusic.ui.library.AlbumDetailScreen
import com.abn3li.telemusic.ui.library.ArtistDetailScreen
import com.abn3li.telemusic.ui.library.LibraryScreen
import com.abn3li.telemusic.ui.library.LibraryViewModel
import com.abn3li.telemusic.ui.library.PlaylistDetailScreen
import com.abn3li.telemusic.ui.library.SmartPlaylistDetailScreen
import com.abn3li.telemusic.ui.library.SmartPlaylistKind
import com.abn3li.telemusic.ui.nowplaying.LocalMiniPlayerInset
import com.abn3li.telemusic.ui.nowplaying.MiniPlayerHeight
import com.abn3li.telemusic.ui.nowplaying.NowPlayingViewModel
import com.abn3li.telemusic.ui.nowplaying.PlayerSheetOverlay
import com.abn3li.telemusic.ui.settings.SettingsScreen
import com.abn3li.telemusic.ui.sync.SyncScreen

object Routes {
    const val CREDENTIALS = "credentials"
    const val LIBRARY = "library"
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

    val playerViewModel = remember {
        NowPlayingViewModel(app.musicRepository, app.playbackController, app.playbackQueue, app.ytDlpRepository, app.applicationContext)
    }

    val libraryViewModel = remember { LibraryViewModel(app.musicRepository) }

    val playSong: (List<Long>, Int) -> Unit = { ids, index ->
        playerViewModel.playFromQueue(ids, index)
    }

    val playerState by playerViewModel.stableUiState.collectAsState()

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val showBottomBar = currentRoute in listOf(Routes.LIBRARY, Routes.YOUTUBE_DOWNLOAD, Routes.SYNC, Routes.SETTINGS)

    val miniPlayerBottomMargin = if (showBottomBar) 84.dp else 12.dp
    val miniPlayerInset = if (playerState.song != null) {
        if (showBottomBar) 154.dp else MiniPlayerHeight
    } else if (showBottomBar) 84.dp else 0.dp

    Box(Modifier.fillMaxSize()) {
        Scaffold { innerPadding ->
            CompositionLocalProvider(LocalMiniPlayerInset provides miniPlayerInset) {
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
                    LibraryScreen(
                        viewModel = libraryViewModel,
                        onSongClick = playSong,
                        onSyncClick = { navController.navigate(Routes.SYNC) },
                        onSettingsClick = { navController.navigate(Routes.SETTINGS) },
                        onAlbumClick = { album -> navController.navigate(Routes.album(album)) },
                        onArtistClick = { artist -> navController.navigate(Routes.artist(artist)) },
                        onPlaylistClick = { id, name -> navController.navigate(Routes.playlist(id, name)) },
                        onSmartPlaylistClick = { kind -> navController.navigate(Routes.smartPlaylist(kind)) },
                        onYouTubeDownloadClick = { navController.navigate(Routes.YOUTUBE_DOWNLOAD) }
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
                    AlbumDetailScreen(album, onBack = { navController.popBackStack() }, onSongClick = playSong)
                }
                composable(Routes.ARTIST) { backStackEntry ->
                    val artist = backStackEntry.arguments?.getString("artist")?.let { Uri.decode(it) } ?: ""
                    ArtistDetailScreen(artist, onBack = { navController.popBackStack() }, onSongClick = playSong)
                }
                composable(Routes.PLAYLIST) { backStackEntry ->
                    val id = backStackEntry.arguments?.getString("id")?.toLongOrNull() ?: 0L
                    val name = backStackEntry.arguments?.getString("name")?.let { Uri.decode(it) } ?: "Playlist"
                    PlaylistDetailScreen(id, name, onBack = { navController.popBackStack() }, onSongClick = playSong)
                }
                composable(Routes.SMART_PLAYLIST) { backStackEntry ->
                    val kind = backStackEntry.arguments?.getString("kind")
                        ?.let { runCatching { SmartPlaylistKind.valueOf(it) }.getOrNull() } ?: SmartPlaylistKind.LIKED
                    SmartPlaylistDetailScreen(kind, onBack = { navController.popBackStack() }, onSongClick = playSong)
                }
            }
            }
        }

        if (showBottomBar) {
            AppBottomNavBar(
                currentRoute = currentRoute,
                onNavigate = { route ->
                    if (currentRoute != route) {
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

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(78.dp),
        color = Color(0xF0121417),
        border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.08f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
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
                                .align(Alignment.TopCenter)
                                .width(26.dp)
                                .height(3.dp)
                                .clip(RoundedCornerShape(99.dp))
                                .background(AccentGreen)
                        )
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            tint = if (isSelected) AccentGreen else Color(0xFF7B8390),
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            text = item.label,
                            color = if (isSelected) AccentGreen else Color(0xFF7B8390),
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

private data class NavigationItem(
    val route: String,
    val label: String,
    val icon: ImageVector
)
