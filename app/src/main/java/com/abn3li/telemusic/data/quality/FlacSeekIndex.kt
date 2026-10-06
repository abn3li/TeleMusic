package com.abn3li.telemusic.data.quality

import androidx.annotation.OptIn
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.FlacFrameReader
import androidx.media3.extractor.FlacStreamMetadata
import androidx.media3.extractor.SeekPoint
import java.io.File
import java.io.RandomAccessFile

/** Builds sparse seek points only as new bytes arrive, without decoding or rereading audio. */
@OptIn(UnstableApi::class)
internal class FlacSeekIndex {
    private var metadata: FlacStreamMetadata? = null
    private var scanPosition = 0L
    private var frameMarker = 0
    private var pendingOffset = -1L
    private var pendingSample = 0L
    private var pendingSamples = 0
    private var disabled = false
    private val points = ArrayList<SeekPoint>()
    private val bytes = ByteArray(64 * 1024 + 16)
    private val data = ParsableByteArray(bytes)
    private val sample = FlacFrameReader.SampleNumberHolder()
    @Volatile var bufferedUntilMs = 0L; private set
    @Volatile var durationUs = 0L; private set
    @Volatile var isReady = false; private set

    @Synchronized
    fun update(file: File, available: Long, complete: Boolean) {
        if (disabled) return
        try {
            RandomAccessFile(file, "r").use { reader ->
                if (metadata == null && !readMetadata(reader, available)) return
                val info = metadata!!
                if (frameMarker == 0) {
                    if (available - scanPosition < 2) return
                    reader.seek(scanPosition)
                    frameMarker = reader.readUnsignedShort()
                    if ((frameMarker and 0xfffe) != 0xfff8) {
                        disabled = true
                        return
                    }
                }
                // Retain a header-sized overlap so a split header is checked on the next write.
                val scanEnd = available - if (complete) 5 else 15
                while (scanPosition < scanEnd) {
                    val count = minOf(bytes.size.toLong(), available - scanPosition).toInt()
                    reader.seek(scanPosition)
                    reader.readFully(bytes, 0, count)
                    data.reset(bytes, count)
                    val candidates = minOf(64 * 1024L, scanEnd - scanPosition).toInt()
                    for (offset in 0 until candidates) {
                        if ((bytes[offset].toInt() and 0xff) != 0xff ||
                            (bytes[offset + 1].toInt() and 0xff) != (frameMarker and 0xff)) continue
                        data.position = offset
                        val valid = try {
                            FlacFrameReader.checkAndReadFrameHeader(data, info, frameMarker, sample)
                        } catch (_: RuntimeException) { false }
                        if (!valid) continue
                        val firstSample = sample.sampleNumber
                        if (firstSample < 0 || firstSample >= info.totalSamples ||
                            firstSample != (if (pendingOffset < 0) 0L else pendingSample + pendingSamples)) continue
                        data.position = offset + 4
                        val blockSamples = try {
                            data.readUtf8EncodedLong()
                            FlacFrameReader.readFrameBlockSizeSamplesFromKey(data,
                                (bytes[offset + 2].toInt() and 0xff) ushr 4)
                        } catch (_: RuntimeException) { -1 }
                        if (blockSamples <= 0 || blockSamples > info.maxBlockSizeSamples ||
                            firstSample + blockSamples > info.totalSamples) continue
                        if (pendingOffset >= 0) {
                            addPending(info)
                            bufferedUntilMs = firstSample * 1000 / info.sampleRate
                        }
                        pendingOffset = scanPosition + offset
                        pendingSample = firstSample
                        pendingSamples = blockSamples
                    }
                    scanPosition += candidates
                }
                if (complete && pendingOffset >= 0 && pendingSample + pendingSamples == info.totalSamples) {
                    addPending(info)
                    bufferedUntilMs = durationUs / 1000
                }
            }
        } catch (_: java.io.IOException) {
            // An unusable index keeps the existing complete-file playback path available.
            disabled = true
        } catch (_: RuntimeException) {
            disabled = true
        }
    }

    private fun readMetadata(reader: RandomAccessFile, available: Long): Boolean {
        if (available < 42) return false
        reader.seek(0)
        reader.readFully(bytes, 0, 42)
        if (bytes[0] != 'f'.code.toByte() || bytes[1] != 'L'.code.toByte() ||
            bytes[2] != 'a'.code.toByte() || bytes[3] != 'C'.code.toByte() ||
            (bytes[4].toInt() and 0x7f) != 0 || bytes[5].toInt() != 0 ||
            bytes[6].toInt() != 0 || bytes[7].toInt() != 34) {
            disabled = true
            return false
        }
        val info = FlacStreamMetadata(bytes, 8)
        if (info.sampleRate !in 8000..192000 || info.channels !in 1..2 ||
            info.bitsPerSample !in 16..24 || info.totalSamples <= 0) {
            disabled = true
            return false
        }
        var position = 4L
        while (position <= 4L * 1024 * 1024) {
            if (available - position < 4) return false
            reader.seek(position)
            val flags = reader.readUnsignedByte()
            val length = (reader.readUnsignedByte() shl 16) or
                (reader.readUnsignedByte() shl 8) or reader.readUnsignedByte()
            val next = position + 4 + length
            if (next > 4L * 1024 * 1024) break
            if (next > available) return false
            if ((flags and 0x80) != 0) {
                metadata = info
                durationUs = info.durationUs
                scanPosition = next
                return true
            }
            position = next
        }
        disabled = true
        return false
    }

    private fun addPending(info: FlacStreamMetadata) {
        val timeUs = pendingSample * 1_000_000 / info.sampleRate
        if (points.isEmpty() || timeUs - points.last().timeUs >= 1_000_000) {
            points.add(SeekPoint(timeUs, pendingOffset))
            isReady = true
        }
    }

    @Synchronized
    fun seekPoint(timeUs: Long): SeekPoint {
        if (points.isEmpty()) return SeekPoint.START
        var low = 0
        var high = points.lastIndex
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (points[middle].timeUs <= timeUs) low = middle else high = middle - 1
        }
        return points[low]
    }
}
