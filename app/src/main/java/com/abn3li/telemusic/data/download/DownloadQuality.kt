package com.abn3li.telemusic.data.download

/** Which yt-dlp format string a download uses - real quality off a real available audio itag,
 * not a guess.
 *
 * Opus-only, no fallback: verified empirically (checked real yt-dlp format listings against two
 * different videos) that YouTube's own Opus stream - itag 251, 48.0 kHz, up to ~160 kbps VBR - is
 * consistently its highest-quality public audio option, ahead of AAC (itag 140, ~129 kbps). This
 * selector has no "/bestaudio/best" fallback any more - if a video genuinely has no Opus stream
 * at all (rare), the download fails outright rather than silently settling for AAC. Nothing here
 * transcodes (no ffmpeg involved - see ytdlp_bridge.py's own doc), so the file on disk is exactly
 * the Opus/WebM bytes YouTube served. There's no quality picker in the UI - every download always
 * uses this. */
enum class DownloadQuality(val formatSelector: String) {
    BEST("bestaudio[acodec^=opus]")
}
