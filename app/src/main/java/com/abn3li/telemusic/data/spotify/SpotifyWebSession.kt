package com.abn3li.telemusic.data.spotify

import android.content.SharedPreferences
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Spotify reached the way its own web player reaches it: with the account's sign-in cookie
 * (`sp_dc`), so it works for free accounts and needs no developer app.
 *
 * The web player signs its token request with a one-time password made from a secret in its own
 * script, and names every library request by a hash that is also in that script. Both change
 * when Spotify rebuilds the player, so they're read from the player itself and kept for a day,
 * and read again at once when Spotify refuses them. Nothing here runs unless a request needs it.
 */
internal class SpotifyWebSession(private val prefs: SharedPreferences, private val http: OkHttpClient) {
    private val tokenLock = Mutex()
    private val playerLock = Mutex()

    var cookie: String?
        get() = prefs.getString(KEY_COOKIE, null)
        set(value) {
            val edit = prefs.edit().remove(KEY_TOKEN).remove(KEY_TOKEN_EXPIRES)
            if (value == null) edit.remove(KEY_COOKIE) else edit.putString(KEY_COOKIE, value)
            edit.apply()
        }

    /**
     * The signed-in session's access token. Throws a 401 only when Spotify itself says the sign-in
     * is no longer a session (which signs out); a weak or lost connection throws without one, so
     * it can be tried again later instead of signing you out.
     */
    suspend fun accessToken(forceRefresh: Boolean = false): String = tokenLock.withLock {
        val spDc = cookie ?: throw SpotifyException("Not connected to Spotify", 401)
        val kept = prefs.getString(KEY_TOKEN, null)
        if (!forceRefresh && kept != null && System.currentTimeMillis() < prefs.getLong(KEY_TOKEN_EXPIRES, 0L) - 60_000L) return kept
        withContext(Dispatchers.IO) {
            // A refused one-time password may be an outdated secret: the player is read again once.
            val answer = requestToken(spDc, player(force = false)).let {
                if (it is TokenAnswer.Token) it else requestToken(spDc, player(force = true))
            }
            when (answer) {
                is TokenAnswer.Token -> {
                    prefs.edit().putString(KEY_TOKEN, answer.value).putLong(KEY_TOKEN_EXPIRES, answer.expires).apply()
                    answer.value
                }
                TokenAnswer.Refused -> throw SpotifyException("Spotify didn't accept the sign-in - sign in again", 401)
                TokenAnswer.Unreachable -> throw SpotifyException("Couldn't reach Spotify - check the connection", 0)
            }
        }
    }

