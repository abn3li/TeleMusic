package com.abn3li.telemusic.ui.nowplaying

import com.abn3li.telemusic.ui.library.CalmSpinner
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.abn3li.telemusic.data.local.SongEntity
import com.abn3li.telemusic.data.local.displayArtwork
import kotlinx.coroutines.launch

private val SheetColor = Color(0xFF1C1C1E)

/**
 * The Now Playing "..." menu. [song] is a snapshot taken when the sheet opened, so the header
 * doesn't change under the user if the track advances while it's open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NowPlayingOverflowSheet(
    song: SongEntity,
    isDownloading: Boolean,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onSongInfo: () -> Unit,
    onSearchLyrics: () -> Unit,
    onLyricsSource: () -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String) -> Unit
) {
    val app = LocalContext.current.applicationContext as com.abn3li.telemusic.TgMusicApp
    val youtubeLike = com.abn3li.telemusic.ui.library.rememberYouTubeLikeAction(song.youtubeVideoId)
    val flac by app.flacUpgradeStore.activeFlac.collectAsState()
    val hasActiveFlac = flac?.songId == song.telegramMessageId.toString()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    // The choice happens at once and the menu slides away meanwhile - waiting for it to finish
    // closing first made every choice feel slow.
    fun closeThen(action: () -> Unit) {
        action()
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }
    // The sleep timer's choices are a second page of this menu, slid in - not a menu of their own
    // opened after this one closed.
    var timerPage by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // Down under the navigation bar (its rows stay above it); the dim is the player's own -
        // see NowPlayingScreen - so it reaches the status bar too.
        windowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        scrimColor = Color.Transparent,
        containerColor = SheetColor,
        contentColor = Color.White,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.3f)) }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp)
                .padding(bottom = 18.dp)
        ) {
            AnimatedContent(
                targetState = timerPage,
                transitionSpec = {
                    val forward = targetState
                    (slideInHorizontally(tween(260)) { if (forward) it else -it / 3 } + fadeIn(tween(200)))
                        .togetherWith(slideOutHorizontally(tween(260)) { if (forward) -it / 3 else it } + fadeOut(tween(160)))
                        .using(SizeTransform(clip = false))
                },
                label = "menuPage"
            ) { onTimer ->
            if (onTimer) SleepTimerOptions(onPicked = { closeThen {} }, onBack = { timerPage = false })
            else Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(alpha = 0.35f), modifier = Modifier.size(28.dp))
                    if (!song.displayArtwork.isNullOrEmpty()) {
                        AsyncImage(
                            model = song.displayArtwork,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        song.title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        song.artist,
                        fontSize = 13.5.sp,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(top = 3.dp)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                closeThen { onOpenArtist(song.artist) }
                            }
                    )
                    val album = song.album?.takeIf { it.isNotBlank() }
                    if (album != null) {
                        Text(
                            album,
                            fontSize = 12.5.sp,
                            color = Color.White.copy(alpha = 0.5f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                    closeThen { onOpenAlbum(album) }
                                }
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.07f))
            ) {
                if (youtubeLike != null) {
                    SheetAction(icon = androidx.compose.material.icons.Icons.Rounded.ThumbUp,
                        label = youtubeLike.label,
                        onClick = { closeThen(youtubeLike.perform) })
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(start = 52.dp))
                }
                // A real local import is already on the device; a YouTube "Play" stream (local
                // import flag but no file yet) can still be downloaded for real.
                val isRealLocalImport = song.isLocalImport && song.localFilePath != null && !song.isExplicitDownload && !hasActiveFlac
                if (!isRealLocalImport) {
                    val downloaded = song.isExplicitDownload && (!hasActiveFlac || song.sourceMime == "audio/flac")
                    SheetAction(
                        icon = if (downloaded) Icons.Rounded.CloudDone else Icons.Rounded.CloudDownload,
                        label = when {
                            downloaded -> "Downloaded"
                            isDownloading -> "Downloading…"
                            hasActiveFlac -> "Download FLAC"
                            else -> "Download"
                        },
                        enabled = !downloaded && !isDownloading,
                        busy = isDownloading,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            closeThen(onDownload)
                        }
                    )
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(start = 52.dp))
                }
                SheetAction(icon = Icons.Rounded.Info, label = "Song info", onClick = { closeThen(onSongInfo) })
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(start = 52.dp))
                SheetAction(icon = Icons.Rounded.Lyrics, label = "Search lyrics", onClick = { closeThen(onSearchLyrics) })
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(start = 52.dp))
                SheetAction(icon = Icons.Rounded.SwapHoriz, label = "Change lyrics source", onClick = { closeThen(onLyricsSource) })
            }
            Spacer(Modifier.height(12.dp))
            val timerState by app.sleepTimer.state.collectAsState()
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.07f))
            ) {
                SheetAction(
                    icon = Icons.Rounded.Bedtime,
                    label = "Sleep Timer",
                    trailing = when (timerState) {
                        is com.abn3li.telemusic.playback.SleepTimer.State.At -> "${app.sleepTimer.minutesLeft()} min"
                        com.abn3li.telemusic.playback.SleepTimer.State.EndOfSong -> "End of song"
                        else -> null
                    },
                    onClick = { timerPage = true }
                )
            }
            }
            }
        }
    }
}

@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    busy: Boolean = false,
    trailing: String? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp)
            .alpha(if (enabled || busy) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            if (busy) {
                CalmSpinner(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            } else {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Text(label, fontSize = 16.sp, color = Color.White, modifier = Modifier.weight(1f))
        trailing?.let { Text(it, fontSize = 15.sp, color = Color.White.copy(alpha = 0.55f)) }
    }
}
