package com.abn3li.telemusic.data.spotify

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager

/**
 * The Spotify cookies kept in the app's web view by its login page. Cleared on signing out and
 * before the login page opens, so an old session is never picked up as a new sign-in.
 *
 * Spotify sets them for the whole of .spotify.com and marks them secure, so each is overwritten
 * the same way: an expired copy that isn't secure, or is for one host only, would leave it in place.
 */
internal object SpotifyWebCookies {
    private val hosts = listOf("https://accounts.spotify.com", "https://open.spotify.com", "https://www.spotify.com")

    /** Clears them, then runs [onDone] on the main thread once they're gone. */
    fun clear(onDone: () -> Unit = {}) {
        val main = Handler(Looper.getMainLooper())
        main.post {
            val cookies = runCatching { CookieManager.getInstance() }.getOrNull()
            if (cookies == null) { onDone(); return@post }
            val names = hosts.flatMap { host ->
                cookies.getCookie(host)?.split(';')?.map { it.substringBefore('=').trim() }.orEmpty()
            }.filter { it.isNotEmpty() }.toSet()
            val writes = hosts.flatMap { host ->
                names.flatMap { name ->
                    listOf(
                        host to "$name=; Max-Age=0; Path=/; Domain=.spotify.com; Secure",
                        host to "$name=; Max-Age=0; Path=/; Secure"
                    )
                }
            }
            if (writes.isEmpty()) { onDone(); return@post }
            var left = writes.size
            writes.forEach { (host, value) ->
                cookies.setCookie(host, value) {
                    left--
                    if (left == 0) { cookies.flush(); onDone() }
                }
            }
        }
    }

    /** The session cookie, once Spotify has set one - only signing in sets it. */
    fun session(): String? {
        val cookies = runCatching { CookieManager.getInstance() }.getOrNull() ?: return null
        return hosts.firstNotNullOfOrNull { host ->
            cookies.getCookie(host)?.split(';')?.map { it.trim() }
                ?.firstOrNull { it.startsWith("sp_dc=") }?.removePrefix("sp_dc=")?.takeIf { it.isNotBlank() }
        }
    }
}
