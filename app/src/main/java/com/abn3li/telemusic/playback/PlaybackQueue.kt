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

    fun hasNext(): Boolean {
        if (songIds.size <= 1) return false
        return when (repeatMode) {
            RepeatMode.ONE, RepeatMode.ALL -> true
            RepeatMode.OFF -> currentIndex in songIds.indices && currentIndex + 1 < songIds.size
        }
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