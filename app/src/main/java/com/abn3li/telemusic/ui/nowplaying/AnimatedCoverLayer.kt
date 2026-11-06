package com.abn3li.telemusic.ui.nowplaying

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.data.animatedcover.AnimatedCover
import com.abn3li.telemusic.data.local.SongEntity
import kotlinx.coroutines.delay

// Waits out the player opening and quick skips through the queue, so neither pays for a lookup
// or a video decoder starting mid-movement.
private const val SETTLE_MS = 500L
private const val FADE_MS = 280
// The clip comes in holding its first frame and only starts moving once it covers the artwork, so
// the picture seems to come to life rather than swap for a video.
private const val REVEAL_MS = 700
// A clip's player stops at once but is let go after the player has closed: releasing a video
// decoder can hold the main thread for a moment, and that shouldn't land mid-animation.
private const val RELEASE_DELAY_MS = 700L

/** The clip to show for [song] while [wanted] and the app is in front, else null. */
@Composable
internal fun rememberAnimatedCover(song: SongEntity?, wanted: Boolean, allowMobileData: Boolean): AnimatedCover? {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var inFront by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> inFront = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val active = wanted && inFront
    var found by remember { mutableStateOf<AnimatedCover?>(null) }
    LaunchedEffect(song?.telegramMessageId, song?.title, song?.artist, active, allowMobileData) {
        found = null
        if (!active || song == null) return@LaunchedEffect
        delay(SETTLE_MS)
        found = (context.applicationContext as TgMusicApp).animatedCovers.find(song, allowMobileData)
    }
    return found
}

/**
 * Plays [cover] on a loop over the still artwork, which stays underneath: nothing shows until the
 * clip's first frame, so a slow or failed clip just leaves the artwork. Keeps going through its
 * fade when [cover] goes away. A closed player has no decoder running at all.
 */
@Composable
internal fun AnimatedCoverLayer(
    cover: AnimatedCover?,
    playing: Boolean,
    onShowing: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var held by remember { mutableStateOf(cover) }
    LaunchedEffect(cover) {
        if (cover == null) delay(FADE_MS.toLong())
        held = cover
    }
    held?.let { clip ->
        key(clip) {
            ClipPlayer(clip, playing, visible = cover == clip, onShowing, modifier)
        }
    }
}

/** Survives the screen, for the moment between the view going and the player being let go. */
private class ClipSurface {
    private var surface: Surface? = null
    private var texture: SurfaceTexture? = null

    fun attach(new: SurfaceTexture, player: ExoPlayer) {
        val oldSurface = surface
        val oldTexture = texture
        texture = new
        surface = Surface(new).also(player::setVideoSurface)
        oldSurface?.release()
        oldTexture?.release()
    }

    fun release() {
        surface?.release()
        texture?.release()
        surface = null
        texture = null
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun ClipPlayer(
    clip: AnimatedCover,
    playing: Boolean,
    visible: Boolean,
    onShowing: (Boolean) -> Unit,
    modifier: Modifier
) {
    val context = LocalContext.current
    var rendered by remember { mutableStateOf(false) }
    val showing = visible && rendered
    val alpha by animateFloatAsState(
        if (showing) 1f else 0f,
        if (showing) tween(REVEAL_MS, easing = FastOutSlowInEasing) else tween(FADE_MS),
        label = "animatedCoverFade"
    )
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(showing) {
        if (showing) {
            delay(REVEAL_MS.toLong())
            revealed = true
        }
    }
    val latestOnShowing by rememberUpdatedState(onShowing)
    LaunchedEffect(showing) { latestOnShowing(showing) }

    val player = remember { clipPlayer(context) }
    val surface = remember { ClipSurface() }
    val view = remember { TextureView(context) }
    var aspect by remember { mutableFloatStateOf(1f) }

    DisposableEffect(player) {
        var triedFallback = false
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) =
                surface.attach(texture, player)
            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
            // Kept alive: told its surface is gone, the player would block the main thread until
            // the decoder stopped. It's released with the player instead.
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture) = false
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
        }
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                rendered = true
                player.playWhenReady = false
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                val next = clip.fallbackUrl
                if (next != null && !triedFallback) {
                    triedFallback = true
                    player.load(next)
                } else {
                    rendered = false
                }
            }
        }
        player.addListener(listener)
        player.load(clip.url)
        onDispose {
            latestOnShowing(false)
            player.removeListener(listener)
            player.playWhenReady = false
            Handler(Looper.getMainLooper()).postDelayed({
                player.release()
                surface.release()
            }, RELEASE_DELAY_MS)
        }
    }
    // Runs hidden until it has a frame to show (a paused player may never draw one), then holds it.
    LaunchedEffect(player, playing, revealed, rendered) {
        player.playWhenReady = !rendered || (playing && revealed)
    }

    // Laid out at the video's own shape, overflowing the cover and clipped by it, the way the still
    // artwork is cropped: same top edge, trimmed evenly at the sides, so the two line up.
    BoxWithConstraints(modifier.clipToBounds().graphicsLayer { this.alpha = alpha }) {
        val height = maxOf(maxHeight, maxWidth / aspect)
        AndroidView(
            factory = { view },
            modifier = Modifier.wrapContentSize(Alignment.TopCenter, unbounded = true).size(height * aspect, height)
        )
    }
}

@OptIn(UnstableApi::class)
private fun clipPlayer(context: Context): ExoPlayer {
    val app = context.applicationContext as TgMusicApp
    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(app.animatedCovers.dataSourceFactory))
        .build()
        .apply {
            // Silent for good: its audio is never fetched, and it can't take focus from the music.
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
        }
}

private fun ExoPlayer.load(url: String) {
    val item = MediaItem.Builder().setUri(url)
    if (url.substringBefore('?').endsWith(".m3u8")) item.setMimeType(MimeTypes.APPLICATION_M3U8)
    setMediaItem(item.build())
    prepare()
}
