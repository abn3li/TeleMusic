package com.abn3li.telemusic.ui.nowplaying

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.abn3li.telemusic.data.download.DownloadQuality
import com.abn3li.telemusic.data.download.YtDlpRepository
import com.abn3li.telemusic.data.download.ytDlpStableSongId
import com.abn3li.telemusic.data.local.SongEntity
import com.abn3li.telemusic.data.local.displayArtwork
import com.abn3li.telemusic.playback.PlaybackController
import com.abn3li.telemusic.playback.PlaybackQueue
import com.abn3li.telemusic.playback.RepeatMode
import com.abn3li.telemusic.repository.MusicRepository
import com.abn3li.telemusic.repository.SortField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class LyricLine(val timeMs: Long, val text: String)

/**
 * The three fields the 300ms position ticker actually updates every tick. Split out of
 * [NowPlayingUiState] into its own flow (see [NowPlayingViewModel.playbackProgress]) so only the
 * seekbar and the lyrics view's active-line highlight - the two things that genuinely need to
 * react every tick - recompose that often, instead of the whole Now Playing screen.
 */
data class PlaybackProgress(
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val activeLyricIndex: Int = -1
)

data class NowPlayingUiState(
    val song: SongEntity? = null,
    val lyricLines: List<LyricLine> = emptyList(),
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val activeLyricIndex: Int = -1,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    // The song ID currently being fetched/prepared, if any - distinct from `song` (which still
    // points at the PREVIOUS song until loading finishes), so UI can show buffering feedback
    // on the card the user actually swiped to, not the one they swiped away from.
    val loadingSongId: Long? = null,
    val isDownloading: Boolean = false,
    val isFetchingLyrics: Boolean = false,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val isShuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val errorMessage: String? = null
)

