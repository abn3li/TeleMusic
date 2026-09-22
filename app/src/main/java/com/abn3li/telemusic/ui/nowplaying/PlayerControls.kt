package com.abn3li.telemusic.ui.nowplaying

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRouter2
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Airplay
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.abn3li.telemusic.data.local.SongEntity
import kotlin.math.roundToInt

enum class PlayerPage { ARTWORK, LYRICS, QUEUE }

/**
 * Thin, thumbless track that thickens while touched. [value] and the callbacks are fractions in
 * 0..1. A touch jumps to the finger's position and follows it; [onValueChangeFinished] fires
 * once on release.
 */
@Composable
internal fun FlatSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    onInteraction: () -> Unit = {}
) {
    var pressed by remember { mutableStateOf(false) }
    val trackHeight by animateDpAsState(
        targetValue = if (pressed) 11.dp else 7.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "flatSliderHeight"
    )
    val latestOnChange by rememberUpdatedState(onValueChange)
    val latestOnFinished by rememberUpdatedState(onValueChangeFinished)
    val latestOnInteraction by rememberUpdatedState(onInteraction)
    val haptics = LocalHapticFeedback.current

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(26.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    pressed = true
                    latestOnInteraction()
                    latestOnChange((down.position.x / size.width).coerceIn(0f, 1f))
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if (change.positionChange() != Offset.Zero) {
                            change.consume()
                            latestOnChange((change.position.x / size.width).coerceIn(0f, 1f))
                        }
                    }
                    pressed = false
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    latestOnFinished()
                }
            }
    ) {
        val stroke = trackHeight.toPx()
        val y = size.height / 2f
        val start = stroke / 2f
        val end = size.width - stroke / 2f
        drawLine(Color.White.copy(alpha = 0.22f), Offset(start, y), Offset(end, y), stroke, StrokeCap.Round)
        val fillEnd = start + (end - start) * value.coerceIn(0f, 1f)
        drawLine(
            Color.White.copy(alpha = if (pressed) 0.95f else 0.72f),
            Offset(start, y), Offset(fillEnd, y), stroke, StrokeCap.Round
        )
    }
}

/**
 * Scrubber + elapsed/remaining times + the codec badge. Collects playbackProgress itself so only
 * this block recomposes on the 300ms position tick, not the whole player.
 */
@Composable
internal fun PlayerScrubber(
    viewModel: NowPlayingViewModel,
    song: SongEntity?,
    active: Boolean,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier
) {
    // The player stays composed while collapsed; following the 300ms position tick there would
    // redraw an off-screen scrubber for nothing.
    val progress by if (active) {
        viewModel.playbackProgress.collectAsState()
    } else {
        remember { mutableStateOf(viewModel.playbackProgress.value) }
    }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val durationMs = progress.durationMs.coerceAtLeast(1L)
    val fraction = if (dragging) dragFraction else progress.currentPositionMs.toFloat() / durationMs
    val shownPositionMs = (fraction * durationMs).toLong()

    Column(modifier.fillMaxWidth()) {
        FlatSlider(
            value = fraction,
            onValueChange = { dragging = true; dragFraction = it },
            onValueChangeFinished = {
                viewModel.seekTo((dragFraction * durationMs).toLong())
                dragging = false
            },
            onInteraction = onInteraction
        )
        val context = LocalContext.current
        val badgeText = remember(song?.telegramMessageId, song?.localFilePath) {
            if (song == null) "" else {
                val (format, bitrate) = detectAudioFormat(context, song.localFilePath, song.durationSeconds)
                val shortFormat = format.substringBefore(" ").substringBefore("(").trim()
                if (bitrate.isBlank()) shortFormat else "$shortFormat · $bitrate"
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatMs(shownPositionMs),
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.3.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                badgeText,
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1.4f)
            )
            Text(
                "-${formatMs((durationMs - shownPositionMs).coerceAtLeast(0))}",
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.3.sp,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
internal fun TransportRow(
    state: NowPlayingUiState,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TransportButton(Icons.Rounded.FastRewind, "Previous", 44.dp, state.hasPrevious, onPrevious)
        Spacer(Modifier.width(34.dp))
        val loading = state.isBuffering || state.loadingSongId != null
        TransportButton(
            icon = if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = if (state.isPlaying) "Pause" else "Play",
            iconSize = 54.dp,
            enabled = true,
            onClick = onPlayPause,
            animateSwap = true,
            loading = loading
        )
        Spacer(Modifier.width(34.dp))
        TransportButton(Icons.Rounded.FastForward, "Next", 44.dp, state.hasNext, onNext)
    }
}

@Composable
private fun TransportButton(
    icon: ImageVector,
    contentDescription: String,
    iconSize: Dp,
    enabled: Boolean,
    onClick: () -> Unit,
    animateSwap: Boolean = false,
    loading: Boolean = false
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptics = LocalHapticFeedback.current
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.82f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "transportScale"
    )
    val pressGlow by animateFloatAsState(if (pressed) 0.14f else 0f, label = "transportGlow")
    val alpha by animateFloatAsState(if (enabled) 1f else 0.3f, label = "transportAlpha")

    Box(
        modifier = Modifier
            .size(iconSize + 22.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) { drawCircle(Color.White.copy(alpha = pressGlow)) }
        if (animateSwap) {
            AnimatedContent(
                targetState = icon,
                transitionSpec = {
                    (scaleIn(initialScale = 0.3f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.3f) + fadeOut())
                },
                label = "playPauseSwap"
            ) { glyph ->
                Icon(glyph, contentDescription, tint = Color.White, modifier = Modifier.size(iconSize))
            }
        } else {
            Icon(icon, contentDescription, tint = Color.White, modifier = Modifier.size(iconSize).alpha(alpha))
        }
        if (loading) {
            CircularProgressIndicator(
                color = Color.White.copy(alpha = 0.55f),
                strokeWidth = 2.dp,
                modifier = Modifier.size(iconSize + 16.dp)
            )
        }
    }
}

/** Device music volume, kept in sync with the hardware volume keys. */
@Composable
internal fun VolumeRow(onInteraction: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volume by remember {
        mutableFloatStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) / maxVolume.toFloat())
    }
    var dragging by remember { mutableStateOf(false) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (!dragging && intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) == AudioManager.STREAM_MUSIC) {
                    volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) / maxVolume.toFloat()
                }
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION"), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Rounded.VolumeDown, null, tint = Color.White.copy(alpha = 0.45f), modifier = Modifier.size(20.dp))
        FlatSlider(
            value = volume,
            onValueChange = { fraction ->
                dragging = true
                volume = fraction
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (fraction * maxVolume).roundToInt(), 0)
            },
            onValueChangeFinished = { dragging = false },
            onInteraction = onInteraction,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
        )
        Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, tint = Color.White.copy(alpha = 0.45f), modifier = Modifier.size(20.dp))
    }
}

