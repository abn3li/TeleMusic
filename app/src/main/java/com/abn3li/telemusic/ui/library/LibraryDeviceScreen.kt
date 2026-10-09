package com.abn3li.telemusic.ui.library

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.repository.LocalAudioFile
import com.abn3li.telemusic.ui.settings.ImportLocalMusicSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LibraryRecentlyAddedScreen(viewModel: LibraryViewModel, callbacks: LibraryCallbacks) {
    val songs by viewModel.recentlyAddedSongs.collectAsState()
    val actions = rememberLibrarySongActions(viewModel, callbacks.onPlayNext, callbacks.onOpenArtist, callbacks.onOpenAlbum)
    SongListPage("Recently added", songs, actions, callbacks.onBack, callbacks.onPlay, callbacks.onPlayCollection)
}

@Composable
fun LibraryLocalMusicScreen(viewModel: LibraryViewModel, callbacks: LibraryCallbacks) {
    val context = LocalContext.current
    val app = context.applicationContext as TgMusicApp
    val scope = rememberCoroutineScope()
    val songs by viewModel.localSongs.collectAsState()
    val actions = rememberLibrarySongActions(viewModel, callbacks.onPlayNext, callbacks.onOpenArtist, callbacks.onOpenAlbum)
    var files by remember { mutableStateOf<List<LocalAudioFile>>(emptyList()) }
    var importedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var scanning by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            files = emptyList()
            importedIds = emptySet()
            scanning = true
            showImport = true
            scanJob?.cancel()
            scanJob = scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        app.musicRepository.scanLocalFolder(uri) to app.musicRepository.getLocalImportSongIds()
                    }
                    files = result.first
                    importedIds = result.second
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    showImport = false
                    Toast.makeText(context, "Couldn't read this folder. Try another folder.", Toast.LENGTH_LONG).show()
                } finally {
                    scanning = false
                }
            }
        }
    }
    SongListPage("Local music", songs, actions, callbacks.onBack, callbacks.onPlay, callbacks.onPlayCollection,
        menu = { close ->
            LibraryMenuItem("Import from folder", Icons.Outlined.Folder) { close(); folderPicker.launch(null) }
        })
    if (showImport) ImportLocalMusicSheet(files, importedIds, scanning,
        onImport = { selected ->
            // The existing app-scoped importer finishes even if this page is closed.
            app.importLocalSongsInBackground(selected) {
                if (app.settingsStore.enrichMetadataOnSync) app.enrichLibraryInBackground()
            }
            showImport = false
        },
        onDismiss = { showImport = false; scanJob?.cancel() }
    )
}
