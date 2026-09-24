package com.abn3li.telemusic.data.browse

/**
 * YouTube Music hands out cover art as googleusercontent.com links whose size is part of the URL
 * (…=w120-h120-l90-rj). The same image is served at any size by changing those numbers, so a
 * blurry 120 px cover can be swapped for a sharp one without any extra request of our own.
 * Any other link (ytimg.com video frames, local files, other hosts) is returned unchanged.
 */
fun googleArtworkAtSize(url: String?, size: Int): String? {
    if (url.isNullOrBlank() || !url.contains("googleusercontent.com")) return url
    return when {
        Regex("""=w\d+-h\d+""").containsMatchIn(url) -> url.replace(Regex("""=w\d+-h\d+"""), "=w$size-h$size")
        Regex("""=s\d+""").containsMatchIn(url) -> url.replace(Regex("""=s\d+"""), "=s$size")
        else -> url
    }
}

/** Saved-art size: sharp in lists, the mini player and the notification, still small (~40 KB). */
const val SAVED_ARTWORK_SIZE = 544

/** Now Playing's big cover - the only place large enough to need more. */
const val FULL_ARTWORK_SIZE = 1200
