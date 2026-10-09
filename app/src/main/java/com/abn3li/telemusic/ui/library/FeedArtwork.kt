package com.abn3li.telemusic.ui.library

import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.abn3li.telemusic.ui.theme.LocalPalette

/** A faint edge keeps dark covers distinct from the feed's background. */
@Composable
internal fun Modifier.feedArtworkBorder(shape: Shape): Modifier {
    val palette = LocalPalette.current
    return border(1.dp, palette.ink.copy(alpha = if (palette.isLight) 0.12f else 0.16f), shape)
}
