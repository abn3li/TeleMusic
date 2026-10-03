package com.abn3li.telemusic.ui.navigation

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Move the page at its scroll boundary without using the platform stretch renderer. */
@Composable
internal fun rememberVerticalBounce(route: String?): Modifier {
    val scope = rememberCoroutineScope()
    val limit = with(LocalDensity.current) { 72.dp.toPx() }
    val offset = remember(route) { mutableFloatStateOf(0f) }
    val connection = remember(route, limit) {
        object : NestedScrollConnection {
            var returnJob: Job? = null

            suspend fun settle(velocity: Float) {
                if (abs(offset.floatValue) < 0.5f) {
                    offset.floatValue = 0f
                    return
                }
                returnJob?.cancel()
                val job = scope.launch {
                    animate(
                        initialValue = offset.floatValue,
                        targetValue = 0f,
                        initialVelocity = velocity.coerceIn(-limit * 8, limit * 8),
                        animationSpec = spring(dampingRatio = 1f, stiffness = 220f)
                    ) { value, _ -> offset.floatValue = value.coerceIn(-limit, limit) }
                    offset.floatValue = 0f
                }
                returnJob = job
                job.join()
                if (returnJob === job) returnJob = null
            }

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.Drag || available.y == 0f) return Offset.Zero
                returnJob?.cancel()
                returnJob = null
                val current = offset.floatValue
                // A reversed drag first brings the page home, then resumes ordinary scrolling.
                if (current * available.y < 0f) {
                    val consumed = available.y.coerceIn(-abs(current), abs(current))
                    offset.floatValue = current + consumed
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (source != NestedScrollSource.Drag || available.y == 0f) return Offset.Zero
                val resistance = 0.35f * (1f - abs(offset.floatValue) / limit)
                offset.floatValue = (offset.floatValue + available.y * resistance)
                    .coerceIn(-limit, limit)
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (offset.floatValue == 0f) return Velocity.Zero
                settle(available.y)
                return Velocity(0f, available.y)
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                settle(available.y)
                return Velocity.Zero
            }
        }
    }
    DisposableEffect(connection) {
        onDispose { connection.returnJob?.cancel() }
    }
    // Reading the offset in the layer avoids recomposing or laying out the page per frame.
    // The clip stays fixed while the page moves; the navbar and player are outside this layer.
    return Modifier.clipToBounds().nestedScroll(connection).graphicsLayer {
        translationY = offset.floatValue
    }
}
