package com.abn3li.telemusic.ui.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.playback.SleepTimer
import com.abn3li.telemusic.ui.library.AppAccent

/** The sleep timer's page in the player's menu: its choices, the current one ticked, and Turn
 * Off while set. [onPicked] closes the menu; [onBack] returns to the menu's first page. */
@Composable
internal fun SleepTimerOptions(onPicked: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val timer = (context.applicationContext as TgMusicApp).sleepTimer
    val state by timer.state.collectAsState()
    fun pick(action: () -> Unit, message: String) {
        action()
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        onPicked()
    }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBackIos, contentDescription = "Back", tint = Color.White,
                modifier = Modifier.size(36.dp).clip(CircleShape)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onBack)
                    .padding(start = 6.dp, top = 8.dp, bottom = 8.dp, end = 10.dp)
            )
            Spacer(Modifier.width(4.dp))
            Column {
                Text("Sleep Timer", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    when (state) {
                        is SleepTimer.State.At -> "Stops in ${timer.minutesLeft()} min."
                        SleepTimer.State.EndOfSong -> "Stops when this song ends."
                        SleepTimer.State.Off -> "The music fades out and stops."
                    },
                    fontSize = 13.5.sp, color = Color.White.copy(alpha = 0.6f)
                )
            }
        }
        Spacer(Modifier.size(16.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.07f))) {
            for (minutes in SleepTimer.CHOICES) {
                val label = if (minutes == 60) "1 hour" else "$minutes minutes"
                TimerChoice(label, selected = false) { pick({ timer.start(minutes) }, "Music stops in $label") }
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(start = 16.dp))
            }
            TimerChoice("End of Song", selected = state == SleepTimer.State.EndOfSong) {
                pick({ timer.stopAtEndOfSong() }, "Music stops when this song ends")
            }
        }
        if (state != SleepTimer.State.Off) {
            Spacer(Modifier.size(12.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.07f))) {
                Row(Modifier.fillMaxWidth().clickable { pick({ timer.cancel() }, "Sleep timer off") }.padding(16.dp)) {
                    Text("Turn Off Timer", fontSize = 16.sp, color = Color(0xFFFF453A))
                }
            }
        }
    }
}

@Composable
private fun TimerChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 16.sp, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Rounded.Check, contentDescription = "On", tint = AppAccent, modifier = Modifier.size(20.dp))
    }
}
