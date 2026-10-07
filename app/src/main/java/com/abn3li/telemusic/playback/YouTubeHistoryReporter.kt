package com.abn3li.telemusic.playback

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.data.youtube.PlaybackTracking
import com.abn3li.telemusic.data.youtube.YouTubeHistoryClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom

/**
 * Service-owned: handles headset/Auto/queue playback even when every app screen is closed.
 *
 * A YouTube song heard for 30 seconds is registered in the signed-in account's YouTube Music
 * history ("playback" plus the check-in), then its listening time is reported the way the
 * website does - every flush interval while it plays, once when paused, and a final report
 * when the play ends - so it counts for recommendations instead of reading as a skip. Every
 * wait is a single wakeup that a pause cancels: nothing runs while nothing plays.
 */
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
        // Set once the play is registered: where its listening time goes.
        var tracking: PlaybackTracking? = null
        var lastReportedSeconds = -1L
        var closed = false
    }

    private val client = YouTubeHistoryClient(
        headersForSession = app.youtubeAccount::authHeadersForSession,
        trackingFor = { video, headers ->
            val session = app.youtubeAccount.state.value.sessionId
            val web = session?.let { app.youtubeWebSession.cached(it) }
            com.abn3li.telemusic.data.browse.InnertubeBrowseClient().playbackTracking(video, headers, web)
        },
        scope = { session -> app.youtubeWebSession.cached(session) }
    )
    private var current: Play? = null
    private var identifyJob: Job? = null
    private var thresholdJob: Job? = null
    private var watchtimeJob: Job? = null
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
            current?.let { finish(it, now) }
            identifyJob?.cancel()
            thresholdJob?.cancel()
            watchtimeJob?.cancel()
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
        if (play.tracking != null) {
            reportListening(play, now)
            return
        }
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

    /** While it plays: one report every flush interval. Paused: one report now, then nothing. */
    private fun reportListening(play: Play, now: Long) {
        val tracking = play.tracking ?: return
        if (!player.isPlaying) {
            watchtimeJob?.cancel()
            watchtimeJob = null
            sendWatchtime(play, now, final = false)
            return
        }
        if (watchtimeJob?.isActive == true) return
        val interval = tracking.flushSeconds.coerceIn(10, 60) * 1000
        watchtimeJob = scope.launch {
            while (true) {
                delay(interval)
                if (current !== play || !player.isPlaying) break
                sendWatchtime(play, SystemClock.elapsedRealtime(), final = false)
            }
        }
    }

    /** The play is over (another song, a replay, the end, the service closing). */
    private fun finish(play: Play, now: Long) {
        play.listening.setPlaying(false, now)
        if (play.tracking == null) submitIfReady(play, now) else sendWatchtime(play, now, final = true)
    }

    private fun submitIfReady(play: Play, now: Long) {
        val video = play.videoId ?: return
        val account = play.accountSession ?: return
        if (account != app.youtubeAccount.state.value.sessionId || !play.listening.claimReport(now)) return
        app.workScope.launch(Dispatchers.IO) {
            try {
                // The session's own details first (once per sign-in): without the web player's
                // timestamp YouTube won't hand out the report addresses for this account.
                app.youtubeWebSession.scope(account)
                val tracking = client.record(video, play.nonce, account)
                if (tracking == null) {
                    Log.w(TAG, "YouTube listening history skipped: signed out or account changed")
                    return@launch
                }
                Log.i(TAG, "YouTube listening history registered; video=$video; listeningReports=${tracking.watchtimeUrl != null}")
                withContext(Dispatchers.Main) {
                    play.tracking = tracking
                    if (play.closed || current !== play) sendWatchtime(play, SystemClock.elapsedRealtime(), final = true)
                    else update()
                }
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

    private fun sendWatchtime(play: Play, now: Long, final: Boolean) {
        val tracking = play.tracking ?: return
        val account = play.accountSession ?: return
        if (play.closed) return
        val seconds = play.listening.listenedMs(now) / 1000
        if (!final && seconds == play.lastReportedSeconds) return
        play.lastReportedSeconds = seconds
        if (final) play.closed = true
        app.workScope.launch(Dispatchers.IO) {
            try {
                if (client.watchtime(tracking, play.nonce, account, seconds, final)) {
                    Log.i(TAG, "YouTube listening time reported; seconds=$seconds; final=$final")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = if (e is java.io.IOException) e.javaClass.simpleName else e.message
                Log.w(TAG, "Couldn't report YouTube listening time; reason=$reason")
            }
        }
    }

    fun close() {
        player.removeListener(this)
        identifyJob?.cancel()
        thresholdJob?.cancel()
        watchtimeJob?.cancel()
        current?.let { finish(it, SystemClock.elapsedRealtime()) }
        current = null
    }

    companion object {
        private const val TAG = "YouTubeHistory"
        private const val NONCE_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
        private val random = SecureRandom()
    }
}
