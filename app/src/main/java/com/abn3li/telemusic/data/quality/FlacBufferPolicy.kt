package com.abn3li.telemusic.data.quality

/** Compressed size estimates coverage; allow extra room for variable bitrate and seeking.
 * Playback still requires a seek table and sufficient transfer speed, and can fall back if
 * this estimate does not cover the requested position. */
internal fun requiredFlacBufferBytes(totalBytes: Long, durationMs: Long, positionMs: Long): Long {
    require(totalBytes > 0 && durationMs > 0)
    val compressedRate = totalBytes * 1000.0 / durationMs
    return maxOf(5L * 1024 * 1024,
        kotlin.math.ceil(compressedRate * (positionMs.coerceAtLeast(0) / 1000.0 + 15) * 1.25).toLong())
}

internal fun readyFlacBuffer(totalBytes: Long, durationMs: Long, positionMs: Long, receivedBytes: Long,
    complete: Boolean, seekTable: Boolean, transferElapsedMs: Long): Boolean {
    if (complete) return true
    if (!seekTable || transferElapsedMs < 1000) return false
    val compressedRate = totalBytes * 1000.0 / durationMs
    return receivedBytes >= requiredFlacBufferBytes(totalBytes, durationMs, positionMs) &&
        receivedBytes * 1000.0 / transferElapsedMs > compressedRate * 1.2
}

/** Count complete audio frames rather than estimating a timestamp from compressed file size. */
internal fun readyIndexedFlacBuffer(totalBytes: Long, durationMs: Long, positionMs: Long,
    receivedBytes: Long, complete: Boolean, bufferedUntilMs: Long, indexReady: Boolean,
    transferElapsedMs: Long): Boolean {
    if (complete) return true
    if (!indexReady || transferElapsedMs < 1000 || receivedBytes < 5L * 1024 * 1024) return false
    return bufferedUntilMs >= minOf(durationMs, positionMs.coerceAtLeast(0) + 15_000) &&
        receivedBytes * 1000.0 / transferElapsedMs > totalBytes * 1000.0 / durationMs * 1.2
}
