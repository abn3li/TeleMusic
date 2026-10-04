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
}
