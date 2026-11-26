package com.abn3li.telemusic.data.youtube

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.widget.Toast
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.data.browse.BrowseCollection
import com.abn3li.telemusic.data.browse.BrowseKind
import com.abn3li.telemusic.data.browse.BrowseTrack
import com.abn3li.telemusic.data.browse.InnertubeBrowseClient
import com.abn3li.telemusic.data.local.SongEntity
import com.abn3li.telemusic.data.settings.PlaylistPrivacy
import com.abn3li.telemusic.data.remote.Internet
import com.abn3li.telemusic.data.remote.internet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/** A song for a YouTube playlist: one that's on YouTube, or a Telegram song to find there first. */
data class PlaylistSong(val videoId: String?, val song: SongEntity? = null)

/** A playlist of the signed-in account that songs can be added to. */
data class YouTubePlaylist(val id: String, val title: String)

/**
 * Changes made here that belong to the signed-in YouTube account: likes, and its own playlists.
 * Asks YouTube only when something is tapped. A like made without internet is kept on the phone
 * and sent once the internet is back - nothing runs while there is none waiting.
 */
class YouTubeLibrary internal constructor(private val app: TgMusicApp, private val scope: CoroutineScope) {
    private val session get() = app.youtubeAccount.state.value.sessionId
    private val http by lazy { okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build() }

    /** Off the main thread. Reads which profile is signed in first (a brand account has an id of
     * its own), so a change never lands in another profile's library. */
    private fun client(session: String): InnertubeBrowseClient {
        val web = app.youtubeWebSession.scope(session) ?: throw IOException("YouTube session unavailable")
        return InnertubeBrowseClient(auth = { app.youtubeAccount.authHeadersForSession(session) }, webScope = { web }, client = http)
    }

    private var toast: Toast? = null
    private suspend fun say(message: String) = withContext(Dispatchers.Main) {
        toast?.cancel()
        toast = Toast.makeText(app, message, Toast.LENGTH_SHORT).also { it.show() }
    }

    private fun sayLater(message: String) { scope.launch { say(message) } }

    // ---- Likes ----

    private val sync get() = app.settingsStore.sync.value
    private val likesWaiting by lazy { app.getSharedPreferences("youtube_likes_waiting", Context.MODE_PRIVATE) }
    private val likeLock = Any()
    private val sending = Mutex()
    private var network: ConnectivityManager.NetworkCallback? = null

    private val _waiting = MutableStateFlow(0)
    /** Likes not sent yet. */
    val waiting: StateFlow<Int> = _waiting
    private fun countWaiting() {
        val mine = likesWaiting.getString(SESSION, null) == session
        _waiting.value = if (mine) likesWaiting.all.keys.count { it.startsWith(LIKE) || it.startsWith(TELEGRAM_LIKE) } else 0
    }

    /** Off the main thread: counts again for whoever is signed in now. */
    fun refreshWaiting() = synchronized(likeLock) { countWaiting() }

    /** The star changed on [song]. A YouTube song's is also its like in the account; a Telegram
     * song's only when asked for (its match is looked up first); a local file's stays here.
     * Off the main thread; the choice is kept before this returns, so quick taps stay in order. */
    fun favoriteChanged(song: SongEntity, liked: Boolean) {
        val session = session ?: return
        val videoId = song.youtubeVideoId?.takeIf { it.isNotBlank() }
        val sync = sync
        when {
            videoId != null && sync.likes -> {
                if (liked || sync.unlikes) enqueue(session, LIKE + videoId, liked)
                else { forget(LIKE + videoId); sayLater("Removed from Favorites") }
            }
            videoId == null && song.telegramFileId != 0 && sync.telegramLikes -> telegramFavoriteChanged(session, song, liked)
            else -> sayLater(if (liked) "Added to Favorites" else "Removed from Favorites")
        }
    }

