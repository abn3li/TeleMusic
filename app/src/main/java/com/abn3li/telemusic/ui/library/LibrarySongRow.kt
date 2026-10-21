package com.abn3li.telemusic.ui.library

import com.abn3li.telemusic.ui.theme.LocalPalette
import com.abn3li.telemusic.ui.theme.paper
import com.abn3li.telemusic.ui.theme.ink
import com.abn3li.telemusic.ui.theme.MENU_DIM
import com.abn3li.telemusic.ui.theme.SystemBarsState
import com.abn3li.telemusic.data.local.listArtwork
import com.abn3li.telemusic.data.local.displayArtwork
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ArrowCircleDown
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MicExternalOn
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.QueuePlayNext
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.border
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
    val onCreatePlaylistAndAddSongs: (String, List<SongEntity>) -> Unit,
    val onFindArtwork: suspend (SongEntity, String, String) -> Boolean,
    val onGoToArtist: (String) -> Unit,
    val onGoToAlbum: (String) -> Unit,
    val onDownloadSongs: (List<SongEntity>) -> Unit = { songs -> songs.forEach(onDownload) }
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
    val editing = LocalLibraryEditing.current
    if (editing != null) {
        // Keep artwork and gesture state at one composition location across mode changes.
        EditingSongRow(song, editing, horizontalPadding) {
            LibrarySongRowContent(song, actions,
                onClick = { if (editing.selecting) editing.toggle(song.telegramMessageId) else if (!editing.reordering) onClick() },
                leading = leading, subtitle = subtitle, horizontalPadding = 0.dp,
                editing = editing.active)
        }
    } else LibrarySongRowContent(song, actions, onClick, leading, subtitle, horizontalPadding, editing = false)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibrarySongRowContent(song: SongEntity, actions: LibrarySongActions, onClick: () -> Unit,
    leading: SongRowLeading, subtitle: String?, horizontalPadding: Dp, editing: Boolean) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var rowTopInWindow by remember { mutableFloatStateOf(0f) }
    // Plain holder, not state: only read at the moment of a long-press.
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    var menuOpen by remember { mutableStateOf(false) }
    val albumTrack = leading is SongRowLeading.TrackNumber
    val playback = LocalCollectionPlayback.current
    val current = playback.isCurrent(song.telegramMessageId, song.youtubeVideoId)
    fun openMenu() {
        rowTopInWindow = coordinates[0]?.takeIf { it.isAttached }?.positionInWindow()?.y ?: 0f
        menuOpen = true
    }

    SwipeToPlayNext(
        enabled = !editing,
        onPlayNext = {
            actions.onPlayNext(song)
            Toast.makeText(context, "Playing next", Toast.LENGTH_SHORT).show()
        },
        modifier = Modifier.onPlaced { coordinates[0] = it }
    ) { swipeModifier ->
        Row(
            swipeModifier
                .heightIn(min = RowMinHeight)
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        if (editing) return@combinedClickable
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        openMenu()
                    }
                )
                .padding(horizontal = horizontalPadding, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (leading) {
                SongRowLeading.Artwork -> Box {
                    SongArtwork(song, 52.dp, 3.5.dp)
                    if (current) PlayingMark(playback.isPlaying, Color.White, RoundedCornerShape(3.5.dp))
                }
                is SongRowLeading.TrackNumber -> Box(Modifier.width(24.dp).height(44.dp), contentAlignment = Alignment.Center) {
                    if (current) Box(Modifier.size(24.dp)) { PlayingMark(playback.isPlaying, AppAccent, scrim = null) }
                    else Text("${leading.number}", color = ink.copy(alpha = 0.42f), fontSize = 15.sp, maxLines = 1)
                }
            }
            Column(Modifier.weight(1f).padding(start = 16.dp, end = 8.dp)) {
                Text(song.title, color = if (current) AppAccent else ink, fontSize = if (albumTrack) 17.sp else 16.sp,
                    lineHeight = if (albumTrack) 22.sp else 16.sp,
                    fontWeight = if (albumTrack) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 1.dp))
                if (subtitle != null) {
                    Text(subtitle, color = if (current) AppAccent.copy(alpha = 0.75f) else ink.copy(alpha = 0.5f), fontSize = 13.sp, lineHeight = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            when {
                song.telegramMessageId in actions.downloadingIds ->
                    CalmSpinner(color = ink.copy(alpha = 0.5f), strokeWidth = 1.5.dp, modifier = Modifier.size(14.dp))
                song.isExplicitDownload ->
                    Icon(Icons.Rounded.ArrowCircleDown, contentDescription = "Downloaded", tint = ink.copy(alpha = 0.35f), modifier = Modifier.size(16.dp))
            }
            if (albumTrack && !editing) {
                Box(
                    Modifier.size(44.dp).clickable(onClickLabel = "Options for ${song.title}") { openMenu() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.MoreHoriz, contentDescription = "Options for ${song.title}",
                        tint = ink.copy(alpha = 0.5f), modifier = Modifier.size(22.dp))
                }
            }
        }
    }

    if (menuOpen) {
        SongContextMenu(song = song, actions = actions, anchorTopPx = rowTopInWindow, onDismiss = { menuOpen = false })
    }
}

