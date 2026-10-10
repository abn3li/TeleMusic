package com.abn3li.telemusic.ui.nowplaying

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.StateFlow

/** Holds the last drawn value while covered, then catches up when visible again. */
@Composable
internal fun <T> StateFlow<T>.collectAsStateWhileActive(active: Boolean): State<T> {
    val shown = remember(this) { mutableStateOf(value) }
    // Cancel just this visual subscription; the playback clock and transfers keep running.
    LaunchedEffect(this, active) {
        if (active) collect { shown.value = it }
    }
    return shown
}
