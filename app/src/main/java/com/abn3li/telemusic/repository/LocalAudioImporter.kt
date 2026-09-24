package com.abn3li.telemusic.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.FileOutputStream

/** One audio file found inside a user-picked folder tree, not yet imported. */
data class LocalAudioFile(
    val uri: Uri,
    val displayName: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationSeconds: Int
)

/** The SongEntity.telegramMessageId a given file imports as - derived from its own content URI
 * so re-picking the exact same file always resolves to the same row (upsert, not a duplicate),
 * and negative so it can never collide with a real (always positive) Telegram message id. Used
 * both by MusicRepository.importLocalSongs() and by the picker UI to mark files already in the
 * library - kept as one function so the two can never drift out of sync with each other. */
fun LocalAudioFile.stableSongId(): Long = -kotlin.math.abs(uri.toString().hashCode().toLong())

/**
 * Backs the "import from local storage" feature via the Storage Access Framework: the user
 * picks a folder through the system file explorer (ACTION_OPEN_DOCUMENT_TREE), this walks that
 * tree for audio files, and copies whichever ones are chosen into the app's own private
 * storage. No READ_MEDIA_AUDIO/READ_EXTERNAL_STORAGE permission needed anywhere - a SAF tree
 * grant is independent of those, which is the whole reason this reads via DocumentsContract/
 * MediaMetadataRetriever rather than a MediaStore query.
 *
 * Queries DocumentsContract directly rather than going through the DocumentFile helper class -
 * DocumentFile.listFiles() returns child references cheaply, but then each individual
 * isDirectory()/getType()/getName() call on those children is its OWN separate content-provider
 * round trip. For a folder with hundreds of songs that's several hundred extra IPC calls just to
 * classify what's already in the one cursor a plain query returns in a single round trip.
 */
class LocalAudioImporter(private val context: Context) {

    /** Recursively finds every audio file under [treeUri], one query per directory level. There's
     * no MediaStore row for an arbitrary SAF document, so each candidate's title/artist/album/
     * duration is read straight from its own tags via MediaMetadataRetriever rather than queried. */
    fun scanFolder(treeUri: Uri): List<LocalAudioFile> {
        val results = mutableListOf<LocalAudioFile>()
        val rootDocumentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return emptyList()
        collectAudioFiles(treeUri, rootDocumentId, results)
        return results
    }

    private fun collectAudioFiles(treeUri: Uri, parentDocumentId: String, out: MutableList<LocalAudioFile>) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        runCatching {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    val documentId = cursor.getString(idCol) ?: continue
                    val name = cursor.getString(nameCol) ?: continue
                    val mime = cursor.getString(mimeCol)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        collectAudioFiles(treeUri, documentId, out)
                    } else if (isAudio(mime, name)) {
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                        readMetadata(docUri, name)?.let(out::add)
                    }
                }
            }
        }
    }

    private fun isAudio(mimeType: String?, name: String): Boolean {
        val lower = name.lowercase()
        if (UNSUPPORTED_EXTENSIONS.any { lower.endsWith(it) }) return false
        if (mimeType != null && mimeType.startsWith("audio/")) return true
        return AUDIO_EXTENSIONS.any { lower.endsWith(it) }
    }

    private fun readMetadata(uri: Uri, displayName: String): LocalAudioFile? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() } ?: displayName.substringBeforeLast('.')
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() } ?: "Unknown artist"
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() }
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            LocalAudioFile(
                uri = uri,
                displayName = displayName,
                title = title,
                artist = artist,
                album = album,
                durationSeconds = (durationMs / 1000L).toInt()
            )
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Extracts the embedded cover art from [file]'s own tags, if it has any. Deliberately not
     * read during scanFolder()/readMetadata() above - that runs over an entire picked folder just
     * to list what's there, often before the user has chosen anything to import, and holding
     * decoded artwork bytes for every file in a large folder in memory at once for songs that may
     * never even get imported is real avoidable memory pressure. Called once per file, only for
     * the ones the user actually chose to import. */
    fun extractEmbeddedArtwork(file: LocalAudioFile): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, file.uri)
            retriever.embeddedPicture
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Confirms [file]'s own content:// URI is readable right now, so it can be referenced
     * directly - the same way real media players (VLC, Poweramp) handle an imported file, never
     * copying it at all. No permission call needed here: the picked folder's TREE URI already
     * had takePersistableUriPermission() called on it the moment the user picked it (see
     * SettingsScreen's importFolderLauncher), and that grant already covers every document
     * inside the tree permanently - calling takePersistableUriPermission() AGAIN on an individual
     * child document URI (as an earlier version of this function did) actually throws a
     * SecurityException, since that API only accepts a URI a system picker handed back directly,
     * never one an app constructs itself via DocumentsContract.buildDocumentUriUsingTree(). That
     * silently-caught exception was returning false for every single file, which was exactly why
     * every import fell through to [importToPrivateStorage]'s copy regardless.
     */
    fun isReadable(file: LocalAudioFile): Boolean = runCatching {
        context.contentResolver.openInputStream(file.uri)?.use { true } ?: false
    }.getOrDefault(false)

    /** Fallback for when [isReadable] can't confirm read access - copies [file]'s
     * bytes into the app's own private storage instead, so it still plays back and is managed
     * exactly like any other song's localFilePath. Returns the copy's absolute path, or null if
     * the copy failed too (source unreadable, disk full, etc). */
    fun importToPrivateStorage(file: LocalAudioFile, songId: Long): String? {
        val directory = File(context.filesDir, "local_imports").apply { mkdirs() }
        val extension = file.displayName.substringAfterLast('.', "mp3")
        val destination = File(directory, "$songId.$extension")
        return try {
            val copied = context.contentResolver.openInputStream(file.uri)?.use { input ->
                FileOutputStream(destination).use { output -> input.copyTo(output) }
                true
            } ?: false
            if (copied) destination.absolutePath else null
        } catch (e: Exception) {
            destination.delete()
            null
        }
    }

    companion object {
        // .weba is this app's own extension for a YouTube-downloaded Opus/WebM track (see
        // ytdlp_bridge.py's own doc) - included so re-importing a .weba file (moved from another
        // folder, restored from backup, shared from another install) is recognized as real audio
        // instead of silently skipped by the scanner.
        private val AUDIO_EXTENSIONS = listOf(".mp3", ".m4a", ".flac", ".ogg", ".wav", ".aac", ".opus", ".wma", ".weba")
        // DSD files can't be played by the app, so they're never offered for import.
        private val UNSUPPORTED_EXTENSIONS = listOf(".dsf", ".dff")
    }
}
