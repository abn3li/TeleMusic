package com.abn3li.telemusic.repository

/**
 * Finds the album already in the library that a YouTube song belongs to, so a Liked or
 * downloaded song joins the user's own album (synced from Telegram, or imported) instead of
 * starting a second one with a slightly different name. Pure - no database or network.
 */
object LibraryAlbumMatcher {
    /** One album in the library and an artist with songs in it. */
    data class LibraryAlbum(val album: String, val artist: String)

    // Words that only say which release of an album it is - "(Deluxe Edition)", "[Remastered]".
    private val EDITION_WORDS = Regex(
        "deluxe|edition|remaster|expanded|bonus|version|anniversary|explicit|clean|special|super|platinum|extended",
        RegexOption.IGNORE_CASE
    )
    private val BRACKETED = Regex("""\s*[(\[]([^)\]]*)[)\]]""")
    private val RELEASE_SUFFIX = Regex("""\s+-\s+(single|ep|deluxe.*|remaster.*|.*edition)$""", RegexOption.IGNORE_CASE)

    /** "30 (Deluxe Edition)", "30 [Explicit]", "30" -> the same key; "Easy On Me - Single" -> "easyonme". */
    fun albumKey(name: String): String {
        var s = name.trim()
        s = BRACKETED.replace(s) { m -> if (EDITION_WORDS.containsMatchIn(m.groupValues[1])) "" else m.value }
        s = RELEASE_SUFFIX.replace(s, "")
        return normalize(s)
    }

    // A credit that always names more than one artist ("Adele, X", "Drake feat. Y", "A x B") -
    // same separators as ytdlp_bridge.py's _ARTIST_SPLIT.
    private val COLLAB_SPLIT = Regex("""\s*(?:,| feat\.?| ft\.?| x )\s*""", RegexOption.IGNORE_CASE)
    // "&" and "/" are also part of band names ("AC/DC", "Simon & Garfunkel"), so they only split
    // a credit whose first name is an artist of its own (see [collapseCredit]).
    private val BAND_OR_COLLAB_SPLIT = Regex("""\s*(?:&|/)\s*""")

    /** "The Weeknd, Daft Punk" -> "The Weeknd"; "AC/DC" and "Simon & Garfunkel" stay whole. */
    fun primaryArtist(name: String): String =
        COLLAB_SPLIT.split(name, limit = 2).firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: name.trim()

    private fun firstOfAmpersand(name: String): String =
        BAND_OR_COLLAB_SPLIT.split(name, limit = 2).firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: name.trim()

    /**
     * The artist a credit belongs to: its primary artist ([primaryArtist]), and for "A & B" /
     * "A/B" the first name only when [libraryArtists] has that artist on their own - so "The
     * Weeknd & Daft Punk" joins "The Weeknd" but "AC/DC" stays "AC/DC".
     */
    fun collapseCredit(name: String, libraryArtists: List<String>): String {
        val primary = primaryArtist(name)
        val first = firstOfAmpersand(primary)
        if (first == primary) return primary
        val firstKey = artistKey(first)
        return libraryArtists.firstOrNull { it != primary && artistKey(it) == firstKey }?.let { first } ?: primary
    }

    /** One key per artist however it's written: "ADELE" / "Adele", "Beyoncé" / "Beyonce",
     * "AC/DC" / "ACDC". */
    fun artistKey(name: String): String = normalize(name)

    /** The same artist, written either way (see [artistKey]), or as a collab credit under it
     * ("The Weeknd, Daft Punk") - "Drake" and "Drake Bell" are not. */
    fun sameArtist(a: String, b: String): Boolean {
        val x = artistKey(a)
        if (x.isEmpty()) return false
        return x == artistKey(b) || artistKey(primaryArtist(a)) == artistKey(primaryArtist(b)) ||
            artistKey(firstOfAmpersand(primaryArtist(a))) == artistKey(firstOfAmpersand(primaryArtist(b)))
    }

    /**
     * The library's spelling of [artist] when the library has that artist written another way,
     * else [artist]'s primary artist. A placeholder ("Unknown artist") is kept as it is.
     */
    fun canonicalArtist(artist: String, libraryArtists: List<String>): String {
        if (artist.isBlank() || artist == "Unknown artist") return artist
        val known = libraryArtists.filter { it != "Unknown artist" }
        val key = artistKey(artist)
        if (key.isEmpty()) return artist
        known.firstOrNull { artistKey(it) == key }?.let { return it }
        val collapsed = collapseCredit(artist, known)
        val collapsedKey = artistKey(collapsed)
        return known.firstOrNull { artistKey(it) == collapsedKey } ?: collapsed
    }

    /**
     * Artists the library has under more than one spelling, each mapped to the one to keep: the
     * spelling most of its songs use (one not in all capitals on a tie). [artistSongCounts] is
     * every artist with its number of songs.
     */
    fun artistMerges(artistSongCounts: List<Pair<String, Int>>): Map<String, String> {
        val merges = mutableMapOf<String, String>()
        artistSongCounts.filter { it.first.isNotBlank() && it.first != "Unknown artist" }
            .groupBy { artistKey(it.first) }
            .filterKeys { it.isNotEmpty() }
            .values.filter { it.size > 1 }
            .forEach { spellings ->
                val keep = spellings.sortedWith(
                    compareByDescending<Pair<String, Int>> { it.second }
                        .thenBy { it.first == it.first.uppercase() }
                        .thenBy { it.first }
                ).first().first
                spellings.forEach { if (it.first != keep) merges[it.first] = keep }
            }
        return merges
    }

    /**
     * The library's own spelling of the album for [candidates] (YouTube Music's album name first,
     * then any other source's) by [artist], or null when the library has none of them. An album
     * is only matched through an artist who has songs in it, so two artists' "Greatest Hits"
     * stay apart.
     */
    fun match(candidates: List<String>, artist: String, library: List<LibraryAlbum>): String? {
        for (candidate in candidates) {
            val key = albumKey(candidate)
            if (key.isEmpty()) continue
            library.firstOrNull { albumKey(it.album) == key && sameArtist(it.artist, artist) }?.let { return it.album }
        }
        return null
    }

    private val MARKS = Regex("""\p{Mn}+""")

    private fun normalize(s: String): String =
        MARKS.replace(java.text.Normalizer.normalize(s.lowercase().replace("&", "and"), java.text.Normalizer.Form.NFD), "")
            .filter { it.isLetterOrDigit() }
}
