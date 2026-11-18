package com.abn3li.telemusic.playback

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import com.abn3li.telemusic.data.settings.AudioOutputSettings
import com.abn3li.telemusic.data.settings.BitPerfectMode
import com.abn3li.telemusic.playback.bitperfect.ExclusiveAudioSink
import com.abn3li.telemusic.playback.bitperfect.ExclusiveOpener
import com.abn3li.telemusic.playback.bitperfect.NativeBitPerfect
import com.abn3li.telemusic.playback.bitperfect.UsbDacSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.util.Locale

/**
 * Knows which output the music goes to and how to feed it.
 *
 * Nothing here runs on its own: the device list is refreshed only when Android reports a device
 * plugged in or out, and the output is chosen once per song, when the audio sink is configured.
 *
 * - 32-bit float is only offered to an external output that lists float among its encodings.
 *   The phone speaker always stays 16-bit: some OEM speaker paths distort with float.
 * - USB DAC (Android 14+) asks Android for a mixer in the song's exact format.
 * - USB DAC (Direct Driver) takes the DAC from Android and streams to it from native code.
 * - Built-in DAC opens the wired/line output with AAudio in exclusive mode.
 * Whenever a mode can't play a song exactly, that song plays the normal way.
 */
/** What the Signal Path page shows: each step a song takes from file to output. */
data class SignalPath(
    val source: String,
    val sourceDetail: String?,
    val lossless: Boolean,
    val decoded: String,
    val route: Route,
    val output: String,
    val outputDetail: String?,
    /** Android's own mixer rate when it differs from the song's: it probably resamples. */
    val mixerRateHz: Int?,
    /** Whether every sample so far reached the output unchanged; null where the app can't know. */
    val exact: Boolean?,
    /** The song's own bit depth (the decoded one when the file doesn't say) and rate, for the big readout. */
    val bits: Int,
    val rateHz: Int
) {
    enum class Route(val label: String) {
        MIXER("Android Mixer"), ANDROID_BIT_PERFECT("Android Bit-Perfect"), DIRECT("Direct Driver"), EXCLUSIVE("Exclusive")
    }
}

/**
 * Knows which output the music goes to and how to feed it.
 *
 * Nothing here runs on its own: the device list is refreshed only when Android reports a device
 * plugged in or out, and the output is chosen once per song, when the audio sink is configured.
 *
 * - Float is only used for hi-res lossless songs (FLAC/ALAC above 16-bit); everything else is
 *   decoded to 16-bit. The phone speaker never gets float: some OEM speaker paths distort.
 * - USB DAC (Android 14+) asks Android for a mixer in the song's exact format (lossless only).
 * - USB DAC (Direct Driver) takes the DAC from Android and streams to it from native code.
 * - Exclusive opens the wired or USB output with AAudio in exclusive mode (lossless only).
 * Whenever a mode can't play a song exactly, that song plays the normal way.
 */
