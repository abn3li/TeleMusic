package com.abn3li.telemusic.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import java.io.File

private const val MAX_ERROR_RETRIES = 3

class PlaybackController(context: Context) {
    private var controller: MediaController? = null
    private val appContext = context.applicationContext
    // Listeners registered before connect()'s async future has resolved - flushed onto the
    // real controller the moment it's ready, rather than silently dropped. Registration only
    // ever happens once per listener in practice (NowPlayingViewModel.init), so a plain list
    // with no removal path is enough.
    private val pendingListeners = mutableListOf<Player.Listener>()

    // Bounded so a genuinely broken file can't retry forever - reset the moment playback
    // actually gets going again (STATE_READY) or a different song starts, so an unrelated
    // later stall on a DIFFERENT song still gets its own full set of retries.
    private var consecutiveErrorRetries = 0

    fun connect(
        onPlaybackError: ((String) -> Unit)? = null,
        onBufferingChanged: ((Boolean) -> Unit)? = null,
        onReady: () -> Unit
    ) {
        val sessionToken = SessionToken(appContext, ComponentName(appContext, MusicService::class.java))
        val future = MediaController.Builder(appContext, sessionToken).buildAsync()
        future.addListener({
            controller = future.get()
            controller?.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Log.e("PlaybackController", "Playback error: ${error.errorCodeName}", error)
                    val detail = error.cause?.message ?: error.message
                    onPlaybackError?.invoke("${error.errorCodeName}: $detail")

                    // Without this, a player error left playback silently dead - nothing ever
                    // called prepare() again, so the ONLY way to resume was the user manually
                    // hitting play, which happened to work because prepare()+play() is exactly
                    // what that button's togglePlayPause() effectively triggers via a fresh
                    // command. prepare() after an error is ExoPlayer's own documented recovery
                    // path (it retains the current MediaItem and position rather than
                    // restarting), so do it automatically instead of waiting on the user.
                    if (consecutiveErrorRetries < MAX_ERROR_RETRIES) {
                        consecutiveErrorRetries++
                        Log.w("PlaybackController", "Auto-retrying playback (attempt $consecutiveErrorRetries/$MAX_ERROR_RETRIES)")
                        controller?.prepare()
                        controller?.play()
                    } else {
                        Log.w("PlaybackController", "Giving up auto-retry after $MAX_ERROR_RETRIES attempts")
                    }
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    onBufferingChanged?.invoke(playbackState == Player.STATE_BUFFERING)
                    if (playbackState == Player.STATE_READY) {
                        consecutiveErrorRetries = 0
                    }
                    // STATE_ENDED (auto-advance to the next song) is handled in
                    // NowPlayingViewModel's own addListener() instead - it already has the
                    // queue/repeat-mode logic nextSong() needs, so this class doesn't need its
                    // own separate "what happens when a song ends" callback and caller to wire up.
                }
            })
            pendingListeners.forEach { controller?.addListener(it) }
            pendingListeners.clear()
            onReady()
        }, MoreExecutors.directExecutor())
    }

    /**
     * Extra listener alongside the one [connect] installs internally - used by
     * NowPlayingViewModel to notice when the current song changed for a reason it didn't
     * initiate itself (the system media notification's own next/previous buttons, handled
     * entirely inside MusicService via QueueAwareForwardingPlayer), so its own displayed song/
     * lyrics can be kept in sync instead of going stale.
     */
    fun addListener(listener: Player.Listener) {
        controller?.addListener(listener) ?: pendingListeners.add(listener)
    }

    fun playUri(uri: Uri, songId: Long, title: String, artist: String, artworkUrl: String?) {
        val item = MediaItem.Builder().setUri(uri).setMediaId(songId.toString()).setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title).setArtist(artist)
                .setArtworkUri(artworkUriOf(artworkUrl)).build()
        ).build()
        controller?.setMediaItem(item, true)
        controller?.prepare()
        controller?.play()
    }

    /** [artworkUrl] arrives here as either a real https:// URL (SongEntity.albumArtUrl) or a
     * bare local filesystem path with no scheme (SongEntity.thumbnailPath, e.g. an embedded/
     * Telegram-provided cover with no URL of its own - see SongEntity.displayArtwork) - callers
     * pass whichever one exists. A bare path handed straight to toUri() has no scheme, which the
     * media notification/lock screen artwork loader won't resolve, so it needs wrapping through
     * File(...).toUri() to become a real file:// Uri instead. */
    private fun artworkUriOf(artworkUrl: String?): Uri? {
        if (artworkUrl.isNullOrBlank()) return null
        return if (artworkUrl.startsWith("http://") || artworkUrl.startsWith("https://") || artworkUrl.startsWith("content://")) {
            artworkUrl.toUri()
        } else {
            File(artworkUrl).toUri()
        }
    }

    fun playSong(fileId: Int, songId: Long, title: String, artist: String, artworkUrl: String?) {
        playUri(TdlibDataSource.uriFor(fileId), songId, title, artist, artworkUrl)
    }

    fun playLocalFile(filePath: String, songId: Long, title: String, artist: String, artworkUrl: String?) {
        playUri(File(filePath).toURI().toString().toUri(), songId, title, artist, artworkUrl)
    }

    /** Stops the current song immediately, without starting anything new - used right when the
     * user picks a different song, BEFORE any slow resolve step (a YouTube stream URL, a Telegram
     * prebuffer wait) runs. Without this, the OLD song kept audibly playing through that whole
     * wait, only actually stopping once playUri() finally ran for the new one - which read as
     * "the old song won't stop" rather than "the new one is still loading". */
    fun stop() { controller?.stop() }

    fun togglePlayPause() { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun seekTo(positionMs: Long) { controller?.seekTo(positionMs) }
    fun currentPositionMs(): Long = controller?.currentPosition ?: 0L
    fun durationMs(): Long = controller?.duration?.takeIf { it > 0 } ?: 0L
    fun isPlaying(): Boolean = controller?.isPlaying ?: false

    /** The song id already playing on the shared MediaSession, if any - lets a freshly created
     * NowPlayingViewModel (see its own init{} doc) recover "what's actually playing right now"
     * instead of starting from a blank song=null state and waiting on a transition event that,
     * for a song that already started before this instance existed, will never fire. */
    fun currentSongId(): Long? = controller?.currentMediaItem?.mediaId?.toLongOrNull()
}