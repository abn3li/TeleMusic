package com.abn3li.telemusic.playback

import kotlinx.coroutines.Job
import android.app.PendingIntent
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.abn3li.telemusic.widget.MusicWidgets
import android.util.Log
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
import com.abn3li.telemusic.data.local.displayArtworkUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// A song whose fresh YouTube link also fails isn't refetched again within this window.
private const val LINK_REFRESH_COOLDOWN_MS = 60_000L

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

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        running = this
        val app = application as TgMusicApp
        val dataSourceFactory = DefaultDataSource.Factory(this, ResolvingDataSource.Factory(app.tdlibManager))

        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setMp4ExtractorFlags(Mp4Extractor.FLAG_READ_SEF_DATA)

        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)

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
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = refreshExpiredStreamLink(error)

            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(
                        Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED,
                        Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_POSITION_DISCONTINUITY
                    )
                ) onPlayerChangedForWidgets()
            }
        })
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
    // Background "did this stream finish?" poll for the song a skip started - same auto-cache
    // bookkeeping NowPlayingViewModel does for in-app plays, replaced on every skip.
    private var cacheJob: Job? = null

    /** Widget next/previous: the same skip the notification buttons do. Main thread only. */
    fun skipToNextFromWidget() = skipTo((application as TgMusicApp).playbackQueue.next())
    fun skipToPreviousFromWidget() = skipTo((application as TgMusicApp).playbackQueue.previous())

    private fun skipTo(songId: Long?) {
        if (songId == null) return
        skipJob?.cancel()
        skipJob = serviceScope.launch { playAdjacentSong(songId) }
    }

    private suspend fun playAdjacentSong(songId: Long) {
        val app = application as TgMusicApp
        val repository = app.musicRepository

        // Stop the old song and its background download right away, like the in-app path:
        // otherwise every skipped-past stream kept downloading to completion, untracked.
        val outgoingId = player.currentMediaItem?.mediaId?.toLongOrNull()
        player.stop()
        cacheJob?.cancel()
        if (outgoingId != null && outgoingId != songId) {
            val outgoing = withContext(Dispatchers.IO) { repository.getSongById(outgoingId) }
            if (outgoing != null && !outgoing.isLocalImport && outgoing.youtubeVideoId == null &&
                !outgoing.isExplicitDownload && outgoing.localFilePath == null && outgoing.telegramFileId != 0
            ) {
                serviceScope.launch(Dispatchers.IO) { repository.cancelStreamingDownload(outgoing.telegramFileId) }
            }
        }

        val song = withContext(Dispatchers.IO) { repository.getSongById(songId) } ?: return
        val uri = withContext(Dispatchers.IO) { repository.resolvePlaybackUri(song) } ?: return
        if (app.playbackQueue.currentSongId() != songId) return

        val item = MediaItem.Builder()
            .setUri(uri)
            .setMediaId(song.telegramMessageId.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setArtworkUri(song.displayArtworkUri)
                    .build()
            )
            .build()
        player.setMediaItem(item, true)
        player.prepare()
        player.play()

        withContext(Dispatchers.IO) { repository.stampLastPlayed(song) }
        if (!song.isLocalImport && song.youtubeVideoId == null && song.localFilePath == null) {
            cacheJob = serviceScope.launch(Dispatchers.IO) { repository.markStreamedFileCached(song) }
        }
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
            val song = withContext(Dispatchers.IO) { repository.getSongById(songId) } ?: return@launch
            val videoId = song.youtubeVideoId ?: return@launch
            repository.invalidateStreamCache(videoId)
            val uri = withContext(Dispatchers.IO) { repository.resolvePlaybackUri(song) } ?: return@launch
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
        serviceScope.cancel()
        mediaSession?.run { player.release(); release(); mediaSession = null }
        super.onDestroy()
    }
}
