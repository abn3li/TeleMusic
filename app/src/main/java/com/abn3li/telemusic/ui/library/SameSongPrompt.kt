package com.abn3li.telemusic.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.ui.theme.ink

/** Asks, one at a time, whether the song found on YouTube is the Telegram song. */
@Composable
internal fun SameSongPrompt(app: TgMusicApp) {
    val asks by app.youtubeLibrary.asks.collectAsState()
    val ask = asks.firstOrNull() ?: return
    val account by app.youtubeAccount.state.collectAsState()
    // Asked for an account that has signed out since.
    val stale = account.sessionId != ask.session
    LaunchedEffect(ask, stale) { if (stale) ask.answer(false) }
    if (stale) return
    key(ask) {
        AppAlert(
            title = "Is this the same song?",
            message = "${ask.song.title} · ${ask.song.artist}",
            onDismiss = { ask.answer(false) },
            actions = listOf(AlertAction("No") { ask.answer(false) }, AlertAction("Yes", bold = true) { ask.answer(true) })
        ) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(ink.copy(alpha = 0.06f)).padding(9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                coil.compose.AsyncImage(ask.found.thumbnailUrl, null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(7.dp)).background(ink.copy(alpha = 0.08f)))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(ask.found.title, color = ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val length = "%d:%02d".format(java.util.Locale.US, ask.found.durationSeconds / 60, ask.found.durationSeconds % 60)
                    Text("${ask.found.artist} • $length • YouTube", color = ink.copy(alpha = 0.5f), fontSize = 12.5.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
    }
}
