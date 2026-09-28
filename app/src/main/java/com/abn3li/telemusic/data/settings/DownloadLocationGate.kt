package com.abn3li.telemusic.data.settings

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * Asks once, on the very first download from anywhere in the app, where downloads should live:
 * app storage (private) or a folder the user picks (a single visible copy, played from there).
 * Every download entry point goes through [run]; until a choice exists, the download is parked
 * in [pending] and the app-wide prompt (NavGraph) shows. Event-driven only - nothing polls.
 */
class DownloadLocationGate(private val settings: AppSettingsStore) {
    private val _pending = MutableStateFlow<(() -> Unit)?>(null)
    val pending: StateFlow<(() -> Unit)?> = _pending

    fun run(download: () -> Unit) {
        if (settings.downloadLocationChosen) download() else _pending.value = download
    }

    fun chooseAppStorage() {
        settings.downloadFolderUri = null
        finish()
    }

    /** A folder just picked with the system folder picker becomes the download folder: keeps
     * access to it, saves it, and lets a waiting download go on. The one way every screen
     * (Settings, the first-download prompt, the hello screens) sets the folder. */
    fun useFolder(resolver: ContentResolver, treeUri: Uri) {
        takeFolderAccess(resolver, treeUri)
        chooseFolder(treeUri.toString())
    }

    fun chooseFolder(treeUri: String) {
        settings.downloadFolderUri = treeUri
        finish()
    }

    fun cancel() {
        _pending.value = null
    }

    private fun finish() {
        settings.downloadLocationChosen = true
        _pending.getAndUpdate { null }?.invoke()
    }
}

/** Keeps read/write access to a folder the user picked, across restarts. */
fun takeFolderAccess(resolver: ContentResolver, treeUri: Uri) {
    runCatching { resolver.takePersistableUriPermission(treeUri, FOLDER_ACCESS) }
}

/** Gives back access to a folder that was picked but not kept. Never call it for the saved
 * download folder: songs already downloaded there play straight from it. */
fun releaseFolderAccess(resolver: ContentResolver, treeUri: Uri) {
    runCatching { resolver.releasePersistableUriPermission(treeUri, FOLDER_ACCESS) }
}

private const val FOLDER_ACCESS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
