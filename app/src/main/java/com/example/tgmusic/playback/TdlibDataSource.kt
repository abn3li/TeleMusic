package com.example.tgmusic.playback

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.example.tgmusic.data.telegram.TdlibManager
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

private const val TAG = "TdlibDataSource"
private const val PREBUFFER_BYTES = 384_000L   // ~384KB default prebuffer
private const val READ_WAIT_STEP_MS = 100L
private const val READ_WAIT_MAX_MS = 20_000L   // 20s max wait for new bytes while downloading

/**
 * Plays audio files from local disk or streams Telegram files via TDLib as they download.
 */
class TdlibDataSource(private val tdlibManager: TdlibManager) : BaseDataSource(true) {

    private var fileId: Int = -1
    private var raf: RandomAccessFile? = null
    private var readPosition: Long = 0
    private var bytesRemaining: Long = 0
    private var dataSpecUri: Uri? = null
    private var opened: Boolean = false

    override fun open(dataSpec: DataSpec): Long {
        dataSpecUri = dataSpec.uri
        fileId = dataSpec.uri.lastPathSegment?.toIntOrNull() ?: -1

        // Safely close previous handle if open
        try {
            raf?.close()
        } catch (_: Exception) {
        }
        raf = null

        transferInitializing(dataSpec)

        val filePath: String = if (dataSpec.uri.scheme == "file" || fileId == -1) {
            // Local file playback path
            val path = dataSpec.uri.path
            if (path.isNullOrBlank() || !File(path).exists()) {
                throw IOException("Local file does not exist or invalid path: ${dataSpec.uri}")
            }
            path
        } else {
            // TDLib streaming download path
            val initialFile = try {
                runBlocking { tdlibManager.beginStreamingDownload(fileId) }
            } catch (e: Exception) {
                Log.e(TAG, "beginStreamingDownload failed for fileId=$fileId", e)
                throw IOException("TDLib file $fileId unavailable: ${e.message}", e)
            }

            // Poll until TDLib creates the local file on disk and downloads required bytes for dataSpec.position
            val updatedFile = waitForFileAndBytes(fileId, dataSpec.position, PREBUFFER_BYTES)
                ?: initialFile

            val path = updatedFile.local.path
            if (path.isNullOrBlank()) {
                throw IOException("TDLib file path is empty for fileId=$fileId")
            }
            path
        }

        val fileOnDisk = File(filePath)
        if (!fileOnDisk.exists()) {
            throw IOException("Audio file does not exist on disk at $filePath (fileId=$fileId)")
        }

        Log.d(TAG, "open() fileId=$fileId path=$filePath pos=${dataSpec.position} size=${fileOnDisk.length()}")

        try {
            raf = RandomAccessFile(fileOnDisk, "r")
            readPosition = dataSpec.position
            raf?.seek(readPosition)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open RandomAccessFile for path=$filePath", e)
            throw IOException("Cannot open audio file at $filePath: ${e.message}", e)
        }

        val progress = if (fileId != -1) tdlibManager.getCachedFileProgress(fileId) else null
        val completed = progress?.local?.isDownloadingCompleted ?: false

        val totalSize = if (fileId != -1) {
            val sizeFromTdlib = progress?.expectedSize?.takeIf { it > 0 }?.toLong()
                ?: progress?.size?.takeIf { it > 0 }?.toLong()
                ?: try {
                    runBlocking { tdlibManager.getFreshFileSize(fileId) }
                } catch (_: Exception) { null }

            if (sizeFromTdlib != null && sizeFromTdlib > 0) {
                sizeFromTdlib
            } else if (completed) {
                fileOnDisk.length().takeIf { it > 0 } ?: C.LENGTH_UNSET.toLong()
            } else {
                C.LENGTH_UNSET.toLong() // NEVER cap at partial disk length while downloading!
            }
        } else {
            fileOnDisk.length().takeIf { it > 0 } ?: C.LENGTH_UNSET.toLong()
        }

        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            totalSize != C.LENGTH_UNSET.toLong() -> totalSize - readPosition
            else -> C.LENGTH_UNSET.toLong()
        }

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    /**
     * Polls until TDLib assigns a non-empty local path and downloads required prebuffer bytes relative to startPosition.
     * Calculates the exact required prebuffer dynamically based on container structure & metadata headers.
     */
    private fun waitForFileAndBytes(fileId: Int, startPosition: Long, defaultTargetBytes: Long): TdApi.File? {
        var waited = 0L
        val maxWaitMs = 15_000L
        var progress: TdApi.File? = null

        while (waited < maxWaitMs) {
            progress = tdlibManager.getCachedFileProgress(fileId)
            val path = progress?.local?.path
            val downloaded = progress?.local?.downloadedSize ?: 0L
            val completed = progress?.local?.isDownloadingCompleted ?: false
            val totalSize = progress?.size?.takeIf { it > 0 }?.toLong()
                ?: progress?.expectedSize?.takeIf { it > 0 }?.toLong()
                ?: (startPosition + defaultTargetBytes)

            val fileOnDisk = path?.let { File(it) }
            val requiredBytes = if (fileOnDisk != null) {
                getRequiredPrebufferBytes(fileOnDisk, defaultTargetBytes, totalSize)
            } else {
                minOf(defaultTargetBytes, totalSize)
            }

            val requiredDownloaded = startPosition + requiredBytes

            if (!path.isNullOrBlank() && (downloaded >= requiredDownloaded || completed)) {
                return progress
            }
            try {
                Thread.sleep(READ_WAIT_STEP_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            waited += READ_WAIT_STEP_MS
        }
        return progress ?: tdlibManager.getCachedFileProgress(fileId)
    }

    /**
     * Inspects the file header to dynamically calculate the required prebuffer bytes.
     * Parses MP4 box layouts (including 64-bit extended box sizes), ID3v2 tags, FLAC/OGG,
     * and WebM/MKV header blocks so ExoPlayer extractors can finish track preparation cleanly.
     */
    private fun getRequiredPrebufferBytes(file: File, defaultTargetBytes: Long, totalSize: Long): Long {
        if (!file.exists() || file.length() < 12) return minOf(defaultTargetBytes, totalSize)

        try {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(32)
                raf.readFully(header)

                // 1. Check for MP3 with ID3v2 Tag ("ID3" at byte 0..2)
                if (header[0] == 0x49.toByte() && header[1] == 0x44.toByte() && header[2] == 0x33.toByte()) {
                    val id3Size = ((header[6].toInt() and 0x7F) shl 21) or
                                  ((header[7].toInt() and 0x7F) shl 14) or
                                  ((header[8].toInt() and 0x7F) shl 7) or
                                  (header[9].toInt() and 0x7F)
                    val hasFooter = (header[5].toInt() and 0x10) != 0
                    val id3TotalHeaderSize = 10L + id3Size + (if (hasFooter) 10L else 0L)
                    val needed = id3TotalHeaderSize + 128_000L
                    return minOf(maxOf(defaultTargetBytes, needed), totalSize)
                }

                // 2. Check for MP4 / M4A Container ("ftyp" at byte 4..7)
                val isFtyp = header[4] == 0x66.toByte() && header[5] == 0x74.toByte() &&
                             header[6] == 0x79.toByte() && header[7] == 0x70.toByte()
                if (isFtyp) {
                    var pos = 0L
                    val fileLen = file.length()

                    while (pos < fileLen && pos < 2_000_000L) { // Scan first 2MB for box headers
                        raf.seek(pos)
                        val boxHeader = ByteArray(16)
                        val read = raf.read(boxHeader)
                        if (read < 8) break

                        var boxSize = ((boxHeader[0].toLong() and 0xFF) shl 24) or
                                      ((boxHeader[1].toLong() and 0xFF) shl 16) or
                                      ((boxHeader[2].toLong() and 0xFF) shl 8) or
                                      (boxHeader[3].toLong() and 0xFF)

                        val boxType = String(boxHeader, 4, 4, Charsets.US_ASCII)

                        if (boxSize == 1L && read >= 16) {
                            // 64-bit box size in bytes 8..15
                            boxSize = ((boxHeader[8].toLong() and 0xFF) shl 56) or
                                      ((boxHeader[9].toLong() and 0xFF) shl 48) or
                                      ((boxHeader[10].toLong() and 0xFF) shl 40) or
                                      ((boxHeader[11].toLong() and 0xFF) shl 32) or
                                      ((boxHeader[12].toLong() and 0xFF) shl 24) or
                                      ((boxHeader[13].toLong() and 0xFF) shl 16) or
                                      ((boxHeader[14].toLong() and 0xFF) shl 8) or
                                      (boxHeader[15].toLong() and 0xFF)
                        }

                        if (boxType == "moov") {
                            val needed = pos + boxSize + 128_000L
                            return minOf(maxOf(defaultTargetBytes, needed), totalSize)
                        }

                        if (boxType == "mdat") {
                            // mdat comes BEFORE moov -> moov is at the end of the file!
                            return totalSize // Must download complete file to read moov at end
                        }

                        if (boxSize <= 0) break
                        pos += boxSize
                    }
                }

                // 3. Check for FLAC Container ("fLaC" at byte 0..3)
                if (header[0] == 0x66.toByte() && header[1] == 0x4C.toByte() && header[2] == 0x61.toByte() && header[3] == 0x43.toByte()) {
                    return minOf(maxOf(defaultTargetBytes, 384_000L), totalSize)
                }

                // 4. Check for OGG / Opus / Vorbis Container ("OggS" at byte 0..3)
                if (header[0] == 0x4F.toByte() && header[1] == 0x67.toByte() && header[2] == 0x67.toByte() && header[3] == 0x53.toByte()) {
                    return minOf(maxOf(defaultTargetBytes, 384_000L), totalSize)
                }

                // 5. Check for WebM / Matroska Container (0x1A, 0x45, 0xDF, 0xA3 at byte 0..3)
                if (header[0] == 0x1A.toByte() && header[1] == 0x45.toByte() && header[2] == 0xDF.toByte() && header[3] == 0xA3.toByte()) {
                    return minOf(maxOf(defaultTargetBytes, 512_000L), totalSize)
                }
            }
        } catch (_: Exception) {
        }

        return minOf(defaultTargetBytes, totalSize)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(length.toLong(), bytesRemaining).toInt()
        }

        var waited = 0L
        while (waited < READ_WAIT_MAX_MS) {
            val progress = if (fileId != -1) tdlibManager.getCachedFileProgress(fileId) else null
            val completed = progress?.local?.isDownloadingCompleted ?: true
            val currentDiskLength = raf?.length() ?: 0L
            val availableOnDisk = maxOf(0L, currentDiskLength - readPosition)

            if (availableOnDisk > 0) {
                val safeToRead = minOf(toRead.toLong(), availableOnDisk).toInt()
                val bytesRead = raf?.read(buffer, offset, safeToRead) ?: -1
                if (bytesRead > 0) {
                    readPosition += bytesRead
                    if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                        bytesRemaining -= bytesRead
                    }
                    bytesTransferred(bytesRead)
                    return bytesRead
                }
            }

            if (completed) {
                // File download is 100% completed on disk and no more bytes to read -> EOF
                return C.RESULT_END_OF_INPUT
            }

            try {
                Thread.sleep(READ_WAIT_STEP_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return C.RESULT_END_OF_INPUT
            }
            waited += READ_WAIT_STEP_MS
        }

        return C.RESULT_END_OF_INPUT
    }

    override fun getUri(): Uri? = dataSpecUri

    override fun close() {
        try {
            raf?.close()
        } catch (_: Exception) {
        }
        raf = null
        fileId = -1
        readPosition = 0
        bytesRemaining = 0
        dataSpecUri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    class Factory(private val tdlibManager: TdlibManager) : DataSource.Factory {
        override fun createDataSource(): DataSource = TdlibDataSource(tdlibManager)
    }

    companion object {
        fun uriFor(fileId: Int): Uri = Uri.parse("tdlib://file/$fileId")
    }
}