/**
 * Swipe a row right to "Play next": an accent strip grows under it with the queue icon, a tick
 * at the trigger point (a fifth of the row), and the row springs back on release. [content]
 * puts the given modifier on its row. Nothing runs until a finger moves.
 */
@Composable
internal fun SwipeToPlayNext(
    onPlayNext: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    rowBackground: Color = paper,
    content: @Composable (Modifier) -> Unit
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var rowWidth by remember { mutableIntStateOf(0) }
    val swipe = remember { Animatable(0f) }
    val playNext by rememberUpdatedState(onPlayNext)
    LaunchedEffect(enabled) { if (!enabled) swipe.snapTo(0f) }
    // Read inside the gesture, never captured: the row's width can change after it starts.
    fun trigger() = rowWidth * 0.2f
    fun dragBy(delta: Float) {
        val before = swipe.value
        val after = (before + delta).coerceIn(0f, rowWidth.toFloat())
        if ((before >= trigger()) != (after >= trigger())) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        scope.launch { swipe.snapTo(after) }
    }
    Box(modifier.fillMaxWidth().clipToBounds().onSizeChanged { rowWidth = it.width }) {
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
        content(
            Modifier
                .graphicsLayer { translationX = swipe.value }
                .background(if (enabled) rowBackground else Color.Transparent)
                // Claims only a rightward swipe: a leftward one is left to whatever holds the
                // row (the Search pager flips back to YouTube Music with it).
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var start = 0f
                        // Only a clearly sideways move claims the row: a diagonal scroll that
                        // drifts right is left to the list, so scrolling never sticks on a row.
                        val claimed = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                            val moved = change.position - down.position
                            if (over > 0f && kotlin.math.abs(moved.x) > kotlin.math.abs(moved.y) * SIDEWAYS_RATIO) {
                                change.consume(); start = over
                            }
                        } ?: return@awaitEachGesture
                        if (rowWidth == 0) return@awaitEachGesture
                        dragBy(start)
                        horizontalDrag(claimed.id) { change ->
                            dragBy(change.positionChange().x)
                            change.consume()
                        }
                        scope.launch {
                            if (trigger() > 0f && swipe.value >= trigger()) playNext()
                            swipe.animateTo(0f, spring(dampingRatio = 0.72f, stiffness = 420f))
                        }
                    }
                }
        )
    }
}

// How much more sideways than up/down a move must be to start "Play next".
private const val SIDEWAYS_RATIO = 1.5f

@Composable
internal fun SongArtwork(song: SongEntity, size: Dp, corner: Dp, modifier: Modifier = Modifier) {
    // The cached list thumbnail is only 160 px - sharp in a list row, blurry stretched across a
    // Home card (128 dp is ~380 px). Big tiles load the full cover instead; Coil decodes it down
    // to the tile's size once and keeps it in memory, so scrolling back costs nothing.
    // If the full cover can't load (a dead link), fall back to the thumbnail rather than blank.
    var fullFailed by remember(song.telegramMessageId) { mutableStateOf(false) }
    val art = if (size >= LARGE_TILE && !fullFailed) song.displayArtwork else song.listArtwork
    Box(
        modifier.size(size).clip(RoundedCornerShape(corner)).background(LocalPalette.current.raised),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Rounded.MusicNote, null, tint = ink.copy(alpha = 0.3f), modifier = Modifier.size(size * 0.45f))
        if (!art.isNullOrEmpty()) {
            AsyncImage(
                model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                onError = { if (size >= LARGE_TILE && art != song.listArtwork) fullFailed = true }
            )
        }
    }
}

