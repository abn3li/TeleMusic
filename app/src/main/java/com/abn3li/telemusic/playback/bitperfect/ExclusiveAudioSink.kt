package com.abn3li.telemusic.playback.bitperfect

import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer

/** Opens exclusive streams for the sink, and takes the device back when the sink lets it go. */
internal interface ExclusiveOpener {
    /** A stream for a song's PCM format, or 0 to play it the normal way. */
    fun open(format: Format): Long

    /** The sink closed its stream after a long pause: the device may be handed back to Android. */
    fun onIdleRelease()
}

/**
 * Plays PCM through a native exclusive stream (USB DAC driven directly, or AAudio exclusive).
 * No mixing, resampling or effects: speed changes are ignored, and volume only applies while
 * the player fades (sleep timer), so normal playback stays untouched.
 *
 * Lossy songs carry encoder delay and padding, which the normal sink trims; this sink trims
 * them too, so lossy songs start and end where they should.
 *
 * After [IDLE_RELEASE_MS] paused, the stream is closed and the device handed back, keeping
 * what was queued but not yet played; play reopens it and carries on from that exact sample.
 */
@UnstableApi
internal class ExclusiveAudioSink(private val opener: ExclusiveOpener) : AudioSink {
    // The native handle is used from the player thread and released from the main thread
    // (DAC unplugged, mode changed, idle release), so every use of it holds this lock.
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private var listener: AudioSink.Listener? = null
    private var handle = 0L
    private var current: Format? = null
    private var rate = 44100
    private var startUs = C.TIME_UNSET
    private var writtenFrames = 0L
    private var playedBase = 0L  // frames played on streams closed by an idle release
    private var frameBytes = 4
    private var playing = false
    private var ending = false
    private var volume = 1f
    private var parameters = PlaybackParameters.DEFAULT
    private var attributes: AudioAttributes? = AudioAttributes.DEFAULT

    // Encoder delay/padding trimming (lossy songs).
    private var trimStartBytes = 0
    private var holdBackBytes = 0
    private var staged = ByteArray(0)
    private var stagedLength = 0

    // Idle release.
    private var suspendedFormat: Format? = null
    private var suspendedQueue: ByteArray? = null
    private val idleRelease = Runnable { releaseForIdle() }

    // Opening a device can take a while, so it runs outside the lock (Song Info and the volume
    // keys never wait on it). A detach meanwhile bumps this, and the late stream is closed.
    private var detachCount = 0

    /** True once [detach] took the stream away mid-song; the routing sink moves the song to the normal path. */
    @Volatile var detached = false
        private set

    /** Software volume for a DAC without its own (set by the router from the phone's volume). */
    @Volatile var extraGain = 1f
        set(value) { field = value; synchronized(lock) { if (handle != 0L) NativeBitPerfect.setGain(handle, volume * value) } }

    /** Whether every sample of the current song reached the output unchanged so far; null without a stream. */
    fun isExact(): Boolean? = synchronized(lock) { if (handle != 0L) NativeBitPerfect.isExact(handle) else null }

    /** Bits the output runs at (0 = float), or null without a stream. */
    fun outputBits(): Int? = synchronized(lock) { if (handle != 0L) NativeBitPerfect.outputBits(handle) else null }

    /** Tries to open a stream for [format]; true when this sink will play the song. */
    fun tryConfigure(format: Format): Boolean {
        if (MimeTypes.AUDIO_RAW != format.sampleMimeType) return false
        if (format.pcmEncoding != C.ENCODING_PCM_16BIT && format.pcmEncoding != C.ENCODING_PCM_FLOAT) return false
        if (format.channelCount !in 1..2) return false
        main.removeCallbacks(idleRelease)
        // Same layout as the song before: keep the stream, so songs follow on without a gap.
        // (A changed mode or device always detaches the stream first, so this is the same output.)
        synchronized(lock) {
            suspendedFormat = null
            suspendedQueue = null
            if (handle != 0L && current?.let { it.sampleRate == format.sampleRate && it.channelCount == format.channelCount &&
                    it.pcmEncoding == format.pcmEncoding } == true) {
                setTrim(format)
                return true
            }
        }
        // A new layout: let the last song's tail play out first (at most about a second).
        var waited = 0
        while (playing && waited < 1000 && synchronized(lock) { handle != 0L && NativeBitPerfect.queuedFrames(handle) > 0 }) {
            Thread.sleep(10); waited += 10
        }
        val before = synchronized(lock) { closeStream(); detachCount }
        val opened = opener.open(format)
        if (opened == 0L) return false
        synchronized(lock) {
            if (detachCount != before) { NativeBitPerfect.close(opened); return false }
            handle = opened
            current = format
            detached = false
            rate = format.sampleRate
            frameBytes = format.channelCount * if (format.pcmEncoding == C.ENCODING_PCM_FLOAT) 4 else 2
            startUs = C.TIME_UNSET
            writtenFrames = 0
            playedBase = 0
            ending = false
            setTrim(format)
            NativeBitPerfect.setGain(handle, volume * extraGain)
            if (playing) NativeBitPerfect.start(handle)
            return true
        }
    }

