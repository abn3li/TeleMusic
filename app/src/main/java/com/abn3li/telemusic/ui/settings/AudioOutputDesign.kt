package com.abn3li.telemusic.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.data.settings.BitPerfectMode
import com.abn3li.telemusic.playback.SignalPath
import com.abn3li.telemusic.ui.library.*
import com.abn3li.telemusic.ui.nowplaying.SignalPathView
import com.abn3li.telemusic.ui.theme.ink
import java.util.Locale

private val PathOk = Color(0xFF30D158)
private val PathWarn = Color(0xFFFFB800)

/**
 * The top of Audio Quality: the song now playing as a big bit-depth / rate readout, how it
 * reaches the output, and the way into Signal Path. Read again only when the output status
 * changes (a new song or route); nothing is polled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NowPlayingOutput() {
    val app = LocalContext.current.applicationContext as TgMusicApp
    val status by app.audioOutput.status.collectAsState()
    val path = remember(status) { app.audioOutput.signalPath() }
    var showPath by remember { mutableStateOf(false) }
    val muted = ink.copy(alpha = 0.5f)

    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp)) {
        Text("NOW PLAYING", color = GroupLabelColor, fontSize = 13.sp, letterSpacing = 0.4.sp)
        if (path == null) {
            Text("Play a song to see how it sounds here.", color = muted, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
            return@Column
        }
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp)) {
            BigNumber(path.bits.toString())
            UnitLabel(if (path.bits == 32 && path.route == SignalPath.Route.MIXER) "bit float" else "bit")
            Spacer(Modifier.width(16.dp))
            BigNumber(rateNumber(path.rateHz))
            UnitLabel("kHz")
        }
        val (state, tone) = when {
            path.exact == false -> "Not exact" to PathWarn
            path.route != SignalPath.Route.MIXER && path.lossless -> "Bit-perfect" to PathOk
            path.route != SignalPath.Route.MIXER -> "Unchanged · lossy source" to null
            path.mixerRateHz != null -> "May resample" to PathWarn
            else -> "Android Mixer" to null
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(tone ?: muted))
            Spacer(Modifier.width(7.dp))
            Text(state, color = tone ?: ink.copy(alpha = 0.8f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(" · ${path.output}", color = muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(
            Modifier
                .padding(top = 14.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(GroupCardColor)
                .clickable { showPath = true }
                .padding(horizontal = 15.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Signal Path", color = ink, fontSize = 16.5.sp, modifier = Modifier.weight(1f))
            val dots = tone ?: muted
            repeat(3) { i ->
                if (i > 0) Box(Modifier.width(11.dp).height(1.5.dp).background(dots.copy(alpha = 0.6f)))
                Box(Modifier.size(6.dp).clip(CircleShape).background(dots))
            }
            Spacer(Modifier.width(10.dp))
            ChevronIcon()
        }
    }

    if (showPath) {
        ModalBottomSheet(
            onDismissRequest = { showPath = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Color(0xFF1C1C1E),
            contentColor = Color.White,
            dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.3f)) }
        ) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp).padding(bottom = 22.dp)) {
                SignalPathView(path, onClose = { showPath = false })
            }
        }
    }
}

private fun rateNumber(rate: Int): String =
    if (rate % 1000 == 0) (rate / 1000).toString() else String.format(Locale.US, "%.1f", rate / 1000.0)

@Composable
private fun BigNumber(text: String) {
    Text(text, color = ink, fontSize = 50.sp, lineHeight = 52.sp, fontWeight = FontWeight.ExtraLight, letterSpacing = (-1.5).sp)
}

@Composable
private fun UnitLabel(text: String) {
    Text(text, color = ink.copy(alpha = 0.5f), fontSize = 14.sp, modifier = Modifier.padding(start = 3.dp, bottom = 7.dp))
}

private class ModeLook(val icon: ImageVector, val tile: Color, val title: String, val detail: String, val needs: String?)

private fun BitPerfectMode.look() = when (this) {
    BitPerfectMode.OFF -> ModeLook(Icons.Rounded.Tune, Color(0xFF636366), "Off", "Android's mixer and the app's effects", null)
    BitPerfectMode.USB_ANDROID -> ModeLook(Icons.Rounded.PhoneAndroid, Color(0xFF0A84FF), "USB DAC",
        "Android plays each song at its own rate", "Android 14 and newer")
    BitPerfectMode.USB_DIRECT -> ModeLook(Icons.Rounded.Bolt, PathOk, "Direct Driver",
        "TeleMusic drives the DAC itself", "Any Android · asks for USB access")
    BitPerfectMode.DAP_EXCLUSIVE -> ModeLook(Icons.Rounded.Memory, Color(0xFFBF5AF2), "Exclusive",
        "Music players with their own DAC", "Lossless songs · not every device")
}

/** The mode's tile on the Audio Quality row. */
@Composable
internal fun BitPerfectModeIcon(mode: BitPerfectMode) { mode.look().let { GroupIcon(it.icon, it.tile) } }

internal fun BitPerfectMode.title() = look().title

/** The Bit-Perfect page: every mode, with what it needs. */
@Composable
internal fun BitPerfectModes() {
    val app = LocalContext.current.applicationContext as TgMusicApp
    val output by app.settingsStore.audioOutput.collectAsState()
    val modes = BitPerfectMode.entries.filter { it != BitPerfectMode.USB_ANDROID || android.os.Build.VERSION.SDK_INT >= 34 }
    GroupCard {
        modes.forEachIndexed { index, mode ->
            if (index > 0) GroupDivider(start = 57.dp)
            val look = mode.look()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (mode != output.bitPerfect) {
                            app.settingsStore.updateAudioOutput { it.copy(bitPerfect = mode) }
                            app.audioOutput.onModeChanged()
                        }
                    }
                    .padding(horizontal = 15.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GroupIcon(look.icon, look.tile)
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(look.title, color = ink, fontSize = 16.5.sp, lineHeight = 20.5.sp)
                    Text(look.detail, color = ink.copy(alpha = 0.5f), fontSize = 13.2.sp, lineHeight = 16.2.sp)
                    look.needs?.let { Text(it, color = ink.copy(alpha = 0.32f), fontSize = 12.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 1.dp)) }
                }
                if (mode == output.bitPerfect) {
                    Spacer(Modifier.width(10.dp))
                    Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = AppAccent, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
    GroupFooter("Playback speed and the app's volume are skipped. Use the DAC or the phone's volume keys.")
}
