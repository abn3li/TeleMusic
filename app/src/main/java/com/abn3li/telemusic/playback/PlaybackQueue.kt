package com.abn3li.telemusic.playback

enum class RepeatMode { OFF, ALL, ONE }

/**
 * The ordered list of song IDs being played, plus which one is current. Two parts:
 *
 * - [base]: the browsing order the queue was started from (shuffled when shuffle is on), with
 *   [baseIndex] pointing at the current song.
 * - [nextInQueue]: songs the user explicitly asked to "Play next". They always play before the
 *   rest of [base], in the order added, and are consumed as they play.
 *
 * Every navigation entry point (the in-app UI, the media notification, Android Auto) goes
 * through [next]/[previous]/[hasNext], so queued songs are honoured everywhere.
 */
class PlaybackQueue {
    private var originalSongIds: MutableList<Long> = mutableListOf()
    private var base: MutableList<Long> = mutableListOf()
    private var baseIndex: Int = -1
    private val nextInQueue: MutableList<Long> = mutableListOf()

    var isShuffleEnabled: Boolean = false
        private set

    var repeatMode: RepeatMode = RepeatMode.OFF
        private set

    fun setQueue(ids: List<Long>, startIndex: Int) {
        nextInQueue.clear()
        originalSongIds = ids.toMutableList()
        if (ids.isEmpty()) {
            base = mutableListOf()
            baseIndex = -1
            return
        }
        val start = startIndex.coerceIn(ids.indices)
        if (isShuffleEnabled) {
            base = shuffledWithCurrentFirst(ids, ids[start])
            baseIndex = 0
        } else {
            base = ids.toMutableList()
            baseIndex = start
        }
    }

    fun currentSongId(): Long? = base.getOrNull(baseIndex)

    fun nextInQueueIds(): List<Long> = nextInQueue.toList()

    fun upNextIds(): List<Long> =
        if (baseIndex in base.indices) base.subList(baseIndex + 1, base.size).toList() else emptyList()

    /**
     * Adds [id] to the end of "Next in Queue". Returns true when nothing was playing, in which
     * case [id] became the current song and the caller has to start playback itself.
     */
    fun playNext(id: Long): Boolean {
        if (currentSongId() == null) {
            setQueue(listOf(id), 0)
            return true
        }
        nextInQueue.add(id)
        return false
    }

    fun clearNextInQueue() = nextInQueue.clear()

    fun removeNextInQueue(offset: Int) {
        if (offset in nextInQueue.indices) nextInQueue.removeAt(offset)
    }

    fun moveNextInQueue(fromOffset: Int, toOffset: Int) {
        if (fromOffset !in nextInQueue.indices || toOffset !in nextInQueue.indices || fromOffset == toOffset) return
        nextInQueue.add(toOffset, nextInQueue.removeAt(fromOffset))
    }

    fun removeUpNext(offset: Int) {
        val index = baseIndex + 1 + offset
        if (index !in base.indices) return
        val id = base.removeAt(index)
        if (isShuffleEnabled) originalSongIds.remove(id) else originalSongIds = base.toMutableList()
    }

    /** Reorders within "Up Next". Offsets are relative to the song after the current one. While
     * shuffled only the shuffled order changes, so turning shuffle off restores the original. */
    fun moveUpNext(fromOffset: Int, toOffset: Int) {
        val from = baseIndex + 1 + fromOffset
        val to = baseIndex + 1 + toOffset
        if (from !in base.indices || to !in base.indices || from == to) return
        base.add(to, base.removeAt(from))
        if (!isShuffleEnabled) originalSongIds = base.toMutableList()
    }

    fun moveUpNextToNextInQueue(offset: Int): Boolean {
        val index = baseIndex + 1 + offset
        if (index !in base.indices) return false
        val id = base.removeAt(index)
        if (isShuffleEnabled) originalSongIds.remove(id) else originalSongIds = base.toMutableList()
        nextInQueue.add(id)
        return true
    }

    /** Plays the "Next in Queue" song at [offset] now; the queued songs before it are skipped. */
    fun jumpToNextInQueue(offset: Int): Long? {
        if (offset !in nextInQueue.indices) return null
        repeat(offset) { nextInQueue.removeAt(0) }
        return advanceIntoNextInQueue()
    }

