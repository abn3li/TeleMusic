package com.abn3li.telemusic.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ForwardingExtractorInput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.flac.FlacExtractor
import com.abn3li.telemusic.data.quality.FlacSeekIndex

@OptIn(UnstableApi::class)
internal class FlacStreamingExtractorsFactory(private val normal: ExtractorsFactory) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> = normal.createExtractors()

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> {
        val index = if (uri.scheme == "quality")
            FlacStreamRegistry.streams[uri.lastPathSegment]?.seekIndex else null
        return if (index?.isReady == true) arrayOf(IndexedFlacExtractor(index))
            else normal.createExtractors(uri, responseHeaders)
    }
}

/** The growing cache has exact frame offsets; the extractor must not probe unavailable bytes. */
@OptIn(UnstableApi::class)
private class IndexedFlacExtractor(private val index: FlacSeekIndex) : Extractor {
    private val delegate = FlacExtractor()
    private var upstream: ExtractorInput? = null
    private var growingInput: ExtractorInput? = null

    private fun growing(source: ExtractorInput): ExtractorInput {
        if (upstream !== source) {
            upstream = source
            growingInput = object : ForwardingExtractorInput(source) {
                override fun getLength(): Long = C.LENGTH_UNSET.toLong()
            }
        }
        return growingInput!!
    }

    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(growing(input))
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
        delegate.read(growing(input), seekPosition)
    override fun seek(position: Long, timeUs: Long) = delegate.seek(position, timeUs)
    override fun release() = delegate.release()

    override fun init(output: ExtractorOutput) {
        delegate.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = output.track(id, type)
            override fun endTracks() = output.endTracks()
            override fun seekMap(seekMap: SeekMap) {
                output.seekMap(object : SeekMap {
                    override fun isSeekable(): Boolean = true
                    override fun getDurationUs(): Long = index.durationUs
                    override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints =
                        SeekMap.SeekPoints(index.seekPoint(timeUs))
                })
            }
        })
    }
}
