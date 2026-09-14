package com.abn3li.telemusic.ui.navigation

import android.net.Uri
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.ui.credentials.CredentialsScreen
import com.abn3li.telemusic.ui.download.BrowseCollectionScreen
import com.abn3li.telemusic.ui.download.YouTubeDownloadScreen
import com.abn3li.telemusic.ui.library.AlbumDetailScreen
import com.abn3li.telemusic.ui.library.ArtistDetailScreen
import com.abn3li.telemusic.ui.library.LibraryScreen
import com.abn3li.telemusic.ui.library.LibraryViewModel
import com.abn3li.telemusic.ui.library.PlaylistDetailScreen
import com.abn3li.telemusic.ui.library.SmartPlaylistDetailScreen
import com.abn3li.telemusic.ui.library.SmartPlaylistKind
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

    // params is a required path segment (not optional query) to keep this route's argument
    // handling simple - "none" stands in for "no params" rather than the segment itself being
    // absent or empty, since Navigation Compose's route matcher rejects an empty path segment
    // outright (the whole route fails to match anything in the graph, not just that argument).
    private const val NO_PARAMS = "none"
    fun youtubeBrowse(browseId: String, title: String, params: String?) =
        "youtube_browse/${Uri.encode(browseId)}/${Uri.encode(title)}/${Uri.encode(params ?: NO_PARAMS)}"
}

/**
 * Now Playing is deliberately NOT a NavHost destination. It's a persistent overlay
 * (PlayerSheetOverlay) mounted once here, right alongside the Scaffold - see the comment on
 * PlayerSheetOverlay for why: routing it through Navigation Compose meant every tap on the
 * mini player paid a full first-composition cost during the slide-in animation, which made
 * both opening and swipe-to-dismiss feel like a stutter instead of one continuous motion.
 */
@Composable
fun TgMusicNavGraph(navController: NavHostController = rememberNavController()) {
    val app = LocalContext.current.applicationContext as TgMusicApp
    val startDestination = if (app.credentialsStore.hasCredentials()) Routes.LIBRARY else Routes.CREDENTIALS

    // Constructed once here at the navigation root - shared by the mini player and full player
    // inside PlayerSheetOverlay, so there's exactly one poller/one source of truth for
    // playback state for the whole app session instead of a fresh instance per navigation.
    val playerViewModel = remember { NowPlayingViewModel(app.musicRepository, app.playbackController, app.playbackQueue) }
    val playerState by playerViewModel.uiState.collectAsState()

    // Same reasoning as playerViewModel above: constructed once here so it survives navigating
    // to Artist/Album/Playlist/Settings and back, instead of LibraryScreen creating its own via
    // a local `remember` that NavHost disposes (and recreates from scratch) every round trip -
    // see LibraryScreen's own doc on its viewModel param for what that broke.
    val libraryViewModel = remember { LibraryViewModel(app.musicRepository) }

    // Plays the selected song by handing the queue straight to the shared player ViewModel -
    // it's the single place responsible for starting playback, so this never races with it.
    val playSong: (List<Long>, Int) -> Unit = { ids, index ->
        playerViewModel.playFromQueue(ids, index)
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                // Reserves space for the floating MiniPlayer (rendered in the overlay below,
                // not here) so list content in the NavHost isn't covered by it.
                if (playerState.song != null) {
                    Spacer(Modifier.height(MiniPlayerHeight))
                }
            }
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = startDestination,
                modifier = Modifier.padding(innerPadding),
                // Every screen in this app snaps instantly rather than sliding/fading - set
                // once here for all four directions (enter/exit/popEnter/popExit) so every
                // route pairs consistently with whatever it's pushed from or popped back to.
                // Individual composable()s used to only override exitTransition/
                // popEnterTransition (the properties that matter for the SOURCE of a push, e.g.
                // Library), which left every PUSHED screen (Settings, Artist, Playlist, ...)
                // still using Navigation Compose's own default animated enter/popExit - a
                // mismatched pairing (one side instant, one side animated) that showed up as a
                // visual flash/broken-layout frame on both the way in and the way back.
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None }
            ) {
                composable(Routes.CREDENTIALS) {
                    CredentialsScreen(onSaved = {
                        // Land on Library first (so Sync has somewhere to go back to), then
                        // push straight into phone-number sign-in instead of leaving the user
                        // to find the Sync icon themselves on an empty library.
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
                        onOpenCollection = { c -> navController.navigate(Routes.youtubeBrowse(c.browseId, c.title, c.params)) }
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
                        onOpenCollection = { c -> navController.navigate(Routes.youtubeBrowse(c.browseId, c.title, c.params)) }
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

        PlayerSheetOverlay(viewModel = playerViewModel, modifier = Modifier.fillMaxSize())
    }
}
