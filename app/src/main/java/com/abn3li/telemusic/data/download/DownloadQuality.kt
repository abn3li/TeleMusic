package com.abn3li.telemusic.data.download

/** Which yt-dlp format string a download uses - real quality off real available audio itags, not
 * a guess: yt-dlp's own selector syntax picks the best match, falling back to whatever's
 * actually available when a video has nothing better.
 *
 * YouTube itself only ever serves audio-only as Opus (WebM) or AAC (M4A) - never true MP3 or
 * FLAC, and nothing here transcodes (no ffmpeg involved - see ytdlp_bridge.py's own doc), so the
 * file on disk is always one of those two. Opus is explicitly preferred: verified empirically
 * (checked real yt-dlp format listings against two different videos) that YouTube's own Opus
 * stream (itag 251, ~128-133 kbps) is consistently its highest-bitrate public audio option,
 * ahead of AAC (itag 140, ~129 kbps) - so asking for "the best" means picking Opus on purpose,
 * not however yt-dlp's own default tie-breaking happens to land. There's no quality picker in
 * the UI any more - every download always uses this, the only tier that ever existed for
 * "highest quality, no re-encode". */
enum class DownloadQuality(val formatSelector: String) {
    BEST("bestaudio[acodec^=opus]/bestaudio/best")
}
