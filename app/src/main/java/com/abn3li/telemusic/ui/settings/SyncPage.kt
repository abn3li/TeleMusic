package com.abn3li.telemusic.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.data.settings.NewPlaylistPlace
import com.abn3li.telemusic.data.settings.PlaylistPrivacy
import com.abn3li.telemusic.data.settings.SyncSettings
import com.abn3li.telemusic.ui.library.AccountBadge
import com.abn3li.telemusic.ui.library.AlertAction
import com.abn3li.telemusic.ui.library.AppAccent
import com.abn3li.telemusic.ui.library.AppAlert
import com.abn3li.telemusic.ui.library.GroupCardColor
import com.abn3li.telemusic.ui.library.GroupLabelColor
import com.abn3li.telemusic.ui.library.GroupSwitch
import com.abn3li.telemusic.ui.library.LargeTitleList
import com.abn3li.telemusic.ui.library.SegmentedChoice
import com.abn3li.telemusic.ui.theme.ink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What's shared with the YouTube account: likes, playlists, and Telegram songs found there. */
@Composable
internal fun SyncPage(app: TgMusicApp, onBack: () -> Unit, onSignIn: () -> Unit) {
    val account by app.youtubeAccount.state.collectAsState()
    val sync by app.settingsStore.sync.collectAsState()
    val waiting by app.youtubeLibrary.waiting.collectAsState()
    val scope = rememberCoroutineScope()
    var oldLikes by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(account.sessionId) { withContext(Dispatchers.IO) { app.youtubeLibrary.refreshWaiting() } }

    fun change(transform: (SyncSettings) -> SyncSettings) = app.settingsStore.updateSync(transform)

    LargeTitleList(title = "TeleMusic Tweaks", onBack = onBack, grouped = true) {
        item("account") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.5.dp).padding(top = 8.dp)
                    .clip(RoundedCornerShape(22.dp)).background(GroupCardColor)
                    .then(if (account.signedIn) Modifier else Modifier.clickable(onClick = onSignIn))
                    .padding(horizontal = 17.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AccountBadge(account, null, 46.dp)
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (account.signedIn) account.name ?: "YouTube Music" else "Sign In to YouTube", color = ink, fontSize = 17.sp,
                        fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            !account.signedIn -> "To sync your likes and playlists"
                            waiting == 1 -> "1 like waiting"
                            waiting > 1 -> "$waiting likes waiting"
                            sync.likes -> "Likes are up to date"
                            else -> "Connected to YouTube"
                        },
                        color = ink.copy(alpha = 0.5f), fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp)
                    )
                }
            }
        }
        if (!account.signedIn) return@LargeTitleList
        syncCard("likes", Icons.Rounded.Star, Color(0xFFFF375F), "Likes", listOf(
            "Songs you add to Favorites are saved on this phone and in your YouTube account.",
            "Offline likes are added when you're back online.",
            "When off, likes stay in the app only."
        )) {
            SyncSwitch("Also Like on YouTube", sync.likes) { on ->
                change { it.copy(likes = on) }
                if (!on) app.workScope.launch(Dispatchers.IO) { app.youtubeLibrary.dropWaiting(telegram = false) }
            }
            AnimatedVisibility(sync.likes) {
                SyncSwitch("Also Unlike on YouTube", sync.unlikes) { on -> change { it.copy(unlikes = on) } }
            }
        }
        syncCard("playlists", Icons.AutoMirrored.Rounded.QueueMusic, Color(0xFFAF52DE), "Playlists", listOf(
            "Create and edit playlists for YouTube songs.",
            "Turning this off doesn't delete anything."
        )) {
            SyncSwitch("Show YouTube Playlists", sync.showPlaylists) { on -> change { it.copy(showPlaylists = on) } }
            AnimatedVisibility(sync.showPlaylists) {
                SyncSwitch("Edit YouTube Playlists", sync.editPlaylists) { on -> change { it.copy(editPlaylists = on) } }
            }
            AnimatedVisibility(sync.playlistsEditable) {
                Column {
                    SyncChoice("Save New Playlists", listOf(NewPlaylistPlace.ASK to "Ask", NewPlaylistPlace.YOUTUBE to "YouTube", NewPlaylistPlace.PHONE to "This phone"),
                        sync.newPlaylists) { place -> change { it.copy(newPlaylists = place) } }
                    SyncChoice("Who Can See Them on YouTube", listOf(PlaylistPrivacy.PRIVATE to "Only me", PlaylistPrivacy.UNLISTED to "With link", PlaylistPrivacy.PUBLIC to "Everyone"),
                        sync.privacy) { privacy -> change { it.copy(privacy = privacy) } }
                }
            }
        }
        syncCard("telegram", Icons.AutoMirrored.Rounded.Send, Color(0xFF0A84FF), "Sync Telegram Songs with YouTube", listOf(
            "The app looks for the same song on YouTube.",
            "If it isn't found, the song stays on this phone."
        )) {
            SyncSwitch("Also Like on YouTube", sync.telegramLikes) { on ->
                change { it.copy(telegramLikes = on) }
                if (!on) app.workScope.launch(Dispatchers.IO) { app.youtubeLibrary.dropWaiting(telegram = true) }
            }
            AnimatedVisibility(sync.playlistsEditable) {
                SyncSwitch("Add to YouTube Playlists", sync.telegramPlaylists) { on -> change { it.copy(telegramPlaylists = on) } }
            }
            AnimatedVisibility(sync.telegramLikes || (sync.playlistsEditable && sync.telegramPlaylists)) {
                SyncSwitch("Ask Me First", sync.askFirst) { on -> change { it.copy(askFirst = on) } }
            }
        }
        syncCard("old_likes", Icons.Rounded.ArrowUpward, Color(0xFF8E8E93), "Old Likes", listOf(
            "Only YouTube songs you liked before.",
            "You'll see how many first."
        )) {
            SyncDivider()
            Box(
                Modifier.fillMaxWidth().heightIn(min = 54.dp).clickable {
                    scope.launch { oldLikes = withContext(Dispatchers.IO) { app.musicRepository.likedYouTubeIds() } }
                },
                contentAlignment = Alignment.CenterStart
            ) { Text("Like My Old Songs on YouTube…", color = AppAccent, fontSize = 16.5.sp) }
        }
        item("end") { Spacer(Modifier.height(12.dp)) }
    }

    oldLikes?.let { ids ->
        if (ids.isEmpty()) AppAlert(
            title = "No Songs to Like",
            message = "None of your Favorites are from YouTube.",
            onDismiss = { oldLikes = null },
            actions = listOf(AlertAction("OK", bold = true) { oldLikes = null })
        ) else AppAlert(
            title = if (ids.size == 1) "Like 1 Song on YouTube?" else "Like ${ids.size} Songs on YouTube?",
            message = "They're added to Liked Songs in ${account.name ?: "your YouTube account"}.",
            onDismiss = { oldLikes = null },
            actions = listOf(
                AlertAction("Cancel") { oldLikes = null },
                AlertAction("Like", bold = true) {
                    oldLikes = null
                    app.workScope.launch(Dispatchers.IO) { app.youtubeLibrary.likeAll(ids) }
                }
            )
        )
    }
}

