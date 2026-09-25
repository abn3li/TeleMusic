package com.abn3li.telemusic.data.spotify

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/** One Spotify track, from your account or from a public link. */
data class SpotifyTrack(val id: String, val title: String, val artists: String, val durationMs: Long)

/** A playlist in your Spotify library. */
data class SpotifyPlaylist(val id: String, val name: String, val imageUrl: String?, val total: Int, val owner: String?)

/** What Home and the Spotify page show: Liked Songs count plus your playlists. */
data class SpotifyLibrary(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val likedTotal: Int? = null,
    val playlists: List<SpotifyPlaylist> = emptyList(),
    val error: String? = null
)

class SpotifyException(message: String, val code: Int = 0) : Exception(message)

/**
 * Sign-in with your own Spotify developer app (its Client ID), using the phone-app flow (PKCE):
 * no secret is stored or needed. Access is read-only - Liked Songs and playlists. Tokens are kept
 * encrypted on the phone and refreshed only when a request needs one; nothing here runs in the
 * background.
 */
class SpotifyAccount(context: Context, private val scope: CoroutineScope) {
    private val appContext = context.applicationContext
    // Opened the first time Spotify is actually used, not at app start - setting up the
    // encrypted store costs ~100 ms of CPU, which every launch would pay for nothing.
    private val prefs by lazy {
        EncryptedSharedPreferences.create(
            appContext, "spotify_account",
            MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val tokenLock = Mutex()

    /** Display name while signed in, else null. */
    private val _accountName by lazy { MutableStateFlow(if (prefs.contains(KEY_REFRESH)) prefs.getString(KEY_NAME, "Spotify") else null) }
    val accountName: StateFlow<String?> get() = _accountName

    private val _library = MutableStateFlow(SpotifyLibrary())
    val library: StateFlow<SpotifyLibrary> = _library

    var clientId: String
        get() = prefs.getString(KEY_CLIENT_ID, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_CLIENT_ID, value.trim()).apply()

    val isConnected: Boolean get() = _accountName.value != null

    // ---- Sign-in ----

    /** Opens Spotify's login page in the browser. False when there's no Client ID yet. */
    fun startSignIn(context: Context): Boolean {
        val id = clientId
        if (id.isBlank()) return false
        val verifier = randomUrlSafe(64)
        val state = randomUrlSafe(16)
        prefs.edit().putString(KEY_VERIFIER, verifier).putString(KEY_STATE, state).apply()
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val uri = Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", id)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("state", state)
            .build()
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    /** Spotify sent the browser back to telemusic://spotify: finish signing in, then say so. */
    fun completeSignIn(redirect: Uri) {
        scope.launch {
            val message = try {
                val error = redirect.getQueryParameter("error")
                if (error != null) throw SpotifyException(if (error == "access_denied") "Spotify sign-in was cancelled" else "Spotify said: $error")
                val code = redirect.getQueryParameter("code") ?: throw SpotifyException("Spotify didn't return a sign-in code")
                if (redirect.getQueryParameter("state") != prefs.getString(KEY_STATE, null)) throw SpotifyException("Sign-in expired - try again")
                val verifier = prefs.getString(KEY_VERIFIER, null) ?: throw SpotifyException("Sign-in expired - try again")
                val tokens = postToken(
                    FormBody.Builder()
                        .add("grant_type", "authorization_code")
                        .add("code", code)
                        .add("redirect_uri", REDIRECT_URI)
                        .add("client_id", clientId)
                        .add("code_verifier", verifier)
                        .build()
                )
                saveTokens(tokens)
                prefs.edit().remove(KEY_VERIFIER).remove(KEY_STATE).apply()
                val me = getJson("https://api.spotify.com/v1/me")
                val name = me.optString("display_name").ifBlank { me.optString("id").ifBlank { "Spotify" } }
                prefs.edit().putString(KEY_NAME, name).apply()
                _accountName.value = name
                "Connected to Spotify as $name"
            } catch (e: Exception) {
                Log.w(TAG, "Spotify sign-in failed", e)
                e.message ?: "Spotify sign-in failed"
            }
            withContext(Dispatchers.Main) { Toast.makeText(appContext, message, Toast.LENGTH_LONG).show() }
        }
    }

    fun signOut() {
        prefs.edit().remove(KEY_ACCESS).remove(KEY_EXPIRES).remove(KEY_REFRESH).remove(KEY_NAME).apply()
        _accountName.value = null
        _library.value = SpotifyLibrary()
    }

    // ---- Library ----

    /** Loads Liked Songs' count and your playlists - once per app run unless [force]. */
    fun loadLibrary(force: Boolean = false) {
        val current = _library.value
        if (!isConnected || current.loading || (current.loaded && !force)) return
        _library.update { it.copy(loading = true, error = null) }
        scope.launch {
            try {
                val liked = getJson("https://api.spotify.com/v1/me/tracks?limit=1").optInt("total")
                val playlists = mutableListOf<SpotifyPlaylist>()
                var url: String? = "https://api.spotify.com/v1/me/playlists?limit=50"
                while (url != null) {
                    val page = getJson(url)
                    val items = page.optJSONArray("items")
                    for (i in 0 until (items?.length() ?: 0)) {
                        val p = items!!.optJSONObject(i) ?: continue
                        val id = p.optString("id")
                        if (id.isBlank()) continue
                        val counts = p.optJSONObject("tracks") ?: p.optJSONObject("items")
                        playlists += SpotifyPlaylist(
                            id = id,
                            name = p.optString("name").ifBlank { "Playlist" },
                            imageUrl = p.optJSONArray("images")?.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() },
                            total = counts?.optInt("total") ?: 0,
                            owner = p.optJSONObject("owner")?.optString("display_name")?.takeIf { it.isNotBlank() }
                        )
                    }
                    url = page.optString("next").takeIf { it.isNotBlank() && it != "null" }
                }
                _library.value = SpotifyLibrary(loaded = true, likedTotal = liked, playlists = playlists)
            } catch (e: Exception) {
                Log.w(TAG, "Loading Spotify library failed", e)
                _library.value = SpotifyLibrary(loaded = false, error = e.message ?: "Couldn't reach Spotify")
            }
        }
    }

    /** Every song in your Liked Songs, newest first. */
    suspend fun likedTracks(): List<SpotifyTrack> =
        pagedTracks("https://api.spotify.com/v1/me/tracks?limit=50")

    /** Every song in a playlist, in the playlist's order. Spotify is replacing the old
     * /playlists/{id}/tracks address with /playlists/{id}/items and refuses newer developer apps
     * on the old one - so the new one is asked first, the old one only if that isn't available. */
    suspend fun playlistTracks(playlistId: String): List<SpotifyTrack> {
        val first = try {
            return pagedTracks("https://api.spotify.com/v1/playlists/$playlistId/items?limit=100&additional_types=track")
        } catch (e: SpotifyException) {
            if (e.code != 403 && e.code != 404) throw e
            Log.w(TAG, "playlist $playlistId /items refused (${e.code}): ${e.message}")
            e
        }
        return try {
            pagedTracks("https://api.spotify.com/v1/playlists/$playlistId/tracks?limit=100&additional_types=track")
        } catch (e: SpotifyException) {
            Log.w(TAG, "playlist $playlistId /tracks refused (${e.code}): ${e.message}")
            // Both refused: say what Spotify said rather than guess.
            throw if (e.code == 403 || e.code == 404) SpotifyException("Spotify won't share this playlist's songs: ${first.message}", e.code) else e
        }
    }

    private suspend fun pagedTracks(firstUrl: String): List<SpotifyTrack> {
        val tracks = mutableListOf<SpotifyTrack>()
        var url: String? = firstUrl
        while (url != null) {
            val page = getJson(url)
            val items = page.optJSONArray("items")
            for (i in 0 until (items?.length() ?: 0)) {
                // "track" in the old reply format, "item" in the new one.
                val entry = items!!.optJSONObject(i) ?: continue
                val t = entry.optJSONObject("track") ?: entry.optJSONObject("item") ?: continue
                if (t.optString("type", "track") != "track" || t.optBoolean("is_local")) continue
                val title = t.optString("name")
                if (title.isBlank()) continue
                val artists = t.optJSONArray("artists")?.let { a ->
                    (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
                }.orEmpty().joinToString(", ")
                tracks += SpotifyTrack(t.optString("id"), title, artists, t.optLong("duration_ms"))
            }
            url = page.optString("next").takeIf { it.isNotBlank() && it != "null" }
        }
        return tracks
    }

    // ---- HTTP ----

    private suspend fun getJson(url: String): JSONObject {
        var refreshed = false
        var waits = 0
        while (true) {
            val token = accessToken(forceRefresh = false)
            val (code, body, retryAfter) = withContext(Dispatchers.IO) {
                val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
                http.newCall(request).execute().use { r ->
                    Triple(r.code, r.body?.string().orEmpty(), r.header("Retry-After")?.toLongOrNull())
                }
            }
            when {
                code in 200..299 -> return JSONObject(body.ifBlank { "{}" })
                code == 401 && !refreshed -> { refreshed = true; accessToken(forceRefresh = true) }
                // Rate limited: wait what Spotify asks (a few seconds), a few times at most.
                code == 429 && waits < 3 -> { waits++; delay(((retryAfter ?: 2L).coerceIn(1L, 30L)) * 1000L) }
                else -> throw SpotifyException(errorMessage(code, body), code)
            }
        }
    }

    private fun errorMessage(code: Int, body: String): String {
        val detail = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull()
        return when (code) {
            401 -> "Spotify sign-in expired - connect again in Settings"
            403 -> detail?.takeIf { it.isNotBlank() }?.let { "Spotify refused: $it" } ?: "Spotify refused (is your email added under User Management in your Spotify app?)"
            429 -> "Spotify is busy - try again in a minute"
            else -> "Spotify error $code${detail?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""}"
        }
    }

    private suspend fun accessToken(forceRefresh: Boolean): String = tokenLock.withLock {
        val token = prefs.getString(KEY_ACCESS, null)
        val expiresAt = prefs.getLong(KEY_EXPIRES, 0L)
        if (!forceRefresh && token != null && System.currentTimeMillis() < expiresAt - 60_000L) return token
        val refresh = prefs.getString(KEY_REFRESH, null) ?: throw SpotifyException("Not connected to Spotify", 401)
        val tokens = try {
            postToken(
                FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", refresh)
                    .add("client_id", clientId)
                    .build()
            )
        } catch (e: SpotifyException) {
            // A refresh token Spotify no longer accepts (revoked, or the app changed): sign out
            // so the app asks to connect again instead of failing every time.
            if (e.code == 400 || e.code == 401) signOut()
            throw e
        }
        saveTokens(tokens)
        tokens.getString("access_token")
    }

    private suspend fun postToken(form: FormBody): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("https://accounts.spotify.com/api/token").post(form).build()
        http.newCall(request).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val detail = runCatching { JSONObject(body).optString("error_description") }.getOrNull()
                throw SpotifyException("Spotify sign-in failed${detail?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""}", r.code)
            }
            JSONObject(body)
        }
    }

    private fun saveTokens(tokens: JSONObject) {
        val edit = prefs.edit()
            .putString(KEY_ACCESS, tokens.getString("access_token"))
            .putLong(KEY_EXPIRES, System.currentTimeMillis() + tokens.optLong("expires_in", 3600L) * 1000L)
        tokens.optString("refresh_token").takeIf { it.isNotBlank() }?.let { edit.putString(KEY_REFRESH, it) }
        edit.apply()
    }

    private fun randomUrlSafe(bytes: Int): String = base64Url(ByteArray(bytes).also { SecureRandom().nextBytes(it) })
    private fun base64Url(data: ByteArray): String = Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    companion object {
        private const val TAG = "SpotifyAccount"
        const val REDIRECT_URI = "telemusic://spotify"
        private const val SCOPES = "user-library-read playlist-read-private playlist-read-collaborative"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_ACCESS = "access_token"
        private const val KEY_EXPIRES = "expires_at"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_NAME = "display_name"
        private const val KEY_VERIFIER = "pkce_verifier"
        private const val KEY_STATE = "pkce_state"
    }
}
