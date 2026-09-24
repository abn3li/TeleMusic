package com.abn3li.telemusic.ui.nowplaying

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import java.io.File
import java.util.Locale

/**
 * The real format and quality of a song's file, read once when Song Info opens (callers run this
 * off the main thread). Uses the platform's own media readers for codec / sample rate / bitrate,
 * plus the file header for what they don't report on every Android version (FLAC/WAV bit
 * depth). Nothing is guessed: a value that can't be read is left out, and a song with no file at
 * all (streaming) says so instead of inventing a bitrate.
 *
 * Returns (format, quality), e.g. ("FLAC · Hi-Res Lossless", "24-bit / 96 kHz · 2,851 kbps") or
 * ("MP3", "320 kbps · 44.1 kHz").
 */
internal fun detectAudioFormat(context: Context, filePath: String?, durationSec: Int): Pair<String, String> {
    if (filePath == null) return "Streaming" to ""
    val uri = if (filePath.startsWith("content://")) Uri.parse(filePath) else Uri.fromFile(File(filePath))
    val header = readHeader(context, filePath)

    var mime: String? = null
    var sampleRate = 0
    var channels = 0
    var bitrate = 0L
    runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val trackMime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!trackMime.startsWith("audio/") || trackMime == "audio/unknown") continue
                mime = trackMime
                sampleRate = format.intOrZero(MediaFormat.KEY_SAMPLE_RATE)
                channels = format.intOrZero(MediaFormat.KEY_CHANNEL_COUNT)
                bitrate = format.intOrZero(MediaFormat.KEY_BIT_RATE).toLong()
                break
            }
        } finally {
            extractor.release()
        }
    }
    var bitsPerSample = 0
    runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
                ?.takeIf { it > 0 }?.let { bitrate = it }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                bitsPerSample = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull() ?: 0
                if (sampleRate == 0) sampleRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() ?: 0
            }
        } finally {
            retriever.release()
        }
    }

    // Bit depth / sample rate straight from the header where it's defined.
    if (header.startsWith("fLaC") && header.size >= 22) {
        val b = { i: Int -> header[i].toInt() and 0xFF }
        val rate = (b(18) shl 12) or (b(19) shl 4) or (b(20) ushr 4)
        val bps = (((b(20) and 0x01) shl 4) or (b(21) ushr 4)) + 1
        if (rate > 0) sampleRate = rate
        if (bps in 4..32) bitsPerSample = bps
        if (mime == null) mime = MediaFormat.MIMETYPE_AUDIO_FLAC
    } else if (header.startsWith("RIFF") && header.size >= 36) {
        header.intLE(24).takeIf { it > 0 }?.let { sampleRate = it }
        (header.shortLE(34)).takeIf { it in 8..32 }?.let { bitsPerSample = it }
        if (mime == null) mime = MediaFormat.MIMETYPE_AUDIO_RAW
    }

    // ALAC in an .m4a: many devices' platform readers report it as "audio/unknown", so read its
    // settings from the file's own ALAC config box instead.
    if (mime == null && header.size >= 8 && header.copyOfRange(4, 8).contentEquals("ftyp".toByteArray())) {
        readAlacConfig(context, filePath)?.let { alac ->
            mime = "audio/alac"
            if (alac.bitDepth > 0) bitsPerSample = alac.bitDepth
            if (alac.sampleRate > 0) sampleRate = alac.sampleRate
            if (alac.channels > 0) channels = alac.channels
            if (bitrate <= 0 && alac.avgBitRate > 0) bitrate = alac.avgBitRate
        }
    }

    val codec = codecName(mime, header, filePath)
    val lossless = codec in setOf("FLAC", "ALAC", "WAV")
    val hiRes = lossless && (bitsPerSample > 16 || sampleRate > 48_000)

    // Last resort for bitrate only: size over duration (accurate for lossless and CBR audio).
    if (bitrate <= 0 && durationSec > 0) {
        val bytes = localFileSizeBytes(context, filePath)
        if (bytes > 0) bitrate = bytes * 8 / durationSec
    }

    val format = when {
        hiRes -> "$codec · Hi-Res Lossless"
        lossless -> "$codec · Lossless"
        else -> codec
    }
    // Lossless leads with bit depth / sample rate; lossy leads with bitrate, what people compare.
    val kbps = if (bitrate > 0) String.format(Locale.US, "%,d kbps", bitrate / 1000) else null
    val rate = if (sampleRate > 0) formatKHz(sampleRate) else null
    val parts = buildList {
        if (lossless) {
            when {
                bitsPerSample > 0 && rate != null -> add("$bitsPerSample-bit / $rate")
                bitsPerSample > 0 -> add("$bitsPerSample-bit")
                rate != null -> add(rate)
            }
            kbps?.let(::add)
        } else {
            kbps?.let(::add)
            rate?.let(::add)
        }
        if (channels == 1) add("Mono")
    }
    return format to parts.joinToString(" · ")
}

