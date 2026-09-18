package com.abn3li.telemusic.playback

enum class RepeatMode { OFF, ALL, ONE }

/**
 * The ordered list of song IDs currently being browsed, plus which index is playing.
 * Set by the UI right before navigating into Now Playing, supporting shuffle & repeat modes.
 */
class PlaybackQueue {
    private var originalSongIds: List<Long> = emptyList()
    private var songIds: List<Long> = emptyList()
    private var currentIndex: Int = -1

    var isShuffleEnabled: Boolean = false
        private set

    var repeatMode: RepeatMode = RepeatMode.OFF
        private set

    fun setQueue(ids: List<Long>, startIndex: Int) {
        originalSongIds = ids
        if (isShuffleEnabled) {
            val startId = ids.getOrNull(startIndex)
            val shuffled = ids.shuffled().toMutableList()
            if (startId != null) {
                shuffled.remove(startId)
                shuffled.add(0, startId)
            }
            songIds = shuffled
            currentIndex = 0
        } else {
            songIds = ids
            currentIndex = startIndex.coerceIn(ids.indices)
        }
    }

    fun currentSongId(): Long? = songIds.getOrNull(currentIndex)

    /** The full ordered id list as currently playing (post-shuffle if shuffle is on) - for the
     * Queue screen's "Up Next" list, which is everything after [currentIndex]. */
    fun orderedIds(): List<Long> = songIds
    fun currentIndexValue(): Int = currentIndex

    /** Jumps straight to [index] within [orderedIds] - the Queue screen's "tap an upcoming
     * track to play it now" action. Returns the id now playing, or null if out of range. */
    fun jumpToIndex(index: Int): Long? {
        if (index !in songIds.indices) return null
        currentIndex = index
        return songIds[index]
    }

    /** "Clear Queue" - keeps the currently playing song, drops everything queued after it.
     * originalSongIds is truncated too so toggling shuffle back off afterward doesn't silently
     * bring the cleared songs back. */
    fun removeUpcoming() {
        val keepId = songIds.getOrNull(currentIndex) ?: return
        songIds = listOf(keepId)
        originalSongIds = listOf(keepId)
        currentIndex = 0
    }

    /** Reorders two tracks within the "Up Next" slice (i.e. offsets are relative to
     * currentIndex + 1, not absolute positions in [orderedIds]) - the Queue screen's
     * drag-to-reorder. Only reorders the currently-playing order (the shuffled view, if shuffle
     * is on); originalSongIds is left alone while shuffled so turning shuffle back off restores
     * the original browsing order rather than baking a shuffle-time reorder into it. */
    fun moveUpcoming(fromOffset: Int, toOffset: Int) {
        val base = currentIndex + 1
        val from = base + fromOffset
        val to = base + toOffset
        if (from !in songIds.indices || to !in songIds.indices || from == to) return
        val mutable = songIds.toMutableList()
        val item = mutable.removeAt(from)
        mutable.add(to, item)
        songIds = mutable
        if (!isShuffleEnabled) originalSongIds = mutable
    }

    /** Empties the queue entirely - used when playback moves to something that was never part
     * of any queue (an ephemeral YouTube stream, see PlaybackController.playUri's callers), so
     * Next/Previous can't silently fall back to whatever library queue was playing before.
     * setQueue(emptyList(), 0) is NOT equivalent to this: startIndex.coerceIn(ids.indices) on an
     * empty list's indices (0..-1, an inverted/empty range) throws IllegalArgumentException. */
    fun clear() {
        originalSongIds = emptyList()
        songIds = emptyList()
        currentIndex = -1
    }

    fun hasNext(): Boolean {
        if (songIds.size <= 1) return false
        return when (repeatMode) {
            RepeatMode.ONE, RepeatMode.ALL -> true
            RepeatMode.OFF -> currentIndex in songIds.indices && currentIndex + 1 < songIds.size
        }
    }

    /** Peeks at the next song ID without advancing [currentIndex] - used for background pre-fetching. */
    fun peekNextId(): Long? {
        if (songIds.isEmpty()) return null
        if (repeatMode == RepeatMode.ONE) return currentSongId()
        if (currentIndex + 1 < songIds.size) return songIds[currentIndex + 1]
        if (repeatMode == RepeatMode.ALL) return songIds.firstOrNull()
        return null
    }

    fun hasPrevious(): Boolean {
        if (songIds.size <= 1) return false
        return when (repeatMode) {
            RepeatMode.ONE, RepeatMode.ALL -> true
            RepeatMode.OFF -> currentIndex - 1 >= 0
        }
    }

    fun toggleShuffle(): Boolean {
        isShuffleEnabled = !isShuffleEnabled
        val currentId = currentSongId()
        if (isShuffleEnabled) {
            val shuffled = originalSongIds.shuffled().toMutableList()
            if (currentId != null) {
                shuffled.remove(currentId)
                shuffled.add(0, currentId)
            }
            songIds = shuffled
            currentIndex = 0
        } else {
            songIds = originalSongIds
            currentIndex = if (currentId != null) songIds.indexOf(currentId).coerceAtLeast(0) else 0
        }
        return isShuffleEnabled
    }

    fun toggleRepeat(): RepeatMode {
        repeatMode = when (repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        return repeatMode
    }

    fun next(): Long? {
        if (songIds.isEmpty()) return null
        if (repeatMode == RepeatMode.ONE) {
            return currentSongId()
        }
        if (currentIndex + 1 < songIds.size) {
            currentIndex++
            return songIds[currentIndex]
        } else if (repeatMode == RepeatMode.ALL) {
            currentIndex = 0
            return songIds[currentIndex]
        } else {
            // When Repeat is OFF and we hit the last song, wrap around if user explicitly clicked Next
            currentIndex = (currentIndex + 1) % songIds.size
            return songIds[currentIndex]
        }
    }

    fun previous(): Long? {
        if (songIds.isEmpty()) return null
        if (repeatMode == RepeatMode.ONE) {
            return currentSongId()
        }
        if (currentIndex - 1 >= 0) {
            currentIndex--
            return songIds[currentIndex]
        } else if (repeatMode == RepeatMode.ALL || repeatMode == RepeatMode.OFF) {
            currentIndex = if (currentIndex - 1 < 0) songIds.size - 1 else currentIndex - 1
            return songIds[currentIndex]
        }
        return null
    }

}