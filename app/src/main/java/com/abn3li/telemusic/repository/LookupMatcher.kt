package com.abn3li.telemusic.repository

/**
 * Turns an uploaded song's title into what iTunes/Deezer know it as, and decides which of their
 * results really is that song - so a "(Official Music Video)" or "[Lyrics]" tag no longer hides
 * the song, and a same-named song by someone else never lends its cover. Pure - no network.
 */
object LookupMatcher {
    /** The title and artist a lookup searches for; [artist] is null when the song has none. */
    data class Wanted(val title: String, val artist: String?, val durationSeconds: Int = 0)

    /** One store result. */
    data class Candidate(val title: String?, val artist: String?, val durationSeconds: Int?)

    // Words uploads add that no store title has. English as whole words; Arabic as written.
    private val NOISE = Regex(
        """(?i)\b(official|lyrics?|video|audio|visuali[sz]er|mv|m/v|hd|hq|4k|1080p|720p|clip|with lyrics|slowed|reverb|sped up|8d|remaster(ed)?)\b|كلمات|مترجم|مترجمة|كليب|فيديو|حصري|حصريا|اغنية|أغنية|ريمكس"""
    )
    private val FEAT = Regex("""(?i)^\s*(feat\.?|ft\.?|featuring|with)\s""")
    private val BRACKETED = Regex("""\s*[(\[【{（]([^)\]】}）]*)[)\]】}）]""")
    private val FEAT_SUFFIX = Regex("""(?i)\s+(feat\.?|ft\.?|featuring)\s+.*$""")
    private val EXTENSION = Regex("""(?i)\.(mp3|m4a|flac|ogg|wav|aac|opus|webm)$""")
    private val TRACK_NUMBER = Regex("""^\d{1,3}\s*[.\-_)]\s*|^\d{1,3}\s+(?=\D)""")
    private val PART_SEPARATOR = Regex("""\s+(?:\||//|｜)\s+""")
    private val DASH = Regex("""\s+[-–—]\s+""")
    private val CHANNEL_SUFFIX = Regex("""(?i)\s*(-\s*topic|vevo|official)\s*$""")
    private val QUOTES = Regex("""["'“”‘’«»]""")
    private val WHITESPACE = Regex("""\s+""")

    private fun isNoise(part: String) = NOISE.containsMatchIn(part) || FEAT.containsMatchIn(part)

    /** "Lana Del Rey - Cruel World (Official Video) | HD" with artist "Unknown artist"
     * -> Wanted("Cruel World", "Lana Del Rey"). Unknown titles give a blank title. */
    fun clean(title: String, artist: String, durationSeconds: Int = 0): Wanted {
        var knownArtist = artist.trim().takeUnless { it.isBlank() || it == "Unknown artist" }
            ?.let { CHANNEL_SUFFIX.replace(BRACKETED.replace(it) { m -> if (isNoise(m.groupValues[1])) "" else m.value }, "").trim() }
            ?.takeIf { it.isNotBlank() }
        if (title.isBlank() || title == "Unknown title") return Wanted("", knownArtist, durationSeconds)
        var s = EXTENSION.replace(title.trim(), "").replace('_', ' ')
        s = TRACK_NUMBER.replace(s, "")
        s = BRACKETED.replace(s) { m -> if (isNoise(m.groupValues[1])) "" else m.value }
        // "Song | Official Video", "Song // Lyrics": the first part that isn't only noise.
        s = PART_SEPARATOR.split(s).firstOrNull { it.isNotBlank() && !isNoise(it) } ?: s
        val dashed = DASH.split(s).toMutableList()
        while (dashed.size > 1 && isNoise(dashed.last())) dashed.removeAt(dashed.lastIndex)
        if (dashed.size > 1) {
            val left = dashed.first()
            val rest = dashed.drop(1).joinToString(" - ")
            when {
                knownArtist == null -> { knownArtist = left.trim(); s = rest }
                // Same artist: the title's spelling searches better than a channel's "LanaDelRey".
                LibraryAlbumMatcher.sameArtist(left, knownArtist) -> { knownArtist = left.trim(); s = rest }
                else -> s = dashed.joinToString(" - ")
            }
        } else s = dashed.first()
        s = FEAT_SUFFIX.replace(s, "")
        s = WHITESPACE.replace(QUOTES.replace(s, ""), " ").trim()
        return Wanted(s.ifBlank { title.trim() }, knownArtist, durationSeconds)
    }

    /** The index of the result that is this song, or null: the title must match (an exact match
     * beats one that only starts the same), and so must the artist - or, when it can't be compared
     * (none known, or written in another script), the length must be within 10 seconds. */
    fun best(candidates: List<Candidate>, wanted: Wanted): Int? {
        val wantedKey = titleKey(wanted.title)
        if (wantedKey.isEmpty()) return null
        var bestIndex: Int? = null
        var bestScore = 0
        candidates.forEachIndexed { index, candidate ->
            val key = titleKey(clean(candidate.title.orEmpty(), candidate.artist.orEmpty()).title)
            val titleScore = when {
                key == wantedKey -> 2
                key.length >= 4 && wantedKey.length >= 4 && (key.startsWith(wantedKey) || wantedKey.startsWith(key)) -> 1
                else -> 0
            }
            if (titleScore == 0) return@forEachIndexed
            val artist = wanted.artist
            val candidateArtist = candidate.artist.orEmpty()
            val ok = if (artist != null && hasArabic(artist) == hasArabic(candidateArtist)) {
                LibraryAlbumMatcher.sameArtist(candidateArtist, artist)
            } else {
                val seconds = candidate.durationSeconds ?: 0
                if (wanted.durationSeconds > 0 && seconds > 0) kotlin.math.abs(seconds - wanted.durationSeconds) <= 10
                else titleScore == 2 && artist == null
            }
            if (ok && titleScore > bestScore) { bestScore = titleScore; bestIndex = index }
        }
        return bestIndex
    }

    /** Search text for the stores: title then artist. */
    fun term(wanted: Wanted): String = listOfNotNull(wanted.title, wanted.artist).joinToString(" ")

    private fun titleKey(s: String): String = LibraryAlbumMatcher.artistKey(arabicFolded(s))

    private fun hasArabic(s: String) = s.any { it in '؀'..'ۿ' }

    // Spellings Arabic titles are written either way: alef forms, taa marbuta, alef maqsura.
    private fun arabicFolded(s: String) = s.map {
        when (it) { 'أ', 'إ', 'آ' -> 'ا'; 'ة' -> 'ه'; 'ى' -> 'ي'; else -> it }
    }.joinToString("")
}
