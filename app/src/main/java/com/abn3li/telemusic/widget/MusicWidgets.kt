package com.abn3li.telemusic.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Scale
import coil.transform.RoundedCornersTransformation
import com.abn3li.telemusic.MainActivity
import com.abn3li.telemusic.R
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.data.local.SongEntity
import com.abn3li.telemusic.data.local.displayArtwork
import com.abn3li.telemusic.data.local.listArtwork
import com.abn3li.telemusic.playback.RepeatMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The home-screen widgets (small, medium, large). They are pictures the launcher keeps: nothing
 * of ours runs while they sit there. They are redrawn only when something they show changes -
 * the song, play/pause, shuffle, repeat, Like, the queue - and the progress bar is moved by
 * MusicService once a second only while music plays with the screen on (see [updateProgress]).
 * With no widget placed, every call here returns straight away.
 */
object MusicWidgets {

    /** What the player is doing, as last reported by MusicService. */
    data class PlayerState(
        val songId: Long? = null,
        val isPlaying: Boolean = false,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val atElapsedMs: Long = SystemClock.elapsedRealtime()
    ) {
        /** Position now: while playing it keeps moving from where it was reported. */
        fun positionNow(): Long =
            if (isPlaying) positionMs + (SystemClock.elapsedRealtime() - atElapsedMs) else positionMs
    }

    @Volatile var player = PlayerState()
        private set

    // The song the widget is showing (the playing one, else the last one played) - what the
    // Like button and a cold-start play act on.
    @Volatile var shownSongId: Long? = null
        private set

    private enum class Size(val provider: Class<*>, val layout: Int) {
        SMALL(SmallMusicWidget::class.java, R.layout.widget_small),
        MEDIUM(MediumMusicWidget::class.java, R.layout.widget_medium),
        LARGE(LargeMusicWidget::class.java, R.layout.widget_large)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val renderLock = Mutex()
    private var renderJob: Job? = null
    @Volatile private var cachedIds: Map<Size, IntArray>? = null
    @Volatile private var lastRenderKey: Any? = null

    // Covers for the song on screen and the two up-next rows, so a play/pause redraw doesn't
    // decode anything again.
    private val artCache = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 6
    }

    private fun ids(context: Context): Map<Size, IntArray> =
        cachedIds ?: run {
            val manager = AppWidgetManager.getInstance(context)
            Size.values().associateWith { manager.getAppWidgetIds(ComponentName(context, it.provider)) }
                .also { cachedIds = it }
        }

    fun hasWidgets(context: Context): Boolean = ids(context).values.any { it.isNotEmpty() }

    /** A widget was added, removed or asked to redraw by the launcher. */
    fun onWidgetsChanged(context: Context) {
        cachedIds = null
        lastRenderKey = null
        refresh(context)
    }

    /** MusicService: the song, play/pause or position jumped. */
    fun onPlayerChanged(context: Context, state: PlayerState) {
        player = state
        refresh(context)
    }

    /** Redraws the widgets if anything they show changed. Safe from any thread. */
    fun refresh(context: Context) {
        val appContext = context.applicationContext
        if (!hasWidgets(appContext)) return
        renderJob?.cancel()
        renderJob = scope.launch { renderLock.withLock { render(appContext) } }
    }

    /** One progress-bar step: a partial update that touches only the bar (and the large
     * widget's times), no pictures. Called by MusicService's once-a-second tick. */
    fun updateProgress(context: Context) {
        val ids = ids(context)
        val state = player
        val position = state.positionNow()
        val duration = state.durationMs
        val manager = AppWidgetManager.getInstance(context)
        for (size in Size.values()) {
            val widgetIds = ids[size] ?: continue
            if (widgetIds.isEmpty()) continue
            val views = RemoteViews(context.packageName, size.layout)
            setProgress(views, size, position, duration)
            manager.partiallyUpdateAppWidget(widgetIds, views)
        }
    }

    private suspend fun render(context: Context) {
        val app = context as TgMusicApp
        val repository = app.musicRepository
        val state = player
        // The queue is only ever changed on the main thread - read it there.
        val queue = withContext(Dispatchers.Main) {
            val q = app.playbackQueue
            Triple(q.isShuffleEnabled, q.repeatMode, (q.nextInQueueIds() + q.upNextIds()).take(2))
        }
        val (shuffle, repeat, upNextIds) = queue
        val song = state.songId?.let { repository.getSongById(it) }
            ?: repository.getRecentlyPlayed(1).firstOrNull()
        val live = state.songId != null && song?.telegramMessageId == state.songId
        val upNext = if (live) upNextIds.mapNotNull { repository.getSongById(it) } else emptyList()
        shownSongId = song?.telegramMessageId

        val key = listOf(
            song?.telegramMessageId, song?.title, song?.artist, song?.isFavorite, song?.displayArtwork,
            state.isPlaying, live, shuffle, repeat, upNext.map { it.telegramMessageId }
        )
        if (key == lastRenderKey) {
            // Nothing visible changed except, maybe, where the song is - move just the bar.
            withContext(Dispatchers.Main) { updateProgress(context) }
            return
        }

        val density = context.resources.displayMetrics.density
        val cover = song?.let { loadArt(context, it.displayArtwork, (140 * density).toInt(), 14 * density) }
        val smallCover = song?.let { loadArt(context, it.displayArtwork, (170 * density).toInt(), 0f) }
        val nextCovers = upNext.map { loadArt(context, it.listArtwork, (40 * density).toInt(), 6 * density) }

        val manager = AppWidgetManager.getInstance(context)
        val ids = ids(context)
        for (size in Size.values()) {
            val widgetIds = ids[size] ?: continue
            if (widgetIds.isEmpty()) continue
            val views = RemoteViews(context.packageName, size.layout)
            bind(context, views, size, song, if (size == Size.SMALL) smallCover else cover, state, live, shuffle, repeat, upNext, nextCovers)
            manager.updateAppWidget(widgetIds, views)
        }
        lastRenderKey = key
    }