/** Lyrics toggle, audio output picker, queue toggle. */
@Composable
internal fun PlayerBottomRow(
    page: PlayerPage,
    onLyrics: () -> Unit,
    onQueue: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val outputName = rememberExternalOutputName()
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BottomRowButton(
            icon = if (page == PlayerPage.LYRICS) Icons.Rounded.ChatBubble else Icons.Outlined.ChatBubbleOutline,
            contentDescription = "Lyrics",
            active = page == PlayerPage.LYRICS,
            onClick = onLyrics,
            modifier = Modifier.weight(1f)
        )
        Column(
            Modifier.weight(1f).clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { openSystemOutputSwitcher(context) },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                if (outputName != null) Icons.Rounded.Headphones else Icons.Rounded.Airplay,
                contentDescription = "Audio output",
                tint = Color.White.copy(alpha = if (outputName != null) 0.9f else 0.5f),
                modifier = Modifier.size(26.dp)
            )
            if (outputName != null) {
                Text(
                    outputName,
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        BottomRowButton(
            icon = Icons.AutoMirrored.Rounded.QueueMusic,
            contentDescription = "Queue",
            active = page == PlayerPage.QUEUE,
            onClick = onQueue,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun BottomRowButton(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction)
    val haptics = LocalHapticFeedback.current
    val pillAlpha by animateFloatAsState(if (active) 0.9f else 0f, label = "bottomRowPill")
    val tintAlpha by animateFloatAsState(if (active) 1f else 0.5f, label = "bottomRowTint")
    Box(modifier.height(40.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(38.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clickable(interactionSource = interaction, indication = null) {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawRoundRect(
                    Color.White.copy(alpha = pillAlpha),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx())
                )
            }
            Icon(
                icon,
                contentDescription,
                tint = if (active) Color.Black else Color.White.copy(alpha = tintAlpha),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/** Name of a connected headphone/Bluetooth/USB output, or null when playing through the phone. */
@Composable
internal fun rememberExternalOutputName(): String? {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var name by remember { mutableStateOf(currentExternalOutputName(audioManager)) }
    DisposableEffect(audioManager) {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                name = currentExternalOutputName(audioManager)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                name = currentExternalOutputName(audioManager)
            }
        }
        audioManager.registerAudioDeviceCallback(callback, null)
        onDispose { audioManager.unregisterAudioDeviceCallback(callback) }
    }
    return name
}

private val externalOutputTypes = setOf(
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER
)

private fun currentExternalOutputName(audioManager: AudioManager): String? =
    audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .firstOrNull { it.type in externalOutputTypes }
        ?.let { device -> device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Headphones" }

/** Opens the system "play media to" output picker, falling back to the volume panel. */
internal fun openSystemOutputSwitcher(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        val shown = runCatching { MediaRouter2.getInstance(context).showSystemOutputSwitcher() }.getOrDefault(false)
        if (shown) return
    }
    val opened = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && runCatching {
        context.startActivity(Intent(Settings.Panel.ACTION_VOLUME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
    if (!opened) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
