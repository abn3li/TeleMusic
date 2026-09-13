package com.abn3li.telemusic.ui.nowplaying

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.abn3li.telemusic.data.local.SongEntity
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
    private val queue: PlaybackQueue
) : ViewModel() {

    private val _uiState = MutableStateFlow(NowPlayingUiState())
    val uiState: StateFlow<NowPlayingUiState> = _uiState
    private var tickerJob: Job? = null
    private var loadJob: Job? = null

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
            }
        })
    }

    /**
     * Catches this ViewModel's own displayed state up to a song change it didn't initiate - see
     * the transition listener registered in the init block above. The queue's position is
     * already correct (the service advanced the same shared [PlaybackQueue] instance) and
     * playback is already under way, so unlike [loadCurrentQueuePosition] this must never call
     * [startPlayback] or otherwise touch the transport - it only re-reads what's now playing.
     */
    private fun resyncToExternallyChangedSong(songId: Long) {
        viewModelScope.launch {
            val song = allSongsMap.value[songId]
                ?: withContext(Dispatchers.IO) { repository.getSongById(songId) }
                ?: return@launch
            val rawLrc = song.lyricsSynced.takeIf { !it.isNullOrBlank() }
                ?: song.lyricsPlain.takeIf { !it.isNullOrBlank() && it.contains("[00:") }
            val lines = withContext(Dispatchers.Default) { rawLrc?.let(::parseLrc).orEmpty() }
            _uiState.value = _uiState.value.copy(
                song = song,
                lyricLines = lines,
                currentPositionMs = 0L,
                durationMs = (song.durationSeconds * 1000L).coerceAtLeast(1L),
                hasNext = queue.hasNext(),
                hasPrevious = queue.hasPrevious(),
                errorMessage = null,
                isDownloading = false
            )
            withContext(Dispatchers.IO) { repository.stampLastPlayed(song) }
        }
    }

    /** Sets a new queue and immediately starts playing the chosen song. */
    fun playFromQueue(ids: List<Long>, startIndex: Int) {
        queue.setQueue(ids, startIndex)
        loadCurrentQueuePosition(songIdOverride = queue.currentSongId())
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
    }

    fun previousSong() {
        val id = queue.previous() ?: return
        loadCurrentQueuePosition(songIdOverride = id)
    }

    fun toggleShuffle() {
        val enabled = queue.toggleShuffle()
        _uiState.value = _uiState.value.copy(
            isShuffleEnabled = enabled,
            hasNext = queue.hasNext(),
            hasPrevious = queue.hasPrevious()
        )
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

    private fun loadCurrentQueuePosition(songIdOverride: Long? = null) {
        loadJob?.cancel()
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
                loadingSongId = null,
                // isDownloading belongs to whichever song was on screen when a download was
                // started, not necessarily this new one - without resetting it here, skipping
                // away from a song mid-download left the NEXT song showing a downloading
                // spinner it had nothing to do with.
                isDownloading = false
            )

            withContext(Dispatchers.IO) { repository.stampLastPlayed(song) }

            // Zero automatic lyrics fetch on song change! Only manual when user taps button.

            startPlayback(song)

            // Streamed-only auto-cache bookkeeping (does NOT set isExplicitDownload). A local
            // import is already fully on-device and has no real TDLib file behind it to poll
            // for - markStreamedFileCached would otherwise loop forever waiting for a download
            // completion that can never come.
            if (!song.isLocalImport) {
                launch(Dispatchers.IO) { repository.markStreamedFileCached(song) }
            }
        }
    }

    private fun startPlayback(song: SongEntity) {
        val localPath = song.localFilePath
        if (localPath != null && File(localPath).let { it.exists() && it.length() > 0 }) {
            playbackController.playLocalFile(localPath, song.telegramMessageId, song.title, song.artist, song.albumArtUrl)
        } else {
            playbackController.playSong(song.telegramFileId, song.telegramMessageId, song.title, song.artist, song.albumArtUrl)
        }
    }

    private fun startPositionTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (true) {
                val pos = playbackController.currentPositionMs()
                val lines = _uiState.value.lyricLines
                val activeIndex = lines.indexOfLast { it.timeMs <= pos }
                _uiState.value = _uiState.value.copy(
                    currentPositionMs = pos,
                    durationMs = playbackController.durationMs().coerceAtLeast(_uiState.value.durationMs),
                    activeLyricIndex = activeIndex,
                    isPlaying = playbackController.isPlaying()
                )
                delay(300)
            }
        }
    }

    fun togglePlayPause() = playbackController.togglePlayPause()

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