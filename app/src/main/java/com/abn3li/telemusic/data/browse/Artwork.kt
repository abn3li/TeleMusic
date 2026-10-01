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

/**
 * [url] at Now Playing's size ([FULL_ARTWORK_SIZE]) wherever the host serves other sizes from the
 * same link: YouTube Music, iTunes (600 px when saved) and Deezer (1000 px) change a number in
 * it, a YouTube video frame (480x360) becomes its largest one (1280x720, missing for some old
 * videos - see the fallback in Now Playing). Anything else is returned unchanged.
 */
fun fullSizeArtwork(url: String?): String? {
    if (url.isNullOrBlank()) return url
    val size = FULL_ARTWORK_SIZE
    return when {
        "googleusercontent.com" in url -> googleArtworkAtSize(url, size)
        "mzstatic.com" in url -> url.replace(Regex("""/\d+x\d+bb\.(jpg|png|webp)$"""), "/${size}x${size}bb.jpg")
        "dzcdn.net" in url -> url.replace(Regex("""/\d+x\d+-"""), "/${size}x$size-")
        "ytimg.com" in url -> Regex("""ytimg\.com/vi(?:_webp)?/([^/]+)/""").find(url)
            ?.let { "https://i.ytimg.com/vi/${it.groupValues[1]}/maxresdefault.jpg" } ?: url
        else -> url
    }
}

/** Saved-art size: sharp in lists, the mini player and the notification, still small (~40 KB). */
const val SAVED_ARTWORK_SIZE = 544

/** Now Playing's big cover - the only place large enough to need more. */
const val FULL_ARTWORK_SIZE = 1200
