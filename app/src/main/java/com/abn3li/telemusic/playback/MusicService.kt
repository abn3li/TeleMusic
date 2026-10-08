package com.abn3li.telemusic.playback

import kotlinx.coroutines.Job
import android.app.PendingIntent
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.abn3li.telemusic.widget.MusicWidgets
import android.util.Log
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.abn3li.telemusic.MainActivity
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.data.download.DownloadQuality
import com.abn3li.telemusic.data.local.displayArtworkUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// A song whose fresh YouTube link also fails isn't refetched again within this window.
private const val LINK_REFRESH_COOLDOWN_MS = 60_000L

// Unplayable songs passed over in a row when a song ends, before playback just stops.
private const val MAX_UNPLAYABLE_SKIPS = 5

// How often the widget progress bar moves while music plays (see widgetProgressTick).
private const val WIDGET_TICK_MS = 1_000L

class MusicService : MediaLibraryService() {
    companion object {
        // The running service, for the widget's next/previous (same process, main thread).
        @Volatile var running: MusicService? = null
            private set
    }

    // MediaLibrarySession (not a plain MediaSession) is what makes this service browsable by
    // Android Auto/Automotive - see AutoLibraryCallback for the actual browse tree/car playback
    // resolution wired in below via .setCallback().
    private var mediaSession: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var flacUpgrade: FlacUpgradeController? = null
    private var youtubeHistory: YouTubeHistoryReporter? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        running = this
        val app = application as TgMusicApp
        val normalDataSourceFactory = DefaultDataSource.Factory(this, ResolvingDataSource.Factory(app.tdlibManager))
        val dataSourceFactory = androidx.media3.datasource.DataSource.Factory {
            FlacRoutingDataSource(normalDataSourceFactory.createDataSource())
        }

        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setMp4ExtractorFlags(Mp4Extractor.FLAG_READ_SEF_DATA)

        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory,
            FlacStreamingExtractorsFactory(extractorsFactory))

        // Dynamic Audio Engine: Prioritize software extension decoders (FFmpeg ALAC/FLAC/Opus) over system MediaCodec
        val isFfmpegAvailable = FfmpegLibrary.isAvailable()
        val supportsAlac = FfmpegLibrary.supportsFormat("audio/alac")
        val supportsFlac = FfmpegLibrary.supportsFormat("audio/flac")
        Log.d("MusicService", "FFmpeg Native Engine Loaded: $isFfmpegAvailable, ALAC: $supportsAlac, FLAC: $supportsFlac")

        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableAudioFloatOutput(false)
            .setEnableAudioTrackPlaybackParams(true)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        // Audio only: a YouTube stream can be a video file (YouTube now often answers with one),
        // and its picture was decoded in the background for nothing - CPU, battery and heat.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (player.currentMediaItem?.localConfiguration?.uri?.scheme == "quality") {
                    android.util.Log.i("FlacTransfer", "FLAC playback error: ${error.errorCodeName}")
                }
                if (flacUpgrade?.fallback() != true) refreshExpiredStreamLink(error)
            }

            // The end-of-song advance lives here, in the service, so the queue keeps playing
            // with the app closed (it used to live only in Now Playing's listener, which goes
            // away with the app's screen). The app follows along via its transition listener.
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) flacUpgrade?.onReady()
                if (playbackState == Player.STATE_ENDED) advanceAfterSongEnded()
            }

            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED,
                        Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_TIMELINE_CHANGED)) {
                    flacUpgrade?.onPlayerEvent()
                }
                if (events.containsAny(
                        Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED,
                        Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_POSITION_DISCONTINUITY
                    )
                ) onPlayerChangedForWidgets()
            }
        })
        flacUpgrade = FlacUpgradeController(app, player, serviceScope)
        youtubeHistory = YouTubeHistoryReporter(app, player, serviceScope)
        ContextCompat.registerReceiver(
            this, screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        val queueAwarePlayer = QueueAwareForwardingPlayer(
            player = player,
            queueHasNext = { app.playbackQueue.hasNext() },
            queueHasPrevious = { app.playbackQueue.hasPrevious() },
            onSeekToNext = { skipTo(app.playbackQueue.next()) },
            onSeekToPrevious = { skipTo(app.playbackQueue.previous()) }
        )

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).setFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            ),
            PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaLibrarySession.Builder(
            this, queueAwarePlayer, AutoLibraryCallback(app.musicRepository, app.playbackQueue, serviceScope)
        )
            .setSessionActivity(sessionActivity)
            .build()
    }

    // Notification / headset / lock screen / Android Auto skips. One at a time: a newer skip
    // cancels the previous one's resolve, so two quick taps can't finish out of order and leave
    // the player on a different song than the queue.
    private var skipJob: Job? = null

    // True while a song change started here is getting the next song ready (see
    // onUpdateNotification). The player sits paused or ended meanwhile, which would otherwise
    // drop the service out of the foreground - and from the background (screen off) Android
    // doesn't let it back in, so the next song played with no notification, or didn't start.
    @Volatile
    private var changingSong = false

    /** Runs a song change as the one current [skipJob], keeping the service in the foreground
     * until it's done. */
    private fun startSongChange(block: suspend () -> Unit) {
        skipJob?.cancel()
        changingSong = true
        val job = serviceScope.launch { block() }
        skipJob = job
        job.invokeOnCompletion { if (skipJob === job) changingSong = false }
    }

    @OptIn(UnstableApi::class)
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        super.onUpdateNotification(session, startInForegroundRequired || changingSong)
    }

    // Background "did this stream finish?" poll for the song a skip started - same auto-cache
    // bookkeeping NowPlayingViewModel does for in-app plays, replaced on every skip.
    private var cacheJob: Job? = null

    /** Widget next/previous: the same skip the notification buttons do. Main thread only. */
    fun skipToNextFromWidget() = skipTo((application as TgMusicApp).playbackQueue.next())
    fun skipToPreviousFromWidget() = skipTo((application as TgMusicApp).playbackQueue.previous())

    private fun skipTo(songId: Long?) {
        if (songId == null) return
        startSongChange { playAdjacentSong(songId) }
    }

    /** A song finished on its own: play the queue's next one (Repeat One replays it; Repeat off
     * stops at the end). A song that can't be played is passed over, a few at most, the way the
     * in-app player skips an unplayable song. */
    private fun advanceAfterSongEnded() {
        val queue = (application as TgMusicApp).playbackQueue
        // Only the queue's own current song ending moves the queue on - not a one-off stream
        // played from search (the queue is empty then) or an item already replaced.
        val endedId = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        if (endedId != queue.currentSongId()) return
        startSongChange {
            val before = queue.snapshot()
            var lastTried: Long? = null
            for (attempt in 0 until MAX_UNPLAYABLE_SKIPS) {
                val nextId = queue.next(auto = true) ?: break
                lastTried = nextId
                if (playAdjacentSong(nextId)) return@startSongChange
                // Not played because the user moved on meanwhile: leave the queue where they put it.
                if (queue.currentSongId() != nextId) return@startSongChange
            }
            // Nothing could be played: put the queue back on the song that ended, so the app
            // (still showing that song) and the queue agree, and say why the music stopped.
            if (lastTried != null && queue.currentSongId() == lastTried) {
                queue.restore(before)
                Toast.makeText(this@MusicService, "Couldn't play the next songs", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Plays [songId]; false when it couldn't be played (or the queue moved on meanwhile). */
    private suspend fun playAdjacentSong(songId: Long): Boolean {
        val app = application as TgMusicApp
        val repository = app.musicRepository

        // Pause, not stop: a stopped (idle) player makes Media3 take the notification down until
        // the next song starts, which flickered it on every change - and with the screen off it
        // never came back (see changingSong). The old song stays loaded until the new one
        // replaces it below.
        val outgoingId = player.currentMediaItem?.mediaId?.toLongOrNull()
        player.pause()
        cacheJob?.cancel()

        val song = withContext(Dispatchers.IO) { repository.getSongById(songId) } ?: return false
        val uri = withContext(Dispatchers.IO) { repository.resolvePlaybackUri(song) } ?: return false
        if (app.playbackQueue.currentSongId() != songId) return false

        val item = MediaItem.Builder()
            .setUri(uri)
            .setMediaId(song.telegramMessageId.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setArtworkUri(song.displayArtworkUri)
                    .setExtras(android.os.Bundle().apply {
                        putString(EXTRA_PLAYBACK_INSTANCE, java.util.UUID.randomUUID().toString())
                    })
                    .build()
            )
            .build()
        player.setMediaItem(item, true)
        player.prepare()
        player.play()

        // The old song's background download stops only now that it's out of the player:
        // cancelling it while still loaded could fail its source and stop the player anyway.
        // (Otherwise every skipped-past stream kept downloading to completion, untracked.)
        if (outgoingId != null && outgoingId != songId) {
            val outgoing = withContext(Dispatchers.IO) { repository.getSongById(outgoingId) }
            if (outgoing != null && !outgoing.isLocalImport && outgoing.youtubeVideoId == null &&
                !outgoing.isExplicitDownload && outgoing.localFilePath == null && outgoing.telegramFileId != 0
            ) {
                serviceScope.launch(Dispatchers.IO) { repository.cancelStreamingDownload(outgoing.telegramFileId) }
            }
        }

        withContext(Dispatchers.IO) { repository.stampLastPlayed(song) }
        if (!song.isLocalImport && song.youtubeVideoId == null && song.localFilePath == null) {
            cacheJob = serviceScope.launch(Dispatchers.IO) { repository.markStreamedFileCached(song) }
        }
        return true
    }

    // ---- Home-screen widgets ----
    // The widgets are redrawn on player events only. The one repeating job is the progress
    // bar's once-a-second step, and it runs only while ALL of these hold: music is playing, a
    // widget is placed, the screen is on and unlocked. Pause, screen off or lock stops it.
    private val widgetHandler = Handler(Looper.getMainLooper())
    private val widgetProgressTick = object : Runnable {
        override fun run() {
            if (!shouldTickWidgets()) return
            MusicWidgets.updateProgress(this@MusicService)
            widgetHandler.postDelayed(this, WIDGET_TICK_MS)
        }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateWidgetTicking()
    }

    private fun onPlayerChangedForWidgets() {
        val duration = player.duration.takeIf { it != androidx.media3.common.C.TIME_UNSET && it > 0 } ?: 0L
        MusicWidgets.onPlayerChanged(
            this,
            MusicWidgets.PlayerState(
                songId = player.currentMediaItem?.mediaId?.toLongOrNull(),
                isPlaying = player.isPlaying,
                positionMs = player.currentPosition,
                durationMs = duration
            )
        )
        updateWidgetTicking()
    }

    private fun shouldTickWidgets(): Boolean {
        if (!player.isPlaying || !MusicWidgets.hasWidgets(this)) return false
        val interactive = getSystemService(PowerManager::class.java)?.isInteractive ?: true
        val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked ?: false
        return interactive && !locked
    }

    private fun updateWidgetTicking() {
        widgetHandler.removeCallbacks(widgetProgressTick)
        if (shouldTickWidgets()) widgetHandler.postDelayed(widgetProgressTick, WIDGET_TICK_MS)
    }

    // The last expired-link recovery (see refreshExpiredStreamLink): its job, song and time.
    private var linkRefreshJob: Job? = null
    private var lastLinkRefreshSongId: Long? = null
    private var lastLinkRefreshAtMs = 0L

    /**
     * A YouTube stream link only works for a few hours, so resuming a song paused for longer
     * fails with an HTTP error that retrying the same link can never fix. This drops the cached
     * link, fetches a fresh one and continues from the same position, keeping play/pause as it
     * was. Runs only after an error, at most once per song per minute - a song that fails even
     * with a fresh link stays a normal playback error instead of retrying forever.
     */
    private fun refreshExpiredStreamLink(error: PlaybackException) {
        if (error.errorCode != PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) return
        val item = player.currentMediaItem ?: return
        val songId = item.mediaId.toLongOrNull() ?: return
        if (linkRefreshJob?.isActive == true) return
        val now = SystemClock.elapsedRealtime()
        if (songId == lastLinkRefreshSongId && now - lastLinkRefreshAtMs < LINK_REFRESH_COOLDOWN_MS) return
        lastLinkRefreshSongId = songId
        lastLinkRefreshAtMs = now
        val positionMs = player.currentPosition
        val repository = (application as TgMusicApp).musicRepository
        linkRefreshJob = serviceScope.launch {
            val song = withContext(Dispatchers.IO) { repository.getSongById(songId) }
            val uri = if (song != null) {
                val videoId = song.youtubeVideoId ?: return@launch
                repository.invalidateStreamCache(videoId)
                withContext(Dispatchers.IO) { repository.resolvePlaybackUri(song) } ?: return@launch
            } else {
                // A stream played from search or Discovery: no library row, so its video id
                // travels on the media item (see PlaybackController.playUri).
                val videoId = item.mediaMetadata.extras?.getString(EXTRA_YOUTUBE_VIDEO_ID) ?: return@launch
                val app = application as TgMusicApp
                app.ytDlpRepository.invalidateStreamCache(videoId)
                val stream = app.ytDlpRepository.resolveStreamUrl(videoId, DownloadQuality.BEST.formatSelector)
                    .getOrNull()?.streamUrl?.takeIf { it.isNotBlank() } ?: return@launch
                Uri.parse(stream)
            }
            // The user may have moved on to another song while the link was being fetched.
            if (player.currentMediaItem?.mediaId != item.mediaId) return@launch
            player.setMediaItem(item.buildUpon().setUri(uri).build(), positionMs)
            player.prepare()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player ?: return
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        if (running === this) running = null
        widgetHandler.removeCallbacks(widgetProgressTick)
        runCatching { unregisterReceiver(screenReceiver) }
        // The player goes away with the service: widgets show the song as paused.
        val last = MusicWidgets.player
        MusicWidgets.onPlayerChanged(
            this, last.copy(isPlaying = false, positionMs = last.positionNow(), atElapsedMs = SystemClock.elapsedRealtime())
        )
        flacUpgrade?.close()
        youtubeHistory?.close()
        serviceScope.cancel()
        mediaSession?.run { player.release(); release(); mediaSession = null }
        super.onDestroy()
    }
}