    private fun setTrim(format: Format) {
        trimStartBytes = format.encoderDelay.coerceAtLeast(0) * frameBytes
        holdBackBytes = format.encoderPadding.coerceAtLeast(0) * frameBytes
        stagedLength = 0  // anything held back belonged to the song before: its padding
    }

    /** Stops and frees the stream now, from any thread. Safe to call when nothing is open. */
    fun detach() {
        main.removeCallbacks(idleRelease)
        synchronized(lock) {
            detachCount++
            if (handle != 0L || suspendedFormat != null) detached = true
            suspendedFormat = null
            suspendedQueue = null
            closeStream()
        }
    }

    private fun closeStream() {
        if (handle != 0L) NativeBitPerfect.close(handle)
        handle = 0L
        current = null
    }

    private fun releaseForIdle() {
        synchronized(lock) {
            if (playing || handle == 0L) return
            NativeBitPerfect.pause(handle)
            playedBase += NativeBitPerfect.framesPlayed(handle)
            suspendedQueue = NativeBitPerfect.takeQueued(handle)
            suspendedFormat = current
            NativeBitPerfect.close(handle)
            handle = 0L
        }
        opener.onIdleRelease()
    }

    /**
     * Reopens after an idle release and puts back what was queued. False if the device is gone.
     * Player thread, called without the lock held.
     */
    private fun resumeIfSuspended(): Boolean {
        val (format, queued, before) = synchronized(lock) {
            val format = suspendedFormat ?: return true
            Triple(format, suspendedQueue, detachCount)
        }
        val opened = opener.open(format)
        synchronized(lock) {
            if (detachCount != before || suspendedFormat == null) {
                // Taken away, or a new song took over, while the device was opening.
                if (opened != 0L) NativeBitPerfect.close(opened)
                return suspendedFormat == null && handle != 0L
            }
            suspendedFormat = null
            suspendedQueue = null
            if (opened == 0L) { detached = true; return false }
            handle = opened
            current = format
            NativeBitPerfect.setGain(handle, volume * extraGain)
            if (queued != null) NativeBitPerfect.putQueued(handle, queued)
            if (playing) NativeBitPerfect.start(handle)
            return true
        }
    }

