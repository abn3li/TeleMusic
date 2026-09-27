package com.abn3li.telemusic.ui.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abn3li.telemusic.repository.LyricsProvider
import com.abn3li.telemusic.ui.library.AlertAction
import com.abn3li.telemusic.ui.library.AppAccent
import com.abn3li.telemusic.ui.library.AppAlert
import com.abn3li.telemusic.ui.library.CalmSpinner

/**
 * Picks where the current song's lyrics come from, after the automatic search chose one: each
 * source is asked only when tapped, and what it finds replaces the lyrics on screen (and is saved).
 */
@Composable
internal fun LyricsSourceDialog(viewModel: NowPlayingViewModel, onDismiss: () -> Unit) {
    val source by viewModel.lyricsSource.collectAsState()
    LaunchedEffect(Unit) { viewModel.loadLyricsSource() }

    AppAlert(
        title = "Lyrics Source",
        message = "Pick another source if these lyrics are wrong or out of sync.",
        onDismiss = onDismiss,
        actions = listOf(AlertAction("Done", bold = true, onClick = onDismiss))
    ) {
        Column(Modifier.fillMaxWidth()) {
            LyricsProvider.entries.forEachIndexed { index, provider ->
                if (index > 0) Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.12f)))
                val busy = source.loading != null
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !busy) { viewModel.changeLyricsSource(provider) }
                        .alpha(if (busy && source.loading != provider) 0.5f else 1f)
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(provider.label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text(provider.detail, color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp)
                    }
                    when {
                        source.loading == provider -> CalmSpinner(color = AppAccent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        source.current == provider -> Icon(Icons.Rounded.Check, contentDescription = "Current source", tint = AppAccent, modifier = Modifier.size(20.dp))
                    }
                }
            }
            source.message?.let {
                Text(it, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}
