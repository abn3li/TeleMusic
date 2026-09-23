package com.abn3li.telemusic.playback

import kotlinx.coroutines.Job
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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

class MusicService : MediaLibraryService() {
    // MediaLibrarySession (not a plain MediaSession) is what makes this service browsable by
    // Android Auto/Automotive - see AutoLibraryCallback for the actual browse tree/car playback
    // resolution wired in below via .setCallback().
    private var mediaSession: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player ?: return
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        mediaSession?.run { player.release(); release(); mediaSession = null }
        super.onDestroy()
    }
}