class NowPlayingViewModel(
    private val repository: MusicRepository,
    private val playbackController: PlaybackController,
    private val queue: PlaybackQueue,
    private val ytDlpRepository: YtDlpRepository,
    private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(NowPlayingUiState())
    val uiState: StateFlow<NowPlayingUiState> = _uiState
    private var tickerJob: Job? = null
    private var loadJob: Job? = null
    private var prefetchJob: Job? = null
    // Set by playEphemeral() for a YouTube "Play" stream, cleared the moment a real song loads -
    // downloadCurrentSong() needs this to actually download the video (see its own doc for why
    // the ordinary downloadExplicitly() path can't: there's no Telegram message behind this row
    // at all, only a video id, which nothing else in NowPlayingUiState/SongEntity carries).
    private var pendingEphemeralVideoId: String? = null

    // The Queue screen's "Up Next" list - resolved SongEntity rows for everything queued after
    // the currently playing song (see PlaybackQueue.orderedIds/currentIndexValue). Deliberately
    // NOT folded into NowPlayingUiState: it only needs to refresh on an actual queue-order
    // change (song change, shuffle toggle, reorder, clear), never on the 300ms position tick,
    // and giving it its own StateFlow keeps the Queue screen from recomposing off ticks it
    // doesn't care about, same reasoning as playbackProgress being split out below.
    private val _upcomingQueue = MutableStateFlow<List<SongEntity>>(emptyList())
    val upcomingQueue: StateFlow<List<SongEntity>> = _upcomingQueue

    private fun refreshUpcomingQueue() {
        viewModelScope.launch {
            val upcomingIds = queue.orderedIds().drop(queue.currentIndexValue() + 1)
            _upcomingQueue.value = withContext(Dispatchers.IO) { upcomingIds.mapNotNull { repository.getSongById(it) } }
        }
    }

    /** Queue screen's "tap an upcoming track" action - [offsetInUpcoming] is its position within
     * [upcomingQueue], not an absolute queue index. */
    fun jumpToQueueItem(offsetInUpcoming: Int) {
        val id = queue.jumpToIndex(queue.currentIndexValue() + 1 + offsetInUpcoming) ?: return
        loadCurrentQueuePosition(songIdOverride = id)
        // Missing here (unlike every other queue-mutating function above/below) left
        // upcomingQueue holding its stale pre-jump list - the tapped song (now currentIndex)
        // stayed listed as "upcoming" too, so the Queue screen showed duplicate/stale rows and
        // drag-to-reorder's offset math got thrown off against a list that no longer matched
        // the real queue.
        refreshUpcomingQueue()
    }

    /** Queue screen's "Clear Queue" action - keeps the currently playing song, drops the rest. */
    fun clearUpcomingQueue() {
        queue.removeUpcoming()
        refreshUpcomingQueue()
    }

    /** Queue screen's drag-to-reorder - offsets are positions within [upcomingQueue]. */
    fun moveQueueItem(fromOffset: Int, toOffset: Int) {
        queue.moveUpcoming(fromOffset, toOffset)
        refreshUpcomingQueue()
    }

    /**
     * The fast-ticking slice of [uiState], for the two composables that actually need to
     * redraw every 300ms (the seekbar, the lyrics view's active-line highlight) to collect
     * on their own - see [PlaybackProgress].
     */
    val playbackProgress: StateFlow<PlaybackProgress> = uiState
        .map { PlaybackProgress(it.currentPositionMs, it.durationMs, it.activeLyricIndex) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlaybackProgress())

    /**
     * [uiState] with the position ticker's own churn filtered out, for everything in the Now
     * Playing screen that does NOT need to redraw every 300ms - which, before this existed, was
     * the entire screen: the mesh backdrop, every button, the artwork card, all of it recomposed
     * in lockstep with the ticker because they all read the one `uiState` object the ticker's
     * own `.copy()` touches every tick. Compares only the fields that describe something other
     * than "how far into the track we are right now" - those three live in [playbackProgress]
     * instead - so this only re-emits on a change something on screen actually needs to react to
     * (a song swap, play/pause, a button toggling), not on every tick of a song that's already
     * playing.
     */
    val stableUiState: StateFlow<NowPlayingUiState> = uiState
        .distinctUntilChanged { old, new ->
            old.song == new.song &&
                old.lyricLines == new.lyricLines &&
                old.isPlaying == new.isPlaying &&
                old.isBuffering == new.isBuffering &&
                old.loadingSongId == new.loadingSongId &&
                old.isDownloading == new.isDownloading &&
                old.isFetchingLyrics == new.isFetchingLyrics &&
                old.hasNext == new.hasNext &&
                old.hasPrevious == new.hasPrevious &&
                old.isShuffleEnabled == new.isShuffleEnabled &&
                old.repeatMode == new.repeatMode &&
                old.errorMessage == new.errorMessage
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NowPlayingUiState())

    // Pre-computed memory map of all library songs by ID for 0ms instant artwork card rendering in Pager
    val allSongsMap: StateFlow<Map<Long, SongEntity>> = repository.observeLibrary(SortField.TITLE, true)
        .map { list -> list.associateBy { it.telegramMessageId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    init {
        // A single persistent ticker for the life of this ViewModel (now app-session-scoped,
        // constructed once at the nav root instead of per-screen) - just keeps
        // position/duration/isPlaying/queue-flags in sync; song changes are always driven
        // explicitly below, never detected passively here.
        startPositionTicker()

        // The system media notification's own next/previous buttons are handled entirely
        // inside MusicService (via QueueAwareForwardingPlayer), independent of whether this
        // ViewModel/the app's UI even exists - it advances the shared PlaybackQueue singleton
        // and swaps the player's media item directly. Without this, using those buttons left
        // this ViewModel's own displayed song/lyrics silently stale, still showing whatever was
        // on screen before the notification was used.
        playbackController.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val songId = mediaItem?.mediaId?.toLongOrNull() ?: return
                // Already showing this song - either nothing changed, or this transition is the
                // one loadCurrentQueuePosition() itself just caused (that function sets `song`
                // in state BEFORE calling startPlayback(), so by the time the real transition
                // callback fires here the state already matches and there's nothing to redo).
                if (songId == _uiState.value.song?.telegramMessageId) return
                resyncToExternallyChangedSong(songId)
            }

            // isBuffering existed in NowPlayingUiState and the screen already had a spinner
            // wired to it (over the artwork, and swapped in for the play/pause icon) - nothing
            // ever actually set it, so streaming stalls (TDlibDataSource waiting on more bytes
            // from Telegram) played dead silence with no way to tell that apart from the app
            // being broken. Player.STATE_BUFFERING is exactly ExoPlayer's own signal for this.
            override fun onPlaybackStateChanged(playbackState: Int) {
                _uiState.value = _uiState.value.copy(isBuffering = playbackState == Player.STATE_BUFFERING)
                if (playbackState == Player.STATE_ENDED) {
                    val currentPlayingId = _uiState.value.song?.telegramMessageId
                    val queueCurrentId = queue.currentSongId()
                    // Avoid double-advancing: only advance if a new song isn't already loading
                    // and the queue hasn't already been advanced for this ended track.
                    if (_uiState.value.loadingSongId == null && (currentPlayingId == null || queueCurrentId == currentPlayingId)) {
                        nextSong()
                    }
                }
            }
        })

        // This ViewModel instance may be brand new while a song is already playing on the
        // shared MediaSession - e.g. the process was killed after being cleared from recents
        // and the app just relaunched (MusicService's own foreground service survives that
        // independently - see MusicService's own doc), or (before the NavGraph fix pairing
        // this) an Activity recreation. Either way, _uiState still starts at song=null and the
        // transition listener above won't help - it only fires on the NEXT change, and this
        // song already started before this instance existed. Recover it directly instead of
        // showing an empty MiniPlayer/Now Playing screen for audio that's audibly still going.
        playbackController.currentSongId()?.let { songId ->
            if (songId != _uiState.value.song?.telegramMessageId) {
                resyncToExternallyChangedSong(songId, resetPosition = false)
            }
        }
    }

    /**
     * Catches this ViewModel's own displayed state up to a song change it didn't initiate - see
     * the transition listener registered in the init block above. The queue's position is
     * already correct (the service advanced the same shared [PlaybackQueue] instance) and
     * playback is already under way, so unlike [loadCurrentQueuePosition] this must never call
     * [startPlayback] or otherwise touch the transport - it only re-reads what's now playing.
     */
    private fun resyncToExternallyChangedSong(songId: Long, resetPosition: Boolean = true) {
        viewModelScope.launch {
            val song = allSongsMap.value[songId]
                ?: withContext(Dispatchers.IO) { repository.getSongById(songId) }
                ?: return@launch
            val rawLrc = song.lyricsSynced.takeIf { !it.isNullOrBlank() }
                ?: song.lyricsPlain.takeIf { !it.isNullOrBlank() && it.contains("[00:") }
            val lines = withContext(Dispatchers.Default) { rawLrc?.let(::parseLrc).orEmpty() }
            // resetPosition=false is the reconnect-to-an-already-playing-song path (see init{}'s
            // own doc) - that song is somewhere in the middle of playing, not starting over, so
            // pull its REAL current position/playing state from the controller instead of
            // snapping the seekbar back to 0 the way an actual new-song transition should.
            val positionMs = if (resetPosition) 0L else playbackController.currentPositionMs()
            _uiState.value = _uiState.value.copy(
                song = song,
                lyricLines = lines,
                currentPositionMs = positionMs,
                durationMs = (song.durationSeconds * 1000L).coerceAtLeast(1L),
                hasNext = queue.hasNext(),
                hasPrevious = queue.hasPrevious(),
                errorMessage = null,
                isDownloading = false,
                isPlaying = if (resetPosition) _uiState.value.isPlaying else playbackController.isPlaying()
            )
            withContext(Dispatchers.IO) { repository.stampLastPlayed(song) }
        }
    }

    /** Sets a new queue and immediately starts playing the chosen song. */
    fun playFromQueue(ids: List<Long>, startIndex: Int) {
        queue.setQueue(ids, startIndex)
        loadCurrentQueuePosition(songIdOverride = queue.currentSongId())
        refreshUpcomingQueue()
    }

    /** Plays a single song that was never added to the library - the YouTube "Play" button (see
     * YouTubeDownloadViewModel.onPlayClick), which streams a resolved googlevideo.com URL and
     * keeps nothing afterward, unlike the actual Download button which saves a real library row.
     * [song] is an in-memory SongEntity built just for display (never inserted into Room) - see
     * SongEntity.isLocalImport's own doc for why telegramFileId is meaningless (0) here too.
     *
     * The shared queue is cleared, not left as whatever it was before: with it untouched, the
     * mini player's Skip Next button (always enabled, not gated on hasNext - see MiniPlayer.kt)
     * would silently resume the OLD library queue instead of doing nothing, since nextSong()
     * reads straight from `queue` rather than the hasNext flag this sets to false.
     *
     * [videoId] is kept around (not part of [song]/[NowPlayingUiState] - it's not a real library
     * field) purely so [downloadCurrentSong] can actually save this song for real if the user
     * taps Download while it's playing - see that function's own doc. */
    fun playEphemeral(song: SongEntity, streamUri: Uri, videoId: String) {
        loadJob?.cancel()
        queue.clear()
        pendingEphemeralVideoId = videoId
        _uiState.value = _uiState.value.copy(
            song = song,
            lyricLines = emptyList(),
            currentPositionMs = 0L,
            durationMs = (song.durationSeconds * 1000L).coerceAtLeast(1L),
            loadingSongId = null,
            hasNext = false,
            hasPrevious = false,
            errorMessage = null,
            isDownloading = false
        )
        playbackController.playUri(streamUri, song.telegramMessageId, song.title, song.artist, song.displayArtwork)
    }

    fun fetchLyricsOnDemand() {
        val song = _uiState.value.song ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isFetchingLyrics = true)
            val result = runCatching {
                withContext(Dispatchers.IO) { repository.fetchLyricsForSong(song) }
            }.getOrNull()

            val freshDbSong = withContext(Dispatchers.IO) { repository.getSongById(song.telegramMessageId) }
            val plain = result?.plain ?: freshDbSong?.lyricsPlain ?: song.lyricsPlain
            val synced = result?.synced ?: freshDbSong?.lyricsSynced ?: song.lyricsSynced

            val rawLrc = synced.takeIf { !it.isNullOrBlank() }
                ?: plain.takeIf { !it.isNullOrBlank() && it.contains("[00:") }

            val lines = withContext(Dispatchers.Default) { rawLrc?.let(::parseLrc).orEmpty() }

            // Update state.song too, not just lyricLines - the plain-lyrics view in the UI
            // reads state.song?.lyricsPlain directly, and for anything that isn't LRC-synced
            // (i.e. every plain-text result from lyrics.ovh or the Google fallback), lyricLines
            // stays empty by design. Without this, a successful plain-lyrics fetch never
            // actually became visible even though it was correctly saved to the database.
            _uiState.value = _uiState.value.copy(
                song = (freshDbSong ?: song).copy(lyricsPlain = plain, lyricsSynced = synced),
                lyricLines = lines,
                isFetchingLyrics = false
            )
        }
    }

    fun fetchLyricsCustom(customTitle: String, customArtist: String) {
        val song = _uiState.value.song ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isFetchingLyrics = true)
            val customSong = song.copy(title = customTitle.trim(), artist = customArtist.trim())
            val result = runCatching {
                withContext(Dispatchers.IO) { repository.fetchLyricsForSong(customSong) }
            }.getOrNull()

            val freshDbSong = withContext(Dispatchers.IO) { repository.getSongById(song.telegramMessageId) }
            val plain = result?.plain ?: freshDbSong?.lyricsPlain ?: song.lyricsPlain
            val synced = result?.synced ?: freshDbSong?.lyricsSynced ?: song.lyricsSynced

            val rawLrc = synced.takeIf { !it.isNullOrBlank() }
                ?: plain.takeIf { !it.isNullOrBlank() && it.contains("[00:") }

            val lines = withContext(Dispatchers.Default) { rawLrc?.let(::parseLrc).orEmpty() }

            // Same fix as fetchLyricsOnDemand(): state.song must carry the fetched lyrics too,
            // since the plain-lyrics UI branch reads state.song?.lyricsPlain directly and
            // lyricLines only ever gets populated for LRC-synced results.
            _uiState.value = _uiState.value.copy(
                song = (freshDbSong ?: song).copy(lyricsPlain = plain, lyricsSynced = synced),
                lyricLines = lines,
                isFetchingLyrics = false
            )
        }
    }

    fun nextSong() {
        val id = queue.next() ?: return
        loadCurrentQueuePosition(songIdOverride = id)
        refreshUpcomingQueue()
    }

    fun previousSong() {
        val id = queue.previous() ?: return
        loadCurrentQueuePosition(songIdOverride = id)
        refreshUpcomingQueue()
    }

    fun toggleShuffle() {
        val enabled = queue.toggleShuffle()
        _uiState.value = _uiState.value.copy(
            isShuffleEnabled = enabled,
            hasNext = queue.hasNext(),
            hasPrevious = queue.hasPrevious()
        )
        refreshUpcomingQueue()
    }

    fun toggleRepeat() {
        val mode = queue.toggleRepeat()
        _uiState.value = _uiState.value.copy(
            repeatMode = mode,
            hasNext = queue.hasNext(),
            hasPrevious = queue.hasPrevious()
        )
    }

    fun downloadCurrentSong() {
        val song = _uiState.value.song ?: return
        if (song.isExplicitDownload) return
        // A real local import always has a real localFilePath (see SongEntity's own doc) - the
        // only way isLocalImport is ever true with localFilePath null is playEphemeral's
        // in-memory-only stream song (never inserted into Room). That case still needs a real
        // download (see downloadEphemeralSong below) - it just can't go through the ordinary
        // downloadExplicitly() path, which assumes a Telegram message behind the row.
        val videoId = pendingEphemeralVideoId
        if (song.isLocalImport && song.localFilePath == null) {
            if (videoId != null) downloadEphemeralSong(song, videoId)
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isDownloading = true)
            runCatching { repository.downloadExplicitly(song) }
            val updated = repository.getSongById(song.telegramMessageId)
            // Only reflect the finished download in UI state if the user is still looking at
            // THIS song - a download can easily outlast a skip to the next/previous track, and
            // unconditionally writing `song` back here undid that navigation, snapping the
            // screen back to whatever song had just finished downloading regardless of what the
            // user had moved on to. The download itself already landed in the database via
            // downloadExplicitly() above either way, so skipping the UI write here loses
            // nothing - opening this song again later shows it as downloaded correctly.
            if (_uiState.value.song?.telegramMessageId == song.telegramMessageId) {
                _uiState.value = _uiState.value.copy(
                    song = updated ?: song.copy(isExplicitDownload = true),
                    isDownloading = false
                )
            }
        }
    }

    /** The real download behind Now Playing's Download button for a YouTube song currently
     * streaming via [playEphemeral] - same yt-dlp download + library-import flow
     * YouTubeDownloadViewModel's Download button uses (see its startDownload's own doc), just
     * triggered from Now Playing instead of the search results row. Saves a real library row,
     * exactly like tapping Download from search would have - this song just happened to be
     * played first instead. */
    private fun downloadEphemeralSong(song: SongEntity, videoId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isDownloading = true)
            val destDir = File(context.filesDir, "youtube_downloads")
            val songId = ytDlpStableSongId(videoId)
            val outcome = withContext(Dispatchers.IO) {
                ytDlpRepository.download(videoId, destDir, songId.toString(), DownloadQuality.BEST.formatSelector)
            }
            outcome.onSuccess { downloaded ->
                repository.importDownloadedSong(downloaded, songId, videoId)
                repository.backfillThumbnails()
            }
            if (_uiState.value.song?.telegramMessageId == song.telegramMessageId) {
                val updated = outcome.getOrNull()?.let { repository.getSongById(songId) }
                _uiState.value = _uiState.value.copy(
                    song = updated ?: _uiState.value.song,
                    isDownloading = false,
                    errorMessage = outcome.exceptionOrNull()?.let { e -> "Couldn't download \"${song.title}\": ${e.message}" }
                )
            }
        }
    }

    private fun loadCurrentQueuePosition(songIdOverride: Long? = null) {
        loadJob?.cancel()
        pendingEphemeralVideoId = null
        // Unconditionally stop whatever was playing right now, before any slow async work
        // (YouTube stream resolution or Telegram prebuffer wait) begins - this immediately
        // moves ExoPlayer out of STATE_ENDED into STATE_IDLE so duplicate auto-advance events
        // cannot trigger during the network resolution wait.
        playbackController.stop()

        // The song being navigated AWAY from - if it was still mid-stream (no localFilePath yet,
        // so markStreamedFileCached's poll never caught it finishing), tell TDLib to actually
        // stop downloading it. Our own poll coroutine dying (it's a child of the loadJob just
        // cancelled above) doesn't do this on its own - see MusicRepository
        // .cancelStreamingDownload's own doc for why that mattered for storage.
        val outgoingSong = _uiState.value.song
        if (outgoingSong != null && !outgoingSong.isLocalImport && outgoingSong.youtubeVideoId == null &&
            !outgoingSong.isExplicitDownload && outgoingSong.localFilePath == null
        ) {
            viewModelScope.launch(Dispatchers.IO) { repository.cancelStreamingDownload(outgoingSong.telegramFileId) }
        }

        loadJob = viewModelScope.launch {
            val songId = songIdOverride ?: queue.currentSongId() ?: return@launch

            _uiState.value = _uiState.value.copy(loadingSongId = songId)

            // Fast path: the song is almost always already in memory via allSongsMap (it's
            // kept in sync with the library for the pager cards), so this skips a DB
            // round-trip on the common case instead of always awaiting getSongById() - a real
            // chunk of the latency between a swipe settling and the next song loading.
            var song = allSongsMap.value[songId] ?: withContext(Dispatchers.IO) { repository.getSongById(songId) }
            if (song == null) {
                _uiState.value = _uiState.value.copy(errorMessage = "Song not found in library", loadingSongId = null)
                return@launch
            }

            val freshFileId = withContext(Dispatchers.IO) { repository.getFreshFileIdForSong(song) }
            if (freshFileId != song.telegramFileId) {
                song = song.copy(telegramFileId = freshFileId)
            }

            val rawLrc = song.lyricsSynced.takeIf { !it.isNullOrBlank() }
                ?: song.lyricsPlain.takeIf { !it.isNullOrBlank() && it.contains("[00:") }
            val lines = withContext(Dispatchers.Default) { rawLrc?.let(::parseLrc).orEmpty() }

            val initialPos = if (songIdOverride != null) 0L else playbackController.currentPositionMs()

            _uiState.value = _uiState.value.copy(
                song = song,
                lyricLines = lines,
                currentPositionMs = initialPos,
                durationMs = (song.durationSeconds * 1000L).coerceAtLeast(1L),
                hasNext = queue.hasNext(),
                hasPrevious = queue.hasPrevious(),
                isShuffleEnabled = queue.isShuffleEnabled,
                repeatMode = queue.repeatMode,
                errorMessage = null,
                isDownloading = false
            )

            withContext(Dispatchers.IO) { repository.stampLastPlayed(song) }

            // Zero automatic lyrics fetch on song change! Only manual when user taps button.

            startPlayback(song)

            // Background pre-fetch the next track in queue so YouTube stream resolution
            // happens ahead of time for instant (0ms) playback startup when auto-advancing.
            prefetchNextTrack()

            // Only clears loadingSongId if this is still the song actually on screen - a fast
            // skip to the next/previous song while a slow YouTube resolve was still in flight
            // would otherwise clear the NEW song's own loading state out from under it once the
            // old resolve finally finished.
            if (_uiState.value.song?.telegramMessageId == song.telegramMessageId) {
                _uiState.value = _uiState.value.copy(loadingSongId = null)
            }

            // Streamed-only auto-cache bookkeeping (does NOT set isExplicitDownload). A local
            // import or a YouTube-streamable row (youtubeVideoId set) is already fully on-device
            // or plays via yt-dlp, either way with no real TDLib file behind it to poll for.
            // This launch is a structured child of loadJob (the coroutine this whole block runs
            // in), so it's automatically cancelled the next time loadCurrentQueuePosition() runs
            // for a different song - see that function's own doc for the other half of this: our
            // poll coroutine dying doesn't stop TDLib's own independent download, which needs an
            // explicit cancel.
            if (!song.isLocalImport && song.youtubeVideoId == null) {
                launch(Dispatchers.IO) { repository.markStreamedFileCached(song) }
            }
        }
    }

    private suspend fun startPlayback(song: SongEntity) {
        // repository.resolvePlaybackUri() is the one shared "how do I actually play this song"
        // resolution (local file/referenced content:// URI first, then YouTube stream, then a
        // fresh TDLib file id) - MusicService's next/previous handling and the Android Auto
        // browse tree already go through it, so this no longer duplicates that chain (and no
        // longer risks going stale from it the way a separate copy already has once - see
        // SongEntity.isLocalImport's own doc for why a plain File(path) check specifically would
        // silently break a referenced, not copied, local import).
        val uri = withContext(Dispatchers.IO) { repository.resolvePlaybackUri(song) }
        if (uri != null) {
            // A real library row backed by a YouTube video that had no local/cached copy - this
            // is the one case where a fresh resolve can also learn a corrected duration, so pull
            // the just-updated row (see MusicRepository.resolveDirectPlaybackUri's own doc).
            if (song.youtubeVideoId != null && song.localFilePath == null) {
                val freshSong = repository.getSongById(song.telegramMessageId)
                _uiState.value = _uiState.value.copy(
                    song = freshSong ?: song,
                    durationMs = ((freshSong?.durationSeconds ?: song.durationSeconds) * 1000L).takeIf { it > 0 } ?: _uiState.value.durationMs
                )
            }
            playbackController.playUri(uri, song.telegramMessageId, song.title, song.artist, song.displayArtwork)
        } else {
            val reason = if (song.isLocalImport) "file may have been moved or deleted" else "video may be unavailable"
            _uiState.value = _uiState.value.copy(
                errorMessage = "Couldn't play \"${song.title}\" - $reason",
                loadingSongId = null
            )
            // Auto-skip unplayable track after brief pause so playlist playback continues
            if (queue.hasNext()) {
                delay(1500)
                if (_uiState.value.song?.telegramMessageId == song.telegramMessageId && _uiState.value.loadingSongId == null) {
                    nextSong()
                }
            }
        }
    }

    private fun startPositionTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (true) {
                val pos = playbackController.currentPositionMs()
                val lines = _uiState.value.lyricLines
                val activeIndex = lines.indexOfLast { it.timeMs <= pos }
                val knownDurationMs = _uiState.value.song?.durationSeconds?.takeIf { it > 0 }?.times(1000L)
                val effectiveDurationMs = knownDurationMs ?: playbackController.durationMs().coerceAtLeast(_uiState.value.durationMs)
                _uiState.value = _uiState.value.copy(
                    currentPositionMs = pos,
                    durationMs = effectiveDurationMs,
                    activeLyricIndex = activeIndex,
                    isPlaying = playbackController.isPlaying()
                )
                delay(300)
            }
        }
    }

    private fun prefetchNextTrack() {
        prefetchJob?.cancel()
        val nextId = queue.peekNextId() ?: return
        prefetchJob = viewModelScope.launch(Dispatchers.IO) {
            val nextSong = allSongsMap.value[nextId] ?: repository.getSongById(nextId) ?: return@launch
            if (nextSong.youtubeVideoId != null && nextSong.localFilePath == null) {
                repository.resolveDirectPlaybackUri(nextSong)
            }
        }
    }

    // Flips isPlaying in state immediately, not just the player itself - the icon otherwise
    // only caught up on the NEXT 300ms position-tick poll (startPositionTicker below), which
    // read as the wrong icon (still showing Play right after tapping it) sitting there for a
    // beat before flipping. This is the standard optimistic-update pattern: assume the toggle
    // succeeded and show that immediately, then let the next tick's real
    // playbackController.isPlaying() read silently correct it if it didn't.
    fun togglePlayPause() {
        playbackController.togglePlayPause()
        _uiState.value = _uiState.value.copy(isPlaying = !_uiState.value.isPlaying)
    }

    fun seekTo(positionMs: Long) {
        // Write the target position into state BEFORE telling the player to seek, not after.
        // The seekbar drops out of its own dragging state the instant this returns, at which
        // point it displays currentPositionMs straight from here - if that still held the
        // stale pre-seek value (only refreshed by the 300ms position ticker), the thumb would
        // visibly snap back to the old spot for up to 300ms before jumping to the real one.
        _uiState.value = _uiState.value.copy(currentPositionMs = positionMs)
        playbackController.seekTo(positionMs)
    }

    fun toggleFavorite() {
        val song = _uiState.value.song ?: return
        viewModelScope.launch {
            val newFav = !song.isFavorite
            repository.setFavorite(song, newFav)
            _uiState.value = _uiState.value.copy(song = song.copy(isFavorite = newFav))
        }
    }

    private fun parseLrc(lrc: String): List<LyricLine> {
        val regex = Regex("""\[(\d{2}):(\d{2})[.:](\d{2,3})]\s*(.*)""")
        return lrc.lines().mapNotNull { line ->
            val match = regex.find(line) ?: return@mapNotNull null
            val (min, sec, ms, text) = match.destructured
            val millis = min.toLong() * 60_000 + sec.toLong() * 1000 + ms.padEnd(3, '0').take(3).toLong()
            LyricLine(millis, text)
        }.sortedBy { it.timeMs }
    }

    override fun onCleared() {
        tickerJob?.cancel()
        loadJob?.cancel()
        super.onCleared()
    }
}