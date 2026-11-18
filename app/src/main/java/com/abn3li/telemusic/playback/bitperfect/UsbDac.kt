package com.abn3li.telemusic.playback.bitperfect

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.util.Log
import kotlin.math.pow

/** One playback alternate setting of a USB Audio Class streaming interface. */
internal data class UsbAltSetting(
    val iface: Int,
    val alt: Int,
    val channels: Int,
    val subslot: Int,
    val bits: Int,
    /** UAC1 lists its rates; empty means "ask the clock" (UAC2). */
    val rates: List<Int>,
    val endpoint: Int,
    val maxPacket: Int,
    val interval: Int,
    val feedbackEndpoint: Int
)

/** A UAC2 clock or the feature unit with a master volume control. */
internal data class UsbAudioControl(
    val acInterface: Int,
    val uac2: Boolean,
    val clockIds: List<Int>,
    val volumeUnit: Int?
)

internal data class UsbDacLayout(val control: UsbAudioControl, val alts: List<UsbAltSetting>)

/** Reads the class-specific descriptors that UsbInterface doesn't expose. */
internal object UsbDescriptors {
    private const val CS_INTERFACE = 0x24
    private const val SUBCLASS_CONTROL = 1
    private const val SUBCLASS_STREAMING = 2

    fun parse(raw: ByteArray): UsbDacLayout? {
        var ifNum = -1
        var alt = 0
        var subclass = -1
        var protocol = 0
        var acInterface = -1
        val clocks = mutableListOf<Int>()
        var volumeUnit: Int? = null
        var uac2 = false
        val alts = mutableListOf<UsbAltSetting>()
        // Collected for the alt setting being read.
        var channels = 0; var subslot = 0; var bits = 0
        var rates = emptyList<Int>()
        var ep = 0; var maxPacket = 0; var interval = 1; var fb = 0
        var pcm = false

        fun flush() {
            if (subclass == SUBCLASS_STREAMING && alt > 0 && pcm && ep != 0 && (ep and 0x80) == 0 && subslot > 0 && channels > 0) {
                alts += UsbAltSetting(ifNum, alt, channels, subslot, bits, rates, ep, maxPacket, interval, fb)
            }
            channels = 0; subslot = 0; bits = 0; rates = emptyList(); ep = 0; maxPacket = 0; interval = 1; fb = 0; pcm = false
        }

        var i = 0
        while (i + 1 < raw.size) {
            val len = raw[i].u()
            if (len < 2 || i + len > raw.size) break
            val type = raw[i + 1].u()
            fun b(o: Int) = if (o < len) raw[i + o].u() else 0
            fun w(o: Int) = b(o) or (b(o + 1) shl 8)
            when (type) {
                0x04 -> {  // interface
                    flush()
                    ifNum = b(2); alt = b(3)
                    subclass = if (b(5) == UsbConstants.USB_CLASS_AUDIO) b(6) else -1
                    protocol = b(7)
                    if (subclass == SUBCLASS_CONTROL && acInterface < 0) { acInterface = ifNum; uac2 = protocol == 0x20 }
                }
                CS_INTERFACE -> when (subclass) {
                    SUBCLASS_CONTROL -> when (b(2)) {
                        0x0A -> if (uac2) clocks += b(3)  // clock source
                        0x06 -> if (volumeUnit == null) {  // feature unit, master channel
                            val hasVolume = if (uac2) (b(5) shr 2) and 0x3 == 0x3 else b(5) > 0 && (b(6) and 0x02) != 0
                            if (hasVolume) volumeUnit = b(3)
                        }
                    }
                    SUBCLASS_STREAMING -> when (b(2)) {
                        0x01 -> if (protocol == 0x20) {  // AS_GENERAL (UAC2)
                            pcm = b(5) == 1 && (b(6) and 0x01) != 0; channels = b(10)
                        } else {
                            pcm = w(5) == 0x0001
                        }
                        0x02 -> if (b(3) == 1) {  // FORMAT_TYPE_I
                            if (protocol == 0x20) { subslot = b(4); bits = b(5) }
                            else {
                                channels = b(4); subslot = b(5); bits = b(6)
                                val count = b(7)
                                rates = if (count == 0) {
                                    val low = b(8) or (b(9) shl 8) or (b(10) shl 16)
                                    val high = b(11) or (b(12) shl 8) or (b(13) shl 16)
                                    STANDARD_RATES.filter { it in low..high }
                                } else (0 until count).map { k -> b(8 + 3 * k) or (b(9 + 3 * k) shl 8) or (b(10 + 3 * k) shl 16) }
                            }
                        }
                    }
                }
                0x05 -> if (subclass == SUBCLASS_STREAMING && alt > 0) {  // endpoint
                    val address = b(2)
                    val attributes = b(3)
                    val isIso = attributes and 0x3 == 0x1
                    val isFeedback = (attributes shr 4) and 0x3 == 0x1
                    if (isIso && isFeedback && (address and 0x80) != 0) fb = address
                    else if (isIso && (address and 0x80) == 0) {
                        ep = address; maxPacket = w(4) and 0x7FF; interval = b(6).coerceAtLeast(1)
                    }
                }
            }
            i += len
        }
        flush()
        if (acInterface < 0 || alts.isEmpty()) return null
        return UsbDacLayout(UsbAudioControl(acInterface, uac2, clocks, volumeUnit), alts)
    }

