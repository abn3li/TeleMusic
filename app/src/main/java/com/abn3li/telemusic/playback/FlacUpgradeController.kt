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
    private var cachedKey: String? = null
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
        // Starts as soon as the song is chosen - alongside YouTube's own buffering, not after it.
        if (!store.preferences.value.enabled || item == null || item.mediaId == attempted?.mediaId || original != null) return
        if (item.localConfiguration?.uri?.scheme !in listOf("http", "https", "file", "content")) return
        // Not known before the stream is prepared: the library's own length is used then.
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        val title = item.mediaMetadata.title?.toString().orEmpty()
        val artist = item.mediaMetadata.artist?.toString().orEmpty()
        if (title.isBlank() || artist.isBlank() || artist == "Unknown artist") return
        attempted = item
        idleClose?.cancel()
        job = scope.launch {
            // A song skipped within this moment is never searched: tapping through several songs
            // sends one search, for the one that stays.
            delay(SEARCH_SETTLE_MS)
            var requestClient: PeerFlacClient? = client
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
        val librarySong = withContext(Dispatchers.IO) { item.mediaId.toLongOrNull()?.let { app.musicRepository.getSongById(it) } }
        // Telegram must play its own file, including when that file is cached locally.
        // Check the song's source before credentials, cached upgrades or peer searches.
        if (librarySong?.youtubeVideoId == null || librarySong.isLocalImport) return
        val credentials = store.credentials() ?: return
        if (librarySong?.sourceMime?.contains("flac", ignoreCase = true) == true) {
            store.status(item.mediaId, FlacUpgradeStage.LOSSLESS, "Already playing FLAC.")
            return
        }
        val durationMs = duration.takeIf { it > 0 } ?: (librarySong.durationSeconds * 1000L)
        if (durationMs <= 0) return
        val target = FlacTarget(title, artist, librarySong?.album, durationMs)
        val cacheToken = withContext(Dispatchers.IO) { app.flacCache.token() }
        val cached = withContext(NonCancellable + Dispatchers.IO) {
            app.flacCache.acquire(item.mediaId, target) {
                app.workScope.launch(Dispatchers.IO) { app.musicRepository.enforceCacheLimit() }
            }
        }
        if (cached != null) {
            var switched = false
            try {
                currentCoroutineContext().ensureActive()
                if (player.currentMediaItem != item) throw CancellationException()
                cachedKey = cached.key
                switchToFlac(item, cached.buffer, cached.header)
                switched = true
                app.workScope.launch(Dispatchers.IO) { app.musicRepository.enforceCacheLimit(item.mediaId.toLongOrNull()) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fallback(); throw e }
            finally {
                if (!switched) withContext(NonCancellable + Dispatchers.IO) { cached.buffer.deleteWhenUnused() }
            }
            return
        }
        // A saved YouTube original may use a local URI. Reuse its cached upgrade, but
        // do not start a network search for a locally saved file.
        if (item.localConfiguration?.uri?.scheme !in listOf("http", "https")) return
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
        val ready = try {
            awaitReadyFlacBuffer(session, target, directory, position = {
                if (player.currentMediaItem != item) throw CancellationException()
                player.currentPosition
            }, report = { stage, detail, transfer -> store.status(item.mediaId, stage, detail, transfer) })
        } catch (e: FlacPeerException) {
            store.status(item.mediaId, FlacUpgradeStage.UNAVAILABLE, e.reason)
            return
        }
        var switched = false
        try {
            if (player.currentMediaItem != item) throw CancellationException()
            ready.buffer.checkFailure()
            switchToFlac(item, ready.buffer, ready.header)
            switched = true
            session.stopSearch()
            // Only the winning transfer remains; completion needs no further polling.
            withContext(Dispatchers.IO) { session.awaitFinished(ready.candidate.user) }
            // Saving a completed upgrade must never turn successful playback into a failure.
            withContext(Dispatchers.IO) {
                try {
                    if (app.flacCache.store(item.mediaId, target, ready.candidate.filename, ready.buffer, cacheToken)) {
                        app.musicRepository.enforceCacheLimit(item.mediaId.toLongOrNull())
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { android.util.Log.w("FlacCache", "Completed upgrade could not be cached") }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val reason = if (e is FlacPeerException) e.reason else "FLAC transfer interrupted"
            if (!fallback(reason)) store.status(item.mediaId, FlacUpgradeStage.UNAVAILABLE, reason)
        } finally {
            if (!switched) withContext(NonCancellable + Dispatchers.IO) {
                session.discardTransfer(ready.candidate.user)
                ready.file.delete()
            }
        }
    }

    private fun switchToFlac(item: MediaItem, buffer: FlacStreamBuffer, header: FlacHeader) {
        val transfer = FlacTransferInfo(buffer.totalSize, buffer.transferProgress,
            header.sampleRate, header.bitDepth, header.channels)
        val key = UUID.randomUUID().toString()
        FlacStreamRegistry.streams[key] = buffer
        original = item
        streamKey = key
        file = buffer.file
        activeHeader = header
        changingSource = true
        try {
            val position = player.currentPosition
            val playing = player.playWhenReady
            player.setMediaItem(item.buildUpon().setUri(Uri.parse("quality://stream/$key"))
                .setMimeType(MimeTypes.AUDIO_FLAC).build(), position)
            player.prepare()
            player.playWhenReady = playing
        } finally { changingSource = false }
        store.status(item.mediaId, FlacUpgradeStage.PREPARING,
            "Preparing FLAC playback at the current position.", transfer)
        prepareTimeout?.cancel()
        prepareTimeout = scope.launch {
            delay(5000)
            if (streamKey == key && player.playbackState != androidx.media3.common.Player.STATE_READY) {
                fallback("FLAC playback preparation timed out.")
            }
        }
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
        cachedKey?.let { key -> app.workScope.launch(Dispatchers.IO) { app.flacCache.invalidate(key) } }
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
        cachedKey = null
        app.workScope.launch(Dispatchers.IO) {
            if (buffer != null) buffer.deleteWhenUnused() else oldFile?.delete()
            app.musicRepository.enforceCacheLimit()
        }
    }

    private companion object {
        const val SEARCH_SETTLE_MS = 400L
    }

    fun close() {
        stopRequest()
        idleClose?.cancel()
        client?.close()
        clearStream()
    }
}
