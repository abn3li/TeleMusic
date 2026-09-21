package com.abn3li.telemusic.playback

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.net.toUri
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
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.abn3li.telemusic.MainActivity
import com.abn3li.telemusic.TgMusicApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MusicService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
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
            onSeekToNext = { serviceScope.launch { playAdjacentSong(app.playbackQueue.next()) } },
            onSeekToPrevious = { serviceScope.launch { playAdjacentSong(app.playbackQueue.previous()) } }
        )

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).setFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            ),
            PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, queueAwarePlayer)
            .setSessionActivity(sessionActivity)
            .build()
    }

    private suspend fun playAdjacentSong(songId: Long?) {
        if (songId == null) return
        val app = application as TgMusicApp
        val song = withContext(Dispatchers.IO) { app.musicRepository.getSongById(songId) } ?: return

        var localPath = song.localFilePath
        if (localPath != null && !File(localPath).exists()) {
            withContext(Dispatchers.IO) { app.musicRepository.clearStaleLocalPath(song.telegramMessageId) }
            localPath = null
        }

        val uri = when {
            localPath != null && File(localPath).length() > 0 ->
                File(localPath).toURI().toString().toUri()
            song.youtubeVideoId != null -> {
                val resolved = withContext(Dispatchers.IO) { app.musicRepository.resolveDirectPlaybackUri(song) }
                resolved ?: return
            }
            else -> {
                val freshFileId = withContext(Dispatchers.IO) { app.musicRepository.getFreshFileIdForSong(song) }
                TdlibDataSource.uriFor(freshFileId)
            }
        }

        val item = MediaItem.Builder()
            .setUri(uri)
            .setMediaId(song.telegramMessageId.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setArtworkUri(song.albumArtUrl?.toUri())
                    .build()
            )
            .build()
        player.setMediaItem(item, true)
        player.prepare()
        player.play()

        withContext(Dispatchers.IO) { app.musicRepository.stampLastPlayed(song) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

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