    override fun setListener(listener: AudioSink.Listener) { this.listener = listener }
    override fun setPlayerId(playerId: PlayerId?) {}
    override fun supportsFormat(format: Format) = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED
    override fun getFormatSupport(format: Format): Int =
        if (MimeTypes.AUDIO_RAW == format.sampleMimeType &&
            (format.pcmEncoding == C.ENCODING_PCM_16BIT || format.pcmEncoding == C.ENCODING_PCM_FLOAT)
        ) AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY else AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long = synchronized(lock) {
        if (startUs == C.TIME_UNSET || (handle == 0L && suspendedFormat == null)) return AudioSink.CURRENT_POSITION_NOT_SET
        val live = if (handle != 0L) NativeBitPerfect.framesPlayed(handle) else 0L
        val played = (playedBase + live).coerceAtMost(writtenFrames)
        startUs + played * 1_000_000L / rate
    }

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        if (synchronized(lock) { handle == 0L } && !tryConfigure(inputFormat)) {
            throw AudioSink.ConfigurationException("Exclusive output unavailable", inputFormat)
        }
    }

    override fun play() {
        main.removeCallbacks(idleRelease)
        synchronized(lock) { playing = true }
        if (!resumeIfSuspended()) return
        synchronized(lock) { if (handle != 0L) NativeBitPerfect.start(handle) }
    }

    override fun handleDiscontinuity() {}

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (synchronized(lock) { suspendedFormat != null } && !resumeIfSuspended()) return true
        return synchronized(lock) { writeLocked(buffer, presentationTimeUs) }
    }

    private fun writeLocked(buffer: ByteBuffer, presentationTimeUs: Long): Boolean {
        if (handle == 0L) return true
        if (startUs == C.TIME_UNSET) startUs = presentationTimeUs
        // Encoder delay: the first samples of a lossy song are priming, not music.
        if (trimStartBytes > 0) {
            val skip = minOf(trimStartBytes, buffer.remaining())
            buffer.position(buffer.position() + skip)
            trimStartBytes -= skip
        }
        if (holdBackBytes == 0) {
            if (buffer.remaining() > 0) write(buffer, buffer.remaining())
            return !buffer.hasRemaining()
        }
        // Encoder padding: always hold back the last samples; the song's end drops them.
        if (!writeStaged()) return false
        val incoming = buffer.remaining()
        if (staged.size < stagedLength + incoming) staged = staged.copyOf(maxOf(stagedLength + incoming, staged.size * 2))
        buffer.get(staged, stagedLength, incoming)
        stagedLength += incoming
        writeStaged()
        return true
    }

    private fun write(buffer: ByteBuffer, length: Int): Int {
        val used = if (buffer.isDirect) NativeBitPerfect.writeDirect(handle, buffer, buffer.position(), length)
        else NativeBitPerfect.writeArray(handle, buffer.array(), buffer.arrayOffset() + buffer.position(), length)
        buffer.position(buffer.position() + used)
        writtenFrames += used / frameBytes
        return used
    }

    /** Writes staged audio except the held-back tail; true when nothing writable is left. */
    private fun writeStaged(): Boolean {
        val writable = (stagedLength - holdBackBytes) / frameBytes * frameBytes
        if (writable <= 0) return true
        val used = NativeBitPerfect.writeArray(handle, staged, 0, writable)
        if (used > 0) {
            System.arraycopy(staged, used, staged, 0, stagedLength - used)
            stagedLength -= used
            writtenFrames += used / frameBytes
        }
        return used == writable
    }

    override fun playToEndOfStream() = synchronized(lock) {
        ending = true
        stagedLength = 0  // the held-back tail is the song's padding
    }

    override fun isEnded() = synchronized(lock) {
        suspendedFormat == null && (handle == 0L || (ending && NativeBitPerfect.queuedFrames(handle) == 0))
    }

    override fun hasPendingData() = synchronized(lock) {
        suspendedFormat != null || (handle != 0L && NativeBitPerfect.queuedFrames(handle) > 0)
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) { parameters = playbackParameters }
    override fun getPlaybackParameters(): PlaybackParameters = parameters
    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {}
    override fun getSkipSilenceEnabled() = false
    override fun setAudioAttributes(audioAttributes: AudioAttributes) { attributes = audioAttributes }
    override fun getAudioAttributes(): AudioAttributes? = attributes
    override fun setAudioSessionId(audioSessionId: Int) {}
    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {}
    override fun enableTunnelingV21() {}
    override fun disableTunneling() {}

    override fun setVolume(volume: Float) = synchronized(lock) {
        this.volume = volume
        if (handle != 0L) NativeBitPerfect.setGain(handle, volume * extraGain)
    }

    override fun pause() {
        synchronized(lock) {
            playing = false
            if (handle != 0L) NativeBitPerfect.pause(handle)
        }
        // One delayed check, not a loop: if still paused then, the device is handed back.
        main.removeCallbacks(idleRelease)
        main.postDelayed(idleRelease, IDLE_RELEASE_MS)
    }

    override fun flush() = synchronized(lock) {
        if (handle != 0L) NativeBitPerfect.flush(handle)
        suspendedQueue = null  // a seek: what was queued is no longer wanted
        startUs = C.TIME_UNSET
        writtenFrames = 0
        playedBase = 0
        stagedLength = 0
        trimStartBytes = 0  // only a song's very start is trimmed, not after a seek
        ending = false
    }

    override fun reset() {
        main.removeCallbacks(idleRelease)
        synchronized(lock) {
            flush()
            closeStream()
            suspendedFormat = null
            detached = false
        }
    }

    override fun release() = reset()

    private companion object {
        const val IDLE_RELEASE_MS = 30_000L
    }
}
