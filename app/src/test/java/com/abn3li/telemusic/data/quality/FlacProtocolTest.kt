package com.abn3li.telemusic.data.quality

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.DeflaterOutputStream

class FlacProtocolTest {
    private val target = FlacTarget("Thunder", "Imagine Dragons", "Evolve", 200000)

    @Test fun fiveMegabyteBufferStillAccountsForPlaybackPosition() {
        val size = 25L * 1024 * 1024
        assertEquals(5L * 1024 * 1024, requiredFlacBufferBytes(size, 200000, 0))
        val later = requiredFlacBufferBytes(size, 200000, 100000)
        assertTrue(later > size / 2)
        assertTrue(later < size)
        assertEquals(5L * 1024 * 1024, requiredFlacBufferBytes(1000000, 200000, 0))
    }

    @Test fun fastTransferCanSwitchAtFiveMegabytesButUnsafePartialFilesWait() {
        val size = 25L * 1024 * 1024
        val received = 5L * 1024 * 1024
        assertTrue(readyFlacBuffer(size, 200000, 0, received, false, true, 1000))
        assertFalse(readyFlacBuffer(size, 200000, 0, received - 1, false, true, 1000))
        assertFalse(readyFlacBuffer(size, 200000, 100000, received, false, true, 1000))
        assertFalse(readyFlacBuffer(size, 200000, 0, received, false, false, 1000))
        assertFalse(readyFlacBuffer(size, 200000, 0, received, false, true, 60000))
        assertFalse(readyFlacBuffer(size, 200000, 0, received, false, true, 500))
        assertTrue(readyFlacBuffer(1000000, 200000, 0, 1000000, true, false, 500))
    }

    @Test fun peerDeadlineLeavesRequestActiveForTheNextCandidate() = runBlocking {
        var attempts = 0
        for (peer in 1..2) {
            attempts++
            try {
                withFlacTimeout(50, "peer response") {
                    if (peer == 1) awaitCancellation()
                    "ready"
                }
                break
            } catch (e: FlacTimeoutException) {
                assertEquals("peer response", e.operation)
                assertTrue(currentCoroutineContext().isActive)
            }
        }
        assertEquals(2, attempts)
    }

    @Test fun ownerCancellationAndOuterDeadlineStillCancelTheRequest() = runBlocking {
        var convertedToPeerFailure = false
        val started = CompletableDeferred<Unit>()
        val request = launch {
            try {
                withFlacTimeout(5000, "peer response") {
                    started.complete(Unit)
                    awaitCancellation()
                }
            } catch (_: FlacTimeoutException) { convertedToPeerFailure = true }
        }
        started.await()
        request.cancelAndJoin()
        assertTrue(request.isCancelled)
        assertFalse(convertedToPeerFailure)
        try {
            withTimeout(50) { withFlacTimeout(5000, "peer response") { awaitCancellation() } }
            fail("Outer deadline was swallowed")
        } catch (_: TimeoutCancellationException) { }
    }

    private fun searchReply(token: Int, free: Boolean = true, queue: Int = 0, publicFlac: Boolean = true,
        duration: Int = 200, firstPath: String? = null, user: String = "peer"): ByteArray {
        val reply = WireWriter().string(user).int(token).int(3)
        for (name in listOf(firstPath ?: "Imagine Dragons/Evolve/Thunder.${if (publicFlac) "flac" else "mp3"}",
                "Imagine Dragons/Evolve/Thunder Live.flac", "Imagine Dragons/Evolve/Believer.flac")) {
            reply.byte(1).string(name).long(65536).string("").int(1).int(1).int(duration)
        }
        reply.byte(if (free) 1 else 0).int(1000000).int(queue).int(0).int(1)
        // A private matching copy must never become a candidate.
        reply.byte(1).string("Imagine Dragons/Evolve/Thunder.flac").long(65536).string("").int(1).int(1).int(200)
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { it.write(reply.bytes()) }
        return compressed.toByteArray()
    }

    @Test fun onlyPublicAvailableMatchingFlacIsSelected() {
        val candidates = parseSearchReply(searchReply(7).inputStream(), 7, target)
        assertEquals(1, candidates.size)
        assertEquals("Imagine Dragons/Evolve/Thunder.flac", candidates.single().filename)
        assertTrue(parseSearchReply(searchReply(7, free = false).inputStream(), 7, target).isEmpty())
        assertEquals(3L, parseSearchReply(searchReply(7, queue = 3).inputStream(), 7, target).single().queueLength)
        assertTrue(parseSearchReply(searchReply(7, publicFlac = false).inputStream(), 7, target).isEmpty())
        assertTrue(parseSearchReply(searchReply(7).inputStream(), 8, target).isEmpty())
    }

    @Test fun availabilityDiagnosticsDistinguishFreeQueuedPeersFromPeersWithNoSlot() {
        val stats = FlacSearchStats()
        assertEquals(1, parseSearchReply(searchReply(7, free = true, queue = 3).inputStream(), 7, target, stats).size)
        assertFalse(stats.sawUnavailable)
        assertTrue(parseSearchReply(searchReply(7, free = false, queue = 3).inputStream(), 7, target, stats).isEmpty())
        assertEquals(2, stats.matchingReplies.get())
        assertEquals(1, stats.freeSlotReplies.get())
        assertEquals(1, stats.freeSlotWithQueueReplies.get())
        assertEquals(1, stats.noFreeSlotReplies.get())
    }

