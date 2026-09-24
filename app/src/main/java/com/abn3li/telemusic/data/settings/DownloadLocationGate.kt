package com.abn3li.telemusic.data.settings

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
