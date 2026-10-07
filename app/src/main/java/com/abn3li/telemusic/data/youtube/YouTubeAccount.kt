package com.abn3li.telemusic.data.youtube

import android.content.Context
import android.webkit.CookieManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest

/** Who's signed in to YouTube Music, for Home's account button and Settings. */
data class YouTubeAccountState(val signedIn: Boolean = false, val name: String? = null, val photoUrl: String? = null, val sessionId: String? = null)

/**
 * The YouTube Music sign-in: the site's own session cookies, captured once from the sign-in page
 * (see YouTubeSignInScreen) and kept encrypted (Android Keystore) on the phone only. With them,
 * Home and Search ask YouTube Music as you - your mixes, Quick picks, Listen again - instead of
 * as an anonymous visitor. Playback history uses verified YouTube tracking endpoints while
 * listening. Signing out forgets the cookies here and in the sign-in page's cookie store.
 */
class YouTubeAccount(context: Context, private val onSessionChanged: () -> Unit = {}) {
    private val prefs by lazy {
        EncryptedSharedPreferences.create(
            context, "youtube_account",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    private val _state = MutableStateFlow(YouTubeAccountState())
    val state: StateFlow<YouTubeAccountState> = _state

    // Read once, off the main thread (the encrypted store's first open is slow) - see TgMusicApp.
    @Volatile private var cookie: String? = null
    @Volatile private var authUser: String = "0"
    @Volatile private var sessionId: String? = null

    @Synchronized fun load() {
        cookie = prefs.getString(KEY_COOKIE, null)?.takeIf { sapisid(it) != null }
        sessionId = if (cookie != null) prefs.getString(KEY_SESSION, null) ?: java.util.UUID.randomUUID().toString().also { prefs.edit().putString(KEY_SESSION, it).apply() } else null
        authUser = YouTubeLoginPolicy.normalizeAuthUser(prefs.getString(KEY_AUTH_USER, "0").orEmpty())
        onSessionChanged()
        _state.value = YouTubeAccountState(cookie != null, prefs.getString(KEY_NAME, null), prefs.getString(KEY_PHOTO, null), sessionId)
    }

    /** True once the sign-in page has left the cookies we need (the session's SAPISID). */
    @Synchronized fun signIn(cookies: String, sessionIndex: String = "0"): Boolean {
        if (sapisid(cookies) == null) return false
        authUser = YouTubeLoginPolicy.normalizeAuthUser(sessionIndex)
        sessionId = java.util.UUID.randomUUID().toString()
        prefs.edit().putString(KEY_SESSION, sessionId).putString(KEY_COOKIE, cookies).putString(KEY_AUTH_USER, authUser)
            .remove(KEY_NAME).remove(KEY_PHOTO).apply()
        cookie = cookies
        onSessionChanged()
        _state.value = YouTubeAccountState(signedIn = true, sessionId = sessionId)
        return true
    }

    @Synchronized fun setProfile(name: String?, photoUrl: String?, expectedSession: String?) {
        if (cookie == null || sessionId != expectedSession) return
        prefs.edit().putString(KEY_NAME, name).putString(KEY_PHOTO, photoUrl).apply()
        _state.value = YouTubeAccountState(true, name, photoUrl, sessionId)
    }

    @Synchronized fun signOut() {
        prefs.edit().clear().apply()
        cookie = null
        sessionId = null
        authUser = "0"
        onSessionChanged()
        _state.value = YouTubeAccountState()
        runCatching { CookieManager.getInstance().removeAllCookies(null) }
    }

    /**
     * The headers that make a YouTube Music request yours: the session cookies plus the
     * SAPISIDHASH signature the site itself sends (SHA-1 of "time SAPISID origin"). Null when
     * signed out - the request then goes out anonymously, as before.
     */
    @Synchronized fun authHeaders(): Map<String, String>? {
        val cookies = cookie ?: return null
        val sapisid = sapisid(cookies) ?: return null
        val time = System.currentTimeMillis() / 1000
        val hash = MessageDigest.getInstance("SHA-1").digest("$time $sapisid $ORIGIN".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return mapOf(
            "Cookie" to cookies,
            "Authorization" to "SAPISIDHASH ${time}_$hash",
            "X-Origin" to ORIGIN,
            "X-Goog-AuthUser" to authUser
        )
    }

    /** A play can only be reported using the account that was signed in when it started. */
    @Synchronized fun authHeadersForSession(expectedSession: String): Map<String, String>? =
        if (sessionId == expectedSession) authHeaders() else null

    private fun sapisid(cookies: String): String? = YouTubeLoginPolicy.signingSecret(cookies)

    companion object {
        const val ORIGIN = "https://music.youtube.com"
        private const val KEY_SESSION = "session_id"
        private const val KEY_AUTH_USER = "auth_user"
        private const val KEY_COOKIE = "cookie"
        private const val KEY_NAME = "name"
        private const val KEY_PHOTO = "photo"
    }
}
