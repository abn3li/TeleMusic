package com.abn3li.telemusic.playback

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.Collections
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class YouTubeAudioCacheTest {
    private val file = Random(7).nextBytes(5 * 1024 * 1024 + 12345)
    private val ranges = Collections.synchronizedList(mutableListOf<String>())
    private var failNext = false
    private lateinit var server: RangeServer
    private lateinit var cache: YouTubeAudioCache

    @Before fun setUp() {
        server = RangeServer(file, ranges) { failNext }
        cache = YouTubeAudioCache(ApplicationProvider.getApplicationContext())
    }

    @After fun tearDown() {
        cache.clear()
        server.close()
    }

    private fun streamUrl(withLength: Boolean = true): String =
        "http://127.0.0.1:${server.port}/videoplayback?itag=251" + (if (withLength) "&clen=${file.size}" else "")

    private fun readAll(source: DataSource, uri: Uri, from: Long = 0): ByteArray {
        val out = ByteArrayOutputStream()
        source.open(DataSpec.Builder().setUri(uri).setPosition(from).build())
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = source.read(buffer, 0, buffer.size)
            if (n == C.RESULT_END_OF_INPUT) break
            out.write(buffer, 0, n)
        }
        source.close()
        return out.toByteArray()
    }

    @Test fun chunkedFetchReadsTheWholeFileInRanges() {
        for (withLength in listOf(true, false)) {
            ranges.clear()
            val source = ChunkedHttpDataSource(DefaultHttpDataSource.Factory().createDataSource())
            assertArrayEquals(file, readAll(source, Uri.parse(streamUrl(withLength))))
            // 5 MB in ranges no bigger than 2 MB.
            assertEquals(3, ranges.size)
            assertEquals("bytes=0-2097151", ranges[0])
        }
    }

    @Test fun chunkedFetchFromTheMiddle() {
        val source = ChunkedHttpDataSource(DefaultHttpDataSource.Factory().createDataSource())
        val from = 3_000_000L
        assertArrayEquals(file.copyOfRange(from.toInt(), file.size), readAll(source, Uri.parse(streamUrl()), from))
    }

    @Test fun unknownLinksAreNotCached() {
        assertTrue(!cache.handles(Uri.parse(streamUrl())))
        StreamVideoIds.remember(streamUrl(), "abc123")
        assertTrue(cache.handles(Uri.parse(streamUrl())))
    }

    @Test fun aSongPlayedThroughIsKeptAndPlaysFromTheCacheAlone() {
        StreamVideoIds.remember(streamUrl(), "song1")
        assertNull(cache.fullyCachedUri("song1"))
        assertArrayEquals(file, readAll(cache.dataSourceFactory.createDataSource(), Uri.parse(streamUrl())))

        assertEquals(file.size.toLong(), cache.fullyCached()["song1"])
        val cached = cache.fullyCachedUri("song1")
        assertNotNull(cached)
        ranges.clear()
        // Read from the cache only - no request reaches the server.
        assertArrayEquals(file, readAll(cache.dataSourceFactory.createDataSource(), cached!!))
        assertEquals(0, ranges.size)
        assertEquals(file.size.toLong(), cache.entries().single().size)
    }

    @Test fun aNewLinkToTheSameFileUsesTheKeptBytes() {
        StreamVideoIds.remember(streamUrl(), "song2")
        readAll(cache.dataSourceFactory.createDataSource(), Uri.parse(streamUrl()))
        val newLink = streamUrl() + "&expire=999"
        StreamVideoIds.remember(newLink, "song2")
        ranges.clear()
        assertArrayEquals(file, readAll(cache.dataSourceFactory.createDataSource(), Uri.parse(newLink)))
        assertEquals(0, ranges.size)
    }

    @Test fun openingIsFetchedAheadButNotTheWholeSong() = runBlocking {
        StreamVideoIds.remember(streamUrl(), "song3")
        // A 200-second song: 45 seconds of it.
        cache.cacheOpening(Uri.parse(streamUrl()), durationSeconds = 200)
        val kept = cache.entries().single().size
        assertEquals(file.size.toLong() * 45 / 200, kept)
        assertNull(cache.fullyCachedUri("song3"))
        // Only part of it: not listed with the songs that play without internet.
        assertTrue("song3" !in cache.fullyCached())
        // Played after: the opening comes from the phone, the rest from the server.
        ranges.clear()
        assertArrayEquals(file, readAll(cache.dataSourceFactory.createDataSource(), Uri.parse(streamUrl())))
        assertTrue(ranges.all { it.removePrefix("bytes=").substringBefore('-').toLong() >= kept })
    }

    @Test fun aCachedLinkWithPartsMissingFailsLikeAnExpiredLink() {
        StreamVideoIds.remember(streamUrl(), "song4")
        readAll(cache.dataSourceFactory.createDataSource(), Uri.parse(streamUrl()))
        val cached = cache.fullyCachedUri("song4")!!
        cache.clear()
        try {
            readAll(cache.dataSourceFactory.createDataSource(), cached)
            fail("read from an emptied cache")
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            assertEquals(410, e.responseCode)
        }
    }

    @Test fun removingOneSongLeavesTheOthers() {
        StreamVideoIds.remember(streamUrl(), "keep")
        readAll(cache.dataSourceFactory.createDataSource(), Uri.parse(streamUrl()))
        val other = streamUrl() + "&other=1"
        StreamVideoIds.remember(other.replace("itag=251", "itag=140"), "drop")
        readAll(cache.dataSourceFactory.createDataSource(), Uri.parse(other.replace("itag=251", "itag=140")))
        assertEquals(setOf("keep", "drop"), cache.fullyCached().keys)
        assertEquals(file.size.toLong(), cache.removeVideo("drop"))
        assertEquals(setOf("keep"), cache.fullyCached().keys)
    }

    @Test fun clearEmptiesIt() {
        StreamVideoIds.remember(streamUrl(), "song5")
        readAll(cache.dataSourceFactory.createDataSource(), Uri.parse(streamUrl()))
        val (count, bytes) = cache.clear()
        assertEquals(1, count)
        assertEquals(file.size.toLong(), bytes)
        assertTrue(cache.entries().isEmpty())
        assertNull(cache.fullyCachedUri("song5"))
    }
}

/** A small HTTP server answering Range requests for [file] - enough to stand in for YouTube's. */
private class RangeServer(private val file: ByteArray, private val ranges: MutableList<String>, private val refuse: () -> Boolean) : AutoCloseable {
    private val socket = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
    val port: Int get() = socket.localPort
    private val thread = Thread {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            Thread { serve(client) }.start()
        }
    }.apply { isDaemon = true; start() }

    private fun serve(client: java.net.Socket) = client.use {
        val input = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val output = client.getOutputStream()
        while (true) {
            input.readLine() ?: return
            var range: String? = null
            while (true) {
                val line = input.readLine() ?: return
                if (line.isEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
            }
            ranges += range ?: "none"
            if (refuse()) {
                output.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n".toByteArray()); output.flush(); continue
            }
            val (start, end) = range!!.removePrefix("bytes=").split("-").let { (a, b) ->
                a.toLong() to (b.toLongOrNull() ?: (file.size - 1L)).coerceAtMost(file.size - 1L)
            }
            val length = end - start + 1
            output.write(("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $start-$end/${file.size}\r\n" +
                "Content-Length: $length\r\nContent-Type: audio/webm\r\n\r\n").toByteArray())
            output.write(file, start.toInt(), length.toInt())
            output.flush()
        }
    }

    override fun close() = socket.close()
}
