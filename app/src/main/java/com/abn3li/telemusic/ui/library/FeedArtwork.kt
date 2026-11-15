package com.abn3li.telemusic.ui.library

import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.abn3li.telemusic.ui.theme.LocalPalette

/** Covers stand on their own rounded corners. Only on the light page, a hairline keeps a white
 * cover from melting into the white background. */
@Composable
internal fun Modifier.feedArtworkBorder(shape: Shape): Modifier {
    val palette = LocalPalette.current
    return if (palette.isLight) border(0.5.dp, palette.ink.copy(alpha = 0.08f), shape) else this
}
