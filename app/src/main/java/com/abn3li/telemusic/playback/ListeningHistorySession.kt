package com.abn3li.telemusic.playback

/** Elapsed listening time, independent of seeks, playback position and UI tickers. */
internal class ListeningHistorySession {
    private var accumulated = 0L
    private var playingSince: Long? = null
    private var submitted = false

    fun setPlaying(playing: Boolean, now: Long) {
        playingSince?.let { accumulated += (now - it).coerceAtLeast(0) }
        playingSince = if (playing) now else null
    }

    fun remainingMs(now: Long): Long = (MIN_LISTEN_MS - accumulated -
        (playingSince?.let { (now - it).coerceAtLeast(0) } ?: 0)).coerceAtLeast(0)

    fun claimReport(now: Long): Boolean {
        if (submitted || remainingMs(now) > 0) return false
        submitted = true
        return true
    }

    val isSubmitted get() = submitted

    companion object { const val MIN_LISTEN_MS = 30_000L }
}
