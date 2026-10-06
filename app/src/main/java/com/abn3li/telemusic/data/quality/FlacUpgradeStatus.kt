package com.abn3li.telemusic.data.quality

import kotlinx.coroutines.flow.StateFlow

enum class FlacUpgradeStage { OFF, READY, SEARCHING, REQUESTING, BUFFERING, PREPARING, LOSSLESS, UNAVAILABLE }

data class FlacTransferProgress(val downloadedBytes: Long = 0, val bytesPerSecond: Long = 0)

data class FlacTransferInfo(val sizeBytes: Long, val progress: StateFlow<FlacTransferProgress>,
    val sampleRate: Int = 0, val bitDepth: Int = 0, val channels: Int = 0)

data class FlacUpgradeStatus(val songId: String? = null, val stage: FlacUpgradeStage = FlacUpgradeStage.OFF,
    val detail: String = "Automatic upgrade is off.", val transfer: FlacTransferInfo? = null)

internal fun idleFlacStatus(songId: String?, enabled: Boolean, hasAccount: Boolean) = FlacUpgradeStatus(
    songId, if (enabled) FlacUpgradeStage.READY else FlacUpgradeStage.OFF,
    when {
        enabled -> "Ready to find a matching FLAC when this song plays."
        hasAccount -> "Automatic upgrade is off. Enable it in Settings → Audio Quality."
        else -> "Set up your Soulseek account in Settings → Audio Quality."
    }
)

/** A previous song's transfer must never appear under the current song's seek bar. */
internal fun flacStatusForSong(status: FlacUpgradeStatus, songId: String, enabled: Boolean,
    hasAccount: Boolean, originalIsFlac: Boolean): FlacUpgradeStatus = when {
    originalIsFlac && status.songId == songId && status.stage == FlacUpgradeStage.LOSSLESS -> status
    originalIsFlac -> FlacUpgradeStatus(songId, FlacUpgradeStage.LOSSLESS, "Already playing FLAC.")
    !enabled || status.songId != songId -> idleFlacStatus(songId, enabled, hasAccount)
    else -> status
}

internal fun flacStatusLabel(stage: FlacUpgradeStage, receivedBytes: Long, totalBytes: Long): String = when (stage) {
    FlacUpgradeStage.OFF -> "Quality off"
    FlacUpgradeStage.READY -> "Quality"
    FlacUpgradeStage.SEARCHING -> "Searching…"
    FlacUpgradeStage.REQUESTING -> "Connecting…"
    FlacUpgradeStage.BUFFERING -> if (totalBytes > 0)
        "Upgrading · ${(receivedBytes.coerceIn(0, totalBytes) * 100 / totalBytes)}%" else "Upgrading…"
    FlacUpgradeStage.PREPARING -> "Switching…"
    FlacUpgradeStage.LOSSLESS -> "Lossless"
    FlacUpgradeStage.UNAVAILABLE -> "Original"
}

/**
 * The label under the seek bar, kept calm: searching and connecting aren't shown - it reads
 * "Original" until the FLAC download really starts (1%), then "Upgrading" with the percentage.
 * The percentage never goes backwards - when a new uploader starts over, it holds at
 * [heldPercent] until the new download passes it. Returns the label and the percentage to hold
 * next time (-1: none yet).
 */
internal fun flacSteadyLabel(stage: FlacUpgradeStage, receivedBytes: Long, totalBytes: Long,
    heldPercent: Int): Pair<String, Int> {
    fun upgrading(percent: Int) = if (percent >= 1) "Upgrading · $percent%" else "Original"
    return when (stage) {
        FlacUpgradeStage.OFF -> "Quality off" to -1
        // Waiting for the song to play: it's the original that plays.
        FlacUpgradeStage.READY -> "Original" to -1
        FlacUpgradeStage.SEARCHING, FlacUpgradeStage.REQUESTING, FlacUpgradeStage.PREPARING ->
            upgrading(heldPercent) to heldPercent
        FlacUpgradeStage.BUFFERING -> {
            val percent = if (totalBytes > 0) (receivedBytes.coerceIn(0, totalBytes) * 100 / totalBytes).toInt() else -1
            val shown = maxOf(heldPercent, percent)
            upgrading(shown) to shown
        }
        FlacUpgradeStage.LOSSLESS -> "Lossless" to -1
        FlacUpgradeStage.UNAVAILABLE -> "Original" to -1
    }
}

// The first packet is too brief to measure. Later values change only when actual bytes arrive.
internal fun flacTransferRate(receivedBytes: Long, elapsedMs: Long): Long =
    if (elapsedMs < 1000) 0 else receivedBytes * 1000 / elapsedMs
