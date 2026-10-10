package com.abn3li.telemusic.ui.nowplaying

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import com.abn3li.telemusic.ui.navigation.LocalComposeTest
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerVisualStateTest : LocalComposeTest() {
    @Test fun coveredVisualsUnsubscribeWhileTheClockContinuesAndCatchUpOnDismiss() {
        val clock = MutableStateFlow(PlaybackProgress(currentPositionMs = 1000))
        var active by mutableStateOf(true)
        setContent {
            val progress by clock.collectAsStateWhileActive(active)
            Text(progress.currentPositionMs.toString(), Modifier.testTag("position"))
        }
        compose.onNodeWithTag("position").assertTextEquals("1000")
        compose.runOnIdle {
            assertEquals(1, clock.subscriptionCount.value)
            clock.value = PlaybackProgress(currentPositionMs = 2000)
        }
        compose.onNodeWithTag("position").assertTextEquals("2000")

        compose.runOnIdle { active = false }
        compose.onNodeWithTag("position").assertTextEquals("2000")
        compose.runOnIdle {
            assertEquals(0, clock.subscriptionCount.value)
            clock.value = PlaybackProgress(currentPositionMs = 9000)
        }
        compose.mainClock.advanceTimeBy(2000)
        compose.onNodeWithTag("position").assertTextEquals("2000")
        compose.runOnIdle {
            assertEquals(9000, clock.value.currentPositionMs)
            active = true
        }
        compose.onNodeWithTag("position").assertTextEquals("9000")
        compose.runOnIdle { assertEquals(1, clock.subscriptionCount.value) }
    }

    @Test fun initiallyHiddenVisualsDoNotSubscribeOrFollowTicks() {
        val clock = MutableStateFlow(PlaybackProgress(currentPositionMs = 1000))
        var active by mutableStateOf(false)
        setContent {
            val progress by clock.collectAsStateWhileActive(active)
            Text(progress.currentPositionMs.toString(), Modifier.testTag("position"))
        }
        compose.onNodeWithTag("position").assertTextEquals("1000")
        compose.runOnIdle {
            assertEquals(0, clock.subscriptionCount.value)
            clock.value = PlaybackProgress(currentPositionMs = 5000)
        }
        compose.onNodeWithTag("position").assertTextEquals("1000")
        compose.runOnIdle { active = true }
        compose.onNodeWithTag("position").assertTextEquals("5000")
    }

    @Test fun replacingTheClockDropsThePreviousSubscription() {
        val previous = MutableStateFlow(PlaybackProgress(currentPositionMs = 1000))
        val next = MutableStateFlow(PlaybackProgress(currentPositionMs = 7000))
        var clock by mutableStateOf(previous)
        setContent {
            val progress by clock.collectAsStateWhileActive(active = true)
            Text(progress.currentPositionMs.toString(), Modifier.testTag("position"))
        }
        compose.runOnIdle { clock = next }
        compose.onNodeWithTag("position").assertTextEquals("7000")
        compose.runOnIdle {
            assertEquals(0, previous.subscriptionCount.value)
            assertEquals(1, next.subscriptionCount.value)
            previous.value = PlaybackProgress(currentPositionMs = 3000)
        }
        compose.onNodeWithTag("position").assertTextEquals("7000")
    }
}