@UnstableApi
class AudioOutputRouter(private val context: Context, private val settings: StateFlow<AudioOutputSettings>) :
    androidx.media3.decoder.ffmpeg.SourceAwareFfmpegAudioRenderer.SourcePolicy {

    enum class Kind(val label: String) { PHONE("Phone"), USB("USB DAC"), WIRED("Wired"), BLUETOOTH("Bluetooth") }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var device: AudioDeviceInfo? = null
    @Volatile private var wired: AudioDeviceInfo? = null
    @Volatile private var usbOut: AudioDeviceInfo? = null
    @Volatile private var kind = Kind.PHONE
    @Volatile private var bitPerfectOn: AudioDeviceInfo? = null
    @Volatile private var bitPerfectFormat: AudioFormat? = null

    // The song being played: its source (from the decoder) and how it left the app.
    @Volatile private var pendingSource: Format? = null
    @Volatile private var songSource: Format? = null
    @Volatile private var songFormat: Format? = null
    @Volatile private var songRoute = SignalPath.Route.MIXER
    @Volatile private var songOutput: String? = null
    @Volatile private var songOutputDetail: String? = null
    @Volatile private var exclusiveDeviceId = 0

    // Direct driver state.
    @Volatile private var usbSession: UsbDacSession? = null
    @Volatile private var normalHoldUntil = 0L
    private var askedPermissionFor: String? = null
    private var volumeListening = false
    internal var exclusiveSink: ExclusiveAudioSink? = null

    private val _status = MutableStateFlow<String?>(null)
    /** One line for Song Info, e.g. "USB DAC · Direct · 24-bit / 96 kHz". */
    val status: StateFlow<String?> = _status

    /** Set by the service: pause when the device the app drives goes away, like unplugging headphones. */
    @Volatile var onDacLost: (() -> Unit)? = null

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = refresh()
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                val gone = intent.usbDevice() ?: return
                if (usbSession?.device?.deviceName == gone.deviceName) {
                    onDacLost?.invoke()
                    closeUsbSession(expectNormal = false)
                }
                if (askedPermissionFor == gone.deviceName) askedPermissionFor = null
            }
        }
    }

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = applyDacVolume()
    }

    init {
        audioManager.registerAudioDeviceCallback(callback, main)
        ContextCompat.registerReceiver(context, usbReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED), ContextCompat.RECEIVER_EXPORTED)
        refresh()
    }

    private fun refresh() {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        fun first(vararg types: Int) = outputs.firstOrNull { it.type in types }
        val usb = first(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET)
        val wiredOut = first(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL)
        val bluetooth = first(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            *(if (Build.VERSION.SDK_INT >= 31) intArrayOf(AudioDeviceInfo.TYPE_BLE_HEADSET) else intArrayOf()))
        val (picked, pickedKind) = when {
            usb != null -> usb to Kind.USB
            wiredOut != null -> wiredOut to Kind.WIRED
            bluetooth != null -> bluetooth to Kind.BLUETOOTH
            else -> null to Kind.PHONE
        }
        val lost = bitPerfectOn
        if (lost != null && (picked == null || picked.id != lost.id)) {
            if (Build.VERSION.SDK_INT >= 34) clearBitPerfect(lost)
        }
        // The output an exclusive stream was opened on went away: stop it, like an unplug.
        if (songRoute == SignalPath.Route.EXCLUSIVE && exclusiveDeviceId != 0 && outputs.none { it.id == exclusiveDeviceId }) {
            onDacLost?.invoke()
            exclusiveSink?.detach()
            exclusiveDeviceId = 0
        }
        device = picked
        wired = wiredOut
        usbOut = usb
        kind = pickedKind
        if (usb != null) normalHoldUntil = 0L  // Android has the DAC again
        // A DAC plugged in while the direct driver is chosen: ask for it now, so the next song can use it.
        if (settings.value.bitPerfect == BitPerfectMode.USB_DIRECT && usbSession == null) usbDac()?.let { requestPermission(it) }
        if (songRoute == SignalPath.Route.MIXER || songRoute == SignalPath.Route.ANDROID_BIT_PERFECT) updateNormalOutput()
    }

    // ---------- decoder policy ----------

    override fun onDecoderInput(input: Format) { pendingSource = input }

    /** Float only where it carries something 16-bit can't: hi-res lossless sources. */
    override fun allowFloat(input: Format): Boolean = isLossless(input) && sourceBits(input).let { it == null || it > 16 }

    /** A lossless source's bit depth, when it can be told. */
    private fun sourceBits(format: Format?): Int? = if (format == null) null else when {
        format.pcmEncoding == C.ENCODING_PCM_16BIT -> 16
        format.pcmEncoding == C.ENCODING_PCM_24BIT -> 24
        format.pcmEncoding == C.ENCODING_PCM_32BIT || format.pcmEncoding == C.ENCODING_PCM_FLOAT -> 32
        // ALAC: the bit depth is byte 5 of its config (frame length, version, bit depth, ...).
        format.sampleMimeType == MimeTypes.AUDIO_ALAC ->
            format.initializationData.firstOrNull()?.takeIf { it.size > 5 }?.let { it[5].toInt() and 0xFF }?.takeIf { it in 8..32 }
        else -> null
    }

    private fun isLossless(format: Format?) = format?.sampleMimeType in LOSSLESS

    /** Takes the source recorded for the song now being configured (null if another decoder ran). */
    internal fun takeSource(): Format? = pendingSource.also { pendingSource = null; songSource = it }

    // ---------- choosing ----------

    /** Whether an exclusive mode (direct USB or exclusive AAudio) should take this song. */
    internal fun exclusiveWanted(source: Format?): Boolean = when (settings.value.bitPerfect) {
        // The direct driver holds the DAC, so every song goes through it (lossy ones are trimmed like the normal path does).
        BitPerfectMode.USB_DIRECT -> NativeBitPerfect.loaded && (usbSession != null || usbDac() != null)
        BitPerfectMode.DAP_EXCLUSIVE -> NativeBitPerfect.loaded && isLossless(source) && (wired != null || usbOut != null)
        else -> false
    }

    /** Whether the sink may take float at all for a song starting now (the decoder policy narrows it). */
    fun floatAllowed(): Boolean {
        val mode = settings.value.bitPerfect
        if (mode == BitPerfectMode.USB_DIRECT || mode == BitPerfectMode.DAP_EXCLUSIVE) return NativeBitPerfect.loaded
        val out = device ?: return false
        if (kind == Kind.PHONE || !settings.value.floatOutput) return false
        return AudioFormat.ENCODING_PCM_FLOAT in out.encodings
    }

    /** True for a short while after the direct driver let a DAC go, until Android owns it again. */
    internal fun normalOutputPending(): Boolean = normalHoldUntil > android.os.SystemClock.elapsedRealtime() && usbOut == null

    /** Opens an exclusive stream for a song, or 0 to play it the normal way. Player thread. */
    fun openExclusive(format: Format): Long {
        val inFloat = format.pcmEncoding == C.ENCODING_PCM_FLOAT
        return when (settings.value.bitPerfect) {
            BitPerfectMode.USB_DIRECT -> {
                val session = usbSession ?: openUsbSession() ?: return 0
                val alt = session.prepare(format.sampleRate, format.channelCount, inFloat)
                if (alt == null) {
                    // The DAC can't take this song as it is: give it back to Android for this one.
                    Log.i(TAG, "${session.productName} can't play ${format.sampleRate} Hz / ${format.channelCount} ch as is")
                    closeUsbSession(expectNormal = true)
                    return 0
                }
                applyDacVolume(now = true)  // set the DAC's level before the first sample reaches it
                session.openStream(alt, format.sampleRate, format.channelCount, inFloat).also {
                    if (it != 0L) {
                        songRoute = SignalPath.Route.DIRECT
                        songOutput = session.productName
                        songOutputDetail = "USB · ${alt.bits}-bit / ${khz(format.sampleRate)}"
                        publishStatus(format)
                        main.post { startVolumeFollow() }
                    }
                }
            }
            BitPerfectMode.DAP_EXCLUSIVE -> {
                val out = wired ?: usbOut ?: return 0
                NativeBitPerfect.openAAudio(out.id, format.sampleRate, format.channelCount, inFloat).also {
                    if (it != 0L) {
                        val bits = NativeBitPerfect.outputBits(it)
                        exclusiveDeviceId = out.id
                        songRoute = SignalPath.Route.EXCLUSIVE
                        songOutput = deviceName(out)
                        songOutputDetail = "${typeLabel(out)} · ${if (bits == 0) "32-bit float" else "$bits-bit"} / ${khz(format.sampleRate)}"
                        publishStatus(format)
                    }
                }
            }
            else -> 0L
        }
    }

    /** Called once per song played the normal way, right before its audio track opens. */
    fun onSongFormat(format: Format) {
        songFormat = format
        exclusiveDeviceId = 0
        val out = device
        val lossless = isLossless(songSource)
        var bitPerfect = false
        if (Build.VERSION.SDK_INT >= 34 && out != null && kind == Kind.USB && lossless &&
            settings.value.bitPerfect == BitPerfectMode.USB_ANDROID) {
            bitPerfect = applyBitPerfect(out, format)
        } else if (Build.VERSION.SDK_INT >= 34) {
            bitPerfectOn?.let { clearBitPerfect(it) }
        }
        songRoute = if (bitPerfect) SignalPath.Route.ANDROID_BIT_PERFECT else SignalPath.Route.MIXER
        updateNormalOutput()
    }

    internal fun onExclusiveSong(format: Format) { songFormat = format }

    private fun updateNormalOutput() {
        val format = songFormat ?: return
        if (songRoute != SignalPath.Route.MIXER && songRoute != SignalPath.Route.ANDROID_BIT_PERFECT) return
        val out = device
        songOutput = out?.let { deviceName(it) } ?: "Phone Speaker"
        songOutputDetail = if (songRoute == SignalPath.Route.ANDROID_BIT_PERFECT) bitPerfectFormat?.let {
            "${typeLabel(out)} · ${encodingLabel(it.encoding)} / ${khz(it.sampleRate)}"
        } else out?.let { typeLabel(it) }
        publishStatus(format)
    }

    // ---------- USB DAC (Android 14) ----------

    @RequiresApi(34)
    private fun applyBitPerfect(out: AudioDeviceInfo, format: Format): Boolean {
        val encoding = when (format.pcmEncoding) {
            C.ENCODING_PCM_16BIT -> AudioFormat.ENCODING_PCM_16BIT
            C.ENCODING_PCM_FLOAT -> AudioFormat.ENCODING_PCM_FLOAT
            else -> null
        }
        val match = encoding?.let {
            runCatching { audioManager.getSupportedMixerAttributes(out) }.getOrDefault(emptyList()).firstOrNull { mixer ->
                mixer.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                    mixer.format.encoding == it &&
                    mixer.format.sampleRate == format.sampleRate &&
                    mixer.format.channelCount == format.channelCount
            }
        }
        if (match == null) {
            bitPerfectOn?.let { clearBitPerfect(it) }
            return false
        }
        val applied = runCatching { audioManager.setPreferredMixerAttributes(MEDIA, out, match) }
            .onFailure { Log.w(TAG, "Bit-perfect refused", it) }.getOrDefault(false)
        bitPerfectOn = if (applied) out else null
        bitPerfectFormat = if (applied) match.format else null
        return applied
    }

    @RequiresApi(34)
    private fun clearBitPerfect(out: AudioDeviceInfo) {
        runCatching { audioManager.clearPreferredMixerAttributes(MEDIA, out) }
        bitPerfectOn = null
        bitPerfectFormat = null
    }

    // ---------- USB DAC (Direct Driver) ----------

    private fun usbDac(): UsbDevice? = usbManager.deviceList.values.firstOrNull { UsbDacSession.isAudioDevice(it) }

    private fun requestPermission(dac: UsbDevice) {
        if (usbManager.hasPermission(dac) || askedPermissionFor == dac.deviceName) return
        askedPermissionFor = dac.deviceName
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val intent = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName), flags)
        main.post { runCatching { usbManager.requestPermission(dac, intent) } }
    }

    @Synchronized
    private fun openUsbSession(): UsbDacSession? {
        usbSession?.let { return it }
        val dac = usbDac() ?: return null
        if (!usbManager.hasPermission(dac)) { requestPermission(dac); return null }
        val connection = usbManager.openDevice(dac) ?: return null
        return UsbDacSession.open(dac, connection)?.also { usbSession = it }
    }

    /**
     * Lets go of the DAC: the stream stops first, then the connection closes (never the other way).
     * With [expectNormal], the normal path waits (briefly) until Android owns the DAC again, so
     * nothing meant for it plays on the phone speaker in between.
     */
    private fun closeUsbSession(expectNormal: Boolean) {
        val session = synchronized(this) { usbSession.also { usbSession = null } } ?: return
        if (expectNormal) normalHoldUntil = android.os.SystemClock.elapsedRealtime() + NORMAL_HOLD_MS
        exclusiveSink?.detach()
        main.post { stopVolumeFollow() }
        session.close()
    }

    /** Phone volume keys drive the DAC's own volume while the direct driver holds it. */
    private fun startVolumeFollow() {
        if (volumeListening) return
        volumeListening = true
        ContextCompat.registerReceiver(context, volumeReceiver, IntentFilter(VOLUME_CHANGED), ContextCompat.RECEIVER_EXPORTED)
    }

    private fun stopVolumeFollow() {
        if (!volumeListening) return
        volumeListening = false
        runCatching { context.unregisterReceiver(volumeReceiver) }
        exclusiveSink?.extraGain = 1f
    }

    private fun applyDacVolume(now: Boolean = false) {
        val session = usbSession ?: return
        val db = UsbDacSession.volumeToDb(
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC), audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        )
        if (session.hasHardwareVolume) {
            if (now) session.setVolumeDb(db) else Thread { session.setVolumeDb(db) }.start()
            exclusiveSink?.extraGain = 1f
        } else {
            exclusiveSink?.extraGain = UsbDacSession.dbToGain(db)
        }
    }

    /** The sink closed its stream after a long pause: hand the DAC back so it can sleep. */
    internal fun onIdleRelease() {
        if (songRoute == SignalPath.Route.DIRECT) {
            val session = synchronized(this) { usbSession.also { usbSession = null } } ?: return
            main.post { stopVolumeFollow() }
            session.close()
        }
    }

    // ---------- reporting ----------

    private fun publishStatus(format: Format) {
        songFormat = format
        val mode = when (songRoute) {
            SignalPath.Route.MIXER -> null
            SignalPath.Route.ANDROID_BIT_PERFECT -> "Bit-perfect"
            SignalPath.Route.DIRECT -> "Direct"
            SignalPath.Route.EXCLUSIVE -> "Exclusive"
        }
        val where = when (songRoute) {
            SignalPath.Route.DIRECT -> Kind.USB.label
            else -> songOutput ?: kind.label
        }
        _status.value = listOfNotNull(where, mode, "${encodingLabel(format.pcmEncoding, pcm = true)} / ${khz(format.sampleRate)}").joinToString(" · ")
    }

    /**
     * The playing song's format and quality as the player reads them from the stream itself,
     * e.g. ("Opus", "48 kHz") or ("FLAC · Hi-Res Lossless", "24-bit / 96 kHz"); null when nothing
     * is playing. Only values the stream states are shown.
     */
    fun playingQuality(): Pair<String, String>? {
        val source = songSource ?: return null
        val mime = source.sampleMimeType ?: return null
        val codec = codecName(mime).let { if (it == "PCM") "WAV" else it }
        val lossless = isLossless(source)
        val bits = sourceBits(source)
        val rate = source.sampleRate.takeIf { it > 0 }?.let { khz(it) }
        val bitrate = (if (source.averageBitrate > 0) source.averageBitrate else source.bitrate).takeIf { it > 0 }
        val format = when {
            lossless && ((bits ?: 0) > 16 || source.sampleRate > 48_000) -> "$codec · Hi-Res Lossless"
            lossless -> "$codec · Lossless"
            else -> codec
        }
        val quality = if (lossless) {
            listOfNotNull(listOfNotNull(bits?.let { "$it-bit" }, rate).joinToString(" / ").ifBlank { null },
                bitrate?.let { String.format(Locale.US, "%,d kbps", it / 1000) }).joinToString(" · ")
        } else {
            listOfNotNull(bitrate?.let { String.format(Locale.US, "%,d kbps", it / 1000) }, rate).joinToString(" · ")
        }
        return format to quality
    }

    /** The current song's path, read when the Signal Path page opens (nothing is polled). */
    fun signalPath(): SignalPath? {
        val format = songFormat ?: return null
        val source = songSource
        val lossless = isLossless(source)
        val sourceName = source?.sampleMimeType?.let { codecName(it) } ?: "Audio"
        val sourceBits = source?.pcmEncoding?.takeIf { it != Format.NO_VALUE }?.let { encodingLabel(it, pcm = true) }
        val sourceRate = (source?.sampleRate?.takeIf { it > 0 } ?: format.sampleRate)
        val bitrate = source?.let { if (it.averageBitrate > 0) it.averageBitrate else it.bitrate }?.takeIf { it > 0 }
        val sourceDetail = listOfNotNull(
            sourceBits, khz(sourceRate), if (!lossless && bitrate != null) "${bitrate / 1000} kbps" else null
        ).joinToString(" / ")
        val route = songRoute
        val exact = when (route) {
            SignalPath.Route.DIRECT, SignalPath.Route.EXCLUSIVE -> exclusiveSink?.isExact()
            SignalPath.Route.ANDROID_BIT_PERFECT -> true
            SignalPath.Route.MIXER -> null
        }
        val mixerRate = if (route == SignalPath.Route.MIXER) {
            audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()?.takeIf { it != format.sampleRate }
        } else null
        return SignalPath(
            source = "$sourceName · ${if (lossless) "Lossless" else "Lossy"}",
            sourceDetail = sourceDetail,
            lossless = lossless,
            decoded = "${encodingLabel(format.pcmEncoding, pcm = true)} / ${khz(format.sampleRate)}",
            route = route,
            output = songOutput ?: "Phone Speaker",
            outputDetail = songOutputDetail,
            mixerRateHz = mixerRate,
            exact = exact,
            bits = sourceBits(source) ?: sourceBits(format) ?: 16,
            rateHz = sourceRate
        )
    }

    /** The player is gone: hand every DAC back to Android. */
    fun onPlayerReleased() {
        songFormat = null
        songSource = null
        exclusiveDeviceId = 0
        if (Build.VERSION.SDK_INT >= 34) bitPerfectOn?.let { clearBitPerfect(it) }
        closeUsbSession(expectNormal = false)
        _status.value = null
    }

    /** The mode changed in Settings: let go of what the old mode held. */
    fun onModeChanged() {
        // Whatever exclusive stream is playing belongs to the old mode; the song moves on the normal way.
        if (settings.value.bitPerfect != BitPerfectMode.USB_DIRECT) closeUsbSession(expectNormal = true)
        exclusiveSink?.detach()
        exclusiveDeviceId = 0
        if (settings.value.bitPerfect == BitPerfectMode.USB_DIRECT) usbDac()?.let { askedPermissionFor = null; requestPermission(it) }
        if (settings.value.bitPerfect != BitPerfectMode.USB_ANDROID && Build.VERSION.SDK_INT >= 34) bitPerfectOn?.let { clearBitPerfect(it) }
    }

    private fun deviceName(info: AudioDeviceInfo): String =
        info.productName?.toString()?.takeIf { it.isNotBlank() && it != Build.MODEL } ?: typeLabel(info)

    private fun typeLabel(info: AudioDeviceInfo?): String = when (info?.type) {
        null, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone Speaker"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired"
        AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Line Out"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
        else -> if (Build.VERSION.SDK_INT >= 31 && info.type == AudioDeviceInfo.TYPE_BLE_HEADSET) "Bluetooth" else "Output"
    }

    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else @Suppress("DEPRECATION") getParcelableExtra(UsbManager.EXTRA_DEVICE)

    private companion object {
        const val TAG = "AudioOutput"
        const val ACTION_USB_PERMISSION = "com.abn3li.telemusic.USB_DAC_PERMISSION"
        const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        const val NORMAL_HOLD_MS = 3_000L
        val LOSSLESS = setOf(MimeTypes.AUDIO_FLAC, MimeTypes.AUDIO_ALAC, MimeTypes.AUDIO_RAW, MimeTypes.AUDIO_WAV)
        val MEDIA: android.media.AudioAttributes = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        fun khz(rate: Int): String =
            String.format(Locale.US, if (rate % 1000 == 0) "%.0f kHz" else "%.1f kHz", rate / 1000.0)

        fun encodingLabel(encoding: Int, pcm: Boolean = false): String = when (encoding) {
            C.ENCODING_PCM_FLOAT -> "32-bit float"
            C.ENCODING_PCM_24BIT -> "24-bit"
            C.ENCODING_PCM_32BIT -> "32-bit"
            C.ENCODING_PCM_8BIT -> "8-bit"
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> if (pcm) "24-bit" else "24-bit"
            AudioFormat.ENCODING_PCM_32BIT -> if (pcm) "32-bit" else "32-bit"
            else -> "16-bit"
        }

        fun codecName(mime: String): String = when (mime) {
            MimeTypes.AUDIO_FLAC -> "FLAC"
            MimeTypes.AUDIO_ALAC -> "ALAC"
            MimeTypes.AUDIO_MPEG -> "MP3"
            MimeTypes.AUDIO_AAC -> "AAC"
            MimeTypes.AUDIO_OPUS -> "Opus"
            MimeTypes.AUDIO_VORBIS -> "Vorbis"
            MimeTypes.AUDIO_RAW, MimeTypes.AUDIO_WAV -> "PCM"
            else -> mime.substringAfter('/').uppercase(Locale.US)
        }
    }
}

