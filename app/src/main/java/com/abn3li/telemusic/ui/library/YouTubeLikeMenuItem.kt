package com.abn3li.telemusic.ui.library

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ThumbUp
import com.abn3li.telemusic.TgMusicApp
import kotlinx.coroutines.CancellationException

internal data class YouTubeLikeAction(val label: String, val perform: () -> Unit)

@Composable
internal fun rememberYouTubeLikeAction(videoId: String?): YouTubeLikeAction? {
    val app = LocalContext.current.applicationContext as? TgMusicApp ?: return null
    val account by app.youtubeAccount.state.collectAsState()
    val session = account.sessionId
    if (videoId.isNullOrBlank() || !account.signedIn || session == null) return null
    val states by app.youtubeLikeStates.collectAsState()
    // Like BitChord, unknown tracks start neutral; refresh silently without a loading row.
    val liked = states[session to videoId] ?: false
    LaunchedEffect(videoId, session) {
        try { app.youtubeSongLiked(videoId, session) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Preserve the last-known state while offline. */ }
    }
    return YouTubeLikeAction(
        label = if (liked) "Remove from YouTube Liked Songs" else "Add to YouTube Liked Songs",
        perform = { app.setYouTubeSongLiked(videoId, !liked, session) }
    )
}

@Composable
internal fun YouTubeLikeMenuItem(videoId: String?, onAction: (() -> Unit) -> Unit) {
    val action = rememberYouTubeLikeAction(videoId) ?: return
    CardDivider()
    CardItem(action.label, Icons.Rounded.ThumbUp) { onAction(action.perform) }
}
