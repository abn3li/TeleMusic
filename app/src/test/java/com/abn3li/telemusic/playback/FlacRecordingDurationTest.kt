package com.abn3li.telemusic.playback

import org.junit.Assert.*
import org.junit.Test

class FlacRecordingDurationTest {
    @Test fun homeTrackWithoutMetadataCanUpgradeWhenPlayerBecomesReady() {
        assertNull(flacRecordingDuration(-9223372036854775807L, 0))
        assertEquals(266000L, flacRecordingDuration(266000L, 0))
    }

    @Test fun knownMetadataAllowsMatchingWhileThePlayerIsBuffering() {
        assertEquals(200000L, flacRecordingDuration(-9223372036854775807L, 200))
    }

    @Test fun preparedStreamDurationWinsOverStaleShelfMetadata() {
        assertEquals(316000L, flacRecordingDuration(316000L, 200))
    }
}
