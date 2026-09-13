package com.abn3li.telemusic.ui.nowplaying

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * A fixed set of bar heights standing in for a track's waveform - there's no real per-track
 * amplitude data to draw from, so this fakes one the same way most players do: seeded off the
 * track itself so it looks the same every time you open this song, not a different shape on
 * every recomposition.
 */
@Composable
fun rememberWaveformAmplitudes(seed: Long?, count: Int = 48): List<Float> =
    remember(seed, count) {
        val random = Random(seed ?: 0L)
        List(count) { random.nextFloat() * 0.75f + 0.25f }
    }

/**
 * Namida-style waveform seek bar: bars fill in white up to the current playback position. Kept
 * strictly monochrome (onSurface/onSurface-alpha only) - no artwork-derived accent color, per
 * the app's white/black-only theme.
 */
@Composable
fun WaveformSeekBar(
    amplitudes: List<Float>,
    currentPositionMs: Long,
    durationMs: Long,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragPositionMs by remember { mutableLongStateOf(0L) }

    val positionToDisplay = if (isDragging) dragPositionMs else currentPositionMs
    val maxDuration = durationMs.coerceAtLeast(1L)
    val playedColor = MaterialTheme.colorScheme.onSurface
    val unplayedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(34.dp)
            // A single gesture loop instead of separate tap + drag detectors - two competing
            // pointerInput gesture recognizers on the same element fight over the touch stream
            // (the drag detector's touch-slop requirement can swallow what the tap detector
            // needed to see), which is exactly what made the fill stop following the finger.
            // Both press and drag map the finger's x position directly onto the timeline (no
            // damped/relative delta) - the bars are a visible ruler of the whole track, so the
            // fill should sit exactly under the finger at all times. A damped delta relative to
            // the actual playback position (as the old plain Slider needed, since it had no
            // visible ruler) made the fill visibly jump to the touch point on press and then
            // snap away from it the instant a drag started, since press and drag used two
            // different baselines.
            .pointerInput(maxDuration) {
                awaitEachGesture {
                    val down = awaitPointerEvent().changes.firstOrNull() ?: return@awaitEachGesture
                    isDragging = true
                    dragPositionMs = ((down.position.x / size.width) * maxDuration)
                        .toLong()
                        .coerceIn(0L, maxDuration)
                    down.consume()

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            onSeekTo(dragPositionMs)
                            isDragging = false
                            break
                        }
                        dragPositionMs = ((change.position.x / size.width) * maxDuration)
                            .toLong()
                            .coerceIn(0L, maxDuration)
                        change.consume()
                    }
                }
            }
    ) {
        val barCount = amplitudes.size.coerceAtLeast(1)
        val gap = 2.dp.toPx()
        val barWidth = ((size.width - gap * (barCount - 1)) / barCount).coerceAtLeast(1f)
        val progress = positionToDisplay.toFloat() / maxDuration.toFloat()
        val playedCount = (barCount * progress).roundToInt()

        amplitudes.forEachIndexed { i, amp ->
            val barHeight = size.height * amp.coerceIn(0.15f, 1f)
            val x = i * (barWidth + gap)
            val color = if (i < playedCount) playedColor else unplayedColor
            drawLine(
                color = color,
                start = Offset(x + barWidth / 2, size.height / 2 - barHeight / 2),
                end = Offset(x + barWidth / 2, size.height / 2 + barHeight / 2),
                strokeWidth = barWidth,
                cap = Stroke.DefaultCap
            )
        }
    }
}
