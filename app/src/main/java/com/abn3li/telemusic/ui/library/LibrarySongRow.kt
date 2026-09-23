package com.abn3li.telemusic.ui.library

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ArrowCircleDown
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MicExternalOn
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.QueuePlayNext
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import coil.compose.AsyncImage
import com.abn3li.telemusic.data.local.PlaylistEntity
import com.abn3li.telemusic.data.local.SongEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Everything a song row's swipe and long-press card can do, bound once per screen. */
@Stable
internal class LibrarySongActions(
    val playlists: List<PlaylistEntity>,
    val downloadingIds: Set<Long>,
    val onPlayNext: (SongEntity) -> Unit,
    val onToggleFavorite: (SongEntity) -> Unit,
    val onDownload: (SongEntity) -> Unit,
    val onRemoveDownload: (SongEntity) -> Unit,
    val onClearSong: (SongEntity) -> Unit,
    val onAddToPlaylist: (Long, SongEntity) -> Unit,
    val onCreatePlaylistAndAdd: (String, SongEntity) -> Unit,
    val onFindArtwork: suspend (SongEntity, String, String) -> Boolean,
    val onGoToArtist: (String) -> Unit,
    val onGoToAlbum: (String) -> Unit
)

internal sealed interface SongRowLeading {
    data object Artwork : SongRowLeading
    data class TrackNumber(val number: Int) : SongRowLeading
}

private val RowMinHeight = 64.dp

/**
 * One song: artwork (or a track number), title, artist. Swipe right to "Play next"; long-press
 * for the song card.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibrarySongRow(
    song: SongEntity,
    actions: LibrarySongActions,
    onClick: () -> Unit,
    leading: SongRowLeading = SongRowLeading.Artwork,
    subtitle: String? = song.artist,
    horizontalPadding: Dp = 22.dp
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var rowWidth by remember { mutableIntStateOf(0) }
    var rowTopInWindow by remember { mutableFloatStateOf(0f) }
    // Plain holder, not state: only read at the moment of a long-press.
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val swipe = remember { Animatable(0f) }
    var menuOpen by remember { mutableStateOf(false) }
    val trigger = rowWidth * 0.2f

    val swipeState = rememberDraggableState { delta ->
        if (rowWidth == 0) return@rememberDraggableState
        val before = swipe.value
        val after = (before + delta).coerceIn(0f, rowWidth.toFloat())
        if ((before >= trigger) != (after >= trigger)) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        scope.launch { swipe.snapTo(after) }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .clipToBounds()
            .onSizeChanged { rowWidth = it.width }
            .onPlaced { coordinates[0] = it }
    ) {
        if (swipe.value > 0f) {
            val progress = if (rowWidth > 0) swipe.value / rowWidth else 0f
            Box(Modifier.matchParentSize()) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(with(LocalDensity.current) { swipe.value.toDp() })
                        .background(AppAccent),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Icon(
                        Icons.Rounded.QueuePlayNext,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier
                            .padding(start = 22.dp)
                            .size(28.dp)
                            .graphicsLayer { scaleX = 0.82f + progress * 0.18f; scaleY = scaleX }
                    )
                }
            }
        }
        Row(
            Modifier
                .graphicsLayer { translationX = swipe.value }
                .background(Color.Black)
                .draggable(
                    state = swipeState,
                    orientation = Orientation.Horizontal,
                    onDragStopped = {
                        if (trigger > 0f && swipe.value >= trigger) {
                            actions.onPlayNext(song)
                            Toast.makeText(context, "Playing next", Toast.LENGTH_SHORT).show()
                        }
                        swipe.animateTo(0f, spring(dampingRatio = 0.72f, stiffness = 420f))
                    }
                )
                .heightIn(min = RowMinHeight)
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        rowTopInWindow = coordinates[0]?.takeIf { it.isAttached }?.positionInWindow()?.y ?: 0f
                        menuOpen = true
                    }
                )
                .padding(horizontal = horizontalPadding, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (leading) {
                SongRowLeading.Artwork -> SongArtwork(song, 52.dp, 3.5.dp)
                is SongRowLeading.TrackNumber -> Box(Modifier.width(24.dp).height(44.dp), contentAlignment = Alignment.Center) {
                    Text("${leading.number}", color = Color.White.copy(alpha = 0.42f), fontSize = 15.sp, maxLines = 1)
                }
            }
            Column(Modifier.weight(1f).padding(start = 16.dp, end = 8.dp)) {
                Text(song.title, color = Color.White, fontSize = 16.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 1.dp))
                if (subtitle != null) {
                    Text(subtitle, color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp, lineHeight = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            when {
                song.telegramMessageId in actions.downloadingIds ->
                    CircularProgressIndicator(color = Color.White.copy(alpha = 0.5f), strokeWidth = 1.5.dp, modifier = Modifier.size(14.dp))
                song.isExplicitDownload ->
                    Icon(Icons.Rounded.ArrowCircleDown, contentDescription = "Downloaded", tint = Color.White.copy(alpha = 0.35f), modifier = Modifier.size(16.dp))
            }
        }
    }

    if (menuOpen) {
        SongContextMenu(song = song, actions = actions, anchorTopPx = rowTopInWindow, onDismiss = { menuOpen = false })
    }
}

@Composable
internal fun SongArtwork(song: SongEntity, size: Dp, corner: Dp) {
    val art = song.thumbnailPath ?: song.albumArtUrl
    Box(
        Modifier.size(size).clip(RoundedCornerShape(corner)).background(Color(0xFF2A2A2E)),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(size * 0.45f))
        if (!art.isNullOrEmpty()) {
            AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

private object WindowOriginPosition : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
        IntOffset.Zero
}

private val MenuEstimatedHeight = 430.dp

/** The long-press card: song header, then actions. Pops in at the pressed row. */
@Composable
private fun SongContextMenu(song: SongEntity, actions: LibrarySongActions, anchorTopPx: Float, onDismiss: () -> Unit) {
    var show by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var showArtworkDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { delay(16); show = true }
    fun close(after: () -> Unit = {}) {
        if (closing) return
        closing = true
        show = false
        scope.launch {
            delay(200)
            onDismiss()
            after()
        }
    }

    Popup(popupPositionProvider = WindowOriginPosition, onDismissRequest = { close() }, properties = PopupProperties(focusable = true)) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.18f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { close() }
        ) {
            val density = LocalDensity.current
            val anchorTop = with(density) { anchorTopPx.toDp() }
            val maxTop = maxHeight - MenuEstimatedHeight
            val top = if (maxTop > 16.dp) anchorTop.coerceIn(16.dp, maxTop) else 16.dp
            val spec = spring<Float>(dampingRatio = 0.72f, stiffness = 360f)
            AnimatedVisibility(
                visible = show,
                enter = fadeIn(spec) + scaleIn(spec, initialScale = 0.82f, transformOrigin = TransformOrigin(0.5f, 0f)),
                exit = fadeOut(spring(dampingRatio = 0.86f, stiffness = 520f)) +
                    scaleOut(spring(dampingRatio = 0.86f, stiffness = 520f), targetScale = 0.88f, transformOrigin = TransformOrigin(0.5f, 0f)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = top)
                    .fillMaxWidth(0.72f)
            ) {
                SongContextCard(
                    song = song,
                    actions = actions,
                    maxHeight = (maxHeight - top - 16.dp).coerceAtLeast(120.dp),
                    onAction = { action -> close(action) },
                    onFindArtwork = { showArtworkDialog = true },
                    onToast = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                )
            }
        }
    }

    if (showArtworkDialog) {
        FindArtworkDialog(
            song = song,
            onFind = { title, artist -> actions.onFindArtwork(song, title, artist) },
            onDismiss = { showArtworkDialog = false; close() }
        )
    }
}

