package com.abn3li.telemusic.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.abn3li.telemusic.data.quality.FlacStreamBuffer
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

internal object FlacStreamRegistry {
    val streams = ConcurrentHashMap<String, FlacStreamBuffer>()
}

/** Custom scheme prevents DefaultDataSource treating a growing cache as a finished file. */
internal class FlacDataSource : BaseDataSource(true) {
    private var source: FlacStreamBuffer? = null
    private var reader: RandomAccessFile? = null
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L
    private var opened = false
    private var fileLease: java.io.Closeable? = null

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val buffer = FlacStreamRegistry.streams[dataSpec.uri.lastPathSegment] ?: throw IOException("FLAC cache expired")
        buffer.checkFailure()
        if (dataSpec.position > buffer.totalSize) throw IOException("FLAC position outside file")
        val lease = buffer.retainFile(forDownload = false)
        try { reader = RandomAccessFile(buffer.file, "r") }
        catch (e: Exception) { lease.close(); throw e }
        fileLease = lease
        source = buffer
        uri = dataSpec.uri
        position = dataSpec.position
        remaining = (buffer.totalSize - position).let {
            if (dataSpec.length == C.LENGTH_UNSET.toLong()) it else minOf(it, dataSpec.length)
        }
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val count = source!!.read(reader!!, position, buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (count > 0) { position += count; remaining -= count; bytesTransferred(count) }
        return count
    }

    override fun getUri(): Uri? = uri
    override fun close() {
        try { reader?.close() } finally { fileLease?.close(); fileLease = null }
        reader = null
        source = null
        uri = null
        if (opened) { opened = false; transferEnded() }
    }
}

internal class FlacRoutingDataSource(private val normal: DataSource) : DataSource {
    private val flac = FlacDataSource()
    private var active: DataSource = normal
    override fun open(dataSpec: DataSpec): Long {
        active = if (dataSpec.uri.scheme == "quality") flac else normal
        return active.open(dataSpec)
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = active.read(buffer, offset, length)
    override fun getUri(): Uri? = active.uri
    override fun getResponseHeaders(): Map<String, List<String>> = active.responseHeaders
    override fun addTransferListener(transferListener: TransferListener) {
        normal.addTransferListener(transferListener)
        flac.addTransferListener(transferListener)
    }
    override fun close() = active.close()
}
