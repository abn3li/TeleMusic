package com.abn3li.telemusic.ui.library

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abn3li.telemusic.data.local.SongEntity
import com.abn3li.telemusic.ui.nowplaying.LocalMiniPlayerInset
import com.abn3li.telemusic.ui.theme.ink
import androidx.compose.foundation.interaction.MutableInteractionSource
import kotlin.math.roundToInt

internal const val SelectionEnterMillis = 160
internal const val SelectionExitMillis = 180

/** A separate order for each collection. New songs follow the saved songs in source order. */
internal fun applyLibraryOrder(songs: List<SongEntity>, ids: List<Long>): List<SongEntity> {
    if (ids.isEmpty()) return songs
    val rank = ids.withIndex().associate { it.value to it.index }
    return songs.sortedBy { rank[it.telegramMessageId] ?: Int.MAX_VALUE }
}

internal class LibraryEditing(private val context: Context, private val key: String) {
    private val preferences = context.getSharedPreferences("library_orders", Context.MODE_PRIVATE)
    var order by mutableStateOf(preferences.getString(key, null)?.split(',')?.mapNotNull { it.toLongOrNull() }.orEmpty())
        private set
    var isCustom by mutableStateOf(preferences.getBoolean("$key:custom", order.isNotEmpty()))
        private set
    var draft by mutableStateOf<List<Long>>(emptyList())
    var selecting by mutableStateOf(false)
    var reordering by mutableStateOf(false)
    var selected by mutableStateOf<Set<Long>>(emptySet())
    var draggingId by mutableStateOf<Long?>(null)
    var reorderReady by mutableStateOf(false)
    var pendingReorder by mutableStateOf<List<SongEntity>?>(null)
    var dragPointerY by mutableFloatStateOf(0f)
    var dragCenterY by mutableFloatStateOf(0f)
    var dragRowHeight = 0
    var dragGrabOffset = 0f
    var dragHeaderCount = 0
    private var indexedDraft: List<Long>? = null
    private var draftIndices = emptyMap<Long, Int>()
    fun draftIndexOf(id: Long): Int {
        if (indexedDraft !== draft) {
            indexedDraft = draft
            draftIndices = draft.withIndex().associate { it.value to it.index }
        }
        return draftIndices[id] ?: -1
    }
    val active get() = selecting || reordering
    fun ordered(songs: List<SongEntity>) = applyLibraryOrder(songs, if (reordering) draft else if (isCustom) order else emptyList())
    fun select(id: Long) { selecting = true; selected = setOf(id) }
    fun toggle(id: Long) { selected = if (id in selected) selected - id else selected + id }
    fun beginReorder(songs: List<SongEntity>) {
        selecting = false; selected = emptySet()
        draft = ordered(songs).map { it.telegramMessageId }
        reorderReady = false; draggingId = null; reordering = true
    }
    /** Keep user moves while reflecting live additions/removals from the collection. */
    fun reconcileReorder(songs: List<SongEntity>): Boolean {
        if (!reordering) return false
        val ids = songs.map { it.telegramMessageId }.toSet()
        val previous = draft.toSet()
        val updated = draft.filter { it in ids } + ids.filter { it !in previous }
        if (updated == draft) return false
        draft = updated
        if (draggingId != null && draggingId !in ids) draggingId = null
        return true
    }
    fun move(id: Long, target: Long) {
        val from = draft.indexOf(id)
        val to = draft.indexOf(target)
        if (from < 0 || to < 0 || from == to) return
        draft = draft.toMutableList().apply { add(to, removeAt(from)) }
    }
    fun finish() {
        if (reordering) {
            order = draft
            isCustom = true
            preferences.edit().putString(key, order.joinToString(",")).putBoolean("$key:custom", true).apply()
        }
        cancel()
    }
    fun cancel() { selecting = false; reordering = false; selected = emptySet(); draft = emptyList(); draggingId = null; reorderReady = false; pendingReorder = null }
    fun resetOrder() { isCustom = false; preferences.edit().putBoolean("$key:custom", false).apply() }
    fun useCustomOrder() {
        if (order.isEmpty()) return
        isCustom = true
        preferences.edit().putBoolean("$key:custom", true).apply()
    }
    companion object {
        fun saver(context: Context, key: String) = Saver<LibraryEditing, ArrayList<String>>(
            save = { arrayListOf(it.selecting.toString(), it.reordering.toString(), it.selected.joinToString(","), it.draft.joinToString(",")) },
            restore = { values -> LibraryEditing(context, key).apply {
                selecting = values[0].toBoolean(); reordering = values[1].toBoolean()
                selected = values[2].split(',').mapNotNull { it.toLongOrNull() }.toSet()
                draft = values[3].split(',').mapNotNull { it.toLongOrNull() }
            } }
        )
    }
}

internal val LocalLibraryEditing = staticCompositionLocalOf<LibraryEditing?> { null }
internal val LocalEditingListState = staticCompositionLocalOf<LazyListState?> { null }
internal val LocalEditingSongs = staticCompositionLocalOf<List<SongEntity>> { emptyList() }