private fun LazyListScope.syncCard(key: String, icon: ImageVector, tile: Color, title: String, notes: List<String>, content: @Composable ColumnScope.() -> Unit) {
    item(key) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.5.dp).padding(top = 14.dp)
                .clip(RoundedCornerShape(22.dp)).background(GroupCardColor)
                .padding(start = 17.dp, end = 17.dp, top = 17.dp, bottom = 9.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(tile), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = Color.White, modifier = Modifier.size(21.dp))
                }
                Text(title, color = ink, fontSize = 19.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp))
            }
            Column(Modifier.padding(top = 10.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                notes.forEach { Text(it, color = GroupLabelColor, fontSize = 13.5.sp, lineHeight = 18.sp) }
            }
            content()
        }
    }
}

@Composable
private fun SyncDivider() = Spacer(Modifier.fillMaxWidth().height(0.5.dp).background(ink.copy(alpha = 0.12f)))

@Composable
private fun SyncSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Column {
        SyncDivider()
        Row(
            Modifier.fillMaxWidth().heightIn(min = 54.dp).toggleable(checked, role = Role.Switch, onValueChange = onChange),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = ink, fontSize = 16.5.sp, modifier = Modifier.weight(1f).padding(end = 12.dp))
            GroupSwitch(checked, onChange)
        }
    }
}

@Composable
private fun <T> SyncChoice(label: String, options: List<Pair<T, String>>, selected: T, onChange: (T) -> Unit) {
    Column {
        SyncDivider()
        Text(label, color = ink.copy(alpha = 0.85f), fontSize = 14.5.sp, modifier = Modifier.padding(top = 10.dp, bottom = 9.dp))
        SegmentedChoice(options, selected, onChange, Modifier.padding(bottom = 10.dp))
    }
}
