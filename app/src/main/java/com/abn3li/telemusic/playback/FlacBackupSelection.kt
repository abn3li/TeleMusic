package com.abn3li.telemusic.playback

import com.abn3li.telemusic.data.quality.FlacCandidate
import com.abn3li.telemusic.data.quality.FlacTarget
import com.abn3li.telemusic.data.quality.recordingMatches

private fun strongFlacBackup(candidate: FlacCandidate, target: FlacTarget): Boolean =
    recordingMatches(candidate.filename, target) && candidate.duration > 0 &&
        kotlin.math.abs(candidate.duration * 1000L - target.durationMs) <= 2500

/** A ranking estimate only. The existing verified frame coverage still owns the switch. */
internal fun estimatedFlacBufferSeconds(candidate: FlacCandidate, target: FlacTarget,
    positionMs: Long, receivedBytes: Long = 0, bufferedUntilMs: Long? = null,
    bytesPerSecond: Double = candidate.speed.toDouble()): Double {
    if (receivedBytes >= candidate.size) return 0.0
    if (bytesPerSecond <= 0 || target.durationMs <= 0) return Double.POSITIVE_INFINITY
    val consumed = candidate.size * 1000.0 / target.durationMs
    val neededUntil = minOf(target.durationMs, positionMs.coerceAtLeast(0) + 15000)
    val coverageBytes = if (bufferedUntilMs != null)
        consumed * (neededUntil - bufferedUntilMs).coerceAtLeast(0) / 1000.0
    else (consumed * neededUntil / 1000.0 - receivedBytes).coerceAtLeast(0.0)
    val floorBytes = (5L * 1024 * 1024 - receivedBytes).coerceAtLeast(0)
    // Original playback advances while this file catches up. A large FLAC can need
    // more time than a smaller copy even when its uploader advertises more speed.
    val catchUp = if (bytesPerSecond > consumed * 1.2)
        coverageBytes / (bytesPerSecond - consumed) else Double.POSITIVE_INFINITY
    return minOf((candidate.size - receivedBytes) / bytesPerSecond,
        maxOf(floorBytes / bytesPerSecond, catchUp))
}

internal fun bestFlacBufferCandidate(candidates: List<FlacCandidate>, target: FlacTarget,
    positionMs: Long): FlacCandidate? = candidates.minWithOrNull(
        compareByDescending<FlacCandidate> { strongFlacBackup(it, target) }
            .thenBy { estimatedFlacBufferSeconds(it, target, positionMs) }
            .thenBy { it.queueLength > 0 }.thenByDescending { it.speed }.thenBy { it.size })

internal fun replaceWaitingFlacPeer(waiting: FlacCandidate, replacement: FlacCandidate,
    target: FlacTarget, positionMs: Long, waitedMs: Long, receivedBytes: Long): Boolean {
    // Never throw away a partial download merely because another speed looks better.
    if (receivedBytes > 0 || waitedMs < 3000) return false
    if (strongFlacBackup(waiting, target) && !strongFlacBackup(replacement, target)) return false
    if (replacement.queueLength > 0 && waiting.queueLength == 0L) return false
    if (replacement.speed < maxOf(1024L * 1024, waiting.speed * 2)) return false
    val next = estimatedFlacBufferSeconds(replacement, target, positionMs) + 1.5
    val current = estimatedFlacBufferSeconds(waiting, target, positionMs) + waitedMs / 1000.0
    return next < current
}
