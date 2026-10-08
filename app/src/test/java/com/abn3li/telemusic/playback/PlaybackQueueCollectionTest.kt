package com.abn3li.telemusic.playback

import org.junit.Assert.*
import org.junit.Test

class PlaybackQueueCollectionTest {
    @Test fun collectionKeepsOrderAfterAlreadyRequestedSongs() {
        val queue = PlaybackQueue()
        queue.setQueue(listOf(1L, 2L), 0)
        queue.playNext(3L)
        assertFalse(queue.playNext(listOf(4L, 5L, 6L)))
        assertEquals(1L, queue.currentSongId())
        assertEquals(listOf(3L, 4L, 5L, 6L), queue.nextInQueueIds())
        assertEquals(listOf(2L), queue.upNextIds())
    }

    @Test fun collectionStartsWithFirstSongWhenNothingIsPlaying() {
        val queue = PlaybackQueue()
        assertTrue(queue.playNext(listOf(4L, 5L, 6L)))
        assertEquals(4L, queue.currentSongId())
        assertEquals(listOf(5L, 6L), queue.nextInQueueIds())
    }

    @Test fun emptyCollectionLeavesExistingQueueUntouched() {
        val queue = PlaybackQueue()
        queue.setQueue(listOf(1L, 2L), 0)
        queue.playNext(3L)
        assertFalse(queue.playNext(emptyList()))
        assertEquals(1L, queue.currentSongId())
        assertEquals(listOf(3L), queue.nextInQueueIds())
        assertEquals(listOf(2L), queue.upNextIds())
    }
}