    private fun bind(
        context: Context, views: RemoteViews, size: Size, song: SongEntity?, cover: Bitmap?,
        state: PlayerState, live: Boolean, shuffle: Boolean, repeat: RepeatMode,
        upNext: List<SongEntity>, nextCovers: List<Bitmap?>
    ) {
        val openApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        views.setOnClickPendingIntent(android.R.id.background, openApp)

        views.setTextViewText(R.id.widget_title, song?.title ?: "TeleMusic")
        views.setTextViewText(R.id.widget_artist, song?.artist ?: "Tap to open")
        if (cover != null) views.setImageViewBitmap(R.id.widget_art, cover)
        else views.setImageViewResource(R.id.widget_art, R.drawable.widget_art_placeholder)

        val playing = live && state.isPlaying
        views.setImageViewResource(R.id.widget_play, if (playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play)
        // With nothing ever played there is nothing to resume - play just opens the app.
        views.setOnClickPendingIntent(R.id.widget_play, if (song == null) openApp else action(context, WidgetActionReceiver.PLAY_PAUSE))
        setProgress(views, size, if (live) state.positionNow() else 0L, if (live) state.durationMs else 0L)

        if (size == Size.SMALL) return
        views.setOnClickPendingIntent(R.id.widget_prev, action(context, WidgetActionReceiver.PREVIOUS))
        views.setOnClickPendingIntent(R.id.widget_next, action(context, WidgetActionReceiver.NEXT))

        if (size != Size.LARGE) return
        views.setImageViewResource(R.id.widget_like, if (song?.isFavorite == true) R.drawable.ic_widget_star_on else R.drawable.ic_widget_star)
        views.setOnClickPendingIntent(R.id.widget_like, action(context, WidgetActionReceiver.LIKE))
        views.setImageViewResource(R.id.widget_shuffle, if (shuffle) R.drawable.ic_widget_shuffle_on else R.drawable.ic_widget_shuffle)
        views.setOnClickPendingIntent(R.id.widget_shuffle, action(context, WidgetActionReceiver.SHUFFLE))
        views.setImageViewResource(
            R.id.widget_repeat,
            when (repeat) {
                RepeatMode.OFF -> R.drawable.ic_widget_repeat
                RepeatMode.ALL -> R.drawable.ic_widget_repeat_on
                RepeatMode.ONE -> R.drawable.ic_widget_repeat_one_on
            }
        )
        views.setOnClickPendingIntent(R.id.widget_repeat, action(context, WidgetActionReceiver.REPEAT))

        views.setViewVisibility(R.id.widget_upnext_label, if (upNext.isEmpty()) View.GONE else View.VISIBLE)
        val rows = listOf(
            arrayOf(R.id.widget_next1, R.id.widget_next1_art, R.id.widget_next1_title, R.id.widget_next1_artist),
            arrayOf(R.id.widget_next2, R.id.widget_next2_art, R.id.widget_next2_title, R.id.widget_next2_artist)
        )
        rows.forEachIndexed { i, (row, art, title, artist) ->
            val next = upNext.getOrNull(i)
            views.setViewVisibility(row, if (next == null) View.GONE else View.VISIBLE)
            if (next == null) return@forEachIndexed
            views.setTextViewText(title, next.title)
            views.setTextViewText(artist, next.artist)
            val bitmap = nextCovers.getOrNull(i)
            if (bitmap != null) views.setImageViewBitmap(art, bitmap)
            else views.setImageViewResource(art, R.drawable.widget_art_placeholder)
        }
    }

    private fun setProgress(views: RemoteViews, size: Size, positionMs: Long, durationMs: Long) {
        val fraction = if (durationMs > 0) (positionMs.coerceIn(0, durationMs) * 1000 / durationMs).toInt() else 0
        views.setProgressBar(R.id.widget_progress, 1000, fraction, false)
        if (size == Size.LARGE) {
            val position = positionMs.coerceIn(0, durationMs.coerceAtLeast(0))
            views.setTextViewText(R.id.widget_time_elapsed, formatTime(position))
            views.setTextViewText(R.id.widget_time_left, "-" + formatTime((durationMs - position).coerceAtLeast(0)))
        }
    }

    private fun formatTime(ms: Long): String {
        val seconds = ms / 1000
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }

    private fun action(context: Context, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, action.hashCode(),
            Intent(context, WidgetActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    /** The cover as a small software bitmap (widgets can't take hardware bitmaps), rounded to
     * match the layout. Cached, so redraws for play/pause don't decode again. */
    private suspend fun loadArt(context: Context, data: String?, sizePx: Int, radiusPx: Float): Bitmap? {
        if (data.isNullOrBlank()) return null
        val cacheKey = "$data|$sizePx|$radiusPx"
        synchronized(artCache) { artCache[cacheKey] }?.let { return it }
        val request = ImageRequest.Builder(context)
            .data(data)
            .size(sizePx, sizePx)
            .scale(Scale.FILL)
            .allowHardware(false)
            .apply { if (radiusPx > 0f) transformations(RoundedCornersTransformation(radiusPx)) }
            .build()
        val result = context.imageLoader.execute(request)
        val bitmap = (result as? SuccessResult)?.drawable?.toBitmap() ?: run {
            android.util.Log.w("MusicWidgets", "Cover didn't load: $data", (result as? coil.request.ErrorResult)?.throwable)
            return null
        }
        synchronized(artCache) { artCache[cacheKey] = bitmap }
        return bitmap
    }
}
