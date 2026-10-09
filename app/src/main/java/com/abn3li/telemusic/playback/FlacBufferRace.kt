package com.abn3li.telemusic.playback

import com.abn3li.telemusic.data.quality.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import java.io.File
import java.util.UUID

internal data class ReadyFlacBuffer(val candidate: FlacCandidate, val file: File,
    val buffer: FlacStreamBuffer, val header: FlacHeader)

private class FlacAttempt(val candidate: FlacCandidate, val file: File) {
    val startedAtNanos = System.nanoTime()
    var job: Job? = null
    var buffer: FlacStreamBuffer? = null
    var header: FlacHeader? = null
    var canStartBackup = false
    var checkedThreeMb = false
    var betterPeerCheckScheduled = false
}

private sealed interface FlacRaceEvent {
    data class Candidate(val value: FlacCandidate?) : FlacRaceEvent
    data class Progress(val attempt: FlacAttempt, val buffer: FlacStreamBuffer,
        val header: FlacHeader?) : FlacRaceEvent
    data class Failed(val attempt: FlacAttempt, val reason: String) : FlacRaceEvent
    data class Backup(val user: String) : FlacRaceEvent
    data object CandidatesChanged : FlacRaceEvent
}

/** Two independent files may compete; only a verified playable buffer can own playback. */
// How often a transfer's progress reaches the main thread (see the download loop in start()).
private const val PROGRESS_INTERVAL_MS = 120L