@Composable
internal fun rememberLibraryEditing(key: String, sortToken: String = ""): LibraryEditing {
    val context = LocalContext.current.applicationContext
    val edit = rememberSaveable(key, saver = LibraryEditing.saver(context, key)) { LibraryEditing(context, key) }
    var previousSort by remember(key) { mutableStateOf(sortToken) }
    LaunchedEffect(sortToken) { if (previousSort != sortToken) { edit.cancel(); edit.resetOrder(); previousSort = sortToken } }
    BackHandler(edit.active) { edit.cancel() }
    return edit
}

@Composable
internal fun EditingMenuItem(edit: LibraryEditing, songs: List<SongEntity>, close: () -> Unit) {
    LibraryMenuItem("Reorder", Icons.Rounded.DragHandle) { close(); if (songs.isNotEmpty()) edit.pendingReorder = songs }
    LibraryMenuDivider()
}

@Composable
internal fun CustomSortMenuItem(close: () -> Unit) {
    val edit = LocalLibraryEditing.current ?: return
    if (edit.order.isEmpty()) return
    LibraryMenuDivider()
    LibraryMenuItem("Custom", if (edit.isCustom) Icons.Rounded.Check else null, selected = edit.isCustom) {
        edit.useCustomOrder()
        close()
    }
}

/** Editing uses the page's existing navigation material, with no second control card. */
@Composable
internal fun LibraryEditingTopBar(edit: LibraryEditing, modifier: Modifier = Modifier) {
    val songs = LocalEditingSongs.current
    val count = songs.count { it.telegramMessageId in edit.selected }
    Row(modifier.fillMaxWidth().height(LibraryBarHeight).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            Text("Cancel", color = AppAccent, fontSize = 17.sp, modifier = Modifier.clickable { edit.cancel() }.padding(horizontal = 10.dp, vertical = 12.dp))
        }
        Text(if (edit.reordering) "Reorder" else "$count Selected", color = ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            Text(if (edit.reordering) "Done" else "Select All", color = AppAccent, fontSize = 17.sp,
                fontWeight = if (edit.reordering) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.clickable { if (edit.reordering) edit.finish() else edit.selected = songs.map { it.telegramMessageId }.toSet() }
                    .padding(horizontal = 10.dp, vertical = 12.dp))
        }
    }
}

/** Bottom controls sit above the existing player/dock; the list reserves their space. */
@Composable
internal fun BoxScope.LibraryEditingBar(edit: LibraryEditing, songs: List<SongEntity>, actions: LibrarySongActions, onPlay: (List<Long>, Boolean) -> Unit) {
    var pickPlaylist by remember { mutableStateOf(false) }
    val selectedSongs = edit.ordered(songs).filter { it.telegramMessageId in edit.selected }
    var lastSelection by remember { mutableStateOf<List<SongEntity>>(emptyList()) }
    SideEffect { if (edit.selecting) lastSelection = selectedSongs }
    val displayedSongs = if (edit.selecting) selectedSongs else lastSelection
    val allFavorite = displayedSongs.isNotEmpty() && displayedSongs.all { it.isFavorite }
    LaunchedEffect(edit.selecting) { if (!edit.selecting) pickPlaylist = false }
    AnimatedVisibility(visible = edit.selecting,
        enter = fadeIn(tween(SelectionEnterMillis)) + slideInVertically(tween(SelectionEnterMillis)) { it / 6 },
        exit = fadeOut(tween(SelectionExitMillis)) + slideOutVertically(tween(SelectionExitMillis)) { it / 6 },
        modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp).padding(bottom = LocalMiniPlayerInset.current + 8.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(Modifier.weight(1f, fill = false).clip(CircleShape).background(LibraryCardColor)
                    .border(0.5.dp, ink.copy(alpha = 0.12f), CircleShape).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    SelectionAction("Playlist", Icons.AutoMirrored.Rounded.PlaylistAdd, displayedSongs.isNotEmpty(), interactive = edit.selecting) { pickPlaylist = true }
                    SelectionActionDivider()
                    SelectionAction("Download", Icons.Rounded.Download,
                        displayedSongs.any { !it.isLocalImport && !it.isExplicitDownload && it.telegramMessageId !in actions.downloadingIds }, interactive = edit.selecting) {
                        actions.onDownloadSongs(selectedSongs.filter { !it.isLocalImport && !it.isExplicitDownload && it.telegramMessageId !in actions.downloadingIds })
                    }
                    SelectionActionDivider()
                    SelectionAction("Favorite", if (allFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        displayedSongs.any { !it.isFavorite }, interactive = edit.selecting, active = allFavorite) {
                        selectedSongs.filter { !it.isFavorite }.forEach(actions.onToggleFavorite)
                    }
                }
                Spacer(Modifier.width(16.dp))
                Row(Modifier.height(44.dp).clip(CircleShape).background(AppAccent.copy(alpha = if (displayedSongs.isNotEmpty()) 1f else 0.35f))
                    .clickable(enabled = edit.selecting && selectedSongs.isNotEmpty()) {
                        onPlay(selectedSongs.map { it.telegramMessageId }, false); edit.cancel()
                    }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Text("Play", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
    if (pickPlaylist) LibraryContextMenu(0f, onDismiss = { pickPlaylist = false }, centered = true) { maxHeight, onAction ->
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).libraryContextCardSurface()
            .semantics { paneTitle = "Add to Playlist" }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})) {
            Text("Add to Playlist", color = ink, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(18.dp))
            CardDivider()
            Column(Modifier.verticalScroll(rememberScrollState())) {
                PlaylistPicker(actions.playlists,
                    onPick = { playlist -> onAction { selectedSongs.forEach { actions.onAddToPlaylist(playlist.id, it) } } },
                    onCreate = { name -> onAction { actions.onCreatePlaylistAndAddSongs(name, selectedSongs) } })
            }
        }
    }
}