    @Test fun titleFallbackKeepsPrimaryResultsValidAndRejectsUnrelatedSearchTokens() {
        assertEquals(1, parseSearchReply(searchReply(7).inputStream(), 8, target, activeTokens = setOf(7, 8)).size)
        assertEquals(1, parseSearchReply(searchReply(8).inputStream(), 8, target, activeTokens = setOf(7, 8)).size)
        assertTrue(parseSearchReply(searchReply(9).inputStream(), 8, target, activeTokens = setOf(7, 8)).isEmpty())
    }

    @Test fun matchingDoesNotConfuseVersionsOrSubstringTitles() {
        assertFalse(recordingMatches("Imagine Dragons/Thunderstruck.flac", target))
        assertFalse(recordingMatches("Imagine Dragons/Thunder Acoustic.flac", target))
        assertTrue(recordingMatches("Imagine Dragons/Evolve/01 - Thunder.flac", target))
    }

    @Test fun cleanedQueriesKeepRecordingVersionsAndProvideTitleFallback() {
        val video = target.copy(title = "Thunder (Official Music Video)", artist = "Imagine Dragons - Topic")
        assertEquals(listOf("imagine dragons thunder", "thunder"), flacSearchQueries(video))
        assertEquals("Thunder (Live)", cleanFlacTitle("Thunder (Live) [Official Video]"))
        assertFalse(recordingMatches("Imagine Dragons/Thunder.flac", target.copy(title = "Thunder Live")))
        assertTrue(recordingMatches("Dragons Imagine/Thunder.flac", video))
    }

    @Test fun broadResultsAllowMissingDurationAndArtistPathButStillVerifyTags() {
        assertEquals(1, parseSearchReply(searchReply(7, duration = 207).inputStream(), 7, target).size)
        assertEquals(1, parseSearchReply(searchReply(7, duration = 0, firstPath = "01 Thunder.flac").inputStream(), 7, target).size)
        assertTrue(parseSearchReply(searchReply(7, duration = 220).inputStream(), 7, target).isEmpty())
        val header = readFlacHeader(flacMetadata())!!
        assertTrue(verifiedRecording(header, target.copy(album = "Greatest Hits", title = "Thunder (Official Video)")))
        assertFalse(verifiedRecording(header.copy(tags = header.tags + ("ARTIST" to "Someone Else")), target))
        assertFalse(verifiedRecording(header.copy(durationMs = 207000), target))
        assertTrue(verifiedRecording(header.copy(tags = emptyMap()), target, "Imagine Dragons/Thunder.flac"))
        assertFalse(verifiedRecording(header.copy(tags = emptyMap()), target, "Thunder.flac"))
        val stats = FlacSearchStats()
        assertTrue(parseSearchReply(searchReply(7, free = false).inputStream(), 7, target, stats).isEmpty())
        assertTrue(stats.sawFlac && stats.sawMatching && stats.sawUnavailable)
    }

    @Test fun titleFallbackSearchFindsAPublicPeer() = runBlocking {
        val server = ServerSocket(0)
        val peer = ServerSocket(0)
        val client = PeerFlacClient("local-test", "fixture-password", "127.0.0.1", server.localPort, 200)
        val fixture = async(Dispatchers.IO) {
            server.accept().use { control ->
                control.soTimeout = 5000
                control.getInputStream().readFrame()
                control.getOutputStream().sendFrame(1, WireWriter().byte(1))
                repeat(5) { control.getInputStream().readFrame() }
                val primary = control.getInputStream().readFrame()
                assertEquals(26, primary.first)
                primary.second.int()
                assertEquals("imagine dragons thunder", primary.second.string())
                val fallback = control.getInputStream().readFrame()
                assertEquals(26, fallback.first)
                val token = fallback.second.int()
                assertEquals("thunder", fallback.second.string())
                control.getOutputStream().sendFrame(18, WireWriter().string("peer").string("P")
                    .int(0x7f000001).int(peer.localPort).int(123))
                peer.accept().use { messages ->
                    messages.getInputStream().readFrame(init = true)
                    messages.getOutputStream().sendFrame(9, WireWriter().raw(searchReply(token)))
                    delay(300)
                }
            }
        }
        try {
            withContext(Dispatchers.IO) { client.connect() }
            val matches = withContext(Dispatchers.IO) { client.search(target) }
            assertEquals(1, matches.size)
            assertEquals("peer", matches.single().user)
            fixture.await()
        } finally { client.close(); server.close(); peer.close(); fixture.cancel() }
    }

    @Test fun strongMatchReturnsImmediatelyWithoutWaitingOrRunningFallback() = runBlocking {
        val server = ServerSocket(0)
        val peer = ServerSocket(0)
        val done = CompletableDeferred<Unit>()
        val client = PeerFlacClient("local-test", "fixture-password", "127.0.0.1", server.localPort)
        val fixture = async(Dispatchers.IO) {
            server.accept().use { control ->
                control.soTimeout = 5000
                control.getInputStream().readFrame()
                control.getOutputStream().sendFrame(1, WireWriter().byte(1))
                repeat(5) { control.getInputStream().readFrame() }
                val search = control.getInputStream().readFrame()
                assertEquals(26, search.first)
                val token = search.second.int()
                assertEquals("imagine dragons thunder", search.second.string())
                control.getOutputStream().sendFrame(18, WireWriter().string("peer").string("P")
                    .int(0x7f000001).int(peer.localPort).int(123))
                peer.accept().use { messages ->
                    messages.getInputStream().readFrame(init = true)
                    messages.getOutputStream().sendFrame(9, WireWriter().raw(searchReply(token)))
                    done.await()
                    assertEquals(0, control.getInputStream().available())
                }
            }
        }
        try {
            withContext(Dispatchers.IO) { client.connect() }
            val matches = withTimeout(1500) { withContext(Dispatchers.IO) { client.search(target) } }
            assertEquals(1, matches.size)
            done.complete(Unit)
            fixture.await()
        } finally { done.complete(Unit); client.close(); server.close(); peer.close(); fixture.cancel() }
    }

