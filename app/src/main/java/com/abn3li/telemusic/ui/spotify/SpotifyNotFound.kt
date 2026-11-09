package com.abn3li.telemusic.ui.spotify

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.data.browse.BrowseTrack
import com.abn3li.telemusic.data.local.SpotifyMissingEntity
import com.abn3li.telemusic.ui.library.AlertAction
import com.abn3li.telemusic.ui.library.AlertTextField
import com.abn3li.telemusic.ui.library.AppAccent
import com.abn3li.telemusic.ui.library.AppAlert
import com.abn3li.telemusic.ui.theme.ink
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Under a playlist's Play / Shuffle: how many songs its Spotify import couldn't find. */
@Composable
fun NotFoundButton(count: Int, onClick: () -> Unit) {
    Text(
        "$count ${if (count == 1) "Song" else "Songs"} Not Found  ›",
        color = AppAccent,
        fontSize = 15.sp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center
    )
}

/**
 * The songs a Spotify import couldn't find, each with a search: pick the right one and it joins
 * the playlist in its place; Skip drops a song that isn't on YouTube Music at all.
 */
@Composable
fun NotFoundSongs(rows: List<SpotifyMissingEntity>, onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as TgMusicApp
    val scope = rememberCoroutineScope()
    var searching by remember { mutableStateOf<SpotifyMissingEntity?>(null) }

    AppAlert(
        title = "${rows.size} Not Found",
        message = "These songs aren't on YouTube Music under the same name. Search for each one and pick it.",
        onDismiss = onDismiss,
        actions = listOf(AlertAction("Done", bold = true, onClick = onDismiss))
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
        ) {
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(row.title, color = ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(row.artists, color = ink.copy(alpha = 0.55f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("Skip", color = ink.copy(alpha = 0.5f), fontSize = 14.sp,
                        modifier = Modifier.clickable { scope.launch { app.spotifyImporter.skipMissing(row) } }.padding(8.dp))
                    Icon(Icons.Rounded.Search, contentDescription = "Search for ${row.title}", tint = AppAccent,
                        modifier = Modifier.clickable { searching = row }.padding(8.dp).size(22.dp))
                }
            }
        }
    }

    searching?.let { row -> FindSong(row, onDone = { searching = null }) }
}

/** A YouTube Music search for one not-found song, starting from its name and artist. */
@Composable
private fun FindSong(row: SpotifyMissingEntity, onDone: () -> Unit) {
    val app = LocalContext.current.applicationContext as TgMusicApp
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember(row.id) { mutableStateOf("${row.title} ${row.artists.substringBefore(',')}".trim()) }
    var results by remember(row.id) { mutableStateOf<List<BrowseTrack>?>(null) }
    var adding by remember(row.id) { mutableStateOf(false) }

    // Searches as you type, after a short pause so each keystroke doesn't send a request.
    LaunchedEffect(query) {
        if (query.isBlank()) { results = emptyList(); return@LaunchedEffect }
        delay(400)
        results = null
        results = app.spotifyImporter.searchForMissing(query)
    }

    AppAlert(
        title = row.title,
        message = "Spotify: ${row.artists}" + if (row.durationMs > 0) " · ${duration(row.durationMs / 1000)}" else "",
        onDismiss = onDone,
        actions = listOf(AlertAction("Cancel", onClick = onDone))
    ) {
        AlertTextField(query, { query = it }, "Search YouTube Music")
        Box(Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 320.dp)) {
            val found = results
            when {
                found == null || adding -> CircularProgressIndicator(
                    color = AppAccent, strokeWidth = 2.5.dp, modifier = Modifier.align(Alignment.Center).size(24.dp)
                )
                found.isEmpty() -> Text("Nothing found", color = ink.copy(alpha = 0.5f), fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.Center))
                else -> Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    found.forEach { track ->
                        Column(Modifier.fillMaxWidth().clickable(enabled = !adding) {
                            adding = true
                            scope.launch {
                                runCatching { app.spotifyImporter.resolveMissing(row, track) }
                                    .onSuccess { Toast.makeText(context, "Added \"${row.title}\"", Toast.LENGTH_SHORT).show(); onDone() }
                                    .onFailure { adding = false; Toast.makeText(context, "Couldn't add it - try again", Toast.LENGTH_SHORT).show() }
                            }
                        }.padding(vertical = 8.dp)) {
                            Text(track.title, color = ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(track.artist.takeIf { it.isNotBlank() }, track.durationSeconds.takeIf { it > 0 }?.let { duration(it.toLong()) })
                                    .joinToString(" · "),
                                color = ink.copy(alpha = 0.55f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun duration(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)