    /** Plays the "Up Next" song at [offset] now. "Next in Queue" is left intact. */
    fun jumpToUpNext(offset: Int): Long? {
        val index = baseIndex + 1 + offset
        if (index !in base.indices) return null
        baseIndex = index
        return base[index]
    }

    /** Empties the queue entirely - used when playback moves to something that was never part
     * of any queue (an ephemeral YouTube stream), so Next/Previous can't fall back to an old one. */
    fun clear() {
        originalSongIds = mutableListOf()
        base = mutableListOf()
        baseIndex = -1
        nextInQueue.clear()
    }

    fun hasNext(): Boolean {
        if (currentSongId() == null) return false
        if (nextInQueue.isNotEmpty()) return true
        if (base.size <= 1) return false
        return when (repeatMode) {
            RepeatMode.ONE, RepeatMode.ALL -> true
            RepeatMode.OFF -> baseIndex + 1 < base.size
        }
    }

    /** Peeks at the next song ID without advancing - used for background pre-fetching. */
    fun peekNextId(): Long? {
        if (base.isEmpty()) return null
        if (repeatMode == RepeatMode.ONE) return currentSongId()
        nextInQueue.firstOrNull()?.let { return it }
        if (baseIndex + 1 < base.size) return base[baseIndex + 1]
        if (repeatMode == RepeatMode.ALL) return base.firstOrNull()
        return null
    }

    fun hasPrevious(): Boolean {
        if (base.size <= 1) return false
        return when (repeatMode) {
            RepeatMode.ONE, RepeatMode.ALL -> true
            RepeatMode.OFF -> baseIndex - 1 >= 0
        }
    }

    // Bumped when shuffle or repeat changes, from anywhere (the app or the home-screen widget),
    // so Now Playing can follow without polling.
    private val _modeChanges = kotlinx.coroutines.flow.MutableStateFlow(0)
    val modeChanges: kotlinx.coroutines.flow.StateFlow<Int> = _modeChanges

    fun toggleShuffle(): Boolean {
        _modeChanges.value++
        isShuffleEnabled = !isShuffleEnabled
        val currentId = currentSongId() ?: return isShuffleEnabled
        if (isShuffleEnabled) {
            base = shuffledWithCurrentFirst(originalSongIds, currentId)
            baseIndex = 0
        } else {
            base = originalSongIds.toMutableList()
            baseIndex = base.indexOf(currentId).coerceAtLeast(0)
        }
        return isShuffleEnabled
    }

    fun toggleRepeat(): RepeatMode {
        _modeChanges.value++
        repeatMode = when (repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        return repeatMode
    }

    /** [auto] is the end-of-song advance: only that one repeats the song under Repeat One. A
     * Next the user asked for always moves on (wrapping around, like Repeat All). */
    fun next(auto: Boolean = false): Long? {
        if (base.isEmpty()) return null
        if (auto && repeatMode == RepeatMode.ONE) return currentSongId()
        if (nextInQueue.isNotEmpty()) return advanceIntoNextInQueue()
        baseIndex = if (baseIndex + 1 < base.size) baseIndex + 1 else 0
        return base[baseIndex]
    }

    fun previous(): Long? {
        if (base.isEmpty()) return null
        baseIndex = if (baseIndex - 1 >= 0) baseIndex - 1 else base.size - 1
        return base[baseIndex]
    }

    // A consumed "Next in Queue" song becomes part of the base order right after the song that
    // was playing, so Previous can return to it and toggling shuffle doesn't lose track of it.
    private fun advanceIntoNextInQueue(): Long {
        val id = nextInQueue.removeAt(0)
        val previousId = currentSongId()
        base.add(baseIndex + 1, id)
        baseIndex += 1
        val originalAnchor = previousId?.let { originalSongIds.indexOf(it) } ?: -1
        if (originalAnchor >= 0) originalSongIds.add(originalAnchor + 1, id) else originalSongIds.add(id)
        return id
    }

    private fun shuffledWithCurrentFirst(ids: List<Long>, currentId: Long): MutableList<Long> {
        val shuffled = ids.shuffled().toMutableList()
        shuffled.remove(currentId)
        shuffled.add(0, currentId)
        return shuffled
    }
}
