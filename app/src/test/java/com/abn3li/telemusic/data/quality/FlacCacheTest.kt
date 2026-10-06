package com.abn3li.telemusic.data.quality

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

class FlacCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val target = FlacTarget("Thunder", "Imagine Dragons", "Evolve", 200000)
    private val sourcePath = "Imagine Dragons/Evolve/Thunder.flac"

    private fun source(title: String = "Thunder"): FlacStreamBuffer {
        val info = ByteArray(34)
        ByteBuffer.wrap(info, 10, 8).putLong((44100L shl 44) or (1L shl 41) or
            (15L shl 36) or (44100L * 200))
        val comments = WireWriter().string("cache fixture").int(3).string("TITLE=$title")
            .string("ARTIST=Imagine Dragons").string("ALBUM=Evolve").bytes()
        val bytes = ByteArrayOutputStream().apply {
            write("fLaC".toByteArray())
            write(byteArrayOf(0, 0, 0, 34)); write(info)
            write(byteArrayOf(0x84.toByte(), 0, (comments.size ushr 8).toByte(), comments.size.toByte()))
            write(comments)
            write(ByteArray(512) { it.toByte() })
        }.toByteArray()
        val file = temporary.newFile().apply { writeBytes(bytes) }
        return FlacStreamBuffer.completed(file, bytes.size.toLong()) { file.delete() }
    }

    @Test fun completedVerifiedUpgradeSurvivesRestartAndCanSeekWithoutProgressiveScan() = runBlocking {
        val directory = temporary.newFolder()
        val source = source()
        val cache = FlacCache(directory)
        assertTrue(cache.store("-17", target, sourcePath, source, cache.token()))
        source.deleteWhenUnused()
        assertFalse(source.file.exists())
        val restarted = FlacCache(directory)
        val hit = restarted.acquire("-17", target)!!
        assertTrue(hit.buffer.complete)
        assertEquals(44100, hit.header.sampleRate)
        assertEquals(16, hit.header.bitDepth)
        assertFalse(hit.buffer.seekIndex.isReady)
        hit.buffer.awaitComplete()
        val expected = hit.buffer.file.readBytes().takeLast(30).toByteArray()
        val bytes = ByteArray(30)
        RandomAccessFile(hit.buffer.file, "r").use {
            assertEquals(30, hit.buffer.read(it, hit.buffer.totalSize - 30, bytes, 0, 30))
        }
        assertArrayEquals(expected, bytes)
        hit.buffer.deleteWhenUnused()
        assertTrue(hit.buffer.file.exists())
        assertNotNull(restarted.acquire("-17", target)?.also { it.buffer.deleteWhenUnused() })
    }

    @Test fun partialWrongRecordingAndChangedMetadataNeverBecomeReplayHits() {
        val cache = FlacCache(temporary.newFolder())
        val complete = source()
        val partial = FlacStreamBuffer(complete.file, complete.totalSize)
        assertFalse(cache.store("17", target, sourcePath, partial, cache.token()))
        assertFalse(cache.store("17", target, sourcePath, source("Believer"), cache.token()))
        assertTrue(cache.snapshot().isEmpty())
        assertTrue(cache.store("17", target, sourcePath, complete, cache.token()))
        assertNull(cache.acquire("18", target))
        assertNull(cache.acquire("17", target.copy(title = "Thunder Live")))
        assertNull(cache.acquire("17", target.copy(durationMs = 220000)))
    }

    @Test fun clearDefersDeletionUntilPlaybackReaderAndExplicitDownloadRelease() {
        val cache = FlacCache(temporary.newFolder())
        val source = source()
        assertTrue(cache.store("17", target, sourcePath, source, cache.token()))
        val hit = cache.acquire("17", target)!!
        val reader = hit.buffer.retainFile(forDownload = false)
        val download = hit.buffer.retainFile()
        assertEquals(0L, cache.evict(hit.key))
        assertEquals(0 to 0L, cache.clear())
        assertNull(cache.acquire("17", target))
        hit.buffer.deleteWhenUnused()
        reader.close()
        assertTrue(hit.buffer.file.exists())
        download.close()
        download.close()
        assertFalse(hit.buffer.file.exists())
        assertTrue(cache.snapshot().isEmpty())
    }

    @Test fun clearingCacheInvalidatesInFlightAdmissionsAndSurvivesProcessDeath() {
        val directory = temporary.newFolder()
        val cache = FlacCache(directory)
        val token = cache.token()
        cache.clear()
        assertFalse(cache.store("17", target, sourcePath, source(), token))
        assertTrue(cache.store("17", target, sourcePath, source(), cache.token()))
        val hit = cache.acquire("17", target)!!
        cache.clear()
        // Simulate a stopped process; a persisted tombstone must not resurrect playback.
        val restarted = FlacCache(directory)
        assertNull(restarted.acquire("17", target))
        assertTrue(restarted.snapshot().isEmpty())
        hit.buffer.deleteWhenUnused()
    }

    @Test fun restartRemovesTruncatedCorruptAndUncommittedFilesWithoutTouchingDownloads() {
        val directory = temporary.newFolder()
        val source = source()
        val cache = FlacCache(directory)
        assertTrue(cache.store("17", target, sourcePath, source, cache.token()))
        val key = cache.snapshot().single().key
        File(directory, "$key.flac").writeBytes(byteArrayOf(1))
        val partial = File(directory, "copy.part").apply { writeText("unfinished") }
        val orphan = File(directory, "orphan.flac").apply { writeText("no metadata") }
        val explicit = temporary.newFolder("flac_downloads").resolve("saved.flac").apply { writeText("saved") }
        assertTrue(FlacCache(directory).snapshot().isEmpty())
        assertFalse(partial.exists())
        assertFalse(orphan.exists())
        assertTrue(explicit.exists())
        File(directory, "${"a".repeat(64)}.json").writeText("invalid json")
        assertTrue(FlacCache(directory).snapshot().isEmpty())
    }

    @Test fun playbackReadersDoNotKeepAbandonedNetworkTransfersRunning() {
        val source = source()
        val growing = FlacStreamBuffer(source.file, source.totalSize)
        val reader = growing.retainFile(forDownload = false)
        assertFalse(growing.shouldFinishDownload)
        val download = growing.retainFile()
        assertTrue(growing.shouldFinishDownload)
        growing.deleteWhenUnused()
        download.close()
        assertFalse(growing.shouldFinishDownload)
        assertTrue(source.file.exists())
        reader.close()
        assertFalse(source.file.exists())
    }

    @Test fun oneBudgetEvictsBothKindsInGlobalOrderAndCountsProtectedPlayback() = runBlocking {
        val remaining = linkedSetOf("original-old", "active-flac", "flac-old", "original-new")
        val order = mutableListOf<String>()
        fun entry(name: String, age: Long, protected: Boolean = false) = AutomaticCacheFile(100, age) {
            if (protected) 0 else { remaining.remove(name); order += name; 100L }
        }
        trimAutomaticCache(listOf(entry("original-new", 4), entry("flac-old", 3),
            entry("original-old", 1), entry("active-flac", 2, protected = true)), 200)
        assertEquals(listOf("original-old", "flac-old"), order)
        assertEquals(setOf("active-flac", "original-new"), remaining)
        trimAutomaticCache(listOf(entry("active-flac", 2, protected = true), entry("original-new", 4)), 50)
        assertEquals(setOf("active-flac"), remaining)
    }
}
