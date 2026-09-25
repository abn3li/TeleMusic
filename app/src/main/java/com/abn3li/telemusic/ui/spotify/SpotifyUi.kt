package com.abn3li.telemusic.ui.spotify

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.data.local.SpotifyLinkCount
import com.abn3li.telemusic.data.local.SpotifyLinkEntity
import com.abn3li.telemusic.data.spotify.SpotifyPlaylist
import com.abn3li.telemusic.repository.SpotifyImportState
import com.abn3li.telemusic.repository.SpotifyImporter
import com.abn3li.telemusic.ui.library.AlertAction
import com.abn3li.telemusic.ui.library.AppAccent
import com.abn3li.telemusic.ui.library.AppAlert
import com.abn3li.telemusic.ui.library.CoverTile
import com.abn3li.telemusic.ui.library.DestructiveRed
import com.abn3li.telemusic.ui.library.GroupActionRow
import com.abn3li.telemusic.ui.library.GroupCard
import com.abn3li.telemusic.ui.library.GroupDivider
import com.abn3li.telemusic.ui.library.GroupFooter
import com.abn3li.telemusic.ui.library.GroupHeader
import com.abn3li.telemusic.ui.library.GroupIcon
import com.abn3li.telemusic.ui.library.GroupRow
import com.abn3li.telemusic.ui.library.GroupTextField
import com.abn3li.telemusic.ui.library.GroupValue
import com.abn3li.telemusic.ui.library.LargeTitleList

private val SpotifyGreen = Color(0xFF1DB954)
private val LikedGradient = Brush.linearGradient(listOf(Color(0xFF4B2BB8), Color(0xFF8FC9C0)))

/** One thing you can import: your Liked Songs, or one of your playlists. */
private sealed interface SpotifyItem {
    val key: String
    val title: String
    val total: Int?
    data class Liked(override val total: Int?) : SpotifyItem {
        override val key = SpotifyImporter.LIKED_KEY
        override val title = "Liked Songs"
    }
    data class Playlist(val playlist: SpotifyPlaylist) : SpotifyItem {
        override val key = "playlist:${playlist.id}"
        override val title = playlist.name
        override val total: Int = playlist.total
    }
}

/**
 * Settings → Spotify. Signed in: your account and a "Playlists" row that opens
 * [SpotifyLibraryScreen]. Not signed in: Client ID, Connect and how to get a Client ID.
 */
@Composable
fun SpotifySettingsGroup(onOpenPlaylists: () -> Unit, onHelp: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as TgMusicApp
    val account = app.spotifyAccount
    val accountName by account.accountName.collectAsState()
    val library by account.library.collectAsState()
    var clientId by remember { mutableStateOf(account.clientId) }

    if (accountName != null) {
        GroupCard {
            GroupRow(
                title = "Account",
                icon = { SpotifyLogo(29.dp) },
                trailing = { GroupValue(accountName, showChevron = false) }
            )
            GroupDivider(start = 57.dp)
            GroupRow(
                title = "Playlists",
                icon = { GroupIcon(Icons.AutoMirrored.Rounded.QueueMusic, AppAccent) },
                onClick = onOpenPlaylists,
                trailing = { GroupValue(if (library.loaded) (library.playlists.size + 1).toString() else null) }
            )
        }
        GroupFooter("Import your Liked Songs and playlists as TeleMusic playlists.")
    } else {
        GroupCard {
            GroupTextField(
                value = clientId,
                onValueChange = { clientId = it.trim(); account.clientId = it },
                placeholder = "Paste your Client ID",
                label = "Client ID"
            )
            GroupDivider()
            GroupActionRow("Connect Spotify", enabled = clientId.isNotBlank()) {
                account.clientId = clientId
                account.startSignIn(context)
            }
            GroupDivider()
            GroupRow(title = "How to get a Client ID", onClick = onHelp, trailing = { GroupValue(null) })
        }
        GroupFooter("Create a free app at developer.spotify.com (Dashboard → Create app). Set its Redirect URI to telemusic://spotify, tick Web API, and add your Spotify email under User Management. Then paste its Client ID here. TeleMusic can only read your Liked Songs and playlists.")
    }
}

