package com.abn3li.telemusic.playback

import com.abn3li.telemusic.data.quality.*
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.DeflaterOutputStream

class FlacBackupSelectionTest {
    private val target = FlacTarget("Thunder", "Imagine Dragons", "Evolve", 240000)
    private fun candidate(user: String, size: Long = 30_000_000, speed: Long = 1_000_000,
        duration: Int = 240, queue: Long = 0) =
        FlacCandidate(user, "Imagine Dragons/Evolve/Thunder.flac", size, duration, speed, queue)

    @Test fun smallerFlacCanCatchPlaybackBeforeALargerFasterFile() {
        val large = candidate("large", 120_000_000, 1_500_000)
        val small = candidate("small", 30_000_000, 1_000_000)
        assertEquals(17.5, estimatedFlacBufferSeconds(large, target, 20000), .001)
        assertEquals(5.24288, estimatedFlacBufferSeconds(small, target, 20000), .001)
        assertEquals(small, bestFlacBufferCandidate(listOf(large, small), target, 20000))
    }

    @Test fun observedSpeedOverridesAnOptimisticAdvertisedSpeed() {
        val peer = candidate("peer", speed = 10_000_000)
        assertTrue(estimatedFlacBufferSeconds(peer, target, 20000, bytesPerSecond = 200_000.0) >
            estimatedFlacBufferSeconds(peer, target, 20000))
    }

    @Test fun strongerRecordingMatchRemainsAheadOfUncertainMetadata() {
        val known = candidate("known", speed = 500_000)
        val unknown = candidate("unknown", speed = 20_000_000, duration = 0)
        assertEquals(known, bestFlacBufferCandidate(listOf(unknown, known), target, 0))
        assertFalse(replaceWaitingFlacPeer(known, unknown, target, 0, 8000, 0))
    }

    @Test fun evenOneReceivedByteProtectsTheWorkingTransfer() {
        assertFalse(replaceWaitingFlacPeer(candidate("old"), candidate("new", speed = 10_000_000),
            target, 0, 10000, 1))
    }

    @Test fun waitingPeerKeepsItsGraceBeforeAReplacement() {
        val old = candidate("old")
        val next = candidate("next", speed = 10_000_000)
        assertFalse(replaceWaitingFlacPeer(old, next, target, 0, 2999, 0))
        assertTrue(replaceWaitingFlacPeer(old, next, target, 0, 3000, 0))
    }

    @Test fun slightlyHigherSpeedOrANewQueueDoesNotJustifyDiscardingARequest() {
        val old = candidate("old")
        assertFalse(replaceWaitingFlacPeer(old, candidate("next", speed = 1_500_000), target, 0, 5000, 0))
        assertFalse(replaceWaitingFlacPeer(old, candidate("next", speed = 10_000_000, queue = 8),
            target, 0, 5000, 0))
    }

    @Test fun fasterAdvertisementDoesNotWinWhenItsFileNeedsLongerToCatchUp() {
        val old = candidate("old", 30_000_000, 1_000_000)
        val next = candidate("next", 240_000_000, 2_000_000)
        assertFalse(replaceWaitingFlacPeer(old, next, target, 100000, 5000, 0))
    }

    @Test fun rankingStillAccountsForTheFiveMibFloorAndLaterPlaybackPosition() {
        val peer = candidate("peer", speed = 2_000_000)
        assertEquals(5.0 * 1024 * 1024 / 2_000_000,
            estimatedFlacBufferSeconds(peer, target, 0), .001)
        assertTrue(estimatedFlacBufferSeconds(peer, target, 100000) >
            estimatedFlacBufferSeconds(peer, target, 0))
        assertEquals(0.0, estimatedFlacBufferSeconds(peer, target, 0, peer.size), 0.0)
    }

    private fun metadata(): ByteArray {
        val info = ByteArray(34)
        val packed = (44100L shl 44) or (1L shl 41) or (15L shl 36) or (44100L * 240)
        ByteBuffer.wrap(info, 10, 8).putLong(packed)
        val comments = WireWriter().string("test encoder").int(2)
            .string("TITLE=Thunder").string("ARTIST=Imagine Dragons").bytes()
        return ByteArrayOutputStream().apply {
            write("fLaC".toByteArray()); write(byteArrayOf(0, 0, 0, 34)); write(info)
            write(byteArrayOf(0x84.toByte(), 0, (comments.size ushr 8).toByte(), comments.size.toByte()))
            write(comments)
        }.toByteArray()
    }

    @Test fun laterFasterReplyReplacesOnlyOneZeroByteRequestAndCompletesTheRace() {
        lateFasterReply(3200)
    }

    @Test fun fasterReplyInsideTheGracePeriodIsReconsideredWithoutMorePackets() {
        lateFasterReply(300)
    }