    val STANDARD_RATES = listOf(44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000, 705600, 768000)

    private fun Byte.u() = toInt() and 0xFF
}

/**
 * A USB DAC this app drives itself: Android's own USB audio driver is detached from it while
 * the session lasts, and handed back when it ends.
 */
internal class UsbDacSession private constructor(
    val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val layout: UsbDacLayout,
    private val claimed: List<UsbInterface>,
    private val highSpeed: Boolean
) {
    private var activeAlt: UsbAltSetting? = null
    private var activeRate = 0
    private var volumeRange: IntArray? = null  // min, max in 1/256 dB

    val productName: String get() = device.productName?.takeIf { it.isNotBlank() } ?: "USB DAC"
    val hasHardwareVolume: Boolean get() = volumeRange != null

    /** Picks the alt setting for a song and sets the DAC to its rate. Null if the DAC can't play it as is. */
    fun prepare(rate: Int, channels: Int, inFloat: Boolean): UsbAltSetting? {
        val wantedBits = if (inFloat) 24 else 16
        val candidates = layout.alts.filter { it.channels == channels && supportsRate(it, rate) }
        val alt = candidates.filter { it.bits >= wantedBits }.minByOrNull { it.bits }
            ?: candidates.maxByOrNull { it.bits }
            ?: return null
        if (activeAlt != alt || activeRate != rate) {
            val idle = interfaceOf(alt.iface, 0) ?: return null
            val streaming = interfaceOf(alt.iface, alt.alt) ?: return null
            connection.setInterface(idle)
            if (layout.control.uac2) {
                // UAC2: the clock is set while the stream is idle, then the alt setting opens.
                if (!setRate(alt, rate)) return null
                connection.setInterface(streaming)
            } else {
                // UAC1: the rate belongs to the endpoint, which only exists once the alt setting is on.
                connection.setInterface(streaming)
                if (!setRate(alt, rate)) { connection.setInterface(idle); activeAlt = null; return null }
            }
            activeAlt = alt
            activeRate = rate
        }
        return alt
    }

    fun openStream(alt: UsbAltSetting, rate: Int, channels: Int, inFloat: Boolean): Long {
        val packetsPerSecond = if (highSpeed) 8000 shr (alt.interval - 1).coerceIn(0, 3) else 1000
        val busIntervalsPerPacket = if (highSpeed) 1 shl (alt.interval - 1).coerceIn(0, 3) else 1
        return NativeBitPerfect.openUsb(
            connection.fileDescriptor, alt.endpoint, alt.maxPacket, packetsPerSecond, alt.feedbackEndpoint,
            busIntervalsPerPacket, rate, channels, alt.subslot, alt.bits, inFloat
        )
    }

    private fun supportsRate(alt: UsbAltSetting, rate: Int): Boolean {
        if (alt.rates.isNotEmpty()) return rate in alt.rates
        return rate in uac2Rates
    }

    private val uac2Rates: Set<Int> by lazy {
        val clock = layout.control.clockIds.firstOrNull() ?: return@lazy emptySet()
        val buffer = ByteArray(2 + 12 * 32)
        val read = connection.controlTransfer(0xA1, 0x02, 0x0100, (clock shl 8) or layout.control.acInterface, buffer, buffer.size, TIMEOUT)
        if (read < 2) return@lazy emptySet()
        val ranges = (buffer[0].toInt() and 0xFF) or ((buffer[1].toInt() and 0xFF) shl 8)
        val out = mutableSetOf<Int>()
        for (r in 0 until ranges) {
            val at = 2 + r * 12
            if (at + 12 > read) break
            val min = intAt(buffer, at); val max = intAt(buffer, at + 4); val res = intAt(buffer, at + 8)
            if (res == 0 || min == max) out += min
            else out += ratesIn(min, max)
        }
        out
    }

    private fun ratesIn(min: Int, max: Int) = UsbDescriptors.STANDARD_RATES.filter { it in min..max }

    private fun setRate(alt: UsbAltSetting, rate: Int): Boolean {
        return if (layout.control.uac2) {
            val clock = layout.control.clockIds.firstOrNull() ?: return true
            val data = byteArrayOf(rate.toByte(), (rate shr 8).toByte(), (rate shr 16).toByte(), (rate shr 24).toByte())
            connection.controlTransfer(0x21, 0x01, 0x0100, (clock shl 8) or layout.control.acInterface, data, 4, TIMEOUT) >= 0
        } else {
            val data = byteArrayOf(rate.toByte(), (rate shr 8).toByte(), (rate shr 16).toByte())
            connection.controlTransfer(0x22, 0x01, 0x0100, alt.endpoint, data, 3, TIMEOUT) >= 0 || alt.rates.size <= 1
        }
    }

    /** Reads the DAC's volume range once; false if it has no master volume. */
    private fun readVolumeRange(): IntArray? {
        val unit = layout.control.volumeUnit ?: return null
        val index = (unit shl 8) or layout.control.acInterface
        return if (layout.control.uac2) {
            val buffer = ByteArray(2 + 6)
            if (connection.controlTransfer(0xA1, 0x02, 0x0200, index, buffer, buffer.size, TIMEOUT) < 8) null
            else intArrayOf(shortAt(buffer, 2), shortAt(buffer, 4))
        } else {
            val min = ByteArray(2); val max = ByteArray(2)
            if (connection.controlTransfer(0xA1, 0x82, 0x0200, index, min, 2, TIMEOUT) < 2 ||
                connection.controlTransfer(0xA1, 0x83, 0x0200, index, max, 2, TIMEOUT) < 2) null
            else intArrayOf(shortAt(min, 0), shortAt(max, 0))
        }
    }

    /** Sets the DAC's own volume in dB (clamped to what it offers). */
    fun setVolumeDb(db: Double) {
        val range = volumeRange ?: return
        val unit = layout.control.volumeUnit ?: return
        val value = (db * 256).toInt().coerceIn(range[0], range[1])
        val data = byteArrayOf(value.toByte(), (value shr 8).toByte())
        connection.controlTransfer(0x21, 0x01, 0x0200, (unit shl 8) or layout.control.acInterface, data, 2, TIMEOUT)
    }

    fun close() {
        runCatching { activeAlt?.let { a -> interfaceOf(a.iface, 0)?.let { connection.setInterface(it) } } }
        for (iface in claimed) {
            runCatching { connection.releaseInterface(iface) }
            runCatching { NativeBitPerfect.reattachKernelDriver(connection.fileDescriptor, iface.id) }
        }
        runCatching { connection.close() }
    }

    private fun interfaceOf(id: Int, alt: Int): UsbInterface? =
        (0 until device.interfaceCount).map { device.getInterface(it) }.firstOrNull { it.id == id && it.alternateSetting == alt }

    companion object {
        private const val TAG = "UsbDac"
        private const val TIMEOUT = 1000

        fun isAudioDevice(device: UsbDevice) = (0 until device.interfaceCount).any {
            val iface = device.getInterface(it)
            iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO && iface.interfaceSubclass == 2
        }

        fun open(device: UsbDevice, connection: UsbDeviceConnection): UsbDacSession? {
            if (!NativeBitPerfect.loaded) { connection.close(); return null }
            val layout = connection.rawDescriptors?.let { UsbDescriptors.parse(it) }
            if (layout == null) { connection.close(); return null }
            val ids = (layout.alts.map { it.iface } + layout.control.acInterface).toSet()
            val claimed = ids.mapNotNull { id ->
                val iface = (0 until device.interfaceCount).map { device.getInterface(it) }.firstOrNull { it.id == id && it.alternateSetting == 0 }
                iface?.takeIf { connection.claimInterface(it, true) }
            }
            if (claimed.size != ids.size) {
                Log.w(TAG, "Couldn't claim the DAC's interfaces")
                claimed.forEach { connection.releaseInterface(it); NativeBitPerfect.reattachKernelDriver(connection.fileDescriptor, it.id) }
                connection.close()
                return null
            }
            val highSpeed = NativeBitPerfect.isHighSpeedOrFaster(NativeBitPerfect.usbSpeed(connection.fileDescriptor))
            return UsbDacSession(device, connection, layout, claimed, highSpeed).also { session ->
                session.volumeRange = session.readVolumeRange()
                Log.i(TAG, "Opened ${session.productName}: uac2=${layout.control.uac2} highSpeed=$highSpeed alts=${layout.alts.size} volume=${session.volumeRange?.toList()}")
            }
        }

        private fun intAt(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)
        private fun shortAt(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)).toShort().toInt()

        /** Phone volume step to DAC dB: the top step is 0 dB, each step down a bit quieter, 0 is mute. */
        fun volumeToDb(index: Int, max: Int): Double {
            if (index <= 0 || max <= 0) return -127.0
            val fraction = index.toDouble() / max
            return 60.0 * (fraction - 1.0)
        }

        fun dbToGain(db: Double): Float = if (db <= -120) 0f else 10.0.pow(db / 20).toFloat()
    }
}