@Composable
private fun SelectionAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean,
    interactive: Boolean = true, active: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clickable(enabled = enabled && interactive, onClick = onClick)
        .then(if (active) Modifier.semantics { selected = true } else Modifier), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = if (active) AppAccent else ink.copy(alpha = if (enabled) 0.85f else 0.3f), modifier = Modifier.size(23.dp))
    }
}

@Composable
private fun SelectionActionDivider() { Spacer(Modifier.width(0.5.dp).height(18.dp).background(ink.copy(alpha = 0.1f))) }

@Composable
internal fun EditingSongRow(song: SongEntity, edit: LibraryEditing, horizontalPadding: Dp = 22.dp, content: @Composable () -> Unit) {
    val selectionProgress = animateFloatAsState(if (edit.selecting) 1f else 0f,
        tween(if (edit.selecting) SelectionEnterMillis else SelectionExitMillis, easing = FastOutSlowInEasing), label = "selectionGutter")
    val listState = LocalEditingListState.current
    Row(Modifier.fillMaxWidth().graphicsLayer {
        translationY = if (edit.draggingId == song.telegramMessageId) {
            val row = listState?.layoutInfo?.visibleItemsInfo?.firstOrNull { it.key == song.telegramMessageId }
            if (row == null) 0f else edit.dragCenterY - row.offset - row.size / 2f
        } else 0f
    }.background(if (edit.draggingId == song.telegramMessageId) LibraryCardColor else Color.Transparent), verticalAlignment = Alignment.CenterVertically) {
        // Animate only this small gutter's measurement. The artwork/text subtree stays mounted.
        Box(Modifier.layout { measurable, constraints ->
            val fullWidth = 48.dp.roundToPx()
            val normalWidth = horizontalPadding.roundToPx()
            val placeable = measurable.measure(constraints.copy(minWidth = fullWidth, maxWidth = fullWidth))
            val width = (normalWidth + (fullWidth - normalWidth) * selectionProgress.value).roundToInt()
            layout(width, placeable.height) { placeable.placeRelative((width - fullWidth) / 2, 0) }
        }.clipToBounds().graphicsLayer { alpha = selectionProgress.value }
            .then(if (!edit.selecting) Modifier.clearAndSetSemantics {} else Modifier)) {
            val checked = song.telegramMessageId in edit.selected
            val checkProgress = animateFloatAsState(if (checked) 1f else 0f, tween(120), label = "songCheck")
            Box(Modifier.size(48.dp).toggleable(checked, enabled = edit.selecting, role = Role.Checkbox) { edit.toggle(song.telegramMessageId) }
                .semantics { contentDescription = "Select ${song.title}" }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(22.dp).then(if (checked) Modifier.background(AppAccent, CircleShape)
                    else Modifier.border(1.5.dp, ink.copy(alpha = 0.35f), CircleShape)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(16.dp).graphicsLayer {
                        alpha = checkProgress.value
                        scaleX = 0.85f + checkProgress.value * 0.15f
                        scaleY = scaleX
                    })
                }
            }
        }
        Box(Modifier.weight(1f)) { content() }
        if (!edit.reordering) Spacer(Modifier.width(horizontalPadding))
        if (edit.reordering) Icon(Icons.Rounded.DragHandle, "Drag to reorder ${song.title}", tint = ink.copy(alpha = 0.6f), modifier = Modifier
            .size(48.dp).semantics {
                customActions = listOf(
                    CustomAccessibilityAction("Move up") { val index = edit.draft.indexOf(song.telegramMessageId); if (index > 0) edit.move(song.telegramMessageId, edit.draft[index - 1]); index > 0 },
                    CustomAccessibilityAction("Move down") { val index = edit.draft.indexOf(song.telegramMessageId); if (index >= 0 && index < edit.draft.lastIndex) edit.move(song.telegramMessageId, edit.draft[index + 1]); index >= 0 && index < edit.draft.lastIndex }
                )
            }.padding(12.dp))
    }
}
