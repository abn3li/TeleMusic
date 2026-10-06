package com.abn3li.telemusic.ui.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.data.local.SongEntity
import com.abn3li.telemusic.data.quality.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private val QualityGreen = Color(0xFF5BDA8A)

@Composable
private fun qualityStatus(song: SongEntity, active: Boolean): FlacUpgradeStatus {
    val store = (LocalContext.current.applicationContext as TgMusicApp).flacUpgradeStore
    // The full player stays composed behind the mini player. Collect only while it is open.
    val status by if (active) store.upgradeStatus.collectAsState()
        else remember(store, song.telegramMessageId) { mutableStateOf(store.upgradeStatus.value) }
    val preferences by if (active) store.preferences.collectAsState()
        else remember(store, song.telegramMessageId) { mutableStateOf(store.preferences.value) }
    return flacStatusForSong(status, song.telegramMessageId.toString(), preferences.enabled,
        preferences.hasAccount, song.sourceMime?.contains("flac", ignoreCase = true) == true)
}

@Composable
internal fun QualityUpgradeButton(song: SongEntity, active: Boolean, onClick: () -> Unit) {
    val status = qualityStatus(song, active)
    val transfer = status.transfer
    val progress by if (active && status.stage == FlacUpgradeStage.BUFFERING && transfer != null)
        transfer.progress.collectAsState()
    else remember(transfer) { mutableStateOf(transfer?.progress?.value ?: FlacTransferProgress()) }
    // The highest percentage shown for this song, held through retries (see flacSteadyLabel).
    val held = remember(song.telegramMessageId) { IntArray(1) { -1 } }
    val (label, nextHeld) = flacSteadyLabel(status.stage, progress.downloadedBytes, transfer?.sizeBytes ?: 0, held[0])
    held[0] = nextHeld
    // Only an upgrade under way (or done) opens the sheet - "Original" is just a label.
    val tappable = label != "Original"
    Row(Modifier.height(32.dp).widthIn(max = 200.dp).clip(RoundedCornerShape(10.dp))
        .then(if (tappable) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
        .semantics(mergeDescendants = true) {
            contentDescription = if (tappable) "$label. Open quality upgrade status" else label
        }
        .padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.GraphicEq, null, tint = Color.White.copy(alpha = 0.75f), modifier = Modifier.size(14.dp))
        Text(label, color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
        if (tappable) Icon(Icons.Rounded.ChevronRight, null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(11.dp))
    }
}

/** Live transfer details belong to this song, without sending the listener away from playback. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QualityUpgradeSheet(song: SongEntity, onDismiss: () -> Unit) {
    val status = qualityStatus(song, active = true)
    val transfer = status.transfer
    val context = LocalContext.current
    val localQuality by produceState<Pair<String, String>?>(null, song.telegramMessageId, song.localFilePath, status.stage) {
        value = if (status.stage == FlacUpgradeStage.LOSSLESS && transfer == null && song.localFilePath != null)
            withContext(Dispatchers.IO) { detectAudioFormat(context, song.localFilePath, song.durationSeconds) }
        else null
    }
    val upgraded = status.stage == FlacUpgradeStage.LOSSLESS
    val title = when (status.stage) {
        FlacUpgradeStage.OFF -> "Automatic upgrade is off"
        FlacUpgradeStage.READY -> "Ready to upgrade"
        FlacUpgradeStage.SEARCHING -> "Finding a lossless match"
        FlacUpgradeStage.REQUESTING -> "Connecting to an uploader"
        FlacUpgradeStage.BUFFERING -> "Buffering lossless audio"
        FlacUpgradeStage.PREPARING -> "Switching to lossless audio"
        FlacUpgradeStage.LOSSLESS -> "Playing lossless audio"
        FlacUpgradeStage.UNAVAILABLE -> "Upgrade unavailable"
    }
    val originalSource = when {
        song.isExplicitDownload -> "Downloaded"
        song.isLocalImport && song.localFilePath != null -> "Imported from device"
        song.localFilePath != null -> "Cached audio"
        song.youtubeVideoId != null || song.isLocalImport -> "Original · YouTube"
        else -> "Original · Telegram"
    }

    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        containerColor = Color(0xFF1C1C1E), contentColor = Color.White,
        windowInsets = WindowInsets(0), dragHandle = null) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 22.dp).padding(bottom = 22.dp)) {
            Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(32.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.2f)))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Quality upgrade", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Box(Modifier.size(30.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Close, "Close quality status", tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(17.dp))
                    }
                }
            }
            Row(Modifier.padding(top = 12.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(QualityGreen.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.GraphicEq, null, tint = QualityGreen, modifier = Modifier.size(21.dp))
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (upgraded) {
                        if (transfer != null) "Quality upgraded successfully." else "Already playing FLAC."
                    } else "Original audio remains selected.",
                        fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(top = 4.dp))
                }
            }
            if (transfer != null) key(transfer.progress) { QualityTransferProgress(transfer) }
            QualityDetailRow("Playing", if (upgraded) {
                if (transfer != null) "FLAC · Soulseek" else "FLAC · $originalSource"
            } else originalSource)
            val format = if (transfer != null && transfer.sampleRate > 0) {
                "FLAC · ${transfer.bitDepth}-bit / ${qualitySampleRate(transfer.sampleRate)} kHz"
            } else if (upgraded) localQuality?.second?.takeIf { it.isNotBlank() } ?: "FLAC · Lossless"
            else "FLAC only"
            QualityDetailRow(if (upgraded) "Quality" else "Upgrade", format)
            if (transfer != null) key(transfer.progress) { QualityTransferSpeed(transfer) }
            Text(status.detail, fontSize = 12.sp, lineHeight = 17.sp, color = Color.White.copy(alpha = 0.6f),
                maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 14.dp))
        }
    }
}

@Composable
private fun QualityTransferProgress(transfer: FlacTransferInfo) {
    val progress by transfer.progress.collectAsState()
    val fraction = (progress.downloadedBytes.toFloat() / transfer.sizeBytes.coerceAtLeast(1)).coerceIn(0f, 1f)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(if (progress.downloadedBytes >= transfer.sizeBytes) "Buffered" else "Downloaded",
            fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f))
        Text("${qualitySize(progress.downloadedBytes)} / ${qualitySize(transfer.sizeBytes)} MiB", fontSize = 12.sp)
    }
    Box(Modifier.padding(top = 9.dp, bottom = 18.dp).fillMaxWidth().height(4.dp)
        .clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.12f))
        .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) }) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(QualityGreen))
    }
}

@Composable
private fun QualityTransferSpeed(transfer: FlacTransferInfo) {
    val progress by transfer.progress.collectAsState()
    // This is a transfer average, updated by incoming bytes, with no UI timer while stalled or idle.
    QualityDetailRow("Average speed", if (progress.bytesPerSecond > 0) "${qualitySize(progress.bytesPerSecond)} MiB/s"
        else if (progress.downloadedBytes >= transfer.sizeBytes) "Complete" else "Measuring…")
}

@Composable
private fun QualityDetailRow(label: String, value: String) {
    HorizontalDivider(thickness = 0.5.dp, color = Color.White.copy(alpha = 0.12f))
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.padding(end = 12.dp))
        Text(value, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.weight(1f))
    }
}

private fun qualitySize(bytes: Long) = String.format(Locale.US, "%.1f", bytes / (1024.0 * 1024.0))
private fun qualitySampleRate(rate: Int) = String.format(Locale.US, if (rate % 1000 == 0) "%.0f" else "%.1f", rate / 1000.0)
