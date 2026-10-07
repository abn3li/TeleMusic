package com.abn3li.telemusic.playback

/** Unknown player durations are negative; home shelf metadata may also omit duration. */
internal fun flacRecordingDuration(playerDurationMs: Long, metadataDurationSeconds: Int): Long? =
    playerDurationMs.takeIf { it > 0 }
        ?: metadataDurationSeconds.takeIf { it > 0 }?.toLong()?.times(1000L)
