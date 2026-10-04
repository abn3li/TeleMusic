package com.abn3li.telemusic.data.quality

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Readers sleep on a condition until a writer supplies bytes; partial EOF is never song EOF. */
internal class FlacStreamBuffer(val file: File, val totalSize: Long) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var failure: IOException? = null
    @Volatile var available = 0L; private set
    @Volatile var complete = false; private set
    @Volatile var firstByteAtNanos = 0L; private set
    val updates = Channel<Unit>(Channel.CONFLATED)
    private val finished = CompletableDeferred<Unit>()
    private val received = MutableStateFlow(0L)
    val progress: StateFlow<Long> = received
    private val receivedStats = MutableStateFlow(FlacTransferProgress())
    val transferProgress: StateFlow<FlacTransferProgress> = receivedStats
    private var fileUsers = 0
    private var deleteRequested = false
    val shouldFinishDownload: Boolean get() = lock.withLock { fileUsers > 0 && failure == null && !complete }

    fun retainFile(): Closeable = lock.withLock {
        check(!deleteRequested) { "FLAC cache expired" }
        fileUsers++
        var released = false
        Closeable {
            lock.withLock {
                if (!released) {
                    released = true
                    fileUsers--
                    if (deleteRequested && fileUsers == 0) file.delete()
                }
            }
        }
    }

    fun deleteWhenUnused() = lock.withLock {
        deleteRequested = true
        if (fileUsers == 0) file.delete()
    }

    suspend fun awaitComplete() { finished.await(); checkFailure() }

    fun publish(size: Long, done: Boolean = false) = lock.withLock {
        require(size in available..totalSize)
        if (size > 0 && firstByteAtNanos == 0L) firstByteAtNanos = System.nanoTime()
        available = size
        complete = done && size == totalSize
        received.value = size
        receivedStats.value = FlacTransferProgress(size,
            flacTransferRate(size, if (firstByteAtNanos == 0L) 0 else (System.nanoTime() - firstByteAtNanos) / 1_000_000))
        if (complete) finished.complete(Unit)
        changed.signalAll()
        updates.trySend(Unit)
    }

    fun fail(message: String) = lock.withLock {
        if (!complete) {
            failure = IOException(message)
            finished.completeExceptionally(failure!!)
        }
        changed.signalAll()
        updates.trySend(Unit)
    }

    fun checkFailure() = lock.withLock { failure?.let { throw it } }

    fun prefix(): ByteArray = RandomAccessFile(file, "r").use { reader ->
        ByteArray(minOf(available, 4L * 1024 * 1024).toInt()).also { reader.readFully(it) }
    }

    fun read(reader: RandomAccessFile, position: Long, bytes: ByteArray, offset: Int, length: Int): Int = lock.withLock {
        var nanos = TimeUnit.SECONDS.toNanos(3)
        while (available <= position && !complete && failure == null) {
            if (nanos <= 0) throw IOException("FLAC peer stopped supplying audio")
            try { nanos = changed.awaitNanos(nanos) }
            catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw java.io.InterruptedIOException("FLAC read cancelled")
            }
        }
        failure?.let { throw it }
        if (position >= totalSize && complete) return -1
        val count = minOf(length.toLong(), available - position).toInt()
        if (count <= 0) throw IOException("FLAC bytes unavailable")
        reader.seek(position)
        reader.readFully(bytes, offset, count)
        count
    }
}
