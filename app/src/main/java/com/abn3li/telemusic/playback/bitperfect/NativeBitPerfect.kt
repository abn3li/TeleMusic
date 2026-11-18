package com.abn3li.telemusic.playback.bitperfect

import java.nio.ByteBuffer

/** The native exclusive-output streams (bitperfect.cpp). A handle of 0 means "couldn't open". */
internal object NativeBitPerfect {
    val loaded: Boolean = runCatching { System.loadLibrary("telemusic_bitperfect") }.isSuccess

    @JvmStatic external fun usbSpeed(fd: Int): Int
    @JvmStatic external fun reattachKernelDriver(fd: Int, iface: Int): Boolean
    @JvmStatic external fun openUsb(
        fd: Int, ep: Int, maxPacket: Int, packetsPerSecond: Int, fbEp: Int, busIntervalsPerPacket: Int,
        rate: Int, channels: Int, subslot: Int, bits: Int, inFloat: Boolean
    ): Long
    @JvmStatic external fun openAAudio(deviceId: Int, rate: Int, channels: Int, inFloat: Boolean): Long
    @JvmStatic external fun writeDirect(handle: Long, buffer: ByteBuffer, offset: Int, length: Int): Int
    @JvmStatic external fun writeArray(handle: Long, array: ByteArray, offset: Int, length: Int): Int
    @JvmStatic external fun start(handle: Long)
    @JvmStatic external fun pause(handle: Long)
    @JvmStatic external fun flush(handle: Long)
    @JvmStatic external fun framesPlayed(handle: Long): Long
    @JvmStatic external fun queuedFrames(handle: Long): Int
    @JvmStatic external fun setGain(handle: Long, gain: Float)
    @JvmStatic external fun close(handle: Long)
    /** Bits per sample the output runs at; 0 when it runs as float. */
    @JvmStatic external fun outputBits(handle: Long): Int
    /** False once any sample reached the output changed (volume, narrower output, lossy float). */
    @JvmStatic external fun isExact(handle: Long): Boolean
    /** While stopped: the queued, unplayed audio in output format. */
    @JvmStatic external fun takeQueued(handle: Long): ByteArray
    @JvmStatic external fun putQueued(handle: Long, data: ByteArray): Int

    /** Linux USB speeds from USBDEVFS_GET_SPEED: 3 = high, 5 = super, 6 = super+. */
    fun isHighSpeedOrFaster(speed: Int) = speed == 3 || speed >= 5
}
