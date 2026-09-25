package com.abn3li.telemusic.ui.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// ~30 steps a second, 12° each: one smooth-looking turn per second.
private const val STEP_MS = 33L
private const val STEPS_PER_TURN = 30

/**
 * The loading spinner used everywhere instead of Material's CircularProgressIndicator. That one
 * animates every frame - on a 120 Hz screen the whole window is redrawn 120 times a second, about
 * 40% CPU, for as long as it shows (a stuck "Connecting…" showed it indefinitely). This one turns
 * at ~30 frames a second - still smooth to the eye, a quarter of the frames (10 a second looked
 * choppy) - and only while it's actually drawn: with the app in
 * the background there are no frames, so it doesn't tick at all. Same parameters, drop-in.
 */
@Composable
internal fun CalmSpinner(
    modifier: Modifier = Modifier,
    color: Color = AppAccent,
    strokeWidth: Dp = 4.dp
) {
    var step by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(STEP_MS)
            withFrameMillis { } // waits for the next frame - never ticks while not visible
            step = (step + 1) % STEPS_PER_TURN
        }
    }
    Canvas(modifier.then(Modifier.size(40.dp))) {
        val stroke = strokeWidth.toPx()
        rotate(step * (360f / STEPS_PER_TURN)) {
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
    }
}
