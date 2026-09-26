package com.abn3li.telemusic.ui.library

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app's loading spinner: Material's own, animating at the full display rate. (A stepped
 * low-frame-rate version was tried to save CPU and looked choppy.) One place to change it.
 */
@Composable
internal fun CalmSpinner(
    modifier: Modifier = Modifier,
    color: Color = AppAccent,
    strokeWidth: Dp = 4.dp
) {
    CircularProgressIndicator(modifier = modifier, color = color, strokeWidth = strokeWidth)
}