/**
 * The player's audio sink. Each song goes either to Android's normal sink (with float only where
 * the router allows it) or to the exclusive sink, chosen when the song's format is set.
 */
@UnstableApi
internal class RoutedAudioSink(private val normal: AudioSink, private val router: AudioOutputRouter) : AudioSink {
    private val exclusive = ExclusiveAudioSink(object : ExclusiveOpener {
        override fun open(format: Format) = router.openExclusive(format)
        override fun onIdleRelease() = router.onIdleRelease()
    }).also { router.exclusiveSink = it }
    private var active: AudioSink = normal
    private var lastConfig: Triple<Format, Int, IntArray?>? = null
    private val both get() = listOf(normal, exclusive)

    override fun setListener(listener: AudioSink.Listener) = both.forEach { it.setListener(listener) }
    override fun setPlayerId(playerId: PlayerId?) = both.forEach { it.setPlayerId(playerId) }
    override fun setClock(clock: Clock) = normal.setClock(clock)
    override fun supportsFormat(format: Format) = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getFormatSupport(format: Format): Int {
        if (MimeTypes.AUDIO_RAW == format.sampleMimeType && format.pcmEncoding == C.ENCODING_PCM_FLOAT) {
            return if (router.floatAllowed()) AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY else AudioSink.SINK_FORMAT_UNSUPPORTED
        }
        return normal.getFormatSupport(format)
    }

    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport = normal.getFormatOffloadSupport(format)
    override fun getCurrentPositionUs(sourceEnded: Boolean) = active.getCurrentPositionUs(sourceEnded)

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        lastConfig = Triple(inputFormat, specifiedBufferSize, outputChannels)
        val source = router.takeSource()
        if (router.exclusiveWanted(source) && exclusive.tryConfigure(inputFormat)) {
            if (active !== exclusive) { normal.reset(); active = exclusive }
            router.onExclusiveSong(inputFormat)
            return
        }
        if (active !== normal) { exclusive.reset(); active = normal }
        if (MimeTypes.AUDIO_RAW == inputFormat.sampleMimeType) router.onSongFormat(inputFormat)
        normal.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun play() = both.forEach { it.play() }
    override fun handleDiscontinuity() = active.handleDiscontinuity()
    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        // The exclusive stream was taken away mid-song (mode changed, DAC gone): carry on the normal way.
        if (active === exclusive && exclusive.detached) {
            val (format, size, channels) = lastConfig ?: return true
            exclusive.reset()
            active = normal
            if (MimeTypes.AUDIO_RAW == format.sampleMimeType) router.onSongFormat(format)
            normal.configure(format, size, channels)
        }
        // Straight after the direct driver let the DAC go: wait until Android has it again, so
        // nothing meant for the DAC starts on the phone speaker (bounded, a few seconds at most).
        if (active === normal && router.normalOutputPending()) return false
        return active.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }
    override fun playToEndOfStream() = active.playToEndOfStream()
    override fun isEnded() = active.isEnded()
    override fun hasPendingData() = active.hasPendingData()
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) = both.forEach { it.setPlaybackParameters(playbackParameters) }
    override fun getPlaybackParameters(): PlaybackParameters = active.playbackParameters
    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) = normal.setSkipSilenceEnabled(skipSilenceEnabled)
    override fun getSkipSilenceEnabled() = active.skipSilenceEnabled
    override fun setAudioAttributes(audioAttributes: AudioAttributes) = both.forEach { it.setAudioAttributes(audioAttributes) }
    override fun getAudioAttributes(): AudioAttributes? = normal.audioAttributes
    override fun setAudioSessionId(audioSessionId: Int) = normal.setAudioSessionId(audioSessionId)
    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) = normal.setAuxEffectInfo(auxEffectInfo)
    override fun setPreferredDevice(audioDeviceInfo: android.media.AudioDeviceInfo?) = normal.setPreferredDevice(audioDeviceInfo)
    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) = normal.setOutputStreamOffsetUs(outputStreamOffsetUs)
    override fun enableTunnelingV21() = normal.enableTunnelingV21()
    override fun disableTunneling() = normal.disableTunneling()
    override fun setOffloadMode(offloadMode: Int) = normal.setOffloadMode(offloadMode)
    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) = normal.setOffloadDelayPadding(delayInFrames, paddingInFrames)
    override fun setVolume(volume: Float) = both.forEach { it.setVolume(volume) }
    override fun pause() = both.forEach { it.pause() }
    override fun flush() = both.forEach { it.flush() }
    override fun reset() = both.forEach { it.reset() }
    override fun release() = both.forEach { it.release() }
}
