package com.abn3li.telemusic.ui.nowplaying

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// The mini player itself is the dock's (see navigation/ClassicDock.kt).

/** Bottom padding for scrollable lists so content clears the floating MiniPlayer. */
val LocalMiniPlayerInset = compositionLocalOf { 0.dp }

/** "Play next" for a library song id, provided at the nav root; null where there's no player. */
val LocalPlayNext = staticCompositionLocalOf<((Long) -> Unit)?> { null }

internal val MiniPlayerBarHeight: Dp = 56.dp
internal val MiniPlayerSideMargin: Dp = 12.dp
internal val MiniPlayerCorner: Dp = 16.dp