/** Settings › Spotify: your Spotify library as a minimal list, with Import / Update per row. */
@Composable
fun SpotifyLibraryScreen(onBack: () -> Unit, onOpenPlaylist: (Long, String) -> Unit) {
    val app = LocalContext.current.applicationContext as TgMusicApp
    val account = app.spotifyAccount
    val accountName by account.accountName.collectAsState()
    val library by account.library.collectAsState()
    val importState = app.spotifyImporter.state.collectAsState()
    // Changes only when an import starts or ends - not on every progress step.
    val busy by remember { derivedStateOf { importState.value is SpotifyImportState.Running } }
    val linkCounts by remember { app.spotifyDao.observeLinkCounts() }.collectAsState(initial = emptyList())
    val linkByKey = remember(linkCounts) { linkCounts.associateBy { it.sourceKey } }
    var filter by remember { mutableStateOf("") }
    var importChoice by remember { mutableStateOf<SpotifyItem?>(null) }
    var confirmSignOut by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { account.loadLibrary() }
    // Signed out (here or because Spotify revoked access): nothing to show on this page.
    LaunchedEffect(accountName) { if (accountName == null) onBack() }

    val items = remember(library, filter) {
        val all = listOf<SpotifyItem>(SpotifyItem.Liked(library.likedTotal)) + library.playlists.map { SpotifyItem.Playlist(it) }
        if (filter.isBlank()) all else all.filter { it.title.contains(filter.trim(), ignoreCase = true) }
    }

    LargeTitleList(title = "Spotify", onBack = onBack) {
        item("search") {
            GroupCard(Modifier.padding(top = 4.dp)) {
                GroupTextField(value = filter, onValueChange = { filter = it }, placeholder = "Search")
            }
        }
        item("library") {
            GroupHeader("Your Spotify Library")
            GroupCard {
                when {
                    library.loading && library.playlists.isEmpty() -> GroupRow(title = "Loading…", titleColor = Color.White.copy(alpha = 0.5f))
                    library.error != null -> GroupRow(
                        title = library.error.orEmpty(),
                        desc = "Tap to try again",
                        titleColor = Color.White.copy(alpha = 0.7f),
                        onClick = { account.loadLibrary(force = true) }
                    )
                    items.isEmpty() -> GroupRow(title = "No matches", titleColor = Color.White.copy(alpha = 0.5f))
                    else -> items.forEachIndexed { index, item ->
                        if (index > 0) GroupDivider(start = 64.dp)
                        val link = linkByKey[item.key]
                        LibraryRow(
                            item = item,
                            link = link,
                            importState = importState,
                            busy = busy,
                            onOpen = { link?.let { onOpenPlaylist(it.playlistId, it.name) } ?: run { importChoice = item } },
                            onImport = { importChoice = item },
                            onUpdate = {
                                link?.let { app.spotifyImporter.update(SpotifyLinkEntity(it.sourceKey, it.playlistId, it.name, 0L)) }
                            }
                        )
                    }
                }
            }
        }
        item("library_note") {
            GroupFooter("Imported playlists never change on their own - tap Update to add songs that are new on Spotify.")
        }
        item("account") {
            GroupHeader("Account")
            GroupCard {
                GroupRow(title = "Client ID", trailing = { GroupValue(maskedClientId(account.clientId), showChevron = false) })
                GroupDivider()
                GroupActionRow("Sign Out", color = DestructiveRed) { confirmSignOut = true }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    importChoice?.let { item ->
        val dismiss = { importChoice = null }
        AppAlert(
            title = item.title,
            message = "Songs are matched on YouTube Music and added as a TeleMusic playlist that streams; download them anytime.",
            onDismiss = dismiss,
            actions = listOf(
                AlertAction("Import", bold = true) { dismiss(); startImport(app, item, downloadAll = false) },
                AlertAction("Import and Download All") { dismiss(); startImport(app, item, downloadAll = true) },
                AlertAction("Cancel", onClick = dismiss)
            )
        )
    }
    if (confirmSignOut) {
        AppAlert(
            title = "Sign out of Spotify?",
            message = "Imported playlists stay in TeleMusic.",
            onDismiss = { confirmSignOut = false },
            actions = listOf(
                AlertAction("Cancel") { confirmSignOut = false },
                AlertAction("Sign Out", destructive = true) { confirmSignOut = false; account.signOut() }
            )
        )
    }
}

private fun startImport(app: TgMusicApp, item: SpotifyItem, downloadAll: Boolean) {
    when (item) {
        is SpotifyItem.Liked -> app.spotifyImporter.importLiked(downloadAll)
        is SpotifyItem.Playlist -> app.spotifyImporter.importPlaylist(item.playlist, downloadAll)
    }
}

private fun maskedClientId(id: String): String =
    if (id.length <= 8) id else "${id.take(4)}…${id.takeLast(4)}"

@Composable
private fun LibraryRow(
    item: SpotifyItem,
    link: SpotifyLinkCount?,
    importState: State<SpotifyImportState>,
    busy: Boolean,
    onOpen: () -> Unit,
    onImport: () -> Unit,
    onUpdate: () -> Unit
) {
    // Only the row being imported follows the progress; the others don't redraw per step.
    val progressState = remember(item.key, importState) {
        derivedStateOf { (importState.value as? SpotifyImportState.Running)?.takeIf { it.key == item.key } }
    }
    val progress = progressState.value
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = progress == null, onClick = onOpen)
            .padding(horizontal = 15.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Cover(item, 36.dp)
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val subtitle = when {
                progress != null && progress.total == 0 -> "Reading from Spotify…"
                progress != null -> "Importing ${progress.done} of ${progress.total}"
                link != null && item.total != null -> "${item.total} songs · ${link.count} in TeleMusic"
                link != null -> "${link.count} in TeleMusic"
                item.total != null -> "${item.total} songs"
                else -> "Spotify"
            }
            Text(subtitle, color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (progress != null && progress.total > 0) {
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF3A3A3C))) {
                    Box(Modifier.fillMaxWidth(progress.done.toFloat() / progress.total).height(3.dp).background(AppAccent))
                }
            }
        }
        if (progress == null) {
            Spacer(Modifier.width(10.dp))
            if (link != null) {
                Text(
                    "Update",
                    color = if (busy) AppAccent.copy(alpha = 0.4f) else AppAccent,
                    fontSize = 15.sp,
                    modifier = Modifier.clip(CircleShape).clickable(enabled = !busy, onClick = onUpdate).padding(horizontal = 6.dp, vertical = 4.dp)
                )
            } else {
                Text(
                    "Import",
                    color = if (busy) AppAccent.copy(alpha = 0.4f) else AppAccent,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clip(CircleShape)
                        .border(1.dp, AppAccent.copy(alpha = if (busy) 0.25f else 0.55f), CircleShape)
                        .clickable(enabled = !busy, onClick = onImport)
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun Cover(item: SpotifyItem, size: Dp) {
    when (item) {
        is SpotifyItem.Liked -> Box(
            Modifier.size(size).clip(RoundedCornerShape(5.dp)).background(LikedGradient),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Rounded.Favorite, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.45f)) }
        is SpotifyItem.Playlist -> CoverTile(item.playlist.imageUrl, Modifier.size(size), corner = 5, placeholder = Icons.AutoMirrored.Rounded.QueueMusic)
    }
}

/** Spotify's mark: a green circle with three curved lines. */
@Composable
private fun SpotifyLogo(size: Dp) {
    Canvas(Modifier.size(size)) {
        drawCircle(SpotifyGreen)
        val w = this.size.width
        val stroke = w * 0.075f
        listOf(0.30f to 0.62f, 0.45f to 0.50f, 0.59f to 0.38f).forEach { (y, width) ->
            val arcWidth = w * width
            drawArc(
                color = Color.Black,
                startAngle = 200f, sweepAngle = 140f, useCenter = false,
                topLeft = Offset((w - arcWidth) / 2f, w * y),
                size = Size(arcWidth, arcWidth * 0.55f),
                style = Stroke(width = stroke * (0.8f + width * 0.4f), cap = StrokeCap.Round)
            )
        }
    }
}
