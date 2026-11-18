package com.abn3li.telemusic.ui.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abn3li.telemusic.playback.SignalPath
import java.util.Locale

private val Ok = Color(0xFF30D158)
private val Warn = Color(0xFFFFB800)
private val Neutral = Color.White.copy(alpha = 0.35f)

private enum class Step { OK, WARN, NEUTRAL }

private fun Step.color() = when (this) { Step.OK -> Ok; Step.WARN -> Warn; Step.NEUTRAL -> Neutral }

/**
 * Where the current song's sound goes, top to bottom. Green: passed on unchanged. Amber:
 * changed, or may be. Everything shown is read from playback, except Android's mixer, whose
 * actual rate apps can't see; that step only says it may resample.
 */
@Composable
internal fun SignalPathView(path: SignalPath?, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
            Icon(Icons.Rounded.Close, "Close", tint = Color.White, modifier = Modifier.size(24.dp).clip(CircleShape).clickable(onClick = onClose))
            Spacer(Modifier.width(14.dp))
            Text("Signal Path", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        }
        if (path == null) {
            Text("Play a song to see its path.", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp, modifier = Modifier.padding(vertical = 24.dp))
            return
        }
        val bitPerfectRoute = path.route != SignalPath.Route.MIXER
        val clean = bitPerfectRoute && path.exact != false
        val (badge, badgeStep) = when {
            path.exact == false -> "Not exact · something changed the sound" to Step.WARN
            bitPerfectRoute && path.lossless -> "✓ Bit-perfect" to Step.OK
            bitPerfectRoute -> "Unchanged after decoding · lossy source" to Step.NEUTRAL
            path.mixerRateHz != null -> "Android may resample" to Step.WARN
            !path.lossless -> "Lossy source · normal path" to Step.NEUTRAL
            else -> "Android mixer" to Step.NEUTRAL
        }
        Spacer(Modifier.height(12.dp))
        Text(
            badge, color = badgeStep.color().takeIf { badgeStep != Step.NEUTRAL } ?: Color.White.copy(alpha = 0.75f),
            fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(14.dp))
                .background((if (badgeStep == Step.NEUTRAL) Color.White else badgeStep.color()).copy(alpha = 0.13f))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
        Spacer(Modifier.height(18.dp))

        val routeStep = when {
            path.exact == false -> Step.WARN
            bitPerfectRoute -> Step.OK
            path.mixerRateHz != null -> Step.WARN
            else -> Step.NEUTRAL
        }
        PathNode(Icons.Rounded.MusicNote, "SOURCE", path.source, path.sourceDetail,
            if (path.lossless) Step.OK else Step.NEUTRAL, accent = true)
        PathNode(Icons.Rounded.GraphicEq, "DECODED", path.decoded, null, if (clean) Step.OK else Step.NEUTRAL)
        val (routeIcon, routeDetail) = when (path.route) {
            SignalPath.Route.DIRECT -> Icons.Rounded.Bolt to "TeleMusic drives the DAC · no mixer"
            SignalPath.Route.EXCLUSIVE -> Icons.Rounded.Lock to "Output opened exclusively · no mixer"
            SignalPath.Route.ANDROID_BIT_PERFECT -> Icons.Rounded.Tune to "Android passes it through unchanged"
            SignalPath.Route.MIXER -> Icons.Rounded.Tune to "Shared with other apps"
        }
        val routeNote = when {
            path.exact == false -> "Volume, a narrower output or processing changed samples"
            bitPerfectRoute -> "✓ Unchanged"
            path.mixerRateHz != null -> "May resample to ${String.format(Locale.US, "%.1f", path.mixerRateHz / 1000.0).removeSuffix(".0")} kHz"
            else -> null
        }
        PathNode(routeIcon, "PATH", path.route.label, routeDetail, routeStep, note = routeNote)
        PathNode(Icons.Rounded.Headphones, "OUTPUT", path.output, path.outputDetail, routeStep, last = true)
    }
}

@Composable
private fun PathNode(
    icon: ImageVector, label: String, title: String, detail: String?, step: Step,
    accent: Boolean = false, note: String? = null, last: Boolean = false
) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(34.dp).fillMaxHeight()) {
            Box(
                Modifier.size(34.dp).clip(CircleShape)
                    .background(if (accent) com.abn3li.telemusic.ui.library.AppAccent else Color(0xFF2C2C2E))
                    .border(1.dp, if (accent) Color.Transparent else step.color(), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = if (accent) Color.White else step.color().takeIf { step != Step.NEUTRAL } ?: Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(18.dp))
            }
            if (!last) Box(Modifier.width(2.dp).weight(1f).background(step.color().copy(alpha = if (step == Step.NEUTRAL) 0.5f else 0.8f)))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).padding(bottom = if (last) 0.dp else 18.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (!detail.isNullOrBlank()) Text(detail, color = Color.White.copy(alpha = 0.7f), fontSize = 13.5.sp)
            if (note != null) Text(note, color = if (step == Step.NEUTRAL) Color.White.copy(alpha = 0.6f) else step.color(),
                fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 3.dp))
        }
    }
}
