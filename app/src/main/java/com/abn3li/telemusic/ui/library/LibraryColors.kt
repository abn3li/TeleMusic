package com.abn3li.telemusic.ui.library

import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Composable
import com.abn3li.telemusic.ui.theme.LocalPalette
import com.abn3li.telemusic.ui.theme.paper
import com.abn3li.telemusic.ui.theme.ink
import androidx.compose.ui.graphics.Color

/** The one app-wide accent (Apple Music style pink). */
internal val AppAccent = Color(0xFFE64366)

internal val LibraryFieldColor: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.field
internal val LibraryCardColor: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.field.copy(alpha = 0.98f)
internal val LibraryTileColor = Color(0xFFEFEFF1)
internal val LibraryHairline: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.hairline
