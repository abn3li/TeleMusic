package com.abn3li.telemusic.data.youtube

import com.abn3li.telemusic.data.browse.BrowseParser
import com.abn3li.telemusic.data.browse.BrowseTrack
import com.abn3li.telemusic.data.browse.InnertubeBrowseClient
import com.abn3li.telemusic.data.browse.SearchFilter
import java.text.Normalizer
import kotlin.math.abs

/**
 * The same song on YouTube Music, for a song that only has a title, an artist and a length (a
 * Telegram song). Strict: the title, the artist and the length must all agree, and a remix, live
 * or slowed version is never taken for the original. One search per song, only when asked.
 */
internal class YouTubeSongMatcher(private val search: (String) -> List<BrowseTrack>) {

    constructor(client: InnertubeBrowseClient = InnertubeBrowseClient()) : this(
        { query -> BrowseParser.parseBrowseContent(client.search(query, SearchFilter.SONGS)).tracks.take(8) }
    )

    /** Off the main thread. Null when nothing fits; throws when YouTube couldn't be asked. */
    fun find(title: String, artist: String, seconds: Int): BrowseTrack? {
        val (wantedTitle, wantedArtist) = split(title, artist) ?: return null
        val plainTitle = normalize(wantedTitle)
        val plainArtist = normalize(wantedArtist.split(ARTIST_SEPARATOR).first())
        if (plainTitle.isBlank() || plainArtist.isBlank() || seconds <= 0) return null
        return search("$wantedTitle $wantedArtist")
            .filter { it.durationSeconds > 0 && fits(it, wantedTitle, plainTitle, plainArtist, seconds) }
            .minByOrNull { abs(it.durationSeconds - seconds) }
    }

    private fun fits(found: BrowseTrack, wantedTitle: String, title: String, artist: String, seconds: Int): Boolean {
        if (versions(found.title) != versions(wantedTitle)) return false
        val foundTitle = normalize(withoutArtist(found.title, artist))
        val foundArtist = normalize(found.artist)
        if (foundTitle != title) return false
        val difference = abs(found.durationSeconds - seconds)
        if (hasWords(foundArtist, artist) || hasWords(artist, foundArtist)) return difference <= 5
        // The artist's name in another alphabet can't be compared: the length must then agree closely.
        return foundArtist.isNotBlank() && latin(foundArtist) != latin(artist) && difference <= 2
    }

    /** Title and artist, from "Artist - Title" when the file names no artist of its own. */
    private fun split(title: String, artist: String): Pair<String, String>? {
        val plain = title.trim().replace(FILE_ENDING, "").replace(TRACK_NUMBER, "").trim()
        if (artist.isBlank() || normalize(artist) in UNKNOWN) {
            val parts = plain.split(DASH, limit = 2)
            if (parts.size < 2 || parts.any { it.isBlank() }) return null
            return parts[1].trim() to parts[0].trim()
        }
        return withoutArtist(plain, normalize(artist.split(ARTIST_SEPARATOR).first())) to artist.trim()
    }

    /** "Artist - Title" without its artist. */
    private fun withoutArtist(title: String, artist: String): String {
        val parts = title.split(DASH, limit = 2)
        return if (parts.size == 2 && parts[1].isNotBlank() && artist.isNotBlank() && normalize(parts[0]) == artist) parts[1].trim() else title
    }

    private fun versions(title: String): Set<String> = VERSION.findAll(title.lowercase()).map { it.value }.toSet()

    private fun hasWords(text: String, words: String) = words.isNotBlank() && " $text ".contains(" $words ")

    private fun latin(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        return letters.count { it.code < 0x250 } * 2 >= letters.length
    }

    private fun normalize(text: String): String = Normalizer.normalize(text.replace(FILE_ENDING, ""), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase()
        .replace("ـ", "").replace('ة', 'ه').replace('ى', 'ي')
        .replace(Regex("""[(\[][^)\]]*[)\]]"""), " ")
        .replace(Regex("""\s(feat|ft)\.?\s.*$"""), "")
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .trim()

    private companion object {
        val DASH = Regex("""\s+[-–—]\s+""")
        val ARTIST_SEPARATOR = Regex("""\s*[,،;\&/]\s*|\s+(?:feat|ft)\.?\s+""", RegexOption.IGNORE_CASE)
        val TRACK_NUMBER = Regex("""^\d{1,2}(?:\s*[.)]\s+|\s+[-–]\s+)""")
        val FILE_ENDING = Regex("""\.(mp3|m4a|flac|ogg|opus|wav)$""", RegexOption.IGNORE_CASE)
        val UNKNOWN = setOf("unknown artist", "unknown", "various artists")
        val VERSION = Regex("""remix|\blive\b|slowed|sped up|speed up|reverb|nightcore|karaoke|instrumental|acoustic|\bcover\b|\b8d\b|mashup|ريمكس|ريمكسات|حفله|حفلة|كوفر|بطيء|مسرع""")
    }
}
