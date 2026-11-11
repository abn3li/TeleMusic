package com.abn3li.telemusic.ui.library

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import com.abn3li.telemusic.data.local.SongEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/** Outlives the context card, which closes as soon as the item is tapped. */
private val shareScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/** True when this song has its own saved file (a download or a phone import) to send. */
internal val SongEntity.hasShareableFile: Boolean
    get() = localFilePath != null && (isExplicitDownload || isLocalImport)

/**
 * Sends the song's audio file through the share sheet, named "Artist - Title.ext" rather than
 * the stored file's own name. The copy lives in the cache and is replaced on the next share.
 */
internal fun shareSongFile(context: Context, song: SongEntity) {
    val path = song.localFilePath ?: return
    shareScope.launch {
        val uri = runCatching { withContext(Dispatchers.IO) { prepareShareCopy(context.applicationContext, song, path) } }.getOrNull()
        if (uri == null) {
            Toast.makeText(context, "Couldn't find the song file", Toast.LENGTH_SHORT).show()
            return@launch
        }
        val extension = uri.lastPathSegment?.substringAfterLast('.', "").orEmpty()
        val send = Intent(Intent.ACTION_SEND).apply {
            type = audioMimeType(extension)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(send, null)) }
            .onFailure { Toast.makeText(context, "No app to share with", Toast.LENGTH_SHORT).show() }
    }
}

private fun prepareShareCopy(context: Context, song: SongEntity, path: String): Uri? {
    val isContent = path.startsWith("content://")
    val extension = if (isContent) contentExtension(context, Uri.parse(path)) else File(path).extension
    val input: InputStream = if (isContent) {
        context.contentResolver.openInputStream(Uri.parse(path)) ?: return null
    } else {
        File(path).takeIf { it.isFile }?.inputStream() ?: return null
    }
    val dir = File(context.cacheDir, "shared/song").apply {
        listFiles()?.forEach { it.delete() }
        mkdirs()
    }
    val name = shareFileName(song) + if (extension.isNotBlank()) ".${extension.lowercase()}" else ""
    val file = File(dir, name)
    input.use { source -> file.outputStream().use { source.copyTo(it) } }
    return FileProvider.getUriForFile(context, "${context.packageName}.share", file)
}

private fun shareFileName(song: SongEntity): String {
    val raw = listOf(song.artist, song.title).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" - ")
    return raw.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim('.', ' ').take(120).ifEmpty { "Song" }
}

private fun contentExtension(context: Context, uri: Uri): String {
    val fromName = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()?.substringAfterLast('.', "")
    if (!fromName.isNullOrBlank()) return fromName
    return context.contentResolver.getType(uri)
        ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        .orEmpty()
}

private fun audioMimeType(extension: String): String = when (extension.lowercase()) {
    "flac" -> "audio/flac"
    "mp3" -> "audio/mpeg"
    "m4a", "mp4", "aac" -> "audio/mp4"
    "ogg", "oga", "opus" -> "audio/ogg"
    "wav" -> "audio/wav"
    "webm" -> "audio/webm"
    else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase()) ?: "audio/*"
}
