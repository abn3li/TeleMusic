package com.abn3li.telemusic.data.quality

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

internal class FlacSignInException : IOException("Soulseek sign-in rejected")
internal class FlacPeerException(val reason: String) : IOException(reason)

/** A download-only session. No shares, chat, wishlist, uploads, or background scans. */
internal class PeerFlacClient(
    private val username: String,
    private val password: String,
    private val serverHost: String = "server.slsknet.org",
    private val serverPort: Int = 2416,
    private val searchWindowMs: Long = 5000,
    private val responseTimeoutMs: Long = 20000,
    private val firstByteTimeoutMs: Long = 20000,
    private val fileConnectFallbackMs: Long = 1500,
    private val backupSearchWindowMs: Long = searchWindowMs * 6,
    private val diagnostic: (String) -> Unit = { message ->
        // Socket fixtures run on the JVM without Android's logger.
        runCatching { android.util.Log.i("FlacTransfer", message) }
        Unit
    }
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val peerLimit = Semaphore(12)
    // Search connections must leave room for the file connection we actually need.
    private val controlPeerLimit = Semaphore(8)
    private val filePeerLimit = Semaphore(2)
    private val peers = ConcurrentHashMap<String, Socket>()
    private val peerLock = Any()
    private val retainedSearchPeers = mutableMapOf<Socket, FlacCandidate>()
    private val endpoints = ConcurrentHashMap<String, Pair<InetAddress, Int>>()
    private val indirect = ConcurrentHashMap<Int, Pair<String, String>>()
    private val pendingPeers = ConcurrentHashMap<Int, CompletableDeferred<Socket>>()
    private val addresses = ConcurrentHashMap<String, CompletableDeferred<Pair<InetAddress, Int>>>()
    private val tokens = AtomicInteger((System.nanoTime() and 0x3fffffff).toInt())
    private val login = CompletableDeferred<Unit>()
    private var listener: ServerSocket? = null
    @Volatile private var server: Socket? = null
    @Volatile private var closed = false
    @Volatile private var serverAlive = false
    val isConnected: Boolean get() = serverAlive && !closed
    @Volatile private var searchToken = 0
    private val activeSearchTokens = ConcurrentHashMap.newKeySet<Int>()
    private val fallbackSearchSent = AtomicBoolean(false)
    @Volatile private var target: FlacTarget? = null
    @Volatile private var searchStats = FlacSearchStats()
    var lastSearchSummary = "no matching public FLAC found"
        private set
    private val results = Channel<Pair<CompletableDeferred<Unit>, FlacCandidate>>(64)
    private val candidates = ConcurrentHashMap<String, FlacCandidate>()
    private val candidateUpdates = Channel<Unit>(Channel.CONFLATED)
    private var searchCollection: Job? = null
    private var searchFinished = CompletableDeferred<Unit>()
    @Volatile private var transfer: PendingTransfer? = null

    private class PendingTransfer(val candidate: FlacCandidate, @Volatile var buffer: FlacStreamBuffer) {
        @Volatile var controlSocket: Socket? = null
        @Volatile var token: Int? = null
        val accepted = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val receiving = AtomicBoolean(false)
        val failed = AtomicBoolean(false)
        val connected = CompletableDeferred<Unit>()
        val fileSockets = ConcurrentHashMap.newKeySet<Socket>()
        val competingFileRoutes = AtomicBoolean(false)
        var fallbackConnection: Job? = null
    }

    suspend fun connect() {
        check(!closed)
        val listening = ServerSocket(0).also { listener = it }
        scope.launch {
            try {
                while (!closed) {
                    val socket = listening.accept()
                    if (!peerLimit.tryAcquire()) { socket.close(); continue }
                    sockets.add(socket)
                    scope.launch {
                        try { incoming(socket) } catch (_: Exception) { }
                        finally { sockets.remove(socket); socket.close(); peerLimit.release() }
                    }
                }
            } catch (_: IOException) { }
        }
        val socket = dial(InetAddress.getByName(serverHost), serverPort)
        server = socket
        socket.soTimeout = 0
        val hash = MessageDigest.getInstance("MD5").digest((username + password).toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        // Use the experimental client version rather than impersonating an existing client.
        sendServer(1, WireWriter().string(username).string(password).int(177).string(hash).int(2301))
        scope.launch {
            try {
                while (!closed) {
                    val (code, body) = socket.getInputStream().readFrame()
                    when (code) {
                        1 -> if (body.byte() != 0) login.complete(Unit)
                            else login.completeExceptionally(FlacSignInException())
                        3 -> {
                            val user = body.string()
                            val ip = body.int()
                            val port = body.int()
                            val endpoint = ipAddress(ip) to port
                            endpoints[user] = endpoint
                            addresses.remove(user)?.complete(endpoint)
                        }
                        18 -> {
                            val user = body.string(); val type = body.string()
                            val ip = body.int(); val port = body.int(); val token = body.int()
                            endpoints[user] = ipAddress(ip) to port
                            val pending = transfer?.takeIf { it.candidate.user == user && !it.failed.get() }
                            if (type == "F" && pending == null) continue
                            val limit = if (type == "F") filePeerLimit else peerLimit
                            if (type in listOf("P", "F") && limit.tryAcquire()) scope.launch {
                                var peer: Socket? = null
                                try {
                                    if (type == "F") diagnostic("File connection requested through server")
                                    peer = dial(ipAddress(ip), port) { if (type == "F" && pending != null) registerFileRoute(pending, it) }
                                    peer.getOutputStream().sendFrame(0, WireWriter().int(token), init = true)
                                    handlePeer(peer, user, type)
                                } catch (e: Exception) {
                                    // A losing connection route must not abort a working file socket.
                                    if (type == "F") diagnostic("Server file route failed: ${socketFailure(e)}")
                                }
                                finally { peer?.let { pending?.fileSockets?.remove(it); sockets.remove(it); it.close() }; limit.release() }
                            }
                        }
                        22 -> sendServer(23, WireWriter().int(body.int())) // Acknowledge, never send messages.
                        1001 -> pendingPeers.remove(body.int())?.completeExceptionally(IOException("Peer unreachable"))
                    }
                }
            } catch (_: Exception) {
                serverAlive = false
                runCatching { socket.close() }
                login.completeExceptionally(IOException("Soulseek connection failed"))
                transfer?.let { failTransfer(it.candidate.user, "Soulseek connection closed") }
            }
        }
        withFlacTimeout(12000, "sign-in") { login.await() }
        serverAlive = !socket.isClosed
        sendServer(2, WireWriter().int(listening.localPort))
        sendServer(28, WireWriter().int(2))
        sendServer(35, WireWriter().int(0).int(0))
        sendServer(100, WireWriter().byte(0)) // Do not accept distributed-network children.
        sendServer(71, WireWriter().byte(0))
    }

    suspend fun search(recording: FlacTarget): List<FlacCandidate> {
        stopSearch()
        searchCollection?.join()
        target = recording
        searchStats = FlacSearchStats()
        fallbackSearchSent.set(false)
        candidates.clear()
        while (candidateUpdates.tryReceive().isSuccess) { }
        val first = CompletableDeferred<List<FlacCandidate>>()
        val finished = CompletableDeferred<Unit>().also { searchFinished = it }
        searchCollection = scope.launch {
            try {
                coroutineScope {
                    val searchStartedAt = System.nanoTime()
                    val queries = flacSearchQueries(recording)
                    searchToken = tokens.incrementAndGet()
                    activeSearchTokens.add(searchToken)
                    while (results.tryReceive().isSuccess) { }
                    sendServer(26, WireWriter().int(searchToken).string(queries.first()))
                    val broaden = launch {
                        val fallbackDelay = minOf(searchWindowMs, 1500L)
                        delay(fallbackDelay)
                        if (!first.isCompleted) startFallbackSearch(recording)
                        delay(searchWindowMs - fallbackDelay)
                        if (candidates.isNotEmpty()) first.complete(orderedCandidates(recording))
                    }
                    withTimeoutOrNull(backupSearchWindowMs) {
                        var received = 0
                        while (received < 256) {
                            val (generation, candidate) = results.receive()
                            if (generation !== finished) continue
                            received++
                            candidates.compute(candidate.user) { _, old ->
                                if (old == null || candidateOrder(recording).compare(candidate, old) < 0) candidate else old
                            }
                            // Keep bounded backups, but allow a later better uploader to replace one.
                            orderedCandidates(recording).drop(32).forEach { candidates.remove(it.user, it) }
                            candidateUpdates.trySend(Unit)
                            // Start quickly, while the same bounded search still gathers backups.
                            if ((recordingMatches(candidate.filename, recording) && candidate.duration > 0 &&
                                kotlin.math.abs(candidate.duration * 1000L - recording.durationMs) <= 2500) ||
                                (System.nanoTime() - searchStartedAt) / 1_000_000 >= searchWindowMs) {
                                first.complete(orderedCandidates(recording))
                            }
                        }
                    }
                    broaden.cancel()
                }
                lastSearchSummary = when {
                    searchStats.sawUnavailable && candidates.isEmpty() -> "matching FLAC peers have no free upload slot"
                    searchStats.sawFlac && !searchStats.sawMatching -> "FLAC results did not match this recording"
                    else -> "no matching public FLAC found"
                }
                first.complete(orderedCandidates(recording))
                diagnostic("Search finished; available uploaders=${candidates.size}; " +
                    "matchingReplies=${searchStats.matchingReplies.get()}; freeSlotReplies=${searchStats.freeSlotReplies.get()}; " +
                    "freeWithQueueReplies=${searchStats.freeSlotWithQueueReplies.get()}; noFreeSlotReplies=${searchStats.noFreeSlotReplies.get()}")
            } catch (e: Exception) {
                first.completeExceptionally(e)
            } finally {
                if (searchFinished === finished) {
                    searchToken = 0
                    target = null
                    activeSearchTokens.clear()
                    closeUnusedSearchPeers()
                }
                finished.complete(Unit)
                candidateUpdates.trySend(Unit)
            }
        }
        try { return first.await() }
        catch (e: CancellationException) { stopSearch(); throw e }
    }

    suspend fun nextCandidate(recording: FlacTarget, triedUsers: Set<String>): FlacCandidate? {
        while (true) {
            orderedCandidates(recording).firstOrNull { it.user !in triedUsers }?.let { return it }
            if (searchFinished.isCompleted) return null
            // A failed first uploader is a reason to broaden now, while the
            // original query still collects replies within its existing deadline.
            if (triedUsers.isNotEmpty()) startFallbackSearch(recording)
            select<Unit> {
                candidateUpdates.onReceive { }
                searchFinished.onAwait { }
            }
        }
    }

    private fun candidateOrder(recording: FlacTarget) = compareByDescending<FlacCandidate> {
        recordingMatches(it.filename, recording) && it.duration > 0 &&
            kotlin.math.abs(it.duration * 1000L - recording.durationMs) <= 2500
    }.thenBy { it.queueLength > 0 }.thenByDescending { it.speed }.thenBy { it.size }

    private fun orderedCandidates(recording: FlacTarget) = candidates.values.sortedWith(candidateOrder(recording))

    private fun startFallbackSearch(recording: FlacTarget) {
        if (target != recording || searchFinished.isCompleted || searchCollection?.isActive != true) return
        val query = flacSearchQueries(recording).getOrNull(1) ?: return
        if (!fallbackSearchSent.compareAndSet(false, true)) return
        val token = tokens.incrementAndGet()
        activeSearchTokens.add(token)
        sendServer(26, WireWriter().int(token).string(query))
        diagnostic("Starting title fallback search")
    }

    fun hasFasterCandidate(recording: FlacTarget, triedUsers: Set<String>, bytesPerSecond: Double): Boolean =
        orderedCandidates(recording).firstOrNull { it.user !in triedUsers }?.speed?.let { it > bytesPerSecond * 2 } == true

    fun stopSearch() {
        searchCollection?.cancel()
        searchToken = 0
        target = null
        activeSearchTokens.clear()
        closeUnusedSearchPeers()
    }

    private fun closeUnusedSearchPeers() = synchronized(peerLock) {
        val activeSocket = transfer?.controlSocket
        (peers.values + retainedSearchPeers.keys).distinct().forEach { socket ->
            if (activeSocket !== socket) closeSearchPeer(socket)
        }
    }

    private fun closeSearchPeer(socket: Socket) {
        retainedSearchPeers.remove(socket)
        peers.entries.removeIf { it.value === socket }
        runCatching { socket.close() }
    }

    private fun retainSearchPeer(socket: Socket, recording: FlacTarget,
        best: FlacCandidate?): Boolean = synchronized(peerLock) {
        if (best != null) retainedSearchPeers[socket] = best
        val active = transfer?.controlSocket
        // Two useful sockets keep the first request quick; other replies release their slots.
        val idle = retainedSearchPeers.entries.filter { it.key !== active }
            .sortedWith { a, b -> candidateOrder(recording).compare(a.value, b.value) }
        idle.drop(2).forEach { closeSearchPeer(it.key) }
        if (active !== socket && socket !in retainedSearchPeers) {
            closeSearchPeer(socket)
            return@synchronized false
        }
        true
    }

    suspend fun download(candidate: FlacCandidate, file: File): FlacStreamBuffer {
        // Search sockets can close just as we request a file. Reconnect once, but
        // never retry a queue, explicit rejection, or an upload already accepted.
        try {
            return requestDownload(candidate, file)
        } catch (e: FlacPeerException) {
            if (e.reason != "Peer connection closed before acceptance") throw e
            diagnostic("Reconnecting once after the request connection closed")
            peers.remove(candidate.user)?.close()
            return requestDownload(candidate, file)
        }
    }

    private suspend fun requestDownload(candidate: FlacCandidate, file: File): FlacStreamBuffer {
        val pending = PendingTransfer(candidate, FlacStreamBuffer(file, candidate.size))
        file.createNewFile()
        val existingPeer = synchronized(peerLock) {
            transfer = pending
            peers[candidate.user]?.takeUnless { it.isClosed }?.also { pending.controlSocket = it }
        }
        diagnostic("Requesting FLAC; bytes=${candidate.size}")
        try {
            val peer = existingPeer ?: openPeer(candidate.user, "P")
            synchronized(peerLock) { pending.controlSocket = peer }
            peer.soTimeout = 45000
            try {
                peer.getOutputStream().sendFrame(43, WireWriter().string(candidate.filename))
            } catch (e: IOException) {
                // A failed write on a stale search socket needs the same bounded reconnect.
                peers.remove(candidate.user, peer)
                peer.close()
                throw FlacPeerException("Peer connection closed before acceptance")
            }
            withFlacTimeout(responseTimeoutMs, "peer response") { pending.accepted.await() }
            withFlacTimeout(firstByteTimeoutMs, "file connection") { pending.started.await() }
        } catch (e: Exception) {
            if (e !is CancellationException) diagnostic("Transfer start failed: " +
                if (e is FlacTimeoutException) "${e.operation} timed out" else e.javaClass.simpleName)
            pending.buffer.fail("Peer did not start promptly")
            pending.fallbackConnection?.cancel()
            pending.fileSockets.forEach { runCatching { it.close() } }
            transfer = null
            file.delete()
            throw e
        }
        return pending.buffer
    }

    suspend fun awaitFinished() { transfer?.finished?.await() }

    fun cancelTransfer() {
        transfer?.let {
            it.fallbackConnection?.cancel()
            it.fileSockets.forEach { socket -> runCatching { socket.close() } }
            it.buffer.fail("Transfer cancelled")
            it.accepted.cancel()
            it.started.cancel()
            it.finished.cancel()
        }
        transfer = null
        // Backup search sockets belong to the current song, not to the failed file attempt.
    }

    private suspend fun openPeer(user: String, type: String): Socket {
        val address = CompletableDeferred<Pair<InetAddress, Int>>()
        addresses[user] = address
        sendServer(3, WireWriter().string(user))
        val (ip, port) = try {
            withFlacTimeout(10000, "peer address") { address.await() }
        } finally { addresses.remove(user, address) }
        val socket = try { dial(ip, port) } catch (_: IOException) {
            val token = tokens.incrementAndGet()
            val pending = CompletableDeferred<Socket>()
            pendingPeers[token] = pending
            indirect[token] = user to type
            try {
                sendServer(18, WireWriter().int(token).string(user).string(type))
                return withFlacTimeout(15000, "peer connection") { pending.await() }
            } finally { pendingPeers.remove(token); indirect.remove(token) }
        }
        socket.getOutputStream().sendFrame(1, WireWriter().string(username).string(type).int(0), init = true)
        if (type == "P") {
            synchronized(peerLock) {
                peers[user] = socket
                transfer?.takeIf { it.candidate.user == user }?.controlSocket = socket
            }
            scope.launch {
                try { handlePeer(socket, user, type) } catch (_: Exception) { }
                finally { peers.remove(user, socket); sockets.remove(socket); socket.close() }
            }
        }
        return socket
    }

    private suspend fun incoming(socket: Socket) {
        socket.soTimeout = 6000
        val (code, body) = socket.getInputStream().readFrame(init = true)
        val identity = when (code) {
            1 -> { val user = body.string(); val type = body.string(); body.int(); user to type }
            0 -> {
                val token = body.int()
                val peer = indirect.remove(token) ?: return
                pendingPeers.remove(token)?.complete(socket)
                peer
            }
            else -> return
        }
        handlePeer(socket, identity.first, identity.second)
    }

    private suspend fun handlePeer(socket: Socket, user: String, type: String) {
        if (type == "F") { receiveFile(socket, user); return }
        if (type != "P") return
        // An uploader request must not wait behind unrelated search replies.
        val searchConnection = transfer?.candidate?.user != user
        if (searchConnection && !controlPeerLimit.tryAcquire()) return
        synchronized(peerLock) {
            val pending = transfer?.takeIf { it.candidate.user == user }
            if (pending?.controlSocket == null) {
                peers[user] = socket
                pending?.controlSocket = socket
            }
        }
        socket.soTimeout = if (transfer?.candidate?.user == user) 45000 else 6000
        try {
            while (!closed) {
                val (code, body) = socket.getInputStream().readFrame()
                when (code) {
                    9 -> {
                        val recording = target
                        if (recording == null) {
                            if (transfer?.candidate?.user != user) return
                            continue
                        }
                        val generation = searchFinished
                        val reply = parseSearchReply(body.remaining().inputStream(), searchToken, recording, searchStats,
                            activeSearchTokens.toSet()).filter { it.user == user }
                        // One uploader may send dozens of copies. Rank once and count the reply once.
                        val best = reply.minWithOrNull(candidateOrder(recording))
                        // Register retention before waking the requester, so cleanup cannot close its socket.
                        val retained = retainSearchPeer(socket, recording, best)
                        if (best != null) results.trySend(generation to best)
                        if (!retained) return
                        socket.soTimeout = 45000
                    }
                    40 -> {
                        val direction = body.int(); val token = body.int(); val filename = body.string()
                        val size = if (direction == 1) body.long() else 0
                        val pending = transfer
                        val matches = pending != null && direction == 1 && pending.candidate.user == user &&
                            pending.candidate.filename == filename
                        val invalidSize = matches && size !in 42..MAX_FLAC_BYTES
                        var firstOffer = false
                        val allowed = pending != null && synchronized(pending) {
                            if (!matches || invalidSize || transfer !== pending || pending.failed.get()) false
                            else if (pending.token == null) {
                                // Search metadata may describe an older copy. The upload offer
                                // supplies its actual length; recording tags are still verified.
                                if (pending.buffer.totalSize != size) {
                                    diagnostic("Upload size updated; searchBytes=${pending.candidate.size}; offeredBytes=$size")
                                    pending.buffer = FlacStreamBuffer(pending.buffer.file, size)
                                }
                                pending.token = token
                                firstOffer = true
                                true
                            } else pending.token == token && pending.buffer.totalSize == size
                        }
                        val response = WireWriter().int(token).byte(if (allowed) 1 else 0)
                        if (!allowed) response.string(if (invalidSize) "Invalid FLAC file size" else "Cancelled")
                        socket.getOutputStream().sendFrame(41, response)
                        if (invalidSize) {
                            failTransfer(user, "Peer offered an invalid FLAC size", pending)
                        } else if (allowed && firstOffer) {
                            diagnostic("Upload accepted; code=40; bytes=$size")
                            pending!!.accepted.complete(Unit)
                            pending.fallbackConnection = scope.launch {
                                // Prefer the uploader's connection. Some clients need us to
                                // initiate it; mobile networks often reject inbound sockets.
                                delay(fileConnectFallbackMs)
                                if (transfer !== pending || pending.connected.isCompleted || pending.failed.get()) return@launch
                                var audio: Socket? = null
                                try {
                                    val (ip, port) = peerAddress(user)
                                    if (transfer !== pending || pending.connected.isCompleted || pending.failed.get()) return@launch
                                    diagnostic("Opening outbound file connection")
                                    audio = dial(ip, port) { registerFileRoute(pending, it) }
                                    audio.getOutputStream().sendFrame(1,
                                        WireWriter().string(username).string("F").int(0), init = true)
                                    audio.getOutputStream().write(WireWriter().int(token).bytes())
                                    audio.getOutputStream().flush()
                                    receiveFile(audio, user, token)
                                } catch (e: Exception) {
                                    if (e !is CancellationException) diagnostic("Outbound file route failed: ${socketFailure(e)}")
                                } finally { audio?.let { pending.fileSockets.remove(it); sockets.remove(it); it.close() } }
                            }
                        } else if (allowed) {
                            diagnostic("Repeated upload offer acknowledged")
                        } else if (pending != null && pending.candidate.user == user) {
                            diagnostic("Upload offer declined locally; direction=$direction; " +
                                "fileMatches=${pending.candidate.filename == filename}; sizeMatches=${pending.candidate.size == size}; " +
                                "alreadyAccepted=${pending.token != null}")
                        }
                    }
                    44 -> {
                        val filename = body.string(); val place = body.int().toLong() and 0xffffffffL
                        val pending = transfer?.takeIf { it.candidate.filename == filename && it.candidate.user == user }
                        if (place > 0 && pending != null) {
                            if (pending.token == null) failTransfer(user, "Peer is queued", pending)
                            else diagnostic("Late queue update ignored after upload acceptance")
                        }
                    }
                    50, 46 -> {
                        val failure = readFlacPeerFailure(code, body, listOf(password, username))
                        transfer?.takeIf { it.candidate.filename == failure.filename && it.candidate.user == user }?.let {
                            if (it.buffer.complete) {
                                diagnostic("Late peer failure ignored after completion; code=$code")
                            } else if (code == 46 && it.competingFileRoutes.get() && it.receiving.get()) {
                                // UploadFailed names the file, not the socket. After racing routes,
                                // the selected socket's data/EOF determines whether it really failed.
                                diagnostic("Failed competing route reported; keeping the active file reader")
                            } else {
                                diagnostic("Peer response; code=$code; ${failure.reason}")
                                failTransfer(user, failure.reason)
                            }
                        }
                    }
                    4 -> { // Share list request: nothing on the device is exposed.
                        val empty = WireWriter().int(0).int(0).int(0).bytes()
                        val output = java.io.ByteArrayOutputStream()
                        java.util.zip.DeflaterOutputStream(output).use { it.write(empty) }
                        socket.getOutputStream().sendFrame(5, WireWriter().raw(output.toByteArray()))
                    }
                }
            }
        } finally {
            val pendingToFail = synchronized(peerLock) {
                retainedSearchPeers.remove(socket)
                val wasCurrent = peers.remove(user, socket)
                transfer?.takeIf { wasCurrent && it.controlSocket === socket && it.token == null }
            }
            if (searchConnection) controlPeerLimit.release()
            // A replacement connection from this uploader may already be active.
            // Closing its old search connection must not fail the new request.
            if (pendingToFail != null) failTransfer(user, "Peer connection closed before acceptance", pendingToFail)
        }
    }

    private fun failTransfer(user: String, reason: String, expected: PendingTransfer? = null) {
        transfer?.takeIf { (expected == null || it === expected) && it.candidate.user == user &&
            !it.buffer.complete && it.failed.compareAndSet(false, true) }?.let {
            diagnostic("Transfer failed: $reason; accepted=${it.token != null}; received=${it.buffer.available}/${it.buffer.totalSize}")
            it.buffer.fail(reason)
            it.accepted.completeExceptionally(FlacPeerException(reason))
            it.started.completeExceptionally(FlacPeerException(reason))
            it.finished.completeExceptionally(FlacPeerException(reason))
        }
    }

    private fun receiveFile(socket: Socket, user: String, offeredToken: Int? = null) {
        val expected = transfer?.takeIf { it.candidate.user == user && !it.failed.get() } ?: return
        registerFileRoute(expected, socket)
        socket.soTimeout = firstByteTimeoutMs.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
        var stream = expected.buffer
        var ownsWriter = false
        try {
            val ticket = offeredToken ?: WireReader(socket.getInputStream()).int()
            if (transfer !== expected || expected.token != ticket || expected.failed.get() || expected.receiving.get()) return
            stream = expected.buffer
            diagnostic("File route connected; waiting for FLAC bytes")
            socket.getOutputStream().write(WireWriter().long(0).bytes())
            socket.getOutputStream().flush()
            val chunk = ByteArray(65536)
            val firstCount = socket.getInputStream().read(chunk, 0, minOf(chunk.size.toLong(), stream.totalSize).toInt())
            if (firstCount <= 0) throw java.io.EOFException("File route ended before audio arrived")
            if (transfer !== expected || expected.failed.get() || !expected.receiving.compareAndSet(false, true)) return
            ownsWriter = true
            expected.connected.complete(Unit)
            // A TCP handshake alone does not prove this route can supply the file.
            // Keep alternatives alive until one delivers bytes; only that route writes.
            expected.fileSockets.filter { it !== socket }.forEach { runCatching { it.close() } }
            socket.soTimeout = 15000
            diagnostic("File transfer started; route=${if (offeredToken == null) "uploader" else "outbound"}; firstBytes=$firstCount")
            FileOutputStream(stream.file).use { output ->
                output.write(chunk, 0, firstCount)
                output.flush()
                var bytes = firstCount.toLong()
                stream.publish(bytes, bytes == stream.totalSize)
                expected.started.complete(Unit)
                var lastPublish = bytes
                var lastPublishedAt = System.nanoTime()
                while (bytes < stream.totalSize && !closed && transfer === expected) {
                    val count = socket.getInputStream().read(chunk, 0, minOf(chunk.size.toLong(), stream.totalSize - bytes).toInt())
                    if (count < 0) throw java.io.EOFException("Incomplete FLAC transfer")
                    output.write(chunk, 0, count)
                    bytes += count
                    // Progress comes from actual writes, never an idle timer.
                    val now = System.nanoTime()
                    if (bytes - lastPublish >= 262144 ||
                        now - lastPublishedAt >= 1_000_000_000L || bytes == stream.totalSize) {
                        output.flush()
                        stream.publish(bytes, bytes == stream.totalSize)
                        lastPublish = bytes
                        lastPublishedAt = now
                    }
                }
                check(bytes == stream.totalSize)
            }
            expected.finished.complete(Unit)
            diagnostic("FLAC transfer complete; bytes=${stream.totalSize}")
        } catch (e: Exception) {
            if (ownsWriter) {
                diagnostic("File read failed: ${socketFailure(e)}; received=${stream.available}/${stream.totalSize}")
                failTransfer(user, "FLAC transfer stopped receiving data", expected)
            } else if (transfer === expected && !expected.receiving.get() && !expected.failed.get()) {
                // Another direct or server-arranged route can still deliver the first byte.
                diagnostic("File route failed before audio; ${socketFailure(e)}; keeping other routes available")
            }
        }
    }

    private fun registerFileRoute(pending: PendingTransfer, socket: Socket) {
        pending.fileSockets.add(socket)
        if (pending.fileSockets.size > 1) pending.competingFileRoutes.set(true)
    }

    private suspend fun peerAddress(user: String): Pair<InetAddress, Int> {
        endpoints[user]?.let { return it }
        val address = CompletableDeferred<Pair<InetAddress, Int>>()
        addresses[user] = address
        try {
            sendServer(3, WireWriter().string(user))
            return withFlacTimeout(10000, "peer address") { address.await() }
        } finally { addresses.remove(user, address) }
    }

    private fun socketFailure(e: Exception) = when (e) {
        is java.net.SocketTimeoutException -> "socket timed out"
        is java.io.EOFException -> "connection ended"
        is java.net.ConnectException -> "connection refused"
        is java.net.SocketException -> "connection closed"
        else -> "connection or protocol error"
    }

    private fun dial(ip: InetAddress, port: Int, onCreated: (Socket) -> Unit = {}): Socket {
        require(port in 1..65535)
        return Socket().also { socket ->
            sockets.add(socket)
            onCreated(socket)
            try {
                socket.connect(InetSocketAddress(ip, port), 8000)
                socket.soTimeout = 6000
                socket.keepAlive = true
            } catch (e: Exception) { sockets.remove(socket); socket.close(); throw e }
        }
    }

    private fun sendServer(code: Int, body: WireWriter = WireWriter()) =
        (server ?: throw IOException("Not connected")).getOutputStream().sendFrame(code, body)

    private fun ipAddress(value: Int): InetAddress = InetAddress.getByAddress(byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte()))

    override fun close() {
        closed = true
        serverAlive = false
        stopSearch()
        cancelTransfer()
        runCatching { listener?.close() }
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        peers.clear()
        addresses.values.forEach { it.cancel() }
        addresses.clear()
        pendingPeers.values.forEach { it.cancel() }
        pendingPeers.clear()
        scope.cancel()
    }
}