private fun codecName(mime: String?, header: ByteArray, path: String): String = when (mime) {
    MediaFormat.MIMETYPE_AUDIO_MPEG -> "MP3"
    MediaFormat.MIMETYPE_AUDIO_AAC -> "AAC"
    MediaFormat.MIMETYPE_AUDIO_FLAC -> "FLAC"
    "audio/alac" -> "ALAC"
    MediaFormat.MIMETYPE_AUDIO_OPUS -> "Opus"
    MediaFormat.MIMETYPE_AUDIO_VORBIS -> "Vorbis"
    MediaFormat.MIMETYPE_AUDIO_RAW -> "WAV"
    MediaFormat.MIMETYPE_AUDIO_AC3 -> "AC-3"
    MediaFormat.MIMETYPE_AUDIO_EAC3 -> "E-AC-3"
    null -> when {
        header.startsWith("fLaC") -> "FLAC"
        header.startsWith("OggS") -> "OGG"
        header.startsWith("RIFF") -> "WAV"
        header.startsWith("ID3") -> "MP3"
        else -> path.substringAfterLast('.', "").uppercase(Locale.US).ifBlank { "Unknown" }
    }
    else -> mime.substringAfter('/').uppercase(Locale.US)
}

private class AlacConfig(val bitDepth: Int, val channels: Int, val sampleRate: Int, val avgBitRate: Long)

/**
 * Finds an MP4 file's ALACSpecificConfig ("alac" box nested inside the "alac" sample entry) and
 * reads bit depth, channels, average bitrate and sample rate from it. The metadata ("moov") sits
 * at the start or the end of the file, so only those two 512 KB windows are read - never the
 * whole file.
 */
private fun readAlacConfig(context: Context, path: String): AlacConfig? = runCatching {
    val window = 512 * 1024
    val size = localFileSizeBytes(context, path)
    fun read(from: Long, length: Int): ByteArray? {
        val stream = if (path.startsWith("content://")) context.contentResolver.openInputStream(Uri.parse(path)) else File(path).inputStream()
        return stream?.use { input ->
            var skipped = 0L
            while (skipped < from) {
                val n = input.skip(from - skipped)
                if (n <= 0) break
                skipped += n
            }
            val buffer = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(buffer, read, length - read)
                if (n <= 0) break
                read += n
            }
            buffer.copyOf(read)
        }
    }
    val chunks = buildList {
        read(0, window)?.let(::add)
        if (size > window) read((size - window).coerceAtLeast(0), window)?.let(::add)
    }
    val tag = "alac".toByteArray()
    for (bytes in chunks) {
        val entry = bytes.indexOf(tag, 0)
        if (entry < 0) continue
        val box = bytes.indexOf(tag, entry + 4)
        if (box < 0) continue
        val c = box + 8 // skip "alac" + version/flags
        if (c + 24 > bytes.size) continue
        val u8 = { i: Int -> bytes[c + i].toInt() and 0xFF }
        val u32 = { i: Int -> ((u8(i).toLong() shl 24) or (u8(i + 1).toLong() shl 16) or (u8(i + 2).toLong() shl 8) or u8(i + 3).toLong()) }
        return@runCatching AlacConfig(bitDepth = u8(5), channels = u8(9), sampleRate = u32(20).toInt(), avgBitRate = u32(16))
    }
    null
}.getOrNull()

private fun ByteArray.indexOf(pattern: ByteArray, from: Int): Int {
    outer@ for (i in from..size - pattern.size) {
        for (j in pattern.indices) if (this[i + j] != pattern[j]) continue@outer
        return i
    }
    return -1
}

/** First 64 bytes of the file - enough for the FLAC STREAMINFO and WAV fmt fields. */
private fun readHeader(context: Context, path: String): ByteArray = runCatching {
    val stream = if (path.startsWith("content://")) context.contentResolver.openInputStream(Uri.parse(path)) else File(path).inputStream()
    stream?.use { input ->
        val buffer = ByteArray(64)
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n <= 0) break
            read += n
        }
        buffer.copyOf(read)
    } ?: ByteArray(0)
}.getOrDefault(ByteArray(0))

private fun ByteArray.startsWith(magic: String): Boolean =
    size >= magic.length && magic.indices.all { this[it] == magic[it].code.toByte() }

private fun ByteArray.intLE(offset: Int): Int =
    if (size < offset + 4) 0
    else (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or ((this[offset + 3].toInt() and 0xFF) shl 24)

private fun ByteArray.shortLE(offset: Int): Int =
    if (size < offset + 2) 0 else (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

private fun MediaFormat.intOrZero(key: String): Int = if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(0) else 0

private fun formatKHz(hz: Int): String {
    val khz = hz / 1000.0
    return if (khz % 1.0 == 0.0) "${khz.toInt()} kHz" else String.format(Locale.US, "%.1f kHz", khz)
}

