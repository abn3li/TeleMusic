package com.abn3li.telemusic.ui.nowplaying

import com.abn3li.telemusic.ui.download.youtubeTrackActions
import com.abn3li.telemusic.ui.download.LocalYouTubeMenus
import com.abn3li.telemusic.ui.download.YouTubeContextHost

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Velocity
import coil.compose.AsyncImage
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.data.local.displayArtwork
import com.abn3li.telemusic.ui.library.CalmSpinner

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun RelatedSongsSheet(
    player: NowPlayingUiState,
    viewModel: NowPlayingViewModel,
    onOpenPage: () -> Unit,
    onDismiss: () -> Unit
) {
    val related by viewModel.relatedState.collectAsState()
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(player.song?.youtubeVideoId) { viewModel.fetchRelated() }
    DisposableEffect(viewModel) { onDispose { viewModel.closeRelated() } }

    // Reaching the list's ends must not pull the whole sheet around. Its header still
    // supports swipe-to-close; only unused scroll and fling from the song list stop here.
    val listBoundary = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                Offset(0f, available.y)

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
                Velocity(0f, available.y)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.Transparent,
        contentColor = Color.White,
        // Paint through the gesture area; only the controls need the safe inset below.
        windowInsets = WindowInsets(0),
        dragHandle = null
    ) {
        YouTubeContextHost {
        val menus = LocalYouTubeMenus.current
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.58f)) {
            // A cached, still blur gives the sheet the playing artwork's colour without
            // running another background animation beneath the list.
            BlurredArtwork(player.song?.displayArtwork, Modifier.matchParentSize())
            Box(Modifier.matchParentSize().background(Color(0xFF1C1C1E).copy(alpha = 0.72f)))
            Column(Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 20.dp)) {
                Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(32.dp, 4.dp).clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.3f)))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("You might also like", fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
                        Text("Based on ${player.song?.title.orEmpty()}", fontSize = 12.sp, lineHeight = 16.sp, color = Color.White.copy(alpha = 0.6f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.KeyboardArrowDown, "Close related songs")
                    }
                }
                Spacer(Modifier.height(12.dp))
                val current = related.videoId == player.song?.youtubeVideoId
                if (!current || related.loading) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CalmSpinner() }
                } else if (related.error != null || related.tracks.isEmpty()) {
                    Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(related.error ?: "No recommendations for this song yet.", color = Color.White.copy(alpha = 0.7f))
                        TextButton(onClick = { viewModel.fetchRelated(force = true) }) { Text("Retry", color = Color.White) }
                    }
                } else {
                    CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
                        LazyColumn(Modifier.weight(1f).fillMaxWidth().nestedScroll(listBoundary)) {
                            itemsIndexed(related.tracks, key = { _, track -> track.videoId }) { index, track ->
                                val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
                                val play = { viewModel.playRelated(related.tracks, index); onDismiss() }
                                val download = { (context.applicationContext as TgMusicApp).downloadGate.run { viewModel.downloadRelated(track) } }
                                val beforeOpen = { onDismiss(); onOpenPage() }
                                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).onPlaced { coordinates[0] = it }
                                    .youtubeTrackActions(track, play, download, beforeOpen).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    AsyncImage(track.thumbnailUrl, null, contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp))
                                            .background(Color.White.copy(alpha = 0.08f)))
                                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                        Text(track.title, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(track.artist, fontSize = 12.sp, lineHeight = 16.sp, color = Color.White.copy(alpha = 0.6f),
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    IconButton(onClick = {
                                        val anchor = coordinates[0]?.takeIf { it.isAttached }?.positionInWindow()?.y ?: 0f
                                        menus.track(track, anchor, play, download, beforeOpen)
                                    }) {
                                        Icon(Icons.Rounded.MoreHoriz, "Options for ${track.title}",
                                            tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(player.song?.displayArtwork, null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(player.song?.title.orEmpty(), fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(player.song?.artist.orEmpty(), color = Color.White.copy(alpha = 0.6f),
                            fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = viewModel::togglePlayPause) {
                        Icon(if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            if (player.isPlaying) "Pause" else "Play")
                    }
                    IconButton(onClick = viewModel::nextSong, enabled = player.hasNext) {
                        Icon(Icons.Rounded.SkipNext, "Next song")
                    }
                }
            }
        }
        }
    }
}
