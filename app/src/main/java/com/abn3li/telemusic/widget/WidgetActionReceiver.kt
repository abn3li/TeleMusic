package com.abn3li.telemusic.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.data.local.displayArtwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The widget buttons. Each tap is one short job that goes through the same controls the app
 * uses (the in-app MediaController, the shared queue), then ends - nothing keeps running.
 * Works with the app closed too: play then resumes the song the widget shows.
 */
class WidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val app = context.applicationContext as TgMusicApp
        val pending = goAsync()
        scope.launch {
            try {
                // A broadcast gets about 10 seconds; stay inside that.
                withTimeoutOrNull(9_000L) { handle(app, action) }
            } catch (e: Exception) {
                Log.w(TAG, "Widget action $action failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(app: TgMusicApp, action: String) {
        val controller = app.playbackController
        val queue = app.playbackQueue
        when (action) {
            PLAY_PAUSE ->
                if (controller.awaitConnected() && controller.hasMedia()) controller.togglePlayPause()
                else resumeShownSong(app)
            // Straight to the service's own skip - a MediaController seekToNext() is refused
            // because the session's cached "has next" goes stale as the app's queue changes.
            NEXT -> com.abn3li.telemusic.playback.MusicService.running?.skipToNextFromWidget()
            PREVIOUS -> com.abn3li.telemusic.playback.MusicService.running?.skipToPreviousFromWidget()
            SHUFFLE -> { queue.toggleShuffle(); MusicWidgets.refresh(app) }
            REPEAT -> { queue.toggleRepeat(); MusicWidgets.refresh(app) }
            LIKE -> {
                val id = MusicWidgets.shownSongId ?: return
                val repository = app.musicRepository
                val song = withContext(Dispatchers.IO) { repository.getSongById(id) } ?: return
                withContext(Dispatchers.IO) { repository.setFavorite(song, !song.isFavorite) }
            }
        }
    }

    /** Nothing loaded in the player (the app was closed): start the song the widget shows. */
    private suspend fun resumeShownSong(app: TgMusicApp) {
        val id = MusicWidgets.shownSongId ?: return
        val repository = app.musicRepository
        val song = withContext(Dispatchers.IO) { repository.getSongById(id) } ?: return
        val uri = withContext(Dispatchers.IO) { runCatching { repository.resolvePlaybackUri(song) }.getOrNull() } ?: return
        val controller = app.playbackController
        if (!controller.awaitConnected()) return
        if (app.playbackQueue.currentSongId() != id) app.playbackQueue.setQueue(listOf(id), 0)
        controller.playUri(uri, id, song.title, song.artist, song.displayArtwork)
        withContext(Dispatchers.IO) { repository.stampLastPlayed(song) }
        // Same auto-cache bookkeeping as any other Telegram stream (bounded, see its own doc).
        if (!song.isLocalImport && song.youtubeVideoId == null && song.localFilePath == null && song.telegramFileId != 0) {
            backgroundScope.launch { repository.markStreamedFileCached(song) }
        }
    }

    companion object {
        private const val TAG = "WidgetAction"
        const val PLAY_PAUSE = "com.abn3li.telemusic.widget.PLAY_PAUSE"
        const val NEXT = "com.abn3li.telemusic.widget.NEXT"
        const val PREVIOUS = "com.abn3li.telemusic.widget.PREVIOUS"
        const val SHUFFLE = "com.abn3li.telemusic.widget.SHUFFLE"
        const val REPEAT = "com.abn3li.telemusic.widget.REPEAT"
        const val LIKE = "com.abn3li.telemusic.widget.LIKE"

        // Main: the MediaController and the queue are only touched on the main thread.
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