internal suspend fun awaitReadyFlacBuffer(session: PeerFlacClient, target: FlacTarget, directory: File,
    position: () -> Long, report: (FlacUpgradeStage, String, FlacTransferInfo?) -> Unit): ReadyFlacBuffer = coroutineScope {
    val events = Channel<FlacRaceEvent>(Channel.BUFFERED)
    val raceContext = currentCoroutineContext()
    val active = linkedMapOf<String, FlacAttempt>()
    val attempts = mutableListOf<FlacAttempt>()
    val tried = mutableSetOf<String>()
    val betterPeerChecks = mutableListOf<Job>()
    var picker: Job? = null
    var hedge: Job? = null
    var noCandidates = false
    var winner: ReadyFlacBuffer? = null
    var failureReason = "Available peers could not supply FLAC."

    fun requestCandidate() {
        if (picker != null || noCandidates || tried.size >= 6 || active.size >= 2) return
        val excluded = tried.toSet()
        picker = launch(Dispatchers.IO) {
            try {
                events.send(FlacRaceEvent.Candidate(session.nextCandidate(target, excluded)))
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                // Losing the candidate lookup must not discard an already progressing download.
                events.send(FlacRaceEvent.Candidate(null))
            }
        }
    }

    fun start(candidate: FlacCandidate) {
        check(active.size < 2)
        val attempt = FlacAttempt(candidate, File(directory, UUID.randomUUID().toString() + ".flac"))
        tried.add(candidate.user)
        active[candidate.user] = attempt
        attempts.add(attempt)
        if (active.values.none { it.buffer != null })
            report(FlacUpgradeStage.REQUESTING, "Requesting FLAC from a matching uploader.", null)
        attempt.job = launch(Dispatchers.IO) {
            try {
                val buffer = session.download(candidate, attempt.file)
                events.send(FlacRaceEvent.Progress(attempt, buffer, null))
                withFlacTimeout(180000, "FLAC download") {
                    var header: FlacHeader? = null
                    var lastProgressAt = 0L
                    var held = false
                    while (true) {
                        // A held-back progress is still delivered after a quiet interval, so a
                        // transfer that pauses right after it doesn't leave its last bytes unseen.
                        if (held && kotlinx.coroutines.withTimeoutOrNull(PROGRESS_INTERVAL_MS) { buffer.updates.receive() } == null) {
                            held = false
                            lastProgressAt = System.nanoTime()
                            header?.let { events.send(FlacRaceEvent.Progress(attempt, buffer, it)) }
                            continue
                        }
                        if (!held) withFlacTimeout(20000, "FLAC data stalled") { buffer.updates.receive() }
                        buffer.checkFailure()
                        if (header == null) header = readFlacHeader(buffer.prefix())
                        val verified = header
                        if (verified == null) {
                            check(!buffer.complete) { "Incomplete FLAC metadata" }
                            continue
                        }
                        if (!verifiedRecording(verified, target, candidate.filename)) {
                            throw FlacPeerException(if (kotlin.math.abs(verified.durationMs - target.durationMs) > 2500)
                                "FLAC timing differs · unsafe to switch" else "FLAC recording did not match")
                        }
                        // At most one progress event per PROGRESS_INTERVAL_MS (the first and the
                        // last always): every data packet used to reach the main thread - hundreds
                        // a second - and each re-checked readiness and redrew the quality status,
                        // which stalled the screen for half a second as the bytes started.
                        val now = System.nanoTime()
                        if (lastProgressAt == 0L || buffer.complete ||
                            now - lastProgressAt >= PROGRESS_INTERVAL_MS * 1_000_000L) {
                            lastProgressAt = now
                            held = false
                            events.send(FlacRaceEvent.Progress(attempt, buffer, verified))
                        } else held = true
                        if (buffer.complete) break
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val reason = when (e) {
                    is FlacPeerException -> e.reason
                    is FlacTimeoutException -> e.operation + " timed out"
                    else -> "FLAC transfer interrupted"
                }
                events.send(FlacRaceEvent.Failed(attempt, reason))
            }
        }
        hedge?.cancel()
        hedge = if (active.size == 1) launch {
            // One delayed backup per attempt; packet events handle subsequent speed changes.
            delay(1500)
            events.send(FlacRaceEvent.Backup(candidate.user))
        } else null
    }

    fun slow(attempt: FlacAttempt): Boolean {
        val buffer = attempt.buffer ?: return true
        val header = attempt.header ?: return true
        if (buffer.complete) return false
        val elapsed = (System.nanoTime() - buffer.firstByteAtNanos) / 1_000_000
        if (elapsed < 500) return buffer.available < 1024 * 1024
        val rate = buffer.available * 1000.0 / elapsed.coerceAtLeast(1)
        val consumed = buffer.totalSize * 1000.0 / header.durationMs
        val neededMs = (minOf(header.durationMs, position().coerceAtLeast(0) + 15000) -
            buffer.seekIndex.bufferedUntilMs).coerceAtLeast(0)
        val neededBytes = maxOf((5L * 1024 * 1024 - buffer.available).coerceAtLeast(0).toDouble(),
            neededMs * buffer.totalSize.toDouble() / header.durationMs)
        return rate < consumed * 1.2 || neededBytes / rate.coerceAtLeast(1.0) > 2.0
    }

    fun showProgress() {
        val leader = active.values.filter { it.buffer != null }.maxWithOrNull(
            compareBy<FlacAttempt> { it.buffer!!.seekIndex.bufferedUntilMs }
                .thenBy { it.buffer!!.transferProgress.value.bytesPerSecond }) ?: return
        val buffer = leader.buffer!!
        val header = leader.header
        report(FlacUpgradeStage.BUFFERING, if (active.size == 2)
            "Buffering FLAC while checking a backup uploader." else "Buffering audio around the current position.",
            FlacTransferInfo(buffer.totalSize, buffer.transferProgress,
                header?.sampleRate ?: 0, header?.bitDepth ?: 0, header?.channels ?: 0))
    }

    suspend fun considerBetterBackup() {
        if (picker != null || active.isEmpty() || tried.size >= 6) return
        val currentPosition = position()
        val candidate = bestFlacBufferCandidate(session.availableCandidates(target, tried),
            target, currentPosition) ?: return
        if (active.size == 1) {
            val primary = active.values.single()
            if (!slow(primary)) return
            val buffer = primary.buffer
            val measuredMs = buffer?.let { (System.nanoTime() - it.firstByteAtNanos) / 1_000_000 } ?: 0L
            val rate = if (measuredMs >= 500 && buffer != null)
                buffer.available * 1000.0 / measuredMs else primary.candidate.speed.toDouble()
            val currentEstimate = estimatedFlacBufferSeconds(primary.candidate, target,
                currentPosition, buffer?.available ?: 0,
                buffer?.takeIf { it.seekIndex.isReady }?.seekIndex?.bufferedUntilMs, rate)
            val earlier = (System.nanoTime() - primary.startedAtNanos) / 1_000_000 >= 500 &&
                candidate.speed > rate * 2 &&
                estimatedFlacBufferSeconds(candidate, target, currentPosition) + 1.5 < currentEstimate
            if (!primary.canStartBackup && !earlier) return
            runCatching { android.util.Log.i("FlacTransfer", "Starting better FLAC backup; reportedBytesPerSecond=" + candidate.speed) }
            start(candidate)
            return
        }
        val waiting = active.values.filter {
            replaceWaitingFlacPeer(it.candidate, candidate, target, currentPosition,
                (System.nanoTime() - it.startedAtNanos) / 1_000_000, it.buffer?.available ?: 0)
        }.maxByOrNull {
            estimatedFlacBufferSeconds(it.candidate, target, currentPosition) +
                (System.nanoTime() - it.startedAtNanos) / 1_000_000_000.0
        }
        if (waiting == null) {
            active.values.forEach { attempt ->
                val waitedMs = (System.nanoTime() - attempt.startedAtNanos) / 1_000_000
                if (!attempt.betterPeerCheckScheduled && waitedMs < 3000 &&
                    replaceWaitingFlacPeer(attempt.candidate, candidate, target, currentPosition,
                        3000, attempt.buffer?.available ?: 0)) {
                    attempt.betterPeerCheckScheduled = true
                    // A faster reply can arrive inside the grace period. One check
                    // per attempt keeps it usable even if neither peer sends more data.
                    betterPeerChecks.add(launch {
                        delay(3001 - waitedMs)
                        events.send(FlacRaceEvent.CandidatesChanged)
                    })
                }
            }
            return
        }
        // Close the old zero-byte request before taking its slot. The other peer
        // keeps its connection and every byte, and the two-transfer cap stays intact.
        val released = withContext(NonCancellable + Dispatchers.IO) {
            session.discardWaitingTransfer(waiting.candidate.user)
        }
        if (!released) return
        active.remove(waiting.candidate.user)
        waiting.job?.cancel()
        withContext(NonCancellable + Dispatchers.IO) {
            waiting.job?.join()
            waiting.file.delete()
        }
        currentCoroutineContext().ensureActive()
        position()
        runCatching { android.util.Log.i("FlacTransfer", "Replacing waiting FLAC peer with faster backup; reportedBytesPerSecond=" + candidate.speed) }
        start(candidate)
    }

    val candidateWatcher = launch {
        session.candidateChanges.collect { events.send(FlacRaceEvent.CandidatesChanged) }
    }

    try {
        requestCandidate()
        while (winner == null) {
            position() // The caller also checks that this request still belongs to the current song.
            if (active.isEmpty() && picker == null) throw FlacPeerException(failureReason)
            when (val event = events.receive()) {
                is FlacRaceEvent.Candidate -> {
                    picker = null
                    if (event.value == null) noCandidates = true else {
                        // A waiting picker can wake after a better reply also arrived.
                        val candidate = if (active.isEmpty()) event.value else
                            bestFlacBufferCandidate(session.availableCandidates(target, tried), target,
                                position()) ?: event.value
                        start(candidate)
                        considerBetterBackup()
                    }
                }
                is FlacRaceEvent.Backup -> {
                    active[event.user]?.let {
                        it.canStartBackup = true
                        considerBetterBackup()
                        if (active.size == 1 && slow(it)) requestCandidate()
                    }
                }
                is FlacRaceEvent.Progress -> {
                    val attempt = event.attempt
                    if (active[attempt.candidate.user] !== attempt) continue
                    attempt.buffer = event.buffer
                    attempt.header = event.header
                    val buffer = event.buffer
                    val header = event.header
                    if (!attempt.checkedThreeMb && buffer.available >= 3L * 1024 * 1024) {
                        attempt.checkedThreeMb = true
                        android.util.Log.i("FlacTransfer", "FLAC speed checkpoint; bytesPerSecond=" +
                            buffer.transferProgress.value.bytesPerSecond + "; bufferedMs=" + buffer.seekIndex.bufferedUntilMs)
                    }
                    if (header != null && readyIndexedFlacBuffer(buffer.totalSize, header.durationMs,
                            position(), buffer.available, buffer.complete, buffer.seekIndex.bufferedUntilMs,
                            buffer.seekIndex.isReady, (System.nanoTime() - buffer.firstByteAtNanos) / 1_000_000)) {
                        buffer.checkFailure()
                        winner = ReadyFlacBuffer(attempt.candidate, attempt.file, buffer, header)
                    } else {
                        considerBetterBackup()
                        if (active.size == 1 && attempt.canStartBackup && slow(attempt)) requestCandidate()
                    }
                    showProgress()
                }
                is FlacRaceEvent.Failed -> {
                    val attempt = event.attempt
                    if (active.remove(attempt.candidate.user) !== attempt) continue
                    failureReason = event.reason
                    withContext(Dispatchers.IO) { session.discardTransfer(attempt.candidate.user) }
                    attempt.file.delete()
                    // Failure frees only this peer's slot. The other download keeps all its bytes.
                    requestCandidate()
                    if (active.isEmpty()) report(FlacUpgradeStage.SEARCHING,
                        "Looking for another uploader. " + failureReason, null) else showProgress()
                }
                FlacRaceEvent.CandidatesChanged -> considerBetterBackup()
            }
        }
        winner!!
    } finally {
        // Cancelling the race must close blocked sockets before waiting for worker cleanup.
        withContext(NonCancellable + Dispatchers.IO) {
            hedge?.cancel()
            candidateWatcher.cancel()
            betterPeerChecks.forEach { it.cancel() }
            picker?.cancel()
            attempts.forEach { it.job?.cancel() }
            attempts.filter { it.candidate.user != winner?.candidate?.user }.forEach {
                session.discardTransfer(it.candidate.user)
            }
            picker?.join()
            hedge?.join()
            candidateWatcher.join()
            betterPeerChecks.forEach { it.join() }
            attempts.forEach { it.job?.join() }
            attempts.filter { it.candidate.user != winner?.candidate?.user }.forEach { it.file.delete() }
            if (!raceContext.isActive) winner?.let {
                session.discardTransfer(it.candidate.user)
                it.file.delete()
            }
            events.close()
        }
    }
}