    private fun lateFasterReply(fasterDelayMs: Long) = runBlocking {
        val server = ServerSocket(0)
        val peer = ServerSocket(0).also { it.soTimeout = 8000 }
        val directory = File.createTempFile("quality-backup-race", "").apply { delete(); mkdir() }
        val connections = CopyOnWriteArrayList<Socket>()
        val closed = CopyOnWriteArrayList<String>()
        val searchReturned = CompletableDeferred<Unit>()
        val secondRequested = CompletableDeferred<Unit>()
        val client = PeerFlacClient("local-test", "fixture-password", "127.0.0.1", server.localPort,
            backupSearchWindowMs = 10000)
        val payload = ByteArray(65536).also { metadata().copyInto(it) }
        val fixture = async(Dispatchers.IO) {
            server.accept().use { control ->
                control.soTimeout = 8000
                control.getInputStream().readFrame()
                control.getOutputStream().sendFrame(1, WireWriter().byte(1))
                val listen = control.getInputStream().readFrame()
                assertEquals(2, listen.first)
                val port = listen.second.int()
                repeat(4) { control.getInputStream().readFrame() }
                val query = control.getInputStream().readFrame()
                assertEquals(26, query.first)
                val token = query.second.int()
                fun reply(user: String, speed: Int, size: Long): Socket {
                    control.getOutputStream().sendFrame(18, WireWriter().string(user).string("P")
                        .int(0x7f000001).int(peer.localPort).int(123))
                    return peer.accept().also { messages ->
                        connections.add(messages); messages.soTimeout = 8000
                        messages.getInputStream().readFrame(init = true)
                        val body = WireWriter().string(user).int(token).int(1).byte(1)
                            .string("Imagine Dragons/Evolve/Thunder.flac").long(size).string("")
                            .int(1).int(1).int(240).byte(1).int(speed).int(0).int(0).int(0)
                        val compressed = ByteArrayOutputStream()
                        DeflaterOutputStream(compressed).use { it.write(body.bytes()) }
                        messages.getOutputStream().sendFrame(9, WireWriter().raw(compressed.toByteArray()))
                    }
                }
                val first = reply("first", 4*1024*1024, 600000)
                val firstRequest = async(Dispatchers.IO) {
                    assertEquals(43, first.getInputStream().readFrame().first)
                    assertEquals(-1, first.getInputStream().read())
                    closed.add("first")
                }
                searchReturned.await()
                val second = reply("second", 3*1024*1024, 600000)
                val secondRequest = async(Dispatchers.IO) {
                    assertEquals(43, second.getInputStream().readFrame().first)
                    secondRequested.complete(Unit)
                    assertEquals(-1, second.getInputStream().read())
                    closed.add("second")
                }
                secondRequested.await()
                delay(fasterDelayMs)
                val faster = reply("faster", 16*1024*1024, payload.size.toLong())
                val request = faster.getInputStream().readFrame()
                assertEquals(43, request.first)
                // The request that supplied no audio was closed before the third
                // uploader took its place. The other request is still alive.
                withTimeout(1000) {
                    if (!firstRequest.isCompleted && !secondRequest.isCompleted)
                        select<Unit> {
                            firstRequest.onAwait { }
                            secondRequest.onAwait { }
                        }
                }
                assertEquals(1, closed.size)
                faster.getOutputStream().sendFrame(40, WireWriter().int(1).int(99)
                    .string(request.second.string()).long(payload.size.toLong()))
                assertEquals(41, faster.getInputStream().readFrame().first)
                Socket("127.0.0.1", port).use { audio ->
                    audio.soTimeout = 3000
                    audio.getOutputStream().sendFrame(1, WireWriter().string("faster").string("F").int(0), init = true)
                    audio.getOutputStream().write(WireWriter().int(99).bytes())
                    audio.getOutputStream().flush()
                    assertEquals(0L, WireReader(audio.getInputStream()).long())
                    audio.getOutputStream().write(payload); audio.getOutputStream().flush()
                    assertEquals(-1, audio.getInputStream().read())
                }
                firstRequest.await(); secondRequest.await()
            }
        }
        try {
            withContext(Dispatchers.IO) { client.connect() }
            assertEquals("first", withTimeout(2000) { client.search(target) }.single().user)
            searchReturned.complete(Unit)
            val ready = withTimeout(9000) {
                awaitReadyFlacBuffer(client, target, directory, position = { 0 }, report = { _, _, _ -> })
            }
            assertEquals("faster", ready.candidate.user)
            assertTrue(ready.buffer.complete)
            assertArrayEquals(payload, ready.file.readBytes())
            fixture.await()
            assertEquals(2, closed.size)
        } finally {
            client.close(); server.close(); peer.close(); fixture.cancel()
            connections.forEach { it.close() }
            directory.listFiles()?.forEach { it.delete() }; directory.delete()
        }
    }
}
