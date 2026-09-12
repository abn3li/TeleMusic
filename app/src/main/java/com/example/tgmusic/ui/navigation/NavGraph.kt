package com.example.tgmusic.ui.navigation

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
import com.example.tgmusic.TgMusicApp
import com.example.tgmusic.ui.credentials.CredentialsScreen
import com.example.tgmusic.ui.library.AlbumDetailScreen
import com.example.tgmusic.ui.library.ArtistDetailScreen
import com.example.tgmusic.ui.library.LibraryScreen
import com.example.tgmusic.ui.library.PlaylistDetailScreen
import com.example.tgmusic.ui.nowplaying.MiniPlayerHeight
import com.example.tgmusic.ui.nowplaying.NowPlayingViewModel
import com.example.tgmusic.ui.nowplaying.PlayerSheetOverlay
import com.example.tgmusic.ui.settings.SettingsScreen
import com.example.tgmusic.ui.sync.SyncScreen

object Routes {
    const val CREDENTIALS = "credentials"
    const val LIBRARY = "library"
    const val SYNC = "sync"
    const val SETTINGS = "settings"
    const val ALBUM = "album/{album}"
    const val ARTIST = "artist/{artist}"
    const val PLAYLIST = "playlist/{id}/{name}"

    fun album(name: String) = "album/${Uri.encode(name)}"
    fun artist(name: String) = "artist/${Uri.encode(name)}"
    fun playlist(id: Long, name: String) = "playlist/$id/${Uri.encode(name)}"
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
                modifier = Modifier.padding(innerPadding)
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
                composable(
                    route = Routes.LIBRARY,
                    exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None }
                ) {
                    LibraryScreen(
                        onSongClick = playSong,
                        onSyncClick = { navController.navigate(Routes.SYNC) },
                        onSettingsClick = { navController.navigate(Routes.SETTINGS) },
                        onAlbumClick = { album -> navController.navigate(Routes.album(album)) },
                        onArtistClick = { artist -> navController.navigate(Routes.artist(artist)) },
                        onPlaylistClick = { id, name -> navController.navigate(Routes.playlist(id, name)) }
                    )
                }
                composable(Routes.SYNC) { SyncScreen(onBack = { navController.popBackStack() }) }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        onBack = { navController.popBackStack() },
                        onLoggedOut = { navController.navigate(Routes.CREDENTIALS) { popUpTo(0) { inclusive = true } } }
                    )
                }
                composable(
                    route = Routes.ALBUM,
                    exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None }
                ) { backStackEntry ->
                    val album = backStackEntry.arguments?.getString("album")?.let { Uri.decode(it) } ?: ""
                    AlbumDetailScreen(album, onBack = { navController.popBackStack() }, onSongClick = playSong)
                }
                composable(
                    route = Routes.ARTIST,
                    exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None }
                ) { backStackEntry ->
                    val artist = backStackEntry.arguments?.getString("artist")?.let { Uri.decode(it) } ?: ""
                    ArtistDetailScreen(artist, onBack = { navController.popBackStack() }, onSongClick = playSong)
                }
                composable(
                    route = Routes.PLAYLIST,
                    exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None }
                ) { backStackEntry ->
                    val id = backStackEntry.arguments?.getString("id")?.toLongOrNull() ?: 0L
                    val name = backStackEntry.arguments?.getString("name")?.let { Uri.decode(it) } ?: "Playlist"
                    PlaylistDetailScreen(id, name, onBack = { navController.popBackStack() }, onSongClick = playSong)
                }
            }
        }

        PlayerSheetOverlay(viewModel = playerViewModel, modifier = Modifier.fillMaxSize())
    }
}