    private fun telegramFavoriteChanged(session: String, song: SongEntity, liked: Boolean) {
        val id = song.telegramMessageId
        if (!liked) {
            // Only a like this account got from the star here is taken back.
            val videoId = matches.getString(LIKED + id, null)?.takeIf { it.substringAfter('\n') == session }?.substringBefore('\n')
            matches.edit().remove(LIKED + id).apply()
            if (videoId != null && sync.unlikes) enqueue(session, TELEGRAM_LIKE + videoId, false)
            else { videoId?.let { forget(TELEGRAM_LIKE + it) }; sayLater("Removed from Favorites") }
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                when (val match = resolve(session, song)) {
                    is Match.Found -> {
                        // Unliked, signed out or switched off while it was looked up.
                        if (this@YouTubeLibrary.session != session || !sync.telegramLikes) return@launch
                        if (app.musicRepository.getSongById(id)?.isFavorite != true) return@launch
                        matches.edit().putString(LIKED + id, match.videoId + "\n" + session).apply()
                        enqueue(session, TELEGRAM_LIKE + match.videoId, true)
                    }
                    Match.NotFound -> say("Added to Favorites. Not found on YouTube")
                    Match.Declined -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w(TAG, "song not looked up: ${e.message}")
                say("Added to Favorites. Couldn't look for it on YouTube")
            }
        }
    }

    /** Likes the YouTube songs liked here before; they're sent one by one like any other. */
    fun likeAll(videoIds: List<String>) {
        val session = session ?: return
        if (videoIds.isEmpty()) return
        synchronized(likeLock) {
            keepOnly(session)
            likesWaiting.edit().apply { videoIds.forEach { putBoolean(LIKE + it, true) } }.apply()
            countWaiting()
        }
        scope.launch(Dispatchers.IO) { sendLikes() }
    }

    /** A switch went off: its likes that weren't sent yet aren't sent any more. */
    fun dropWaiting(telegram: Boolean) = synchronized(likeLock) {
        val prefix = if (telegram) TELEGRAM_LIKE else LIKE
        likesWaiting.edit().apply { likesWaiting.all.keys.filter { it.startsWith(prefix) }.forEach { remove(it) } }.apply()
        countWaiting()
    }

    private fun enqueue(session: String, key: String, liked: Boolean) {
        synchronized(likeLock) {
            keepOnly(session)
            likesWaiting.edit().putBoolean(key, liked).apply()
            countWaiting()
        }
        scope.launch(Dispatchers.IO) { sendLikes() }
    }

    private fun forget(key: String) = synchronized(likeLock) {
        likesWaiting.edit().remove(key).apply()
        countWaiting()
    }

    /** Anything left from the last run (made offline). */
    internal fun resume() {
        scope.launch(Dispatchers.IO) {
            val session = session ?: return@launch
            val waiting = synchronized(likeLock) { keepOnly(session); countWaiting(); nextLike() != null }
            if (!waiting) return@launch
            if (app.internet() == Internet.OK) sendLikes() else waitForInternet()
        }
    }

    /** Likes waiting for another sign-in are never sent to this one. */
    private fun keepOnly(session: String) {
        if (likesWaiting.getString(SESSION, null) != session) likesWaiting.edit().clear().putString(SESSION, session).apply()
    }

    private fun nextLike(): Pair<String, Boolean>? = likesWaiting.all.entries
        .firstOrNull { it.key.startsWith(LIKE) || it.key.startsWith(TELEGRAM_LIKE) }?.let { it.key to (it.value as Boolean) }

    private suspend fun sendLikes() = sending.withLock {
        var sent = 0
        var refused = 0
        var last = true
        while (true) {
            val session = session ?: return@withLock
            val (key, liked) = synchronized(likeLock) {
                if (likesWaiting.getString(SESSION, null) == session) nextLike() else null
            } ?: break
            try {
                client(session).setSongLiked(key.substringAfter(':'), liked)
                sent++
                last = liked
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                waitForInternet()
                say(if (app.internet() == Internet.OK) "YouTube isn't answering. Your likes will be sent later" else "Saved. YouTube will update when you're online")
                return@withLock
            } catch (e: Exception) {
                android.util.Log.w(TAG, "like not accepted: ${e.message}")
                // Not about this song (too many at once, signed out there, or YouTube is down): they all wait.
                if (TRY_LATER.containsMatchIn(e.message.orEmpty())) {
                    say("YouTube isn't taking likes now. They'll be sent later")
                    return@withLock
                }
                refused++
            }
            // Tapped again meanwhile: the newer choice is still waiting, and goes next.
            val more = synchronized(likeLock) {
                if (likesWaiting.getBoolean(key, !liked) == liked) likesWaiting.edit().remove(key).apply()
                countWaiting()
                _waiting.value > 0
            }
            // A long list (old likes) isn't sent in one burst.
            if (more) kotlinx.coroutines.delay(250)
        }
        when {
            refused > 0 && sent == 0 -> say("Saved on this phone. YouTube couldn't be updated")
            refused > 0 -> say("$refused of ${sent + refused} couldn't be updated on YouTube")
            sent > 1 -> say("YouTube is up to date")
            sent == 1 -> say(if (last) "Added to Favorites and YouTube" else "Removed from Favorites and YouTube")
        }
    }

