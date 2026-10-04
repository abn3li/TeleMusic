package com.abn3li.telemusic.data.quality

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.InflaterInputStream

internal const val MAX_FLAC_BYTES = 256L * 1024 * 1024

/** Bounded wire readers: search replies are untrusted and may be compressed. */
internal class WireReader(private val input: InputStream) {
    constructor(bytes: ByteArray) : this(ByteArrayInputStream(bytes))
    fun bytes(count: Int): ByteArray {
        require(count in 0..8 * 1024 * 1024)
        val bytes = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(bytes, offset, count - offset)
            if (read < 0) throw EOFException()
            offset += read
        }
        return bytes
    }
    fun byte(): Int = input.read().also { if (it < 0) throw EOFException() }
    fun int(): Int = ByteBuffer.wrap(bytes(4)).order(ByteOrder.LITTLE_ENDIAN).int
    fun long(): Long = ByteBuffer.wrap(bytes(8)).order(ByteOrder.LITTLE_ENDIAN).long
    fun string(): String = bytes(int().also { require(it in 0..262144) }).toString(Charsets.UTF_8)
    fun remaining(): ByteArray = input.readBytes()
}

internal class WireWriter {
    private val output = ByteArrayOutputStream()
    fun byte(value: Int) = apply { output.write(value) }
    fun int(value: Int) = apply { output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()) }
    fun long(value: Long) = apply { output.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()) }
    fun string(value: String) = apply { val bytes = value.toByteArray(Charsets.UTF_8); int(bytes.size); output.write(bytes) }
    fun raw(value: ByteArray) = apply { output.write(value) }
    fun bytes(): ByteArray = output.toByteArray()
}

internal fun OutputStream.sendFrame(code: Int, body: WireWriter = WireWriter(), init: Boolean = false) {
    val payload = body.bytes()
    synchronized(this) {
        write(WireWriter().int(payload.size + if (init) 1 else 4).bytes())
        write(if (init) byteArrayOf(code.toByte()) else WireWriter().int(code).bytes())
        write(payload)
        flush()
    }
}

internal fun InputStream.readFrame(init: Boolean = false): Pair<Int, WireReader> {
    val reader = WireReader(this)
    val size = reader.int()
    val codeSize = if (init) 1 else 4
    require(size in codeSize..4 * 1024 * 1024)
    val body = WireReader(reader.bytes(size))
    return (if (init) body.byte() else body.int()) to body
}

internal data class FlacTarget(val title: String, val artist: String, val album: String?, val durationMs: Long)
internal data class FlacCandidate(val user: String, val filename: String, val size: Long, val duration: Int,
    val speed: Long, val queueLength: Long = 0)

