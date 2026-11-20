package com.abn3li.telemusic.ui.sync

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.abn3li.telemusic.ui.library.SongArtwork
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.data.telegram.TelegramChatInfo
import com.abn3li.telemusic.repository.MusicRepository
import com.abn3li.telemusic.sync.SyncService
import com.abn3li.telemusic.ui.library.AppAccent
import com.abn3li.telemusic.ui.library.CalmSpinner
import com.abn3li.telemusic.ui.library.LargeTitleList
import com.abn3li.telemusic.ui.library.LibrarySearchField
import com.abn3li.telemusic.ui.nowplaying.LocalMiniPlayerInset
import com.abn3li.telemusic.ui.theme.ink

/** One chat's songs, to pick which ones to add. Songs already in the library are faded and can't
 * be picked; songs posted since the page was last opened are marked New. */
@Composable
internal fun ChatSongsPage(chat: TelegramChatInfo, title: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as TgMusicApp
    BackHandler(onBack = onBack)
    var songs by remember(chat.id) { mutableStateOf<List<MusicRepository.ChatSong>>(emptyList()) }
    var loading by remember(chat.id) { mutableStateOf(true) }
    var selected by remember(chat.id) { mutableStateOf(emptySet<Long>()) }
    var query by remember(chat.id) { mutableStateOf("") }
    val seenUpTo = remember(chat.id) { app.settingsStore.chatSongsSeenUpTo(chat.id) }
    val syncing by SyncService.isRunning.collectAsState()

    LaunchedEffect(chat.id) {
        app.musicRepository.loadChatSongs(chat.id) { songs = it }
        loading = false
        songs.firstOrNull()?.let { app.settingsStore.setChatSongsSeenUpTo(chat.id, it.message.messageId) }
    }

    val shown = if (query.isBlank()) songs else songs.filter {
        it.message.title.contains(query, ignoreCase = true) || it.message.performer.contains(query, ignoreCase = true)
    }
    val pickable = shown.filter { !it.inLibrary }.map { it.message.messageId }
    val allPicked = pickable.isNotEmpty() && selected.containsAll(pickable)

    LargeTitleList(
        title = title,
        onBack = onBack,
        barActions = {
            if (!loading && pickable.isNotEmpty()) {
                Text(
                    if (allPicked) "Deselect All" else "Select All", color = AppAccent, fontSize = 17.sp,
                    modifier = Modifier.clickable { selected = if (allPicked) selected - pickable.toSet() else selected + pickable }
                        .padding(horizontal = 10.dp, vertical = 12.dp)
                )
            }
        },
        stickyContent = { LibrarySearchField(query, "Search Songs", { query = it }) },
        overlay = {
            if (selected.isNotEmpty()) {
                val count = selected.size
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = LocalMiniPlayerInset.current + 10.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (syncing) AppAccent.copy(alpha = 0.4f) else AppAccent)
                        .clickable(enabled = !syncing) {
                            val picked = songs.filter { it.message.messageId in selected }.map { it.message }
                            SyncService.startPicked(context, chat.id, picked)
                            onBack()
                        }
                        .padding(vertical = 15.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (count == 1) "Sync 1 Song" else "Sync $count Songs", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    ) {
        item("summary") {
            val have = songs.count { it.inLibrary }
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                val total = if (songs.size == 1) "1 song" else "${songs.size} songs"
                Text(if (have > 0) "$total · $have in your library" else total, color = ink.copy(alpha = 0.5f), fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                if (loading) CalmSpinner(color = AppAccent, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
            }
        }
        if (!loading && shown.isEmpty()) {
            item("empty") {
                Text(
                    if (songs.isEmpty()) "No songs in this chat." else "No results",
                    color = ink.copy(alpha = 0.5f), fontSize = 15.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 40.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
        // Newest first, under a heading for each month, as in Telegram.
        val byMonth = shown.groupBy { monthOf(it.message.dateSeconds) }
        for ((month, monthSongs) in byMonth) {
            if (month.isNotEmpty()) item("month_$month", contentType = "month") {
                Text(month, color = ink.copy(alpha = 0.85f), fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 4.dp))
            }
            items(monthSongs, key = { it.message.messageId }, contentType = { "song" }) { song ->
                val id = song.message.messageId
                ChatSongRow(song, isNew = seenUpTo != 0L && id > seenUpTo && !song.inLibrary, checked = id in selected,
                    loadThumbnail = app.tdlibManager::downloadThumbnail) {
                    selected = if (id in selected) selected - id else selected + id
                }
            }
        }
        if (selected.isNotEmpty()) item("button_space") { Spacer(Modifier.height(64.dp)) }
    }
}

private val monthFormat = java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.US)
private fun monthOf(dateSeconds: Int): String =
    if (dateSeconds <= 0) "" else monthFormat.format(java.util.Date(dateSeconds * 1000L))

@Composable
private fun ChatSongRow(
    song: MusicRepository.ChatSong,
    isNew: Boolean,
    checked: Boolean,
    loadThumbnail: suspend (Int) -> String?,
    onToggle: () -> Unit
) {
    val msg = song.message
    val copy = song.libraryCopy
    val have = copy != null
    Row(
        Modifier.fillMaxWidth()
            .toggleable(checked || have, enabled = !have, role = Role.Checkbox) { onToggle() }
            .padding(start = 4.dp, end = 16.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(22.dp).then(
                    when {
                        have -> Modifier.background(ink.copy(alpha = 0.22f), CircleShape)
                        checked -> Modifier.background(AppAccent, CircleShape)
                        else -> Modifier.border(1.5.dp, ink.copy(alpha = 0.35f), CircleShape)
                    }
                ),
                contentAlignment = Alignment.Center
            ) {
                if (checked || have) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
        // Songs already in the library are only slightly faded.
        Row(Modifier.weight(1f).alpha(if (have) 0.62f else 1f), verticalAlignment = Alignment.CenterVertically) {
            if (copy != null) {
                SongArtwork(copy, 44.dp, 6.dp)
            } else {
                // Telegram's own small cover, shown on this page only; songs added get the
                // library's covers as usual.
                val thumbnail by produceState<String?>(null, msg.thumbnailFileId) {
                    if (msg.thumbnailFileId != 0) value = loadThumbnail(msg.thumbnailFileId)
                }
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(ink.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                    if (thumbnail != null) {
                        AsyncImage(model = java.io.File(thumbnail!!), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(44.dp))
                    } else {
                        Icon(Icons.Rounded.MusicNote, null, tint = ink.copy(alpha = 0.35f), modifier = Modifier.size(22.dp))
                    }
                }
            }
            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(msg.title, color = ink, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val parts = buildList {
                        if (msg.performer.isNotBlank() && msg.performer != "Telegram Document") add(msg.performer)
                        if (msg.durationSeconds > 0) add("%d:%02d".format(msg.durationSeconds / 60, msg.durationSeconds % 60))
                        else if (msg.sizeBytes > 0) add("%.1f MB".format(msg.sizeBytes / 1_048_576.0))
                    }
                    Text(parts.joinToString(" · "), color = ink.copy(alpha = 0.5f), fontSize = 13.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (isNew) Text("  New", color = AppAccent, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}
