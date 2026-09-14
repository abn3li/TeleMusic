package com.abn3li.telemusic.data.download

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * Copies a finished download's app-private file into the user-picked shared/media-storage
 * folder (see AppSettingsStore.downloadFolderUri), so it shows up in a real file manager/Music
 * app instead of staying buried where only this app can see it. The app-private copy stays too
 * (see MusicRepository.importDownloadedSong's own doc on why) - this is purely an extra, visible
 * copy, not the file playback actually reads from, so a failure here never breaks the download
 * itself. Mirrors LocalAudioImporter's own DocumentsContract usage for the opposite direction
 * (reading a user's files in) rather than pulling in the androidx.documentfile dependency for
 * one write.
 */
class MediaFolderExporter(private val context: Context) {

    /** Returns the new document's own Uri on success (persisted onto the song's row - see
     * SongEntity.exportedFileUri's own doc - so "Delete download" can find and remove this exact
     * copy later), or null if the export failed for any reason. */
    fun export(source: File, folderTreeUri: Uri, displayName: String): Uri? {
        return runCatching {
            val treeDocumentId = DocumentsContract.getTreeDocumentId(folderTreeUri)
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(folderTreeUri, treeDocumentId)
            val mimeType = mimeTypeFor(source.extension)
            val newDocUri = DocumentsContract.createDocument(context.contentResolver, parentUri, mimeType, displayName)
                ?: return null
            context.contentResolver.openOutputStream(newDocUri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: return null
            newDocUri
        }.getOrNull()
    }

    /** Best-effort removal of a previously exported copy - a failure here (folder moved, file
     * already gone, permission revoked) is never fatal, "Delete download" still clears the row's
     * own reference to it either way. */
    fun delete(documentUri: Uri): Boolean =
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, documentUri) }.getOrDefault(false)

    private fun mimeTypeFor(extension: String): String = when (extension.lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a", "mp4", "aac" -> "audio/mp4"
        "opus", "ogg" -> "audio/ogg"
        // .weba is the real "WebM Audio" extension - see ytdlp_bridge.py's own doc on why a
        // downloaded track is renamed to it instead of staying .webm (which Android's default
        // extension->MIME mapping treats as video). .webm kept here too for any file already on
        // disk from before that rename existed.
        "weba", "webm" -> "audio/webm"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        else -> "audio/*"
    }
}