internal fun normalizedRecording(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
    .replace(Regex("\\p{M}"), "").lowercase(Locale.ROOT)
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

internal fun cleanFlacTitle(value: String): String {
    val labels = "(?:official\\s+(?:music\\s+)?(?:video|audio|visuali[sz]er)|(?:official\\s+)?lyric(?:s|\\s+video)|(?:official\\s+)?(?:hd|4k)\\s+video)"
    val cleaned = value.replace(Regex("(?i)[\\[(]\\s*$labels\\s*[\\])]"), " ")
        .replace(Regex("(?i)\\s*[-–|]\\s*$labels\\s*$"), "")
        .replace(Regex("\\s+"), " ").trim()
    return cleaned.takeIf { it.length >= 2 } ?: value.trim()
}

internal fun cleanFlacArtist(value: String): String = value
    .replace(Regex("(?i)\\s*-\\s*Topic$|VEVO$"), "").trim()

private fun titleWords(value: String) = normalizedRecording(cleanFlacTitle(value)).split(' ').filter { it.isNotBlank() }
private fun artistWords(value: String) = normalizedRecording(cleanFlacArtist(value))
    .split(' ').filter { it.isNotBlank() }.let { if (it.size > 1 && it.first() == "the") it.drop(1) else it }

private fun containsWords(value: String, words: List<String>): Boolean {
    val available = normalizedRecording(value).split(' ').toSet()
    return words.isNotEmpty() && words.all { it in available }
}

internal fun flacSearchQueries(target: FlacTarget): List<String> {
    val title = normalizedRecording(cleanFlacTitle(target.title))
    val artist = normalizedRecording(cleanFlacArtist(target.artist))
    return listOf("$artist $title".trim(), title).filter { it.length >= 2 }.distinct().map { it.take(250) }
}

internal fun recordingMatches(path: String, target: FlacTarget, requireArtist: Boolean = true): Boolean {
    if (!containsWords(path, titleWords(target.title))) return false
    if (requireArtist && !containsWords(path, artistWords(target.artist))) return false
    // A shared directory may contain several versions of a song; never guess across versions.
    val variants = listOf("live", "remix", "acoustic", "instrumental", "karaoke", "demo", "remaster", "remastered")
    val normalized = normalizedRecording(path.substringAfterLast('\\').substringAfterLast('/'))
    val original = normalizedRecording(cleanFlacTitle(target.title))
    return variants.none { (" $normalized ").contains(" $it ") != (" $original ").contains(" $it ") }
}

internal class FlacSearchStats {
    @Volatile var sawFlac = false
    @Volatile var sawMatching = false
    @Volatile var sawUnavailable = false
    val matchingReplies = AtomicInteger()
    val freeSlotReplies = AtomicInteger()
    val freeSlotWithQueueReplies = AtomicInteger()
    val noFreeSlotReplies = AtomicInteger()
}

internal fun parseSearchReply(input: InputStream, token: Int, target: FlacTarget, stats: FlacSearchStats? = null,
    activeTokens: Set<Int>? = null): List<FlacCandidate> {
    val expanded = ByteArrayOutputStream()
    InflaterInputStream(input).use { compressed ->
        val chunk = ByteArray(8192)
        while (true) {
            val size = compressed.read(chunk)
            if (size < 0) break
            require(expanded.size() + size <= 8 * 1024 * 1024)
            expanded.write(chunk, 0, size)
        }
    }
    val reader = WireReader(expanded.toByteArray())
    val user = reader.string()
    val responseToken = reader.int()
    if (!(activeTokens?.contains(responseToken) ?: (responseToken == token))) return emptyList()
    val public = ArrayList<FlacCandidate>()
    repeat(reader.int().also { require(it in 0..10000) }) {
        reader.byte()
        val filename = reader.string()
        val size = reader.long()
        reader.string()
        var duration = 0
        repeat(reader.int().also { require(it in 0..32) }) {
            val attribute = reader.int()
            val value = reader.int()
            if (attribute == 1) duration = value
        }
        val flac = filename.endsWith(".flac", ignoreCase = true) && size in 42..MAX_FLAC_BYTES
        if (flac) stats?.sawFlac = true
        if (flac && (duration == 0 || kotlin.math.abs(duration * 1000L - target.durationMs) <= 10000) &&
            recordingMatches(filename, target, requireArtist = false)) {
            stats?.sawMatching = true
            if (public.size < 50) public.add(FlacCandidate(user, filename, size, duration, 0))
        }
    }
    val free = reader.byte() != 0
    val speed = reader.int().toLong() and 0xffffffffL
    val queue = reader.int().toLong() and 0xffffffffL
    if (public.isNotEmpty()) {
        stats?.matchingReplies?.incrementAndGet()
        if (free) {
            stats?.freeSlotReplies?.incrementAndGet()
            if (queue > 0) stats?.freeSlotWithQueueReplies?.incrementAndGet()
        } else {
            stats?.sawUnavailable = true
            stats?.noFreeSlotReplies?.incrementAndGet()
        }
    }
    // Queue length is separate from the advertised free slot. Let the actual
    // upload response decide, instead of discarding an available public file.
    // Private results follow the public list. Deliberately never inspect or use them.
    return if (free) public.map { it.copy(speed = speed, queueLength = queue) } else emptyList()
}

internal data class FlacHeader(val durationMs: Long, val pcmBytesPerSecond: Long, val seekTable: Boolean,
    val tags: Map<String, String>, val sampleRate: Int, val bitDepth: Int, val channels: Int)

/** Only a complete metadata prefix can identify the file and make an early seek safe. */
internal fun readFlacHeader(prefix: ByteArray): FlacHeader? {
    if (prefix.size < 42) return null
    require(prefix.copyOfRange(0, 4).contentEquals("fLaC".toByteArray())) { "Not a FLAC file" }
    var offset = 4
    var duration = 0L
    var pcmRate = 0L
    var sampleRate = 0
    var bitDepth = 0
    var channelCount = 0
    var seekTable = false
    val tags = mutableMapOf<String, String>()
    while (offset + 4 <= prefix.size) {
        val type = prefix[offset].toInt() and 0x7f
        val last = prefix[offset].toInt() and 0x80 != 0
        val length = ((prefix[offset + 1].toInt() and 255) shl 16) or
            ((prefix[offset + 2].toInt() and 255) shl 8) or (prefix[offset + 3].toInt() and 255)
        offset += 4
        require(offset + length <= 4 * 1024 * 1024) { "FLAC metadata too large" }
        if (offset + length > prefix.size) return null
        when (type) {
            0 -> {
                require(length == 34 && offset == 8)
                val packed = ByteBuffer.wrap(prefix, offset + 10, 8).long
                val rate = packed ushr 44
                val channels = ((packed ushr 41) and 7) + 1
                val bits = ((packed ushr 36) and 31) + 1
                val samples = packed and 0xfffffffffL
                require(rate in 8000..192000 && channels in 1..2 && bits in 16..24 && samples > 0)
                duration = samples * 1000 / rate
                pcmRate = rate * channels * ((bits + 7) / 8)
                sampleRate = rate.toInt()
                bitDepth = bits.toInt()
                channelCount = channels.toInt()
            }
            3 -> {
                require(length % 18 == 0)
                seekTable = (0 until length step 18).any {
                    ByteBuffer.wrap(prefix, offset + it, 8).long >= 0
                }
            }
            4 -> {
                val reader = WireReader(prefix.copyOfRange(offset, offset + length))
                reader.string() // Encoder vendor.
                repeat(reader.int().also { require(it in 0..10000) }) {
                    val entry = reader.string()
                    val key = entry.substringBefore('=').uppercase(Locale.ROOT)
                    if (key in listOf("TITLE", "ARTIST", "ALBUM")) tags[key] = entry.substringAfter('=', "")
                }
            }
        }
        offset += length
        if (last) return FlacHeader(duration, pcmRate, seekTable, tags, sampleRate, bitDepth, channelCount).also { require(pcmRate > 0) }
    }
    return null
}

internal fun verifiedRecording(header: FlacHeader, target: FlacTarget, path: String? = null): Boolean {
    // Broader search tolerates video padding; automatic mid-song switching needs close timing.
    if (kotlin.math.abs(header.durationMs - target.durationMs) > 2500) return false
    val title = header.tags["TITLE"].orEmpty()
    val artist = header.tags["ARTIST"].orEmpty()
    val titleMatches = if (title.isBlank()) path != null && recordingMatches(path, target)
        else containsWords(title, titleWords(target.title)) && containsWords(cleanFlacTitle(target.title), titleWords(title)) &&
            recordingMatches(title, target, requireArtist = false)
    val artistMatches = if (artist.isBlank()) path != null && containsWords(path, artistWords(target.artist))
        else containsWords(artist, artistWords(target.artist))
    return titleMatches && artistMatches
}
