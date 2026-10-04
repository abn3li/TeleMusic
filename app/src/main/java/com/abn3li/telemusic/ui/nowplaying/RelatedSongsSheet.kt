package com.abn3li.telemusic.ui.nowplaying

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.QueuePlayNext
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import coil.compose.AsyncImage
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.data.local.displayArtwork
import com.abn3li.telemusic.ui.library.CalmSpinner

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun RelatedSongsSheet(
    player: NowPlayingUiState,
    viewModel: NowPlayingViewModel,
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
                                var menuOpen by remember { mutableStateOf(false) }
                                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable {
                                    viewModel.playRelated(related.tracks, index)
                                    onDismiss()
                                }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    AsyncImage(track.thumbnailUrl, null, contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp))
                                            .background(Color.White.copy(alpha = 0.08f)))
                                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                        Text(track.title, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(track.artist, fontSize = 12.sp, lineHeight = 16.sp, color = Color.White.copy(alpha = 0.6f),
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Box {
                                        IconButton(onClick = { menuOpen = true }) {
                                            Icon(Icons.Rounded.MoreHoriz, "Options for ${track.title}",
                                                tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
                                        }
                                        RelatedActionsMenu(expanded = menuOpen, onDismiss = { menuOpen = false }) {
                                            RelatedAction("Play next", Icons.Outlined.QueuePlayNext) {
                                                menuOpen = false
                                                viewModel.queueRelated(track)
                                                Toast.makeText(context, "Playing next", Toast.LENGTH_SHORT).show()
                                            }
                                            HorizontalDivider(Modifier.padding(horizontal = 16.dp),
                                                thickness = 0.5.dp, color = Color.White.copy(alpha = 0.12f))
                                            RelatedAction("Download", Icons.Outlined.Download) {
                                                menuOpen = false
                                                (context.applicationContext as TgMusicApp).downloadGate.run { viewModel.downloadRelated(track) }
                                            }
                                        }
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

@Composable
private fun RelatedActionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    if (!expanded) return
    val margin = with(LocalDensity.current) { 12.dp.roundToPx() }
    val position = remember(margin) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize
            ): IntOffset {
                // Near the bottom, open above the button so both actions stay reachable.
                val x = if (layoutDirection == LayoutDirection.Ltr) {
                    anchorBounds.right - popupContentSize.width
                } else anchorBounds.left
                val y = if (anchorBounds.bottom + popupContentSize.height <= windowSize.height - margin) {
                    anchorBounds.bottom
                } else anchorBounds.top - popupContentSize.height
                return IntOffset(
                    x.coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)),
                    y.coerceIn(margin, (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin))
                )
            }
        }
    }
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)) {
        val shape = RoundedCornerShape(14.dp)
        Column(
            Modifier.width(230.dp)
                .shadow(8.dp, shape)
                .clip(shape)
                // A still surface keeps the menu inexpensive while music is playing.
                .background(Color(0xFF29292B).copy(alpha = 0.98f))
                .border(0.5.dp, Color.White.copy(alpha = 0.1f), shape),
            content = content
        )
    }
}

@Composable
private fun RelatedAction(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White, fontSize = 15.sp, lineHeight = 20.sp,
            modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}