internal object WindowOriginPosition : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
        IntOffset.Zero
}

private val MenuEstimatedHeight = 430.dp

/** From this size up, [SongArtwork] shows the full cover rather than the list thumbnail. */
private val LARGE_TILE = 96.dp

/** The long-press card: song header, then actions. Pops in at the pressed row. */
@Composable
internal fun SongContextMenu(song: SongEntity, actions: LibrarySongActions, anchorTopPx: Float, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var showArtworkDialog by remember { mutableStateOf(false) }
    LibraryContextMenu(anchorTopPx, onDismiss) { maxHeight, onAction ->
        SongContextCard(song, actions, maxHeight, onAction,
            onFindArtwork = { showArtworkDialog = true },
            onToast = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() })
        if (showArtworkDialog) FindArtworkDialog(song,
            onFind = { title, artist -> actions.onFindArtwork(song, title, artist) },
            onDismiss = { showArtworkDialog = false; onAction {} })
    }
}

/** Shared anchor and animation so remote items use the same card as library songs. */
@Composable
internal fun LibraryContextMenu(anchorTopPx: Float, onDismiss: () -> Unit, centered: Boolean = false,
    content: @Composable (maxHeight: Dp, onAction: (() -> Unit) -> Unit) -> Unit) {
    var show by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

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

    // The dim over the page fades with the card (drawn only - nothing recomposes). The system
    // bars' own colours sit above it, so they're darkened with it (see MainActivity).
    val dim by animateFloatAsState(if (show) MENU_DIM else 0f,
        androidx.compose.animation.core.tween(if (show) 140 else 160), label = "menuDim")
    DisposableEffect(show) {
        SystemBarsState.menuOpen = show
        onDispose { SystemBarsState.menuOpen = false }
    }
    ContextMenuLayer(onDismissRequest = { close() }) {
      Box(
          Modifier
              .fillMaxSize()
              .drawBehind { drawRect(Color.Black.copy(alpha = dim)) }
              .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { close() }
      ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                // Between the status and navigation bars, where the menu's own window used to sit:
                // the card keeps its place against the pressed row and off the gesture bar.
                .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.systemBars)
                .then(if (centered) Modifier.windowInsetsPadding(
                    androidx.compose.foundation.layout.WindowInsets.safeDrawing.union(androidx.compose.foundation.layout.WindowInsets.ime)
                ) else Modifier)
        ) {
            val density = LocalDensity.current
            val anchorTop = with(density) { anchorTopPx.toDp() }
            val maxTop = maxHeight - MenuEstimatedHeight
            val top = if (maxTop > 16.dp) anchorTop.coerceIn(16.dp, maxTop) else 16.dp
            val spec = spring<Float>(dampingRatio = 0.72f, stiffness = 360f)
            AnimatedVisibility(
                visible = show,
                // Opacity eases (a bouncy spring overshoots and dips - a flicker); only the scale bounces.
                enter = fadeIn(androidx.compose.animation.core.tween(140)) +
                    scaleIn(if (centered) androidx.compose.animation.core.tween(140) else spec,
                        initialScale = if (centered) 0.96f else 0.82f,
                        transformOrigin = if (centered) TransformOrigin.Center else TransformOrigin(0.5f, 0f)),
                exit = fadeOut(androidx.compose.animation.core.tween(160)) +
                    scaleOut(if (centered) androidx.compose.animation.core.tween(120) else spring(dampingRatio = 0.86f, stiffness = 520f),
                        targetScale = if (centered) 0.98f else 0.88f,
                        transformOrigin = if (centered) TransformOrigin.Center else TransformOrigin(0.5f, 0f)),
                modifier = Modifier
                    .align(if (centered) Alignment.Center else Alignment.TopCenter)
                    .then(if (centered) Modifier.padding(vertical = 16.dp) else Modifier.padding(top = top))
                    .fillMaxWidth(0.72f)
            ) {
                val availableHeight = if (centered) maxHeight - 32.dp else maxHeight - top - 16.dp
                content(availableHeight.coerceAtLeast(0.dp)) { action -> close(action) }
            }
        }
      }
    }

}

