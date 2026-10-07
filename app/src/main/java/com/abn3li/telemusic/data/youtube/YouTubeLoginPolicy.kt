package com.abn3li.telemusic.data.youtube

import java.net.URI

internal object YouTubeLoginPolicy {
    const val MUSIC_URL = "https://music.youtube.com/"
    const val SIGN_IN_URL = "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com%2F"
    fun isMusicPage(url: String?): Boolean = runCatching {
        val uri = URI(url ?: return false)
        uri.scheme == "https" && uri.host == "music.youtube.com" && uri.userInfo == null && (uri.port == -1 || uri.port == 443)
    }.getOrDefault(false)
    fun isGoogleRejection(url: String?): Boolean = runCatching {
        val uri = URI(url ?: return false)
        uri.scheme == "https" && uri.host == "accounts.google.com" &&
            (uri.path.contains("/rejected") || uri.rawQuery.orEmpty().contains("disallowed_useragent"))
    }.getOrDefault(false)
    fun signingSecret(cookies: String): String? {
        val values = cookies.split(';').mapNotNull {
            val pair = it.trim().split('=', limit = 2)
            if (pair.size == 2 && pair[1].isNotBlank()) pair[0] to pair[1] else null
        }.toMap()
        return values["SAPISID"] ?: values["__Secure-3PAPISID"] ?: values["__Secure-1PAPISID"]
    }
    fun normalizeAuthUser(value: String): String = value.takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: "0"
}