    /** Sends once when the internet is back. With internet already there and YouTube out of reach,
     * the next like or the next start tries again instead. */
    private fun waitForInternet() {
        if (app.internet() == Internet.OK) return
        val manager = app.getSystemService(ConnectivityManager::class.java) ?: return
        synchronized(likeLock) {
            if (network != null) return
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(changed: Network, capabilities: NetworkCapabilities) {
                    if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return
                    synchronized(likeLock) {
                        if (network !== this) return
                        network = null
                    }
                    runCatching { manager.unregisterNetworkCallback(this) }
                    scope.launch(Dispatchers.IO) { sendLikes() }
                }
            }
            network = callback
            runCatching { manager.registerDefaultNetworkCallback(callback) }.onFailure { network = null }
        }
    }

    // ---- The same song on YouTube ----

    private sealed interface Match {
        class Found(val videoId: String) : Match
        data object NotFound : Match
        data object Declined : Match
    }

    /** "Is this the same song?", shown over the app until answered. */
    class Ask internal constructor(val session: String, val song: SongEntity, val found: BrowseTrack) {
        internal val answered = CompletableDeferred<Boolean>()
        fun answer(same: Boolean) { answered.complete(same) }
    }

    private val _asks = MutableStateFlow<List<Ask>>(emptyList())
    val asks: StateFlow<List<Ask>> = _asks

    private val matcher by lazy { YouTubeSongMatcher() }
    private val looking = Mutex()
    // Songs already matched, and which of them were liked on YouTube because of their star here.
    private val matches by lazy { app.getSharedPreferences("youtube_matches", Context.MODE_PRIVATE) }

    /** Off the main thread. Throws when YouTube couldn't be asked. */
    private suspend fun resolve(session: String, song: SongEntity): Match {
        val key = MATCH + song.telegramMessageId
        // A song renamed since is looked up again.
        val name = song.title + "\n" + song.artist
        matches.getString(key, null)?.let { known ->
            if (known.substringAfter('\n') == name) return Match.Found(known.substringBefore('\n'))
        }
        val found = looking.withLock { matcher.find(song.title, song.artist, song.durationSeconds) } ?: return Match.NotFound
        if (sync.askFirst) {
            val ask = Ask(session, song, found)
            _asks.update { it + ask }
            val same = try { ask.answered.await() } finally { _asks.update { it - ask } }
            if (!same) return Match.Declined
        }
        matches.edit().putString(key, found.videoId + "\n" + name).apply()
        return Match.Found(found.videoId)
    }

    // ---- Playlists ----

    private val _playlists = MutableStateFlow<Pair<String, List<YouTubePlaylist>>?>(null)
    /** The account's own playlists as last read, with the session they belong to. */
    val playlists: StateFlow<Pair<String, List<YouTubePlaylist>>?> = _playlists

    /** Asked when a song's Add to Playlist opens; a failure keeps the list already there. The list
     * is the same for any song, so one that isn't known on YouTube yet asks with another. */
    suspend fun refreshPlaylists(videoId: String?) {
        val session = session ?: return
        try {
            val list = withContext(Dispatchers.IO) { client(session).editablePlaylists(videoId ?: ANY_VIDEO) }
            if (this.session == session) _playlists.value = session to list
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "playlists not read: ${e.message}")
        }
    }

    fun create(name: String, songs: List<PlaylistSong> = emptyList()) = change("Couldn't create the playlist on YouTube") { session ->
        val (ids, missing) = videoIds(session, songs)
        if (songs.isNotEmpty() && ids.isEmpty()) return@change nothingAdded(missing)
        if (this.session != session) return@change null
        val id = client(session).createPlaylist(name, ids, sync.privacy)
        if (this.session == session) {
            _playlists.value?.takeIf { it.first == session }?.let { _playlists.value = session to (listOf(YouTubePlaylist(id, name)) + it.second) }
            app.discoveryRepository.accountPlaylistAdded(session,
                BrowseCollection("VL$id", null, name, "YouTube Music", null, BrowseKind.PLAYLIST))
        }
        if (songs.isEmpty()) "Created $name on YouTube" else added(name, missing)
    }

    fun add(playlist: YouTubePlaylist, songs: List<PlaylistSong>) = change("Couldn't add to ${playlist.title} on YouTube") { session ->
        val (ids, missing) = videoIds(session, songs)
        if (ids.isEmpty()) return@change nothingAdded(missing)
        if (this.session != session) return@change null
        client(session).addToPlaylist(playlist.id, ids)
        added(playlist.title, missing)
    }

    /** The songs' ids on YouTube, in order, and how many weren't found there. */
    private suspend fun videoIds(session: String, songs: List<PlaylistSong>): Pair<List<String>, Int> {
        var missing = 0
        val ids = songs.mapNotNull { item ->
            item.videoId ?: when (val match = resolve(session, item.song ?: return@mapNotNull null)) {
                is Match.Found -> match.videoId
                Match.NotFound -> { missing++; null }
                Match.Declined -> null
            }
        }
        return ids.distinct() to missing
    }

    private fun nothingAdded(missing: Int) = if (missing == 0) null else "Not found on YouTube"
    private fun added(name: String, missing: Int) = "Added to $name on YouTube" + if (missing > 0) ". $missing not found there" else ""

    private fun change(failed: String, work: suspend (String) -> String?) {
        val session = session ?: return
        scope.launch(Dispatchers.IO) {
            val message = try { work(session) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                android.util.Log.w(TAG, "playlist not changed: ${e.message}")
                failed
            }
            message?.let { say(it) }
        }
    }

    // ---- A whole playlist ----

    /** Saves [playlist] (someone else's) in the account's library, or takes it out. True once done. */
    suspend fun setSaved(playlist: BrowseCollection, saved: Boolean): Boolean = playlistChange(
        if (saved) "Saved to your YouTube library" else "Removed from your YouTube library", "Couldn't change your YouTube library"
    ) { session ->
        client(session).setPlaylistSaved(playlist.browseId.removePrefix("VL"), saved)
        if (saved) app.discoveryRepository.accountPlaylistAdded(session, playlist)
        else app.discoveryRepository.accountPlaylistRemoved(session, playlist.browseId)
    }

    suspend fun rename(browseId: String, title: String): Boolean = playlistChange("Renamed to $title", "Couldn't rename it on YouTube") { session ->
        val id = browseId.removePrefix("VL")
        client(session).renamePlaylist(id, title)
        app.discoveryRepository.accountPlaylistRenamed(session, browseId, title)
        _playlists.value?.takeIf { it.first == session }?.let { (s, list) ->
            _playlists.value = s to list.map { if (it.id == id) it.copy(title = title) else it }
        }
    }

    suspend fun setPrivacy(browseId: String, privacy: PlaylistPrivacy): Boolean = playlistChange(
        when (privacy) {
            PlaylistPrivacy.PRIVATE -> "Only you can see it now"
            PlaylistPrivacy.UNLISTED -> "Anyone with the link can see it now"
            PlaylistPrivacy.PUBLIC -> "Everyone can see it now"
        }, "Couldn't change who can see it"
    ) { session -> client(session).setPlaylistPrivacy(browseId.removePrefix("VL"), privacy) }

    suspend fun delete(browseId: String): Boolean = playlistChange("Deleted from YouTube", "Couldn't delete it on YouTube") { session ->
        val id = browseId.removePrefix("VL")
        client(session).deletePlaylist(id)
        app.discoveryRepository.accountPlaylistRemoved(session, browseId)
        _playlists.value?.takeIf { it.first == session }?.let { (s, list) -> _playlists.value = s to list.filterNot { it.id == id } }
    }

    private suspend fun playlistChange(done: String, failed: String, work: (String) -> Unit): Boolean {
        val session = session ?: return false
        return try {
            withContext(Dispatchers.IO) { work(session) }
            say(done)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "playlist not changed: ${e.message}")
            say(failed)
            false
        }
    }

    /** True once YouTube took the song out; says so when it couldn't. */
    suspend fun remove(browseId: String, videoId: String, setVideoId: String): Boolean {
        val session = session ?: return false
        return try {
            withContext(Dispatchers.IO) { client(session).removeFromPlaylist(browseId.removePrefix("VL"), videoId, setVideoId) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "song not removed: ${e.message}")
            say("Couldn't remove it on YouTube")
            false
        }
    }

    private companion object {
        const val TAG = "YouTubeLibrary"
        const val SESSION = "session"
        const val LIKE = "like:"
        const val TELEGRAM_LIKE = "telegram:"
        const val MATCH = "match:"
        const val LIKED = "liked:"
        const val ANY_VIDEO = "dQw4w9WgXcQ"
        val TRY_LATER = Regex("""HTTP (401|408|429|5\d\d)""")
    }
}
