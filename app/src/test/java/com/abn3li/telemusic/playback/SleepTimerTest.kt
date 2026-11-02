package com.abn3li.telemusic.playback

import android.app.Application
import android.os.SystemClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SleepTimerTest {
    @Test fun minutesLeftRoundUpAndEndAtTheSetTime() {
        val timer = SleepTimer()
        assertNull(timer.minutesLeft())
        timer.start(30)
        val start = SystemClock.elapsedRealtime()
        assertEquals(30, timer.minutesLeft(start))
        assertEquals(30, timer.minutesLeft(start + 30_000))
        assertEquals(29, timer.minutesLeft(start + 60_000))
        assertEquals(1, timer.minutesLeft(start + 29 * 60_000L + 1))
        assertEquals(0, timer.minutesLeft(start + 31 * 60_000L))
        assertEquals(start + 30 * 60_000L, (timer.state.value as SleepTimer.State.At).endsAt)
    }

    @Test fun endOfSongAndOffHaveNoMinutes() {
        val timer = SleepTimer()
        timer.stopAtEndOfSong()
        assertEquals(SleepTimer.State.EndOfSong, timer.state.value)
        assertNull(timer.minutesLeft())
        timer.start(15)
        timer.cancel()
        assertEquals(SleepTimer.State.Off, timer.state.value)
    }
}