@Composable
private fun SongContextCard(
    song: SongEntity,
    actions: LibrarySongActions,
    maxHeight: Dp,
    onAction: (() -> Unit) -> Unit,
    onFindArtwork: () -> Unit,
    onToast: (String) -> Unit
) {
    var playlistsOpen by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    val isRealLocalImport = song.isLocalImport && song.localFilePath != null
    val hasArtwork = !(song.thumbnailPath ?: song.albumArtUrl).isNullOrEmpty()
    val album = song.album?.takeIf { it.isNotBlank() }

    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .graphicsLayer { this.shape = shape; shadowElevation = 28f; clip = true }
            .background(LibraryCardColor)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
    ) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            SongArtwork(song, 44.dp, 6.dp)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(song.title, color = Color.White, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (album != null) "${song.artist} · $album" else song.artist,
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        CardDivider()
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            CardItem("Play Next", Icons.Rounded.QueuePlayNext) {
                onAction { actions.onPlayNext(song); onToast("Playing next") }
            }
            CardDivider()
            CardItem("Add to Playlist", Icons.AutoMirrored.Rounded.PlaylistAdd, expandable = true, expanded = playlistsOpen) {
                playlistsOpen = !playlistsOpen
            }
            AnimatedVisibility(playlistsOpen) {
                PlaylistPicker(
                    playlists = actions.playlists,
                    onPick = { playlist ->
                        onAction { actions.onAddToPlaylist(playlist.id, song); onToast("Added to ${playlist.name}") }
                    },
                    onCreate = { name ->
                        onAction { actions.onCreatePlaylistAndAdd(name, song); onToast("Added to $name") }
                    }
                )
            }
            CardDivider()
            CardItem(
                if (song.isFavorite) "Remove from Favorites" else "Add to Favorites",
                if (song.isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder
            ) { onAction { actions.onToggleFavorite(song) } }
            if (!isRealLocalImport) {
                CardDivider()
                val downloading = song.telegramMessageId in actions.downloadingIds
                CardItem(
                    when {
                        song.isExplicitDownload -> "Downloaded"
                        downloading -> "Downloading…"
                        else -> "Download"
                    },
                    if (song.isExplicitDownload) Icons.Rounded.CloudDone else Icons.Rounded.Download,
                    enabled = !song.isExplicitDownload && !downloading
                ) { onAction { actions.onDownload(song) } }
            }
            if (!hasArtwork) {
                CardDivider()
                CardItem("Find Artwork…", Icons.Rounded.Image) { onFindArtwork() }
            }
            CardDivider()
            CardItem("Go to Artist", Icons.Rounded.MicExternalOn) { onAction { actions.onGoToArtist(song.artist) } }
            if (album != null) {
                CardDivider()
                CardItem("Go to Album", Icons.Rounded.Album) { onAction { actions.onGoToAlbum(album) } }
            }
            if (song.isExplicitDownload || isRealLocalImport) {
                CardDivider()
                CardItem("Delete Download", Icons.Rounded.Delete, destructive = true) { onAction { actions.onRemoveDownload(song) } }
            }
            CardDivider()
            CardItem("Remove from Library", Icons.Rounded.DeleteForever, destructive = true) { onAction { actions.onClearSong(song) } }
        }
    }
}