    @Test fun signInRejectionIsReportedWithoutCredentials() = runBlocking {
        val server = ServerSocket(0)
        val client = PeerFlacClient("local-test", "fixture-password", "127.0.0.1", server.localPort, 50)
        val fixture = async(Dispatchers.IO) {
            server.accept().use {
                it.getInputStream().readFrame()
                it.getOutputStream().sendFrame(1, WireWriter().byte(0).string("INVALIDPASS"))
                delay(100)
            }
        }
        try {
            try { withContext(Dispatchers.IO) { client.connect() }; fail("Accepted rejected account") }
            catch (e: FlacSignInException) { assertFalse(e.message.orEmpty().contains("fixture-password")) }
            fixture.await()
        } finally { client.close(); server.close(); fixture.cancel() }
    }

    @Test fun malformedSearchReplyCannotAllocateUnboundedStrings() {
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { it.write(WireWriter().int(Int.MAX_VALUE).bytes()) }
        try { parseSearchReply(compressed.toByteArray().inputStream(), 7, target); fail("Accepted oversized string") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun queuedPeerFailsImmediatelyInsteadOfWaitingForResponseDeadline() =
        peerFailureResponse(44, "Peer is queued")

    @Test fun rejectedPeerPreservesTheActualReasonInStatusAndDiagnostics() =
        peerFailureResponse(50, "Peer rejected: File not shared.")

    @Test fun interruptedUploadIsReportedSeparatelyFromRejection() =
        peerFailureResponse(46, "Peer interrupted the upload")

    @Test fun peerReasonIsBoundedAndCannotExposeCredentialsOrInjectLogLines() {
        val body = WireReader(WireWriter().string("Thunder.flac")
            .string("Banned\nfixture-password\r local-test\u0000" + "x".repeat(300)).bytes())
        val failure = readFlacPeerFailure(50, body, listOf("fixture-password", "local-test"))
        assertTrue(failure.reason.startsWith("Peer rejected: Banned"))
        assertFalse(failure.reason.contains("fixture-password"))
        assertFalse(failure.reason.contains("local-test"))
        assertFalse(failure.reason.any { it.isISOControl() })
        assertTrue(failure.reason.length <= 175)
    }

    private fun peerFailureResponse(code: Int, expectedReason: String) = runBlocking {
        val server = ServerSocket(0)
        val peer = ServerSocket(0)
        val file = File.createTempFile("quality-queued", ".flac")
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        val client = PeerFlacClient("local-test", "fixture-password", "127.0.0.1", server.localPort,
            diagnostic = { events.add(it) })
        val fixture = async(Dispatchers.IO) {
            server.accept().use { control ->
                control.soTimeout = 5000
                assertEquals(1, control.getInputStream().readFrame().first)
                control.getOutputStream().sendFrame(1, WireWriter().byte(1))
                repeat(5) { control.getInputStream().readFrame() }
                val address = control.getInputStream().readFrame()
                assertEquals(3, address.first)
                assertEquals("peer", address.second.string())
                control.getOutputStream().sendFrame(3, WireWriter().string("peer").int(0x7f000001).int(peer.localPort))
                peer.accept().use { messages ->
                    messages.soTimeout = 5000
                    assertEquals(1, messages.getInputStream().readFrame(init = true).first)
                    val request = messages.getInputStream().readFrame()
                    assertEquals(43, request.first)
                    val response = WireWriter().string(request.second.string())
                    if (code == 44) response.int(2)
                    if (code == 50) response.string("File not shared.")
                    messages.getOutputStream().sendFrame(code, response)
                    delay(100)
                }
            }
        }
        try {
            withContext(Dispatchers.IO) { client.connect() }
            try {
                withTimeout(5000) {
                    withContext(Dispatchers.IO) {
                        client.download(FlacCandidate("peer", "Thunder.flac", 600000, 200, 1000000), file)
                    }
                }
                fail("Failed peer was accepted")
            } catch (e: FlacPeerException) { assertEquals(expectedReason, e.reason) }
            fixture.await()
            assertTrue(events.any { it.contains(expectedReason) })
            assertTrue(events.none { it.contains("fixture-password") || it.contains("local-test") || it.contains("Thunder.flac") })
        } finally { client.close(); server.close(); peer.close(); file.delete(); fixture.cancel() }
    }

    private fun flacMetadata(title: String = "Thunder"): ByteArray {
        val info = ByteArray(34)
        val packed = (44100L shl 44) or (1L shl 41) or (15L shl 36) or (44100L * 200)
        ByteBuffer.wrap(info, 10, 8).putLong(packed)
        val comments = WireWriter().string("test encoder").int(3).string("TITLE=$title")
            .string("ARTIST=Imagine Dragons").string("ALBUM=Evolve").bytes()
        val output = ByteArrayOutputStream()
        output.write("fLaC".toByteArray())
        output.write(byteArrayOf(0, 0, 0, 34)); output.write(info)
        output.write(byteArrayOf(0x84.toByte(), 0, (comments.size ushr 8).toByte(), comments.size.toByte()))
        output.write(comments)
        return output.toByteArray()
    }

    @Test fun closedRequestConnectionReconnectsOnceAndReceivesTheFile() = runBlocking {
        val server = ServerSocket(0)
        val peer = ServerSocket(0)
        val file = File.createTempFile("quality-reconnect", ".flac")
        val payload = ByteArray(600000).also { flacMetadata().copyInto(it) }
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        val client = PeerFlacClient("local-test", "fixture-password", "127.0.0.1", server.localPort,
            diagnostic = { events.add(it) })
        val fixture = async(Dispatchers.IO) {
            server.accept().use { control ->
                control.soTimeout = 5000
                assertEquals(1, control.getInputStream().readFrame().first)
                control.getOutputStream().sendFrame(1, WireWriter().byte(1))
                val listen = control.getInputStream().readFrame()
                assertEquals(2, listen.first)
                val port = listen.second.int()
                repeat(4) { control.getInputStream().readFrame() }
                repeat(2) { attempt ->
                    assertEquals(3, control.getInputStream().readFrame().first)
                    control.getOutputStream().sendFrame(3, WireWriter().string("peer")
                        .int(0x7f000001).int(peer.localPort))
                    peer.accept().use { messages ->
                        messages.soTimeout = 5000
                        assertEquals(1, messages.getInputStream().readFrame(init = true).first)
                        val request = messages.getInputStream().readFrame()
                        assertEquals(43, request.first)
                        val filename = request.second.string()
                        if (attempt == 1) {
                            messages.getOutputStream().sendFrame(40, WireWriter().int(1).int(99)
                                .string(filename).long(payload.size.toLong()))
                            assertEquals(41, messages.getInputStream().readFrame().first)
                            Socket("127.0.0.1", port).use { audio ->
                                audio.soTimeout = 5000
                                audio.getOutputStream().sendFrame(1,
                                    WireWriter().string("peer").string("F").int(0), init = true)
                                audio.getOutputStream().write(WireWriter().int(99).bytes())
                                audio.getOutputStream().flush()
                                assertEquals(0L, WireReader(audio.getInputStream()).long())
                                audio.getOutputStream().write(payload)
                                audio.getOutputStream().flush()
                                assertEquals(-1, audio.getInputStream().read())
                            }
                        }
                    }
                }
            }
        }
        try {
            withContext(Dispatchers.IO) { client.connect() }
            val buffer = withTimeout(8000) { withContext(Dispatchers.IO) {
                client.download(FlacCandidate("peer", "Thunder.flac", payload.size.toLong(), 200, 1000000), file)
            } }
            withTimeout(5000) { buffer.awaitComplete() }
            fixture.await()
            assertArrayEquals(payload, file.readBytes())
            assertEquals(1, events.count { it.contains("Reconnecting once") })
        } finally { client.close(); server.close(); peer.close(); file.delete(); fixture.cancel() }
    }

    @Test fun acceptedUploadCanStartFromThePhoneWhenNoIncomingFileConnectionArrives() =
        fileConnectionRoute(serverRequestsConnection = false)

    @Test fun serverRequestedFileConnectionWorksWithoutAnOpenPhonePort() =
        fileConnectionRoute(serverRequestsConnection = true)

    @Test fun smallerActualUploadReplacesAnOutdatedSearchSize() =
        fileConnectionRoute(serverRequestsConnection = true, searchSize = 1200000)

    @Test fun largerActualUploadReplacesAnOutdatedSearchSize() =
        fileConnectionRoute(serverRequestsConnection = false, searchSize = 300000)

    @Test fun staleQueuePositionCannotCancelAnAcceptedUpload() =
        fileConnectionRoute(serverRequestsConnection = true, lateQueueUpdate = true)

    @Test fun repeatedOfferForTheSameAcceptedTokenIsAcknowledgedWithoutAnotherFileAttempt() =
        fileConnectionRoute(serverRequestsConnection = false, repeatedOffer = true)

    @Test fun zeroLengthUploadIsRejectedImmediately() = invalidUploadSize(0)

    @Test fun oversizedUploadIsRejectedImmediately() = invalidUploadSize(MAX_FLAC_BYTES + 1)

    private fun invalidUploadSize(offeredSize: Long) = runBlocking {
        val server = ServerSocket(0)
        val peer = ServerSocket(0)
        val file = File.createTempFile("quality-invalid-size", ".flac")
        val client = PeerFlacClient("local-test", "fixture-password", "127.0.0.1", server.localPort,
            responseTimeoutMs = 1000)
        val fixture = async(Dispatchers.IO) {
            server.accept().use { control ->
                control.soTimeout = 5000
                control.getInputStream().readFrame()
                control.getOutputStream().sendFrame(1, WireWriter().byte(1))
                repeat(5) { control.getInputStream().readFrame() }
                assertEquals(3, control.getInputStream().readFrame().first)
                control.getOutputStream().sendFrame(3, WireWriter().string("peer")
                    .int(0x7f000001).int(peer.localPort))
                peer.accept().use { messages ->
                    messages.soTimeout = 5000
                    messages.getInputStream().readFrame(init = true)
                    val request = messages.getInputStream().readFrame()
                    assertEquals(43, request.first)
                    messages.getOutputStream().sendFrame(40, WireWriter().int(1).int(99)
                        .string(request.second.string()).long(offeredSize))
                    val response = messages.getInputStream().readFrame()
                    assertEquals(41, response.first)
                    assertEquals(99, response.second.int())
                    assertEquals(0, response.second.byte())
                    assertEquals("Invalid FLAC file size", response.second.string())
                    delay(100)
                }
            }
        }
        try {
            withContext(Dispatchers.IO) { client.connect() }
            try {
                withTimeout(2000) { withContext(Dispatchers.IO) {
                    client.download(FlacCandidate("peer", "Thunder.flac", 600000, 200, 1000000), file)
                } }
                fail("Invalid upload size was accepted")
            } catch (e: FlacPeerException) { assertEquals("Peer offered an invalid FLAC size", e.reason) }
            fixture.await()
        } finally { client.close(); server.close(); peer.close(); file.delete(); fixture.cancel() }
    }

    private fun fileConnectionRoute(serverRequestsConnection: Boolean, searchSize: Long = 600000,
        lateQueueUpdate: Boolean = false, repeatedOffer: Boolean = false) = runBlocking {
        val server = ServerSocket(0)
        val peer = ServerSocket(0)
        val file = File.createTempFile("quality-file-route", ".flac")
        val payload = ByteArray(600000).also { flacMetadata().copyInto(it) }
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        val client = PeerFlacClient("local-test", "fixture-password", "127.0.0.1", server.localPort,
            fileConnectFallbackMs = if (serverRequestsConnection) 2000 else 100,
            diagnostic = { events.add(it) })
        val fixture = async(Dispatchers.IO) {
            server.accept().use { control ->
                control.soTimeout = 5000
                control.getInputStream().readFrame()
                control.getOutputStream().sendFrame(1, WireWriter().byte(1))
                repeat(5) { control.getInputStream().readFrame() }
                assertEquals(3, control.getInputStream().readFrame().first)
                control.getOutputStream().sendFrame(3, WireWriter().string("peer")
                    .int(0x7f000001).int(peer.localPort))
                peer.accept().use { messages ->
                    messages.soTimeout = 5000
                    messages.getInputStream().readFrame(init = true)
                    val request = messages.getInputStream().readFrame()
                    assertEquals(43, request.first)
                    val filename = request.second.string()
                    val offer = WireWriter().int(1).int(99).string(filename).long(payload.size.toLong())
                    messages.getOutputStream().sendFrame(40, offer)
                    val response = messages.getInputStream().readFrame()
                    assertEquals(41, response.first)
                    assertEquals(99, response.second.int())
                    assertEquals(1, response.second.byte())
                    if (repeatedOffer) {
                        messages.getOutputStream().sendFrame(40, offer)
                        val repeatResponse = messages.getInputStream().readFrame()
                        assertEquals(41, repeatResponse.first)
                        assertEquals(99, repeatResponse.second.int())
                        assertEquals(1, repeatResponse.second.byte())
                    }
                    if (lateQueueUpdate) messages.getOutputStream().sendFrame(44, WireWriter().string(filename).int(2))
                    if (serverRequestsConnection) {
                        control.getOutputStream().sendFrame(18, WireWriter().string("peer").string("F")
                            .int(0x7f000001).int(peer.localPort).int(777))
                    }
                    peer.soTimeout = 5000
                    peer.accept().use { audio ->
                        audio.soTimeout = 5000
                        val init = audio.getInputStream().readFrame(init = true)
                        if (serverRequestsConnection) {
                            assertEquals(0, init.first)
                            assertEquals(777, init.second.int())
                            audio.getOutputStream().write(WireWriter().int(99).bytes())
                            audio.getOutputStream().flush()
                        } else {
                            assertEquals(1, init.first)
                            assertEquals("local-test", init.second.string())
                            assertEquals("F", init.second.string())
                            init.second.int()
                            assertEquals(99, WireReader(audio.getInputStream()).int())
                        }
                        assertEquals(0L, WireReader(audio.getInputStream()).long())
                        audio.getOutputStream().write(payload)
                        audio.getOutputStream().flush()
                        assertEquals(-1, audio.getInputStream().read())
                    }
                }
            }
        }
        try {
            withContext(Dispatchers.IO) { client.connect() }
            val buffer = withTimeout(5000) { withContext(Dispatchers.IO) {
                client.download(FlacCandidate("peer", "Thunder.flac", searchSize, 200, 1000000), file)
            } }
            withTimeout(5000) { buffer.awaitComplete() }
            fixture.await()
            assertArrayEquals(payload, file.readBytes())
            assertEquals(payload.size.toLong(), buffer.totalSize)
            assertTrue(buffer.firstByteAtNanos > 0)
            assertTrue(events.any { it.contains(if (serverRequestsConnection)
                "File connection requested through server" else "Opening outbound file connection") })
            if (searchSize != payload.size.toLong()) assertTrue(events.any { it.startsWith("Upload size updated;") })
            if (lateQueueUpdate) assertTrue(events.any { it.contains("Late queue update ignored") })
            if (repeatedOffer) {
                assertEquals(1, events.count { it.startsWith("Upload accepted;") })
                assertEquals(1, events.count { it.startsWith("Opening outbound file connection") })
                assertTrue(events.any { it == "Repeated upload offer acknowledged" })
            }
        } finally { client.close(); server.close(); peer.close(); file.delete(); fixture.cancel() }
    }

    @Test fun fastFirstResultStillCollectsALaterBackupUploader() = runBlocking {
        val server = ServerSocket(0)
        val primary = ServerSocket(0)
        val backup = ServerSocket(0)
        val returned = CompletableDeferred<Unit>()
        val client = PeerFlacClient("local-test", "fixture-password", "127.0.0.1", server.localPort,
            searchWindowMs = 150, backupSearchWindowMs = 1000)
        val fixture = async(Dispatchers.IO) {
            server.accept().use { control ->
                control.soTimeout = 5000
                control.getInputStream().readFrame()
                control.getOutputStream().sendFrame(1, WireWriter().byte(1))
                repeat(5) { control.getInputStream().readFrame() }
                val search = control.getInputStream().readFrame()
                assertEquals(26, search.first)
                val token = search.second.int()
                control.getOutputStream().sendFrame(18, WireWriter().string("peer").string("P")
                    .int(0x7f000001).int(primary.localPort).int(111))
                primary.accept().use { first ->
                    first.getInputStream().readFrame(init = true)
                    first.getOutputStream().sendFrame(9, WireWriter().raw(searchReply(token)))
                    returned.await()
                    // This uploader arrives after the old short search deadline.
                    delay(300)
                    control.getOutputStream().sendFrame(18, WireWriter().string("backup").string("P")
                        .int(0x7f000001).int(backup.localPort).int(222))
                    backup.accept().use { second ->
                        second.getInputStream().readFrame(init = true)
                        second.getOutputStream().sendFrame(9, WireWriter().raw(searchReply(token, user = "backup")))
                        delay(100)
                    }
                }
            }
        }
        try {
            withContext(Dispatchers.IO) { client.connect() }
            val first = withTimeout(1000) { client.search(target) }
            assertEquals("peer", first.single().user)
            returned.complete(Unit)
            val next = withTimeout(1500) { client.nextCandidate(target, setOf("peer")) }
            assertEquals("backup", next!!.user)
            assertTrue(client.hasFasterCandidate(target, setOf("peer"), 100000.0))
            fixture.await()
        } finally { client.close(); server.close(); primary.close(); backup.close(); fixture.cancel() }
    }

    @Test fun exhaustedFirstUploaderStartsTheTitleFallbackBeforeTheInitialDeadline() = runBlocking {
        val server = ServerSocket(0)
        val primary = ServerSocket(0)
        val backup = ServerSocket(0)
        val returned = CompletableDeferred<Unit>()
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        val client = PeerFlacClient("local-test", "fixture-password", "127.0.0.1", server.localPort,
            searchWindowMs = 2000, backupSearchWindowMs = 4000, diagnostic = { events.add(it) })
        val fixture = async(Dispatchers.IO) {
            server.accept().use { control ->
                control.soTimeout = 5000
                control.getInputStream().readFrame()
                control.getOutputStream().sendFrame(1, WireWriter().byte(1))
                repeat(5) { control.getInputStream().readFrame() }
                val search = control.getInputStream().readFrame()
                assertEquals(26, search.first)
                val token = search.second.int()
                control.getOutputStream().sendFrame(18, WireWriter().string("peer").string("P")
                    .int(0x7f000001).int(primary.localPort).int(111))
                primary.accept().use { first ->
                    first.getInputStream().readFrame(init = true)
                    first.getOutputStream().sendFrame(9, WireWriter().raw(searchReply(token)))
                    returned.await()
                    // The failed uploader should broaden immediately, rather than wait two seconds.
                    control.soTimeout = 1000
                    val fallback = control.getInputStream().readFrame()
                    assertEquals(26, fallback.first)
                    val fallbackToken = fallback.second.int()
                    assertEquals("thunder", fallback.second.string())
                    control.getOutputStream().sendFrame(18, WireWriter().string("backup").string("P")
                        .int(0x7f000001).int(backup.localPort).int(222))
                    backup.accept().use { second ->
                        second.getInputStream().readFrame(init = true)
                        second.getOutputStream().sendFrame(9, WireWriter().raw(searchReply(fallbackToken,
                            queue = 3, user = "backup")))
                        delay(100)
                    }
                }
            }
        }
        try {
            withContext(Dispatchers.IO) { client.connect() }
            assertEquals("peer", withTimeout(1000) { client.search(target) }.single().user)
            returned.complete(Unit)
            val next = withTimeout(1500) { client.nextCandidate(target, setOf("peer")) }
            assertEquals("backup", next!!.user)
            assertEquals(3L, next.queueLength)
            fixture.await()
            assertEquals(1, events.count { it == "Starting title fallback search" })
        } finally { client.close(); server.close(); primary.close(); backup.close(); fixture.cancel() }
    }

    @Test fun silentOutboundSocketDoesNotCloseTheLaterWorkingUploaderRoute() =
        competingFileRoutes(outboundEndsBeforeData = false)

    @Test fun outboundEofBeforeFirstByteDoesNotAbortTheLaterWorkingUploaderRoute() =
        competingFileRoutes(outboundEndsBeforeData = true)

    @Test fun failedLosingRouteCannotInterruptTheSelectedFileReader() =
        competingFileRoutes(outboundEndsBeforeData = false, reportLosingRouteFailure = true)

    @Test fun selectedFileReaderStillRejectsATruncatedTransfer() =
        competingFileRoutes(outboundEndsBeforeData = false, truncateSelectedRoute = true)

    private fun competingFileRoutes(outboundEndsBeforeData: Boolean, reportLosingRouteFailure: Boolean = false,
        truncateSelectedRoute: Boolean = false) = runBlocking {
        val server = ServerSocket(0)
        val peer = ServerSocket(0)
        val file = File.createTempFile("quality-competing-routes", ".flac")
        val payload = ByteArray(600000).also { flacMetadata().copyInto(it) }
        val downloaded = CompletableDeferred<Unit>()
        val failedRouteObserved = CompletableDeferred<Unit>()
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        val client = PeerFlacClient("local-test", "fixture-password", "127.0.0.1", server.localPort,
            firstByteTimeoutMs = 2000, fileConnectFallbackMs = 50,
            diagnostic = {
                events.add(it)
                if (it.contains("keeping the active file reader")) failedRouteObserved.complete(Unit)
            })
        val fixture = async(Dispatchers.IO) {
            server.accept().use { control ->
                control.soTimeout = 5000
                control.getInputStream().readFrame()
                control.getOutputStream().sendFrame(1, WireWriter().byte(1))
                repeat(5) { control.getInputStream().readFrame() }
                assertEquals(3, control.getInputStream().readFrame().first)
                control.getOutputStream().sendFrame(3, WireWriter().string("peer")
                    .int(0x7f000001).int(peer.localPort))
                peer.soTimeout = 5000
                peer.accept().use { messages ->
                    messages.soTimeout = 5000
                    messages.getInputStream().readFrame(init = true)
                    val request = messages.getInputStream().readFrame()
                    assertEquals(43, request.first)
                    val filename = request.second.string()
                    messages.getOutputStream().sendFrame(40, WireWriter().int(1).int(99)
                        .string(filename).long(payload.size.toLong()))
                    assertEquals(41, messages.getInputStream().readFrame().first)
                    peer.accept().use { silent ->
                        silent.soTimeout = 5000
                        val init = silent.getInputStream().readFrame(init = true)
                        assertEquals(1, init.first)
                        assertEquals("local-test", init.second.string())
                        assertEquals("F", init.second.string())
                        init.second.int()
                        assertEquals(99, WireReader(silent.getInputStream()).int())
                        assertEquals(0L, WireReader(silent.getInputStream()).long())
                        if (outboundEndsBeforeData) silent.close()
                        // The first socket is connected, but it has supplied no file bytes.
                        control.getOutputStream().sendFrame(18, WireWriter().string("peer").string("F")
                            .int(0x7f000001).int(peer.localPort).int(777))
                        peer.accept().use { working ->
                            working.soTimeout = 5000
                            assertEquals(0, working.getInputStream().readFrame(init = true).first)
                            working.getOutputStream().write(WireWriter().int(99).bytes())
                            working.getOutputStream().flush()
                            assertEquals(0L, WireReader(working.getInputStream()).long())
                            working.getOutputStream().write(payload, 0, 300000)
                            working.getOutputStream().flush()
                            downloaded.await()
                            if (!outboundEndsBeforeData) assertEquals(-1, silent.getInputStream().read())
                            if (reportLosingRouteFailure) {
                                messages.getOutputStream().sendFrame(46, WireWriter().string(filename))
                                withTimeout(1000) { failedRouteObserved.await() }
                            }
                            if (!truncateSelectedRoute) {
                                working.getOutputStream().write(payload, 300000, 300000)
                                working.getOutputStream().flush()
                                assertEquals(-1, working.getInputStream().read())
                            }
                        }
                        if (truncateSelectedRoute) delay(100)
                    }
                }
            }
        }
        try {
            withContext(Dispatchers.IO) { client.connect() }
            val buffer = withTimeout(5000) { withContext(Dispatchers.IO) {
                client.download(FlacCandidate("peer", "Thunder.flac", payload.size.toLong(), 200, 1000000), file)
            } }
            downloaded.complete(Unit)
            if (truncateSelectedRoute) {
                try { withTimeout(2000) { buffer.awaitComplete() }; fail("Truncated winning route was accepted") }
                catch (e: java.io.IOException) { assertEquals("FLAC transfer stopped receiving data", e.message) }
                assertFalse(buffer.complete)
            } else {
                withTimeout(2000) { buffer.awaitComplete() }
                assertArrayEquals(payload, file.readBytes())
                assertTrue(events.none { it.startsWith("Transfer failed:") })
            }
            fixture.await()
            assertEquals(1, events.count { it.startsWith("File transfer started;") })
        } finally { client.close(); server.close(); peer.close(); file.delete(); fixture.cancel() }
    }

    @Test fun headerRequiresRealFlacAndChecksEmbeddedRecording() {
        val header = readFlacHeader(flacMetadata())!!
        assertEquals(200000, header.durationMs)
        assertEquals(176400, header.pcmBytesPerSecond)
        assertEquals(44100, header.sampleRate)
        assertEquals(16, header.bitDepth)
        assertEquals(2, header.channels)
        assertFalse(header.seekTable)
        assertTrue(verifiedRecording(header, target))
        assertFalse(verifiedRecording(readFlacHeader(flacMetadata("Believer"))!!, target))
        assertNull(readFlacHeader(flacMetadata().copyOf(50)))
        try { readFlacHeader(ByteArray(100)); fail("Accepted a non-FLAC file") } catch (_: IllegalArgumentException) { }
    }

    @Test fun growingFileWaitsForBytesAndFailsInsteadOfReportingEarlyEof() {
        val file = File.createTempFile("quality", ".flac")
        val buffer = FlacStreamBuffer(file, 6)
        val worker = Executors.newSingleThreadExecutor()
        try {
            RandomAccessFile(file, "r").use { reader ->
                val bytes = ByteArray(3)
                val waiting = worker.submit<Int> { buffer.read(reader, 0, bytes, 0, 3) }
                Thread.sleep(100)
                assertFalse(waiting.isDone)
                file.writeBytes(byteArrayOf(1, 2, 3))
                buffer.publish(3)
                assertEquals(3, waiting.get(1, TimeUnit.SECONDS).toInt())
                assertArrayEquals(byteArrayOf(1, 2, 3), bytes)
                val missing = worker.submit<Int> { buffer.read(reader, 3, bytes, 0, 3) }
                buffer.fail("Peer disconnected")
                try { missing.get(1, TimeUnit.SECONDS); fail("Early EOF hid an interrupted transfer") }
                catch (_: java.util.concurrent.ExecutionException) { }
            }
        } finally { worker.shutdownNow(); file.delete() }
    }

    @Test fun downloadWaitsForCompletionAndRetainsCacheUntilCopyFinishes() = runBlocking {
        val source = File.createTempFile("quality-save", ".flac")
        val saved = File.createTempFile("quality-offline", ".flac")
        val buffer = FlacStreamBuffer(source, 6)
        val lease = buffer.retainFile()
        try {
            val download = async(Dispatchers.IO) {
                buffer.awaitComplete()
                source.copyTo(saved, overwrite = true)
            }
            source.writeBytes(byteArrayOf(1, 2, 3))
            buffer.publish(3)
            delay(50)
            assertFalse(download.isCompleted)
            assertTrue(buffer.shouldFinishDownload)
            buffer.deleteWhenUnused()
            assertTrue(source.exists())
            source.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6))
            buffer.publish(6, done = true)
            download.await()
            assertFalse(buffer.shouldFinishDownload)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), saved.readBytes())
            lease.close()
            assertFalse(source.exists())
            assertTrue(saved.exists())
        } finally { lease.close(); source.delete(); saved.delete() }
    }

    @Test fun interruptedFlacCannotBeSavedAsACompleteDownload() = runBlocking {
        val file = File.createTempFile("quality-interrupted", ".flac")
        val buffer = FlacStreamBuffer(file, 6)
        try {
            file.writeBytes(byteArrayOf(1, 2, 3))
            buffer.publish(3)
            buffer.fail("Peer disconnected")
            try { withTimeout(500) { buffer.awaitComplete() }; fail("Partial FLAC was accepted") }
            catch (e: java.io.IOException) { assertEquals("Peer disconnected", e.message) }
        } finally { file.delete() }
    }

    @Test fun timedOutPeerRequestCanBeFollowedByAProgressiveTransfer() = runBlocking {
        val server = ServerSocket(0)
        val peer = ServerSocket(0)
        val payload = ByteArray(600000).also { flacMetadata().copyInto(it) }
        val file = File.createTempFile("quality-peer", ".flac")
        val client = PeerFlacClient("local-test", "fixture-password", "127.0.0.1", server.localPort, 150,
            responseTimeoutMs = 1000, firstByteTimeoutMs = 2000)
        val fixture = async(Dispatchers.IO) {
            server.accept().use { control ->
                control.soTimeout = 10000
                assertEquals(1, control.getInputStream().readFrame().first)
                control.getOutputStream().sendFrame(1, WireWriter().byte(1))
                val listenFrame = control.getInputStream().readFrame()
                assertEquals(2, listenFrame.first)
                val listenPort = listenFrame.second.int()
                repeat(4) { control.getInputStream().readFrame() }
                val search = control.getInputStream().readFrame()
                assertEquals(26, search.first)
                val token = search.second.int()
                val reply = WireWriter().string("peer").int(token).int(1).byte(1)
                    .string("Imagine Dragons/Evolve/Thunder.flac").long(payload.size.toLong())
                    .string("").int(1).int(1).int(200).byte(1).int(2000000).int(0).int(0).int(0)
                val compressed = ByteArrayOutputStream()
                DeflaterOutputStream(compressed).use { it.write(reply.bytes()) }
                control.getOutputStream().sendFrame(18, WireWriter().string("peer").string("P")
                    .int(0x7f000001).int(peer.localPort).int(1234))
                peer.accept().use { messages ->
                    messages.soTimeout = 10000
                    assertEquals(0, messages.getInputStream().readFrame(init = true).first)
                    messages.getOutputStream().sendFrame(9, WireWriter().raw(compressed.toByteArray()))
                    // Ignore the first request, then accept the next one on the same connection.
                    assertEquals(43, messages.getInputStream().readFrame().first)
                    val request = messages.getInputStream().readFrame()
                    assertEquals(43, request.first)
                    val filename = request.second.string()
                    messages.getOutputStream().sendFrame(40, WireWriter().int(1).int(99).string(filename).long(payload.size.toLong()))
                    val accepted = messages.getInputStream().readFrame()
                    assertEquals(41, accepted.first)
                    assertEquals(99, accepted.second.int())
                    assertEquals(1, accepted.second.byte())
                    Socket("127.0.0.1", listenPort).use { audio ->
                        audio.soTimeout = 10000
                        audio.getOutputStream().sendFrame(1, WireWriter().string("peer").string("F").int(0), init = true)
                        audio.getOutputStream().write(WireWriter().int(99).bytes())
                        audio.getOutputStream().flush()
                        assertEquals(0L, WireReader(audio.getInputStream()).long())
                        // Acceptance has its own deadline; a slower first byte must not reuse it.
                        delay(1100)
                        audio.getOutputStream().write(payload, 0, 300000)
                        audio.getOutputStream().flush()
                        delay(500)
                        audio.getOutputStream().write(payload, 300000, 300000)
                        audio.getOutputStream().flush()
                        delay(100)
                    }
                }
            }
        }
        try {
            withContext(Dispatchers.IO) { client.connect() }
            val candidate = withContext(Dispatchers.IO) { client.search(target) }.single()
            try {
                withContext(Dispatchers.IO) { client.download(candidate, file) }
                fail("Silent peer did not time out")
            } catch (e: FlacTimeoutException) {
                assertEquals("peer response", e.operation)
                assertTrue(currentCoroutineContext().isActive)
            }
            client.cancelTransfer()
            val buffer = withContext(Dispatchers.IO) { client.download(candidate, file) }
            withTimeout(2000) {
                while (buffer.available < 262144) buffer.updates.receive()
            }
            assertFalse(buffer.complete)
            assertTrue(buffer.available >= 262144)
            assertTrue(verifiedRecording(readFlacHeader(buffer.prefix())!!, target))
            withTimeout(5000) { client.awaitFinished() }
            assertTrue(buffer.complete)
            assertArrayEquals(payload, file.readBytes())
            fixture.await()
        } finally { client.close(); server.close(); peer.close(); file.delete(); fixture.cancel() }
    }
}
