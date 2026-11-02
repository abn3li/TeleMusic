package com.abn3li.telemusic.playback

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The sleep timer: the music stops after a set time, or when the song playing ends. Set from the
 * player's menu; MusicService does the stopping, so it works with the app closed. Nothing runs
 * while it waits - one wake-up at the end, then a short fade.
 */
class SleepTimer {
    sealed interface State {
        data object Off : State
        /** Stops at [endsAt] (elapsedRealtime). */
        data class At(val endsAt: Long) : State
        data object EndOfSong : State
    }

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state

    fun start(minutes: Int) { _state.value = State.At(SystemClock.elapsedRealtime() + minutes * 60_000L) }
    fun stopAtEndOfSong() { _state.value = State.EndOfSong }
    fun cancel() { _state.value = State.Off }

    /** Whole minutes left, rounded up (1 in the last minute); null unless set to a time. */
    fun minutesLeft(now: Long = SystemClock.elapsedRealtime()): Int? =
        (_state.value as? State.At)?.let { ((it.endsAt - now).coerceAtLeast(0) + 59_999) / 60_000 }?.toInt()

    companion object {
        val CHOICES = listOf(15, 30, 45, 60)
        /** The music fades out over this long before it stops. */
        const val FADE_MS = 8_000L
    }
}
