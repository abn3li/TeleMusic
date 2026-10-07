package com.abn3li.telemusic.playback

import org.junit.Assert.*
import org.junit.Test

class ListeningHistorySessionTest {
    @Test fun aQuickTapOrSkipIsNotAListen() {
        val play = ListeningHistorySession()
        play.setPlaying(true, 0)
        play.setPlaying(false, 2_000)
        assertFalse(play.claimReport(60_000))
        assertEquals(28_000, play.remainingMs(60_000))
    }

    @Test fun pausesAndBufferingDoNotCountTowardThirtySeconds() {
        val play = ListeningHistorySession()
        play.setPlaying(true, 0)
        play.setPlaying(false, 10_000)
        play.setPlaying(true, 100_000)
        assertFalse(play.claimReport(119_999))
        assertTrue(play.claimReport(120_000))
    }

    @Test fun seeksAndRepeatedPlayerEventsDoNotDuplicateHistory() {
        val play = ListeningHistorySession()
        play.setPlaying(true, 0)
        play.setPlaying(true, 5_000)
        play.setPlaying(true, 15_000)
        assertFalse(play.claimReport(29_999))
        assertTrue(play.claimReport(30_000))
        play.setPlaying(false, 40_000)
        play.setPlaying(true, 50_000)
        assertFalse(play.claimReport(90_000))
    }

    @Test fun aNewPlayCanRegisterAfterThePreviousPlay() {
        val first = ListeningHistorySession()
        first.setPlaying(true, 0)
        assertTrue(first.claimReport(30_000))
        val next = ListeningHistorySession()
        next.setPlaying(true, 30_000)
        assertFalse(next.claimReport(30_001))
        assertTrue(next.claimReport(60_000))
    }
}
