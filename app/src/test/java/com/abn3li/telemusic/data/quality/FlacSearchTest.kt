package com.abn3li.telemusic.data.quality

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.zip.DeflaterOutputStream

class FlacSearchTest {
    private val target = FlacTarget("Thunder", "Imagine Dragons", null, 200000)

    private fun header(title: String, artist: String, durationMs: Long = 200000) =
        FlacHeader(durationMs, 176400, true, mapOf("TITLE" to title, "ARTIST" to artist), 44100, 16, 2)

    @Test fun videoLabelsArtistPrefixesAndFilePrefixesDoNotHideTheSong() {
        val video = target.copy(title = "Imagine Dragons - Thunder (Audio)", artist = "Imagine Dragons - Topic")
        assertEquals(listOf("imagine dragons thunder", "thunder"), flacSearchQueries(video))
        assertTrue(recordingMatches("Imagine Dragons/Evolve/01 - Thunder.flac", video))
        assertTrue(verifiedRecording(header("Thunder", "Imagine Dragons"), video))
        assertTrue(verifiedRecording(header("Imagine Dragons - Thunder", "Imagine Dragons"), target))
        assertEquals("Thunder", cleanFlacTitle("01 - Thunder.flac"))
        assertEquals("7 Rings", cleanFlacTitle("7 Rings"))
        assertEquals("With or Without You", cleanFlacTitle("With or Without You"))
    }

    @Test fun punctuationAndJoinedArtistNamesStillVerifyTheSameSong() {
        val song = target.copy(title = "Don’t Stop", artist = "AC/DC")
        assertTrue(recordingMatches("ACDC/01 - Dont Stop.flac", song))
        assertTrue(verifiedRecording(header("Don't Stop", "ACDC"), song))
        assertFalse(recordingMatches("ACDC/Dont Stopper.flac", song))
        assertFalse(verifiedRecording(header("Dont Stop", "Someone Else"), song))
    }

    @Test fun featureCreditsCanBeAbsentButCannotContradictEachOther() {
        val song = target.copy(title = "Thunder (feat. Guest)", artist = "Imagine Dragons, Guest")
        assertEquals(listOf("imagine dragons thunder", "thunder"), flacSearchQueries(song))
        assertTrue(recordingMatches("Imagine Dragons/Thunder.flac", song))
        assertTrue(verifiedRecording(header("Thunder", "Imagine Dragons"), song))
        assertTrue(verifiedRecording(header("Thunder ft. Guest", "Imagine Dragons"), song))
        assertFalse(recordingMatches("Imagine Dragons/Thunder (feat. Other).flac", song))
        assertFalse(verifiedRecording(header("Thunder", "Imagine Dragons feat. Other"), song))
        assertFalse(verifiedRecording(header("Thunder (feat. Other)", "Imagine Dragons"), song))
    }

    @Test fun broaderNamesStillRejectOtherVersionsAndUnsafeTiming() {
        val song = target.copy(title = "Thunder (feat. Guest) (Remix)", artist = "Imagine Dragons & Guest")
        assertTrue(recordingMatches("Imagine Dragons/Thunder (Remix).flac", song))
        assertTrue(verifiedRecording(header("Thunder (Remix)", "Imagine Dragons"), song))
        assertFalse(recordingMatches("Imagine Dragons/Thunder.flac", song))
        assertFalse(verifiedRecording(header("Thunder", "Imagine Dragons"), song))
        assertFalse(verifiedRecording(header("Thunder (Remix)", "Imagine Dragons", 203000), song))
        assertFalse(recordingMatches("Imagine Dragons/Thunder Live.flac", target))
        assertFalse(recordingMatches("Imagine Dragons/Thunder Acoustic.flac", target))
    }

    private fun reply(token: Int, user: String, matching: Boolean = true, files: Int = 1): ByteArray {
        val body = WireWriter().string(user).int(token).int(files)
        repeat(files) { index ->
            body.byte(1).string("Imagine Dragons/Evolve/$index - ${if (matching) "Thunder" else "Believer"}.flac")
                .long(600000).string("").int(1).int(1).int(200)
        }
        body.byte(1).int(2000000).int(0).int(0).int(0)
        val output = ByteArrayOutputStream()
        DeflaterOutputStream(output).use { it.write(body.bytes()) }
        return output.toByteArray()
    }

    private fun login(control: Socket): Int {
        control.soTimeout = 5000
        control.getInputStream().readFrame()
        control.getOutputStream().sendFrame(1, WireWriter().byte(1))
        val listen = control.getInputStream().readFrame()
        assertEquals(2, listen.first)
        repeat(4) { control.getInputStream().readFrame() }
        return listen.second.int()
    }

    private fun searchPeer(control: Socket, listener: ServerSocket, user: String, token: Int,
        matching: Boolean = true, files: Int = 1): Socket {
        control.getOutputStream().sendFrame(18, WireWriter().string(user).string("P")
            .int(0x7f000001).int(listener.localPort).int(123))
        return listener.accept().also { socket ->
            socket.soTimeout = 3000
            socket.getInputStream().readFrame(init = true)
            socket.getOutputStream().sendFrame(9, WireWriter().raw(reply(token, user, matching, files)))
        }
    }

