package com.abn3li.telemusic.data.quality

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class FlacUpgradeStatusTest {
    private val transfer = FlacTransferInfo(100, MutableStateFlow(FlacTransferProgress(50, 20)), 44100, 16, 2)

    @Test fun changingSongsCannotShowThePreviousTransferOrError() {
        for (stage in listOf(FlacUpgradeStage.BUFFERING, FlacUpgradeStage.LOSSLESS, FlacUpgradeStage.UNAVAILABLE)) {
            val old = FlacUpgradeStatus("old", stage, "Previous song", transfer)
            val next = flacStatusForSong(old, "new", true, true, false)
            assertEquals("new", next.songId)
            assertEquals(FlacUpgradeStage.READY, next.stage)
            assertNull(next.transfer)
            assertNotEquals("Previous song", next.detail)
        }
    }

    @Test fun disablingUpgradeClearsTheDisplayedTransfer() {
        val current = FlacUpgradeStatus("song", FlacUpgradeStage.BUFFERING, "Buffering", transfer)
        val off = flacStatusForSong(current, "song", false, true, false)
        assertEquals(FlacUpgradeStage.OFF, off.stage)
        assertNull(off.transfer)
    }

    @Test fun anOriginalFlacRemainsLosslessWhenAutomaticUpgradeIsOff() {
        val current = flacStatusForSong(FlacUpgradeStatus(), "saved", false, false, true)
        assertEquals(FlacUpgradeStage.LOSSLESS, current.stage)
        assertEquals("Lossless", flacStatusLabel(current.stage, 0, 0))
    }

    @Test fun aFullBufferCannotClaimLosslessUntilPlaybackHasSwitched() {
        assertEquals("Upgrading · 100%", flacStatusLabel(FlacUpgradeStage.BUFFERING, 100, 100))
        assertEquals("Switching…", flacStatusLabel(FlacUpgradeStage.PREPARING, 100, 100))
        assertEquals("Lossless", flacStatusLabel(FlacUpgradeStage.LOSSLESS, 100, 100))
        assertEquals("Original", flacStatusLabel(FlacUpgradeStage.UNAVAILABLE, 100, 100))
    }

    @Test fun transferSpeedStartsWithActualDataAndAvoidsTheFirstPacketSpike() {
        assertEquals(0L, flacTransferRate(65536, 0))
        assertEquals(0L, flacTransferRate(65536, 999))
        assertEquals(2L * 1024 * 1024, flacTransferRate(6L * 1024 * 1024, 3000))
    }

    @Test fun currentSongKeepsItsDetailedFailureWithoutItsOldBuffer() {
        val failure = FlacUpgradeStatus("song", FlacUpgradeStage.UNAVAILABLE, "Peer rejected: File not shared")
        assertEquals(failure, flacStatusForSong(failure, "song", true, true, false))
        assertNull(failure.transfer)
    }

    @Test fun savingTheCurrentFlacKeepsItsLiveTransferDetails() {
        val current = FlacUpgradeStatus("song", FlacUpgradeStage.LOSSLESS, "Upgraded", transfer)
        assertSame(current, flacStatusForSong(current, "song", true, true, true))
    }

    @Test fun searchingAndConnectingStillReadOriginal() {
        for (stage in listOf(FlacUpgradeStage.READY, FlacUpgradeStage.SEARCHING, FlacUpgradeStage.REQUESTING, FlacUpgradeStage.PREPARING)) {
            assertEquals("Original" to -1, flacSteadyLabel(stage, 0, 0, -1))
        }
        // The first bytes (under 1%) don't count as upgrading yet.
        assertEquals("Original" to 0, flacSteadyLabel(FlacUpgradeStage.BUFFERING, 5, 1000, -1))
        assertEquals("Upgrading · 1%" to 1, flacSteadyLabel(FlacUpgradeStage.BUFFERING, 10, 1000, -1))
        assertEquals("Upgrading · 40%" to 40, flacSteadyLabel(FlacUpgradeStage.BUFFERING, 40, 100, -1))
    }

    @Test fun percentageHoldsThroughARetryAndNeverGoesBackwards() {
        // First uploader reached 38%, then cut out: connecting again keeps 38%.
        assertEquals("Upgrading · 38%" to 38, flacSteadyLabel(FlacUpgradeStage.REQUESTING, 0, 0, 38))
        // The new uploader starts from 0: still 38% until it passes.
        assertEquals("Upgrading · 38%" to 38, flacSteadyLabel(FlacUpgradeStage.BUFFERING, 5, 100, 38))
        assertEquals("Upgrading · 44%" to 44, flacSteadyLabel(FlacUpgradeStage.BUFFERING, 44, 100, 38))
    }

    @Test fun finishedStatesShowTheResultAndForgetTheHeldPercentage() {
        assertEquals("Lossless" to -1, flacSteadyLabel(FlacUpgradeStage.LOSSLESS, 0, 0, 80))
        assertEquals("Original" to -1, flacSteadyLabel(FlacUpgradeStage.UNAVAILABLE, 0, 0, 80))
    }
}
