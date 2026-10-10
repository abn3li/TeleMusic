package com.abn3li.telemusic.ui.navigation

import android.app.Application
import android.graphics.RenderNode
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.abn3li.telemusic.ui.library.LargeTitleList
import com.abn3li.telemusic.ui.nowplaying.NowPlayingUiState
import com.abn3li.telemusic.ui.nowplaying.PlayerTransition
import com.abn3li.telemusic.ui.theme.DarkPalette
import com.abn3li.telemusic.ui.theme.LightPalette
import com.abn3li.telemusic.ui.theme.LocalPalette
import com.abn3li.telemusic.data.local.SongEntity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeedRenderingTest : LocalComposeTest() {

    @Test fun scrollingAndChangingPagesKeepOnePageBackdrop() {
        val host = FrostedBackdropHost()
        var page by mutableIntStateOf(0)
        var light by mutableStateOf(false)
        var hasSong by mutableStateOf(false)
        var fallbackDraws = 0
        setContent {
            val fallback = rememberFrostedBackdrop()
            val palette = if (light) LightPalette else DarkPalette
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().then(if (host.active == null)
                    Modifier.drawWithContent { fallbackDraws++; drawContent() }
                        .frostedBackdropSource(fallback, palette.background)
                    else Modifier)) {
                    CompositionLocalProvider(LocalFrostedBackdropHost provides host, LocalFrostedDockVisible provides true, LocalPalette provides palette) {
                        key(page) {
                            val list = rememberLazyListState()
                            LargeTitleList(if (page == 0) "Home" else "Library", listState = list, showTopBar = page != 0) {
                                items(40) { index ->
                                    Box(Modifier.fillMaxWidth().height(100.dp).testTag("row_$index")
                                        .background(if (index % 2 == 0) Color(0xFF295E73) else Color(0xFF834758))) {
                                        Text("Item $index")
                                    }
                                }
                            }
                        }
                    }
                }
                CompositionLocalProvider(LocalPalette provides palette) {
                    val state = NowPlayingUiState(song = if (hasSong) SongEntity(1, 0, "Song", "Artist") else null)
                    ClassicDock("home", {}, host.active ?: fallback, state, 0.dp,
                        {}, {}, {}, {}, {}, {}, remember { PlayerTransition() }, true,
                        Modifier.align(Alignment.BottomCenter))
                }
            }
        }
        lateinit var home: FrostedBackdrop
        compose.runOnIdle {
            home = checkNotNull(host.active)
            fallbackDraws = 0
        }
        scrollToRow(20)
        compose.runOnIdle {
            assertSame(home, host.active)
            assertEquals("Scrolling must not capture the enclosing page a second time", 0, fallbackDraws)
            light = true
            hasSong = true
        }
        scrollToRow(25)
        compose.runOnIdle {
            assertSame(home, host.active)
            page = 1
        }
        scrollToRow(15)
        compose.runOnIdle {
            assertNotSame(home, host.active)
            page = 0
        }
        scrollToRow(20)
        compose.runOnIdle { assertNotNull(host.active) }
    }

    @Test fun idleFeedDoesNotKeepRecomposingOrRedrawing() {
        var compositions = 0
        var draws = 0
        val host = FrostedBackdropHost()
        setContent {
            val fallback = rememberFrostedBackdrop()
            Box(Modifier.fillMaxSize()) {
                SideEffect { compositions++ }
                CompositionLocalProvider(LocalFrostedBackdropHost provides host, LocalFrostedDockVisible provides true) {
                    Box(Modifier.fillMaxSize().drawWithContent { draws++; drawContent() }) {
                        LargeTitleList("Home", showTopBar = false) {
                            items(20) { Text("Item $it", modifier = Modifier.height(100.dp)) }
                        }
                    }
                }
                ClassicDock("home", {}, host.active ?: fallback, NowPlayingUiState(), 0.dp,
                    {}, {}, {}, {}, {}, {}, remember { PlayerTransition() }, true,
                    Modifier.align(Alignment.BottomCenter))
            }
        }
        renderFrame()
        compose.waitForIdle()
        var settledCompositions = 0
        var settledDraws = 0
        compose.runOnIdle { settledCompositions = compositions; settledDraws = draws }
        compose.mainClock.advanceTimeBy(2000)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(settledCompositions, compositions)
            assertEquals(settledDraws, draws)
        }
    }

    private fun scrollToRow(index: Int) {
        // Offscreen lazy items do not exist until the list scrolls to them.
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(index + 1)
        compose.onNodeWithTag("row_$index").assertIsDisplayed()
        renderFrame()
    }

    private fun renderFrame() {
        compose.runOnIdle {
            val view = activity.window.decorView
            val root = RenderNode("Feed fixture")
            root.setPosition(0, 0, view.width, view.height)
            val canvas = root.beginRecording()
            try { view.draw(canvas) } finally { root.endRecording() }
            val bitmap = renderNodeBitmap(root, view.width, view.height)
            assertTrue(bitmap.width > 0 && bitmap.height > 0)
            bitmap.recycle()
        }
        // Rasterizing a RenderNode outside a window frame can queue the first draw's
        // invalidation. Drain that window callback before asking Compose to settle.
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }
}
