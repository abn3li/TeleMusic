package com.abn3li.telemusic.data.download

/** Which yt-dlp format string a download uses - real quality tiers off real available audio
 * itags, not a guess: yt-dlp's own selector syntax picks the best match still under the cap,
 * falling back to whatever's actually available when a video has nothing that high.
 *
 * YouTube itself only ever serves audio-only as Opus (WebM) or AAC (M4A) - never true MP3 or
 * FLAC, and nothing here transcodes (no ffmpeg involved - see ytdlp_bridge.py's own doc), so the
 * file on disk is always one of those two. Opus is explicitly preferred at every tier: it's
 * YouTube's own higher-bitrate option (~160 kbps vs AAC's ~128 kbps) at basically every upload,
 * so asking for "the best" should mean picking it on purpose, not however yt-dlp's own default
 * tie-breaking happens to land. */
enum class DownloadQuality(val label: String, val subtitle: String, val formatSelector: String) {
    BEST(
        "Best available", "Highest bitrate YouTube offers - Opus preferred, AAC as fallback",
        "bestaudio[acodec^=opus]/bestaudio/best"
    ),
    HIGH(
        "High", "Opus ~160 kbps",
        "bestaudio[acodec^=opus][abr<=160]/bestaudio[abr<=160]/bestaudio/best"
    ),
    NORMAL(
        "Normal", "Opus ~128 kbps, smaller file",
        "bestaudio[acodec^=opus][abr<=128]/bestaudio[abr<=128]/bestaudio/best"
    );

    companion object {
        fun fromStoredName(name: String): DownloadQuality =
            entries.firstOrNull { it.name == name } ?: BEST
    }
}
