package com.abn3li.telemusic.playback

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.data.youtube.YouTubeHistoryClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom

/** Service-owned: handles headset/Auto/queue playback even when every app screen is closed. */
internal class YouTubeHistoryReporter(
    private val app: TgMusicApp,
    private val player: Player,
    private val scope: CoroutineScope
) : Player.Listener {
    private class Play(val mediaId: String, val instanceId: String?, val accountSession: String?) {
        val listening = ListeningHistorySession()
        val nonce = CharArray(16) { NONCE_CHARS[random.nextInt(NONCE_CHARS.length)] }.concatToString()
        var videoId: String? = null
        var identified = false
    }

    private val client = YouTubeHistoryClient(app.youtubeAccount::authHeadersForSession)
    private var current: Play? = null
    private var identifyJob: Job? = null
    private var thresholdJob: Job? = null
    private var ended = false

    init { player.addListener(this) }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val restart = ended || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
        ended = false
        update(restart)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) ended = true
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED,
                Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_POSITION_DISCONTINUITY)) update()
    }

    private fun update(restart: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        val item = player.currentMediaItem
        val instanceId = item?.mediaMetadata?.extras?.getString(EXTRA_PLAYBACK_INSTANCE)
        if (restart || item?.mediaId != current?.mediaId || instanceId != current?.instanceId) {
            current?.let { it.listening.setPlaying(false, now); submitIfReady(it, now) }
            identifyJob?.cancel()
            thresholdJob?.cancel()
            current = item?.let { media ->
                Play(media.mediaId, instanceId, app.youtubeAccount.state.value.sessionId).also { play ->
                    identifyJob = scope.launch {
                        val id = try {
                            media.mediaMetadata.extras?.getString(EXTRA_YOUTUBE_VIDEO_ID)
                                ?: media.mediaId.toLongOrNull()?.let { songId ->
                                    withContext(Dispatchers.IO) { app.musicRepository.getSongById(songId) }
                                        ?.takeUnless { it.isLocalImport }?.youtubeVideoId
                                }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) { null }
                        if (current === play) {
                            play.videoId = id?.takeIf { it.isNotBlank() }
                            play.identified = true
                            update()
                        }
                    }
                }
            }
        }
        val play = current ?: return
        play.listening.setPlaying(player.isPlaying, now)
        thresholdJob?.cancel()
        if (!play.identified || play.videoId == null || play.accountSession == null || play.listening.isSubmitted) return
        if (play.accountSession != app.youtubeAccount.state.value.sessionId) return
        submitIfReady(play, now)
        if (player.isPlaying && !play.listening.isSubmitted) {
            // A single wakeup for the remaining listen time; paused/buffering playback cancels it.
            thresholdJob = scope.launch {
                delay(play.listening.remainingMs(SystemClock.elapsedRealtime()))
                if (current === play && player.isPlaying) update()
            }
        }
    }

    private fun submitIfReady(play: Play, now: Long) {
        val video = play.videoId ?: return
        val account = play.accountSession ?: return
        if (account != app.youtubeAccount.state.value.sessionId || !play.listening.claimReport(now)) return
        scope.launch(Dispatchers.IO) {
            try {
                if (client.record(video, play.nonce, account)) Log.i(TAG, "YouTube listening history registered; video=$video")
                else Log.w(TAG, "YouTube listening history skipped: signed out or account changed")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Only the reason (an HTTP code, "no tracking URL", a network error) - never
                // authentication, cookies or tracking URLs; playback is unaffected.
                val reason = if (e is java.io.IOException) e.javaClass.simpleName else e.message
                Log.w(TAG, "Couldn't register YouTube listening history; video=$video; reason=$reason")
            }
        }
    }

    fun close() {
        player.removeListener(this)
        identifyJob?.cancel()
        thresholdJob?.cancel()
    }

    companion object {
        private const val TAG = "YouTubeHistory"
        private const val NONCE_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
        private val random = SecureRandom()
    }
}
