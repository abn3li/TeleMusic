package com.abn3li.telemusic.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.data.quality.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

/** One search per played item, owned by the service so background playback behaves the same. */
internal class FlacUpgradeController(
    private val app: TgMusicApp,
    private val player: ExoPlayer,
    private val scope: CoroutineScope
) {
    private val store get() = app.flacUpgradeStore
    private val requestLock = Mutex()
    private var job: Job? = null
    private var idleClose: Job? = null
    private var prepareTimeout: Job? = null
    private var client: PeerFlacClient? = null
    private var clientUser: String? = null
    private var attempted: MediaItem? = null
    private var original: MediaItem? = null
    private var streamKey: String? = null
    private var file: File? = null
    private var activeHeader: FlacHeader? = null
    private var changingSource = false
    private var nextLoginAt = 0L
    private val detachedSessions = mutableSetOf<PeerFlacClient>()

    init {
        scope.launch {
            store.preferences.collect {
                if (!it.enabled || clientUser != null && clientUser != it.username) {
                    fallback()
                    stopRequest()
                    withContext(Dispatchers.IO) { client?.close() }
                    client = null
                    clientUser = null
                    attempted = null
                }
            }
        }
        // A crashed process can leave only this private temporary directory behind.
        scope.launch(Dispatchers.IO) {
            File(app.cacheDir, "quality_streams").listFiles()?.forEach { it.delete() }
        }
    }

    fun onPlayerEvent() {
        if (changingSource) return
        val item = player.currentMediaItem
        if (store.upgradeStatus.value.songId != item?.mediaId) store.resetStatus(item?.mediaId)
        if (original != null && item?.localConfiguration?.uri?.scheme != "quality") {
            stopRequest()
            original = null
            clearStream()
        }
        if (attempted != null && item?.mediaId != attempted?.mediaId) {
            stopRequest()
            attempted = null
        }
        if (!player.playWhenReady) {
            // Pause keeps this song's search, transfer and cache alive. Track changes above,
            // account changes and real transfer failures still own their normal cleanup.
            return
        }
        if (!store.preferences.value.enabled || !player.isPlaying || item == null || item.mediaId == attempted?.mediaId || original != null) return
        if (item.localConfiguration?.uri?.scheme !in listOf("http", "https", "tdlib")) return
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0) return
        val title = item.mediaMetadata.title?.toString().orEmpty()
        val artist = item.mediaMetadata.artist?.toString().orEmpty()
        if (title.isBlank() || artist.isBlank() || artist == "Unknown artist") return
        attempted = item
        idleClose?.cancel()
        job = scope.launch {
            var requestClient: PeerFlacClient? = null
            requestLock.withLock {
                idleClose?.cancel()
                try { upgrade(item, title, artist, duration) { requestClient = it } }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (player.currentMediaItem == item) store.status(item.mediaId, FlacUpgradeStage.UNAVAILABLE, if (e is FlacSignInException) {
                        "Check your Soulseek account in Settings → Audio Quality."
                    } else if (e is FlacTimeoutException) {
                        "${e.operation} timed out."
                    } else "FLAC connection unavailable.")
                } finally {
                    if (requestClient != null && client !== requestClient && !detachedSessions.remove(requestClient)) {
                        withContext(NonCancellable + Dispatchers.IO) { requestClient?.close() }
                    }
                    idleClose?.cancel()
                    idleClose = scope.launch {
                        // A short one-shot grace period avoids login churn during rapid song changes.
                        delay(30000)
                        requestLock.withLock {
                            if (client !== requestClient) return@withLock
                            val oldClient = client
                            client = null
                            clientUser = null
                            withContext(Dispatchers.IO) { oldClient?.close() }
                        }
                    }
                }
            }
        }
    }

    private suspend fun upgrade(item: MediaItem, title: String, artist: String, duration: Long,
        onSession: (PeerFlacClient) -> Unit) {
        val credentials = store.credentials() ?: return
        val librarySong = withContext(Dispatchers.IO) { item.mediaId.toLongOrNull()?.let { app.musicRepository.getSongById(it) } }
        if (librarySong?.sourceMime?.contains("flac", ignoreCase = true) == true) {
            store.status(item.mediaId, FlacUpgradeStage.LOSSLESS, "Already playing FLAC.")
            return
        }
        val target = FlacTarget(title, artist, librarySong?.album, duration)
        store.status(item.mediaId, FlacUpgradeStage.SEARCHING, "Looking for the same recording in FLAC.")
        val session = withContext(Dispatchers.IO) {
            client?.takeIf { it.isConnected } ?: run {
                client?.close()
                client = null
                clientUser = null
                if (System.currentTimeMillis() < nextLoginAt) throw IllegalStateException("Connection cooling down")
                nextLoginAt = System.currentTimeMillis() + 30000
                PeerFlacClient(credentials.first, credentials.second).also { connecting ->
                    try { connecting.connect() }
                    catch (e: Exception) { connecting.close(); throw e }
                    nextLoginAt = 0L
                    client = connecting
                    clientUser = credentials.first
                }
            }
        }
        onSession(session)
        val candidates = withContext(Dispatchers.IO) { session.search(target) }
        if (candidates.isEmpty()) {
            store.status(item.mediaId, FlacUpgradeStage.UNAVAILABLE, session.lastSearchSummary)
            return
        }
        val directory = File(app.cacheDir, "quality_streams").apply { mkdirs() }
        var failureReason = "available peers could not supply FLAC"
        val triedUsers = mutableSetOf<String>()
        while (triedUsers.size < 6) {
            currentCoroutineContext().ensureActive()
            val candidate = withContext(Dispatchers.IO) { session.nextCandidate(target, triedUsers) } ?: break
            triedUsers.add(candidate.user)
            val pendingFile = File(directory, "${UUID.randomUUID()}.flac")
            var switched = false
            try {
                val peerLabel = "peer ${triedUsers.size}"
                failureReason = "peer could not start the transfer"
                store.status(item.mediaId, FlacUpgradeStage.REQUESTING, "Requesting FLAC from $peerLabel.")
                val buffer = withContext(Dispatchers.IO) { session.download(candidate, pendingFile) }
                var transfer = FlacTransferInfo(buffer.totalSize, buffer.transferProgress)
                store.status(item.mediaId, FlacUpgradeStage.BUFFERING, "Checking the FLAC recording before switching.", transfer)
                failureReason = "FLAC transfer interrupted"
                // Keep a progressing download alive; separately bound silence and total wait.
                withFlacTimeout(180000, "FLAC download") {
                    var header: FlacHeader? = null
                    while (!switched) {
                        withFlacTimeout(20000, "FLAC data stalled") { buffer.updates.receive() }
                        buffer.checkFailure()
                        if (player.currentMediaItem != item) throw CancellationException()
                        if (header == null) header = withContext(Dispatchers.IO) { readFlacHeader(buffer.prefix()) }
                        if (header == null) {
                            check(!buffer.complete) { "Incomplete FLAC metadata" }
                            continue
                        }
                        val verified = header!!
                        if (!verifiedRecording(verified, target, candidate.filename)) {
                            failureReason = if (kotlin.math.abs(verified.durationMs - target.durationMs) > 2500)
                                "FLAC timing differs · unsafe to switch" else "FLAC recording did not match"
                            error("Different recording")
                        }
                        transfer = transfer.copy(sampleRate = verified.sampleRate, bitDepth = verified.bitDepth,
                            channels = verified.channels)
                        store.status(item.mediaId, FlacUpgradeStage.BUFFERING,
                            if (verified.seekTable) "Switches when enough audio is buffered."
                            else "This FLAC needs the complete file before switching.", transfer)
                        // Search and handshake delays are not the uploader's transfer speed.
                        val transferMs = (System.nanoTime() - buffer.firstByteAtNanos) / 1_000_000
                        // Keep at least 5 MB, and enough audio around the current position.
                        // A completed smaller file can play immediately.
                        if (!readyFlacBuffer(buffer.totalSize, verified.durationMs, player.currentPosition,
                                buffer.available, buffer.complete, verified.seekTable, transferMs)) {
                            val measuredRate = buffer.available * 1000.0 / transferMs.coerceAtLeast(1)
                            val playbackRate = buffer.totalSize * 1000.0 / verified.durationMs
                            if (transferMs >= 5000 && measuredRate < playbackRate * 1.2 &&
                                session.hasFasterCandidate(target, triedUsers, measuredRate)) {
                                failureReason = "uploader too slow · trying another peer"
                                android.util.Log.i("FlacTransfer", "Trying a faster available uploader")
                                error("Slow FLAC uploader")
                            }
                            continue
                        }
                        val key = UUID.randomUUID().toString()
                        FlacStreamRegistry.streams[key] = buffer
                        original = item
                        streamKey = key
                        file = pendingFile
                        activeHeader = verified
                        android.util.Log.i("FlacTransfer", "Switching to verified FLAC; received=${buffer.available}/${buffer.totalSize}; transferMs=$transferMs")
                        changingSource = true
                        try {
                            val position = player.currentPosition
                            val playing = player.playWhenReady
                            player.setMediaItem(item.buildUpon().setUri(Uri.parse("quality://stream/$key"))
                                .setMimeType(MimeTypes.AUDIO_FLAC).build(), position)
                            player.prepare()
                            // A buffer can become ready while paused; preparing it must stay silent.
                            player.playWhenReady = playing
                        } finally { changingSource = false }
                        store.status(item.mediaId, FlacUpgradeStage.PREPARING, "Preparing FLAC playback at the current position.", transfer)
                        session.stopSearch()
                        prepareTimeout?.cancel()
                        prepareTimeout = scope.launch {
                            delay(5000)
                            if (streamKey == key && player.playbackState != androidx.media3.common.Player.STATE_READY) {
                                android.util.Log.i("FlacTransfer", "FLAC preparation timed out; restoring original audio")
                                fallback("FLAC playback preparation timed out.")
                            }
                        }
                        switched = true
                    }
                }
                // Keep the connection while bytes arrive; completion needs no further polling.
                withContext(Dispatchers.IO) { session.awaitFinished() }
                return
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e is FlacTimeoutException) failureReason = "${e.operation} timed out"
                if (e is FlacPeerException) failureReason = e.reason
                if (switched) { fallback(failureReason); return }
                store.status(item.mediaId, FlacUpgradeStage.SEARCHING, "Looking for another uploader. $failureReason")
            } finally {
                if (!switched) {
                    withContext(NonCancellable + Dispatchers.IO) { session.cancelTransfer(); pendingFile.delete() }
                }
            }
        }
        store.status(item.mediaId, FlacUpgradeStage.UNAVAILABLE, failureReason)
    }

    fun onReady() {
        if (original != null && player.currentMediaItem?.localConfiguration?.uri?.scheme == "quality") {
            prepareTimeout?.cancel()
            prepareTimeout = null
            android.util.Log.i("FlacTransfer", "FLAC playback ready")
            store.playingFlac(player.currentMediaItem?.mediaId, activeHeader,
                streamKey?.let { FlacStreamRegistry.streams[it] })
            val buffer = streamKey?.let { FlacStreamRegistry.streams[it] }
            val header = activeHeader
            val transfer = if (buffer != null && header != null) FlacTransferInfo(buffer.totalSize,
                buffer.transferProgress, header.sampleRate, header.bitDepth, header.channels) else null
            store.status(player.currentMediaItem?.mediaId, FlacUpgradeStage.LOSSLESS, "Quality upgraded successfully.", transfer)
        }
    }

    /** A stalled transfer or seek beyond received bytes restores normal playback at the same time. */
    fun fallback(reason: String = "FLAC playback or transfer became unavailable."): Boolean {
        val item = original ?: return false
        original = null
        changingSource = true
        try {
            val position = player.currentPosition
            val playing = player.playWhenReady
            player.setMediaItem(item, position)
            player.prepare()
            player.playWhenReady = playing
        } finally { changingSource = false }
        stopRequest()
        clearStream()
        store.status(item.mediaId, FlacUpgradeStage.UNAVAILABLE, reason)
        return true
    }

    private fun stopRequest() {
        client?.stopSearch()
        val buffer = streamKey?.let { FlacStreamRegistry.streams[it] }
        if (buffer?.shouldFinishDownload == true) {
            // Download owns the ongoing transfer after playback leaves it. The old request
            // closes its session on completion; a new song gets an independent session.
            val session = client
            if (session != null) {
                detachedSessions.add(session)
                app.workScope.launch(Dispatchers.IO) {
                    try { session.awaitFinished() }
                    catch (_: Exception) { buffer.fail("FLAC download interrupted") }
                    finally { session.close() }
                }
            }
            job?.cancel()
            job = null
            client = null
            clientUser = null
            return
        }
        job?.cancel()
        job = null
        client?.cancelTransfer()
    }

    private fun clearStream() {
        prepareTimeout?.cancel()
        prepareTimeout = null
        store.playingFlac(null)
        store.resetStatus(player.currentMediaItem?.mediaId)
        val buffer = streamKey?.let { FlacStreamRegistry.streams.remove(it) }
        if (buffer?.shouldFinishDownload != true) buffer?.fail("Stream replaced")
        streamKey = null
        val oldFile = file
        file = null
        activeHeader = null
        app.workScope.launch(Dispatchers.IO) {
            if (buffer != null) buffer.deleteWhenUnused() else oldFile?.delete()
        }
    }

    fun close() {
        stopRequest()
        idleClose?.cancel()
        client?.close()
        clearStream()
    }
}