@Composable
private fun CardItem(
    label: String,
    icon: ImageVector,
    destructive: Boolean = false,
    enabled: Boolean = true,
    expandable: Boolean = false,
    expanded: Boolean = false,
    onClick: () -> Unit
) {
    val rotation by animateFloatAsState(if (expanded) 90f else 0f, spring(dampingRatio = 0.72f, stiffness = 420f), label = "cardChevron")
    val color = when {
        destructive -> Color(0xFFFF453A)
        !enabled -> Color.White.copy(alpha = 0.4f)
        else -> Color.White
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = 19.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = color, fontSize = 14.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (expandable) {
                Spacer(Modifier.width(5.dp))
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowForwardIos,
                    null,
                    tint = Color.White.copy(alpha = 0.48f),
                    modifier = Modifier.size(11.dp).graphicsLayer { rotationZ = rotation }
                )
            }
        }
        Icon(icon, contentDescription = label, tint = color.copy(alpha = if (destructive) 1f else 0.74f), modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun CardDivider() {
    Spacer(Modifier.fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.08f)))
}

@Composable
private fun PlaylistPicker(playlists: List<PlaylistEntity>, onPick: (PlaylistEntity) -> Unit, onCreate: (String) -> Unit) {
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.04f))) {
        if (creating) {
            Row(
                Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.weight(1f).height(30.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.08f)).padding(horizontal = 8.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (name.isEmpty()) Text("Playlist name", color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp)
                    BasicTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 12.sp),
                        cursorBrush = SolidColor(AppAccent),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "Create",
                    color = if (name.isNotBlank()) AppAccent else Color.White.copy(alpha = 0.3f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(enabled = name.isNotBlank()) { onCreate(name.trim()) }
                )
            }
        } else {
            Row(
                Modifier.fillMaxWidth().height(36.dp).clickable { creating = true }.padding(horizontal = 19.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("New Playlist…", color = AppAccent, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.Add, null, tint = AppAccent, modifier = Modifier.size(16.dp))
            }
        }
        if (playlists.isEmpty() && !creating) {
            Text("No playlists yet", color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 19.dp, vertical = 6.dp))
        }
        playlists.forEach { playlist ->
            Row(
                Modifier.fillMaxWidth().height(36.dp).clickable { onPick(playlist) }.padding(horizontal = 19.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(playlist.name, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** For a song with no cover: correct its title/artist and look the artwork up again. */
@Composable
private fun FindArtworkDialog(song: SongEntity, onFind: suspend (String, String) -> Boolean, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf(song.title) }
    var artist by remember { mutableStateOf(song.artist) }
    var searching by remember { mutableStateOf(false) }
    var notFound by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AppAlert(
        title = "Find Artwork",
        message = "Correct the title and artist, then search again.",
        onDismiss = { if (!searching) onDismiss() },
        actions = listOf(
            AlertAction("Cancel", enabled = !searching, onClick = onDismiss),
            AlertAction("Search", bold = true, enabled = title.isNotBlank(), loading = searching) {
                searching = true
                scope.launch {
                    val found = onFind(title.trim(), artist.trim())
                    searching = false
                    if (found) onDismiss() else notFound = true
                }
            }
        )
    ) {
        AlertTextField(title, { title = it; notFound = false }, "Title")
        Spacer(Modifier.height(8.dp))
        AlertTextField(artist, { artist = it; notFound = false }, "Artist")
        if (notFound) AlertNote("No artwork found for that title and artist.", color = DestructiveRed)
    }
}
