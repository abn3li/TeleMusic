package com.abn3li.telemusic.data.quality

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import java.io.IOException

internal class FlacTimeoutException(val operation: String) : IOException("$operation timed out")

/** A peer deadline is a failed attempt; cancellation by the owner still stops the request. */
internal suspend fun <T> withFlacTimeout(milliseconds: Long, operation: String, block: suspend () -> T): T =
    try {
        withTimeout(milliseconds) { block() }
    } catch (e: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw FlacTimeoutException(operation)
    }
