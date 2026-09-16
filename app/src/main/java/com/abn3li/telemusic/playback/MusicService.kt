package com.abn3li.telemusic.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
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

    override fun onCreate() {
        super.onCreate()
        val app = application as TgMusicApp
        val dataSourceFactory = DefaultDataSource.Factory(this, ResolvingDataSource.Factory(app.tdlibManager))

        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)

        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setHandleAudioBecomingNoisy(true)
            .build()

        // The app manages next/previous itself via PlaybackQueue rather than giving ExoPlayer a
        // real multi-item timeline (each song simply replaces the player's one MediaItem), so
        // the raw player always reports a single-item timeline and the system media
        // notification (which reads availability straight off the player) never showed a skip
        // button at all. This wrapper answers both "is there a next/previous song" and "what
        // happens when that command is invoked" from the app's own queue instead - see its own
        // doc for the full explanation.
        val queueAwarePlayer = QueueAwareForwardingPlayer(
            player = player,
            queueHasNext = { app.playbackQueue.hasNext() },
            queueHasPrevious = { app.playbackQueue.hasPrevious() },
            onSeekToNext = { serviceScope.launch { playAdjacentSong(app.playbackQueue.next()) } },
            onSeekToPrevious = { serviceScope.launch { playAdjacentSong(app.playbackQueue.previous()) } }
        )

        // Without a session activity, tapping the notification's body (as opposed to one of
        // its transport buttons, which already worked) did nothing at all - Media3's default
        // notification uses exactly this PendingIntent as its content intent.
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

    /**
     * Mirrors the essential parts of NowPlayingViewModel.loadCurrentQueuePosition() - fetch the
     * song, refresh its Telegram file id if needed, and hand the player a new MediaItem - but
     * scoped to this service so the system notification's next/previous buttons work even when
     * no Activity/ViewModel currently exists (app swiped away, controlling from a locked
     * screen). [app.playbackQueue] has already been advanced by the caller by the time this
     * runs; NowPlayingViewModel picks up the resulting song/lyrics change on its own via the
     * player's onMediaItemTransition callback (see PlaybackController.addListener) rather than
     * this service touching any UI state directly.
     */
    private suspend fun playAdjacentSong(songId: Long?) {
        if (songId == null) return
        val app = application as TgMusicApp
        val song = withContext(Dispatchers.IO) { app.musicRepository.getSongById(songId) } ?: return

        val localPath = song.localFilePath
        val uri = when {
            localPath != null && File(localPath).let { it.exists() && it.length() > 0 } ->
                File(localPath).toURI().toString().toUri()
            // A real library row backed by a YouTube video that hasn't been downloaded (see
            // MusicRepository.importPlaylistTrackAsStreamable's own doc) - same resolution
            // NowPlayingViewModel.startPlayback uses, needed here too so the system
            // notification's own next/previous buttons work for one of these rows.
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
        // See PlaybackController.playUri()'s comment - same single-instance MediaItem swap
        // requires an explicit stop() first to fully reset the audio renderer between songs.
        player.stop()
        player.setMediaItem(item)
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