    @Test fun irrelevantSearchRepliesCannotOccupyAllEightConnections() = runBlocking {
        val server = ServerSocket(0)
        val peer = ServerSocket(0).also { it.soTimeout = 3000 }
        val connections = mutableListOf<Socket>()
        val done = CompletableDeferred<Unit>()
        val client = PeerFlacClient("local-test", "fixture-password", "127.0.0.1", server.localPort)
        val fixture = async(Dispatchers.IO) {
            server.accept().use { control ->
                login(control)
                val token = control.getInputStream().readFrame().second.int()
                repeat(8) { connections.add(searchPeer(control, peer, "unrelated$it", token, matching = false)) }
                connections.add(searchPeer(control, peer, "match", token))
                done.await()
                connections.dropLast(1).forEach { assertEquals(-1, it.getInputStream().read()) }
            }
        }
        try {
            withContext(Dispatchers.IO) { client.connect() }
            val result = withTimeout(3000) { client.search(target) }
            assertEquals("match", result.single().user)
            done.complete(Unit)
            fixture.await()
        } finally {
            client.close(); server.close(); peer.close()
            connections.forEach { it.close() }; fixture.cancel()
        }
    }

    @Test fun sixEarlyUploadersAndManyFilesCannotCutOffTheSeventhUploader() = runBlocking {
        val server = ServerSocket(0)
        val peer = ServerSocket(0).also { it.soTimeout = 3000 }
        val connections = mutableListOf<Socket>()
        val done = CompletableDeferred<Unit>()
        val client = PeerFlacClient("local-test", "fixture-password", "127.0.0.1", server.localPort,
            searchWindowMs = 4000, backupSearchWindowMs = 5000)
        val fixture = async(Dispatchers.IO) {
            server.accept().use { control ->
                login(control)
                val token = control.getInputStream().readFrame().second.int()
                repeat(7) { connections.add(searchPeer(control, peer, "peer$it", token, files = 50)) }
                done.await()
            }
        }
        try {
            withContext(Dispatchers.IO) { client.connect() }
            assertTrue(withTimeout(2000) { client.search(target) }.isNotEmpty())
            val earlier = (0..5).map { "peer$it" }.toSet()
            assertEquals("peer6", withTimeout(3000) { client.nextCandidate(target, earlier) }!!.user)
            done.complete(Unit)
            fixture.await()
        } finally {
            client.close(); server.close(); peer.close()
            connections.forEach { it.close() }; fixture.cancel()
        }
    }

    @Test fun laterBackupStillArrivesBeyondTheFormerSearchDeadline() = runBlocking {
        val server = ServerSocket(0)
        val peer = ServerSocket(0).also { it.soTimeout = 3000 }
        val returned = CompletableDeferred<Unit>()
        val done = CompletableDeferred<Unit>()
        val client = PeerFlacClient("local-test", "fixture-password", "127.0.0.1", server.localPort,
            searchWindowMs = 150)
        val fixture = async(Dispatchers.IO) {
            server.accept().use { control ->
                login(control)
                val token = control.getInputStream().readFrame().second.int()
                searchPeer(control, peer, "first", token).use {
                    returned.await()
                    delay(600)
                    searchPeer(control, peer, "late", token).use { done.await() }
                }
            }
        }
        try {
            withContext(Dispatchers.IO) { client.connect() }
            assertEquals("first", withTimeout(1500) { client.search(target) }.single().user)
            returned.complete(Unit)
            assertEquals("late", withTimeout(1500) { client.nextCandidate(target, setOf("first")) }!!.user)
            done.complete(Unit)
            fixture.await()
        } finally { client.close(); server.close(); peer.close(); fixture.cancel() }
    }

    @Test fun stoppingSearchPreservesTheActiveUploaderControlAndFileSockets() = runBlocking {
        val server = ServerSocket(0)
        val peer = ServerSocket(0).also { it.soTimeout = 3000 }
        val file = File.createTempFile("quality-search-cleanup", ".flac")
        val payload = ByteArray(600000) { (it % 251).toByte() }
        val stopped = CompletableDeferred<Unit>()
        val client = PeerFlacClient("local-test", "fixture-password", "127.0.0.1", server.localPort)
        val fixture = async(Dispatchers.IO) {
            server.accept().use { control ->
                val port = login(control)
                val token = control.getInputStream().readFrame().second.int()
                searchPeer(control, peer, "peer", token).use { messages ->
                    val request = messages.getInputStream().readFrame()
                    assertEquals(43, request.first)
                    val offer = WireWriter().int(1).int(99).string(request.second.string()).long(payload.size.toLong())
                    messages.getOutputStream().sendFrame(40, offer)
                    assertEquals(41, messages.getInputStream().readFrame().first)
                    Socket("127.0.0.1", port).use { audio ->
                        audio.soTimeout = 3000
                        audio.getOutputStream().sendFrame(1, WireWriter().string("peer").string("F").int(0), init = true)
                        audio.getOutputStream().write(WireWriter().int(99).bytes())
                        audio.getOutputStream().flush()
                        assertEquals(0L, WireReader(audio.getInputStream()).long())
                        audio.getOutputStream().write(payload, 0, 300000)
                        audio.getOutputStream().flush()
                        stopped.await()
                        messages.getOutputStream().sendFrame(40, offer)
                        val accepted = messages.getInputStream().readFrame()
                        assertEquals(41, accepted.first)
                        assertEquals(99, accepted.second.int())
                        assertEquals(1, accepted.second.byte())
                        audio.getOutputStream().write(payload, 300000, 300000)
                        audio.getOutputStream().flush()
                        assertEquals(-1, audio.getInputStream().read())
                    }
                }
            }
        }
        try {
            withContext(Dispatchers.IO) { client.connect() }
            val candidate = withTimeout(2000) { client.search(target) }.single()
            val buffer = withTimeout(3000) { withContext(Dispatchers.IO) { client.download(candidate, file) } }
            client.stopSearch()
            stopped.complete(Unit)
            withTimeout(3000) { buffer.awaitComplete() }
            fixture.await()
            assertArrayEquals(payload, file.readBytes())
        } finally { client.close(); server.close(); peer.close(); file.delete(); fixture.cancel() }
    }
}
