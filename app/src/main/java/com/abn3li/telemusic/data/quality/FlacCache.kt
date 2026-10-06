package com.abn3li.telemusic.data.quality

import com.google.gson.Gson
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

/** Completed automatic upgrades only. All disk work is called from Dispatchers.IO. */
internal class FlacCache(private val directory: File) {
    internal data class Entry(val key: String, val songId: String, val title: String,
        val artist: String, val album: String?, val sourcePath: String, val size: Long,
        var lastPlayed: Long, var cleared: Boolean = false)
    internal data class Hit(val key: String, val buffer: FlacStreamBuffer, val header: FlacHeader)
    private val gson = Gson()
    private val entries = mutableMapOf<String, Entry>()
    private val users = mutableMapOf<String, Int>()
    private var loaded = false
    private var generation = 0L

    private fun audio(key: String) = File(directory, "$key.flac")
    private fun metadata(key: String) = File(directory, "$key.json")
    private fun key(songId: String, target: FlacTarget): String = MessageDigest.getInstance("SHA-256")
        .digest(gson.toJson(listOf(songId, target.title, target.artist, target.album)).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun load() {
        if (loaded) return
        directory.mkdirs()
        directory.listFiles()?.filter { it.name.matches(Regex("[a-f0-9]{64}\\.json")) }?.forEach { file ->
            val entry = runCatching {
                require(file.length() <= 65536)
                gson.fromJson(file.readText(), Entry::class.java).also {
                    require(it.key == file.nameWithoutExtension && it.size > 0 &&
                        !it.cleared && !it.songId.isNullOrBlank() && !it.title.isNullOrBlank() &&
                        !it.artist.isNullOrBlank() && !it.sourcePath.isNullOrBlank() && audio(it.key).length() == it.size)
                }
            }.getOrNull()
            if (entry != null) entries[entry.key] = entry else file.delete()
        }
        // Once per process: remove interrupted copies and files without committed metadata.
        directory.listFiles()?.forEach { file ->
            if (file.name.endsWith(".part") || file.extension == "flac" && file.nameWithoutExtension !in entries) file.delete()
        }
        loaded = true
    }

    private fun write(entry: Entry) {
        val pending = File(directory, "${entry.key}.json.part")
        pending.writeText(gson.toJson(entry))
        java.nio.file.Files.move(pending.toPath(), metadata(entry.key).toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    @Synchronized fun token(): Long { load(); return generation }

    @Synchronized fun snapshot(): List<Entry> { load(); return entries.values.map { it.copy() } }

    @Synchronized fun acquire(songId: String, target: FlacTarget, onRelease: () -> Unit = {}): Hit? {
        load()
        val entry = entries[key(songId, target)]?.takeUnless { it.cleared } ?: return null
        val file = audio(entry.key)
        val header = runCatching {
            require(file.length() == entry.size)
            readHeader(file)?.also { require(verifiedRecording(it, target, entry.sourcePath)) }
        }.getOrNull()
        if (header == null) { evict(entry.key); return null }
        entry.lastPlayed = System.currentTimeMillis()
        runCatching { write(entry) }
        users[entry.key] = (users[entry.key] ?: 0) + 1
        val lease = Closeable {
            synchronized(this) {
                val remaining = (users[entry.key] ?: 1) - 1
                if (remaining == 0) {
                    users.remove(entry.key)
                    if (entry.cleared) evict(entry.key)
                } else users[entry.key] = remaining
            }
            onRelease()
        }
        return Hit(entry.key, FlacStreamBuffer.completed(file, entry.size) { lease.close() }, header)
    }

    /** Copy outside the cache lock; readers retain the original file throughout admission. */
    fun store(songId: String, target: FlacTarget, sourcePath: String, buffer: FlacStreamBuffer,
        token: Long): Boolean {
        if (!buffer.complete || buffer.available != buffer.totalSize || buffer.file.length() != buffer.totalSize) return false
        val lease = buffer.retainFile(forDownload = false)
        val pending = File(directory, "${UUID.randomUUID()}.part")
        try {
            val header = readHeader(buffer.file) ?: return false
            if (!verifiedRecording(header, target, sourcePath)) return false
            buffer.file.copyTo(pending, overwrite = false)
            synchronized(this) {
                load()
                // Clear Cache also invalidates transfers that started before the clear.
                if (generation != token || pending.length() != buffer.totalSize) return false
                val cacheKey = key(songId, target)
                if (entries[cacheKey]?.cleared == false) return true
                if ((users[cacheKey] ?: 0) > 0) return false
                check(pending.renameTo(audio(cacheKey))) { "Cannot save FLAC cache audio" }
                val entry = Entry(cacheKey, songId, target.title, target.artist, target.album,
                    sourcePath, buffer.totalSize, System.currentTimeMillis())
                try { write(entry) } catch (e: Exception) { audio(cacheKey).delete(); throw e }
                entries[cacheKey] = entry
                return true
            }
        } finally { pending.delete(); lease.close() }
    }

    @Synchronized fun evict(key: String): Long {
        load()
        val entry = entries[key] ?: return 0
        if ((users[key] ?: 0) > 0) return 0
        val file = audio(key)
        if (file.exists() && !file.delete()) return 0
        metadata(key).delete()
        entries.remove(key)
        return entry.size
    }

    @Synchronized fun clear(): Pair<Int, Long> {
        load()
        generation++
        var count = 0
        var bytes = 0L
        entries.values.toList().forEach { entry ->
            // Persist the tombstone so a process killed during playback cannot resurrect it.
            entry.cleared = true
            runCatching { write(entry) }
            val freed = evict(entry.key)
            if (freed > 0) { count++; bytes += freed }
        }
        return count to bytes
    }

    @Synchronized fun invalidate(key: String) {
        load()
        entries[key]?.let { entry -> entry.cleared = true; runCatching { write(entry) }; evict(key) }
    }

    private fun readHeader(file: File): FlacHeader? = RandomAccessFile(file, "r").use { reader ->
        // Read metadata blocks only, rather than scanning a whole song on every replay.
        val prefix = java.io.ByteArrayOutputStream()
        val marker = ByteArray(4).also { reader.readFully(it) }
        require(marker.contentEquals("fLaC".toByteArray(Charsets.US_ASCII)))
        prefix.write(marker)
        do {
            val block = ByteArray(4).also { reader.readFully(it) }
            val length = ((block[1].toInt() and 255) shl 16) or
                ((block[2].toInt() and 255) shl 8) or (block[3].toInt() and 255)
            require(prefix.size() + 4L + length <= 4L * 1024 * 1024)
            prefix.write(block)
            prefix.write(ByteArray(length).also { reader.readFully(it) })
        } while (block[0].toInt() and 128 == 0)
        readFlacHeader(prefix.toByteArray())
    }
}