/** All song-action surfaces share the context card's exact material and edge treatment. */
@Composable
internal fun Modifier.libraryContextCardSurface(opaque: Boolean = false): Modifier {
    val shape = RoundedCornerShape(18.dp)
    return graphicsLayer { this.shape = shape; clip = true }
        .background(if (opaque) LibraryCardColor.copy(alpha = 1f) else LibraryCardColor)
        .border(0.5.dp, ink.copy(alpha = 0.12f), shape)
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
    val isRealLocalImport = song.isLocalImport && song.localFilePath != null
    val hasArtwork = song.listArtwork != null
    val album = song.album?.takeIf { it.isNotBlank() }
    val editing = LocalLibraryEditing.current

    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            // A hairline edge instead of an elevation shadow, which redrew every frame of the
            // open animation and flashed.
            .libraryContextCardSurface()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
    ) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            SongArtwork(song, 44.dp, 6.dp)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(song.title, color = ink, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (album != null) "${song.artist} · $album" else song.artist,
                    color = ink.copy(alpha = 0.55f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        CardDivider()
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            if (editing != null) {
                CardItem("Select", Icons.Rounded.CheckCircleOutline) {
                    // Start the mode change as the context card closes, rather than after a pause.
                    editing.select(song.telegramMessageId)
                    onAction {}
                }
                CardDivider()
            }
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
            YouTubeLikeMenuItem(song.youtubeVideoId, onAction)
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
internal fun CardItem(
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
        !enabled -> ink.copy(alpha = 0.4f)
        else -> ink
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
                    tint = ink.copy(alpha = 0.48f),
                    modifier = Modifier.size(11.dp).graphicsLayer { rotationZ = rotation }
                )
            }
        }
        Icon(icon, contentDescription = label, tint = color.copy(alpha = if (destructive) 1f else 0.74f), modifier = Modifier.size(20.dp))
    }
}

@Composable
internal fun CardDivider() {
    Spacer(Modifier.fillMaxWidth().height(0.5.dp).background(ink.copy(alpha = 0.08f)))
}

@Composable
internal fun PlaylistPicker(playlists: List<PlaylistEntity>, onPick: (PlaylistEntity) -> Unit, onCreate: (String) -> Unit, standalone: Boolean = false) {
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().background(if (standalone) Color.Transparent else ink.copy(alpha = 0.04f))) {
        if (creating) {
            Row(
                Modifier.fillMaxWidth().height(if (standalone) 50.dp else 42.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.weight(1f).height(if (standalone) 36.dp else 30.dp).clip(RoundedCornerShape(8.dp)).background(ink.copy(alpha = 0.08f)).padding(horizontal = 8.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (name.isEmpty()) Text("Playlist name", color = ink.copy(alpha = 0.45f), fontSize = 12.sp)
                    BasicTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        textStyle = TextStyle(color = ink, fontSize = 12.sp),
                        cursorBrush = SolidColor(AppAccent),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "Create",
                    color = if (name.isNotBlank()) AppAccent else ink.copy(alpha = 0.3f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(enabled = name.isNotBlank()) { onCreate(name.trim()) }
                )
            }
        } else {
            Row(
                Modifier.fillMaxWidth().height(if (standalone) 50.dp else 36.dp).clickable { creating = true }.padding(horizontal = 19.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("New Playlist…", color = AppAccent, fontSize = if (standalone) 16.sp else 13.sp, modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.Add, null, tint = AppAccent, modifier = Modifier.size(if (standalone) 20.dp else 16.dp))
            }
        }
        if (playlists.isEmpty() && !creating) {
            if (standalone) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 19.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("No playlists yet", color = ink.copy(alpha = 0.5f), fontSize = 13.sp)
                    Text("Your playlists will appear here", color = ink.copy(alpha = 0.38f), fontSize = 11.sp)
                }
            } else {
                Text("No playlists yet", color = ink.copy(alpha = 0.4f), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 19.dp, vertical = 6.dp))
            }
        }
        playlists.forEach { playlist ->
            Row(
                Modifier.fillMaxWidth().height(if (standalone) 50.dp else 36.dp).clickable { onPick(playlist) }.padding(horizontal = 19.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(playlist.name, color = ink, fontSize = if (standalone) 15.sp else 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