    /** A library query the web player makes, as JSON. Its hash is read again once if refused. */
    suspend fun query(operation: String, variables: String): JSONObject {
        var playerRead = false
        var tokenRefreshed = false
        while (true) {
            val token = accessToken()
            val hash = player(force = playerRead).hashes[operation] ?: FALLBACK_HASHES[operation]
                ?: throw SpotifyException("Spotify changed how its library is read", 0)
            val (code, body) = withContext(Dispatchers.IO) {
                val url = "$PATHFINDER?operationName=$operation&variables=${Uri.encode(variables)}" +
                    "&extensions=${Uri.encode("""{"persistedQuery":{"version":1,"sha256Hash":"$hash"}}""")}"
                val request = Request.Builder().url(url)
                    .header("Authorization", "Bearer $token")
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .header("App-Platform", "WebPlayer")
                    .apply { clientToken()?.let { header("Client-Token", it) } }
                    .build()
                http.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
            }
            val staleHash = body.contains("PersistedQueryNotFound", ignoreCase = true)
            when {
                code in 200..299 && !staleHash -> return JSONObject(body)
                code == 401 && !tokenRefreshed -> { tokenRefreshed = true; accessToken(forceRefresh = true) }
                (staleHash || code == 400 || code == 412) && !playerRead -> playerRead = true
                code == 429 -> throw SpotifyException("Spotify is busy - try again in a minute", 429)
                else -> throw SpotifyException("Spotify error $code", code)
            }
        }
    }

    /** The account's display name, or null when Spotify doesn't say. */
    suspend fun displayName(): String? = runCatching {
        val token = accessToken()
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url("https://api.spotify.com/v1/me")
                .header("Authorization", "Bearer $token")
                .header("User-Agent", USER_AGENT)
                .apply { clientToken()?.let { header("Client-Token", it) } }
                .build()
            http.newCall(request).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val me = JSONObject(r.body?.string().orEmpty())
                me.optString("display_name").ifBlank { me.optString("id") }.takeIf { it.isNotBlank() }
            }
        }
    }.getOrNull()

    fun clear() {
        prefs.edit().remove(KEY_COOKIE).remove(KEY_TOKEN).remove(KEY_TOKEN_EXPIRES)
            .remove(KEY_CLIENT_TOKEN).remove(KEY_CLIENT_TOKEN_EXPIRES).apply()
    }

    // ---- Token ----

    private sealed interface TokenAnswer {
        class Token(val value: String, val expires: Long) : TokenAnswer
        /** Spotify answered and turned the sign-in down: a guest session, or a refusal. */
        data object Refused : TokenAnswer
        /** No answer worth trusting: no connection, the player couldn't be read, a stand-in token. */
        data object Unreachable : TokenAnswer
    }

    private fun requestToken(spDc: String, player: Player): TokenAnswer {
        val secret = player.secret ?: return TokenAnswer.Unreachable
        val key = otpKey(secret)
        val serverSeconds = runCatching {
            http.newCall(webRequest("$WEB/api/server-time", spDc)).execute().use { r ->
                if (r.isSuccessful) JSONObject(r.body?.string().orEmpty()).optLong("serverTime") else null
            }
        }.getOrNull()?.takeIf { it > 0 } ?: (System.currentTimeMillis() / 1000)
        val clientOtp = otp(key, System.currentTimeMillis() / 1000)
        val serverOtp = otp(key, serverSeconds)
        var refused = false
        for (reason in listOf("transport", "init")) {
            val url = "$WEB/api/token?reason=$reason&productType=web-player" +
                "&totp=$clientOtp&totpServer=$serverOtp&totpVer=${player.secretVersion}"
            val (code, answer) = runCatching {
                http.newCall(webRequest(url, spDc)).execute().use { r ->
                    r.code to (if (r.isSuccessful) runCatching { JSONObject(r.body?.string().orEmpty()) }.getOrNull() else null)
                }
            }.getOrNull() ?: continue
            if (code == 400 || code == 401 || code == 403) refused = true
            answer ?: continue
            val token = answer.optString("accessToken")
            // A guest token means the cookie isn't signed in any more.
            if (answer.optBoolean("isAnonymous")) { refused = true; continue }
            // The short stand-in sometimes sent instead of a real token.
            if (token.length < 100) continue
            answer.optString("clientId").takeIf { it.isNotBlank() }?.let { prefs.edit().putString(KEY_CLIENT_ID, it).apply() }
            val expires = answer.optLong("accessTokenExpirationTimestampMs").takeIf { it > 0 }
                ?: (System.currentTimeMillis() + 3_600_000L)
            return TokenAnswer.Token(token, expires)
        }
        return if (refused) TokenAnswer.Refused else TokenAnswer.Unreachable
    }

    private fun webRequest(url: String, spDc: String) = Request.Builder().url(url)
        .header("User-Agent", USER_AGENT)
        .header("Accept", "application/json")
        .header("Cookie", "sp_dc=$spDc")
        .header("App-Platform", "WebPlayer")
        .header("Origin", WEB)
        .header("Referer", "$WEB/")
        .build()

    /** The secret's characters, each mixed with its position, written out as decimal digits. */
    private fun otpKey(secret: String): ByteArray = buildString {
        secret.forEachIndexed { i, c -> append(c.code xor ((i % 33) + 9)) }
    }.toByteArray(Charsets.US_ASCII)

    /** A standard 6-digit, 30-second one-time password. */
    private fun otp(key: ByteArray, seconds: Long): String {
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }
        val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(seconds / 30).array())
        val offset = hash.last().toInt() and 0x0F
        val value = ((hash[offset].toInt() and 0x7F) shl 24) or ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or (hash[offset + 3].toInt() and 0xFF)
        return (value % 1_000_000).toString().padStart(6, '0')
    }

    /** The token that identifies the web player itself, which library requests carry alongside.
     * One request at a time: several library requests start together, and only one should mint it. */
    @Synchronized
    private fun clientToken(): String? {
        prefs.getString(KEY_CLIENT_TOKEN, null)?.let {
            if (System.currentTimeMillis() < prefs.getLong(KEY_CLIENT_TOKEN_EXPIRES, 0L) - 60_000L) return it
        }
        val deviceId = prefs.getString(KEY_DEVICE_ID, null) ?: ByteArray(16).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }.also { prefs.edit().putString(KEY_DEVICE_ID, it).apply() }
        val body = JSONObject().put("client_data", JSONObject()
            .put("client_version", CLIENT_VERSION)
            .put("client_id", prefs.getString(KEY_CLIENT_ID, null) ?: WEB_CLIENT_ID)
            .put("js_sdk_data", JSONObject()
                .put("device_brand", "unknown").put("device_model", "unknown")
                .put("os", "windows").put("os_version", "NT 10.0")
                .put("device_id", deviceId).put("device_type", "computer")))
        val request = Request.Builder().url("https://clienttoken.spotify.com/v1/clienttoken")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .post(body.toString().toByteArray().toRequestBody("application/json".toMediaType()))
            .build()
        return runCatching {
            http.newCall(request).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val granted = JSONObject(r.body?.string().orEmpty()).optJSONObject("granted_token") ?: return@use null
                val token = granted.optString("token").takeIf { it.isNotBlank() } ?: return@use null
                val expires = System.currentTimeMillis() + granted.optLong("expires_after_seconds", 3600L) * 1000L
                prefs.edit().putString(KEY_CLIENT_TOKEN, token).putLong(KEY_CLIENT_TOKEN_EXPIRES, expires).apply()
                token
            }
        }.getOrNull()
    }

    // ---- The web player's script ----

    private class Player(val secret: String?, val secretVersion: Int, val hashes: Map<String, String>)

    /** What's needed from the web player's script, read at most once a day unless [force]. */
    private suspend fun player(force: Boolean): Player = playerLock.withLock {
        val kept = prefs.getString(KEY_PLAYER, null)?.let(::decodePlayer)
        val fresh = System.currentTimeMillis() - prefs.getLong(KEY_PLAYER_READ_AT, 0L) < PLAYER_KEPT_MS
        if (kept != null && fresh && !force) return@withLock kept
        val read = withContext(Dispatchers.IO) { readPlayer() } ?: return@withLock kept ?: Player(null, 0, emptyMap())
        prefs.edit().putString(KEY_PLAYER, encodePlayer(read)).putLong(KEY_PLAYER_READ_AT, System.currentTimeMillis()).apply()
        read
    }

    private fun readPlayer(): Player? {
        val page = get("$WEB/") ?: return null
        val scripts = SCRIPT_URL.findAll(page).map { it.value }.distinct().take(MAX_SCRIPTS).toList()
        var secret: Pair<String, Int>? = null
        val hashes = mutableMapOf<String, String>()
        for (url in scripts) {
            val script = get(url) ?: continue
            if (secret == null) secret = SECRET.findAll(script).mapNotNull { m ->
                val raw = m.groupValues[1].ifEmpty { m.groupValues[2] }
                m.groupValues[3].toIntOrNull()?.let { unescape(raw) to it }
            }.filter { it.first.isNotEmpty() }.maxByOrNull { it.second }
            OPERATION.findAll(script).forEach { m ->
                if (m.groupValues[1] in FALLBACK_HASHES) hashes.putIfAbsent(m.groupValues[1], m.groupValues[2])
            }
            if (secret != null && hashes.keys.containsAll(FALLBACK_HASHES.keys)) break
        }
        if (secret == null && hashes.isEmpty()) return null
        return Player(secret?.first, secret?.second ?: 0, hashes)
    }

    private fun get(url: String): String? = runCatching {
        http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Referer", "$WEB/").build())
            .execute().use { if (it.isSuccessful) it.body?.string() else null }
    }.getOrNull()

    private fun unescape(raw: String): String = buildString {
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                append(when (val next = raw[i + 1]) { 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; else -> next })
                i += 2
            } else { append(c); i++ }
        }
    }

    private fun encodePlayer(p: Player) = JSONObject()
        .put("secret", p.secret ?: "").put("version", p.secretVersion).put("hashes", JSONObject(p.hashes as Map<*, *>)).toString()

    private fun decodePlayer(text: String): Player? = runCatching {
        val o = JSONObject(text)
        val h = o.optJSONObject("hashes")
        Player(o.optString("secret").takeIf { it.isNotEmpty() }, o.optInt("version"),
            h?.keys()?.asSequence()?.associateWith { h.getString(it) }.orEmpty())
    }.getOrNull()

    companion object {
        const val OP_PLAYLISTS = "libraryV3"
        const val OP_LIKED = "fetchLibraryTracks"
        const val OP_PLAYLIST = "fetchPlaylist"

        private const val WEB = "https://open.spotify.com"
        private const val PATHFINDER = "https://api-partner.spotify.com/pathfinder/v1/query"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val CLIENT_VERSION = "1.2.62.476.g2ad6e7f3"
        private const val WEB_CLIENT_ID = "d8a5ed958d274c2e8ee717e6a4b0971d"
        private const val MAX_SCRIPTS = 4
        private val PLAYER_KEPT_MS = TimeUnit.DAYS.toMillis(1)

        /** Used only when the player's script can't be read or no longer names an operation. */
        private val FALLBACK_HASHES = mapOf(
            OP_PLAYLISTS to "390c78e5b951029bad359785e69b07b536a509c581cbcd0aded5e5067f187455",
            OP_LIKED to "087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240",
            OP_PLAYLIST to "21ea11a6ac75a5f3e01fd365d29c18e0a4a8b7dc09f89bbb5d73910190c6acaf"
        )

        private val SCRIPT_URL = Regex("""https://open\.spotifycdn\.com/cdn/build/web-player/[A-Za-z0-9._~-]+\.(?:js|mjs)""")
        private val SECRET = Regex("""secret:\s*(?:'((?:[^'\\]|\\.)*)'|"((?:[^"\\]|\\.)*)")\s*,\s*version:\s*(\d+)""")
        private val OPERATION = Regex("""["'](\w+)["']\s*,\s*["']query["']\s*,\s*["']([0-9a-f]{64})["']""")

        private const val KEY_COOKIE = "web_sp_dc"
        private const val KEY_TOKEN = "web_token"
        private const val KEY_TOKEN_EXPIRES = "web_token_expires"
        private const val KEY_CLIENT_ID = "web_client_id"
        private const val KEY_CLIENT_TOKEN = "web_client_token"
        private const val KEY_CLIENT_TOKEN_EXPIRES = "web_client_token_expires"
        private const val KEY_DEVICE_ID = "web_device_id"
        private const val KEY_PLAYER = "web_player"
        private const val KEY_PLAYER_READ_AT = "web_player_read_at"
    }
}
