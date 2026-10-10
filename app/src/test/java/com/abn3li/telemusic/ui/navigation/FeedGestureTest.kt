package com.abn3li.telemusic.ui.navigation

import android.app.Application
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.abn3li.telemusic.ui.library.rememberStartSnap
import com.abn3li.telemusic.ui.library.shelfDrag
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeedGestureTest : LocalComposeTest() {
    private lateinit var row: LazyListState
    private lateinit var page: LazyListState

    @Test fun slowCoverDragKeepsItsReleasePosition() {
        setContent { Fixture(snap = false) }
        compose.onNodeWithTag("shelf").performTouchInput {
            down(center)
            moveBy(Offset(-65f, 0f), delayMillis = 200)
            up()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, row.firstVisibleItemIndex)
            assertTrue("Cover must stay partway through the drag", row.firstVisibleItemScrollOffset in 1..100)
        }
    }

    @Test fun songTableStillFinishesOnTheNextColumn() {
        setContent { Fixture(snap = true) }
        compose.onNodeWithTag("shelf").performTouchInput {
            down(center)
            moveBy(Offset(-65f, 0f), delayMillis = 200)
            up()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, row.firstVisibleItemIndex)
            assertEquals(0, row.firstVisibleItemScrollOffset)
        }
    }

    @Test fun cancelledTableDragDoesNotStartAnAnimation() {
        setContent { Fixture(snap = true) }
        compose.onNodeWithTag("shelf").performTouchInput {
            down(center)
            moveBy(Offset(-65f, 0f), delayMillis = 200)
            cancel()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, row.firstVisibleItemIndex)
            assertTrue(row.firstVisibleItemScrollOffset in 1..100)
            assertFalse(row.isScrollInProgress)
        }
    }

    @Test fun verticalDragOverCoversScrollsThePage() {
        setContent { Fixture(snap = false) }
        compose.onNodeWithTag("shelf").performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(page.firstVisibleItemIndex > 0 || page.firstVisibleItemScrollOffset > 0)
            assertEquals(0, row.firstVisibleItemIndex)
            assertEquals(0, row.firstVisibleItemScrollOffset)
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable private fun Fixture(snap: Boolean) {
        row = rememberLazyListState()
        page = rememberLazyListState()
        val rowState = row
        val fling = if (snap) rememberStartSnap(rowState) else ScrollableDefaults.flingBehavior()
        LazyColumn(state = page, modifier = Modifier.fillMaxSize()) {
            item {
                LazyRow(state = rowState, userScrollEnabled = false, flingBehavior = fling,
                    modifier = Modifier.fillMaxWidth().height(180.dp).testTag("shelf")
                        .shelfDrag(rowState, fling, 2200.dp, snap)) {
                    items(20) { index -> Box(Modifier.size(140.dp, 180.dp)
                        .background(if (index % 2 == 0) Color.Blue else Color.Red)) }
                }
            }
            items(20) { Box(Modifier.fillMaxWidth().height(100.dp).background(Color.Gray)) }
        }
    }
}
