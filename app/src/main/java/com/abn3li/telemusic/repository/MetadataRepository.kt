package com.abn3li.telemusic.repository

import com.abn3li.telemusic.data.remote.NetworkModule
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

data class MetadataResult(val title: String?, val artist: String?, val album: String?, val artworkUrl: String?)

/** One cover Find Artwork offers: the picture and the album it belongs to. */
data class ArtworkChoice(val artworkUrl: String, val album: String?, val artist: String?)

/** iTunes -> Deezer -> MusicBrainz, stop at the first result that really is the song (see
 * LookupMatcher). Deezer covers international/Arabic music much better than iTunes and always
 * includes art; MusicBrainz is the last resort. */
class MetadataRepository {
    /** [title]/[artist] as the song has them - upload tags like "(Lyrics)" are fine, they're
     * cleaned before searching. [durationSeconds] helps when the artist can't be compared. */
    suspend fun enrich(title: String, artist: String, durationSeconds: Int = 0): MetadataResult? {
        val wanted = LookupMatcher.clean(title, artist, durationSeconds)
        if (wanted.title.isBlank()) return null
        val term = LookupMatcher.term(wanted)
        iTunesLookup(term, wanted)?.let { return it }
        // Deezer's own fields find a song a plain search buries under covers and remixes.
        if (wanted.artist != null) deezerLookup("artist:\"${wanted.artist}\" track:\"${wanted.title}\"", wanted)?.let { return it }
        deezerLookup(term, wanted)?.let { return it }
        return musicBrainzLookup(term, wanted)
    }

    /** Covers to choose from for [title]/[artist]: iTunes and Deezer results taken in turn, each
     * store's real match first, one per album. Unlike [enrich] nothing is thrown out for not
     * matching - the person picks. */
    suspend fun artworkChoices(title: String, artist: String, limit: Int = 6): List<ArtworkChoice> = coroutineScope {
        val wanted = LookupMatcher.clean(title, artist)
        if (wanted.title.isBlank()) return@coroutineScope emptyList()
        val term = LookupMatcher.term(wanted)
        val iTunes = async {
            runCatching { NetworkModule.iTunesApi.search(term, limit = 15).results }.getOrDefault(emptyList())
        }
        val deezerFields = async {
            if (wanted.artist == null) emptyList()
            else runCatching {
                NetworkModule.deezerApi.search("artist:\"${wanted.artist}\" track:\"${wanted.title}\"", limit = 15).data
            }.getOrDefault(emptyList())
        }
        val deezerPlain = async {
            runCatching { NetworkModule.deezerApi.search(term, limit = 15).data }.getOrDefault(emptyList())
        }
        val fromITunes = iTunes.await().let { tracks ->
            bestFirst(tracks, wanted) { LookupMatcher.Candidate(it.trackName, it.artistName, it.trackTimeMillis?.let { ms -> (ms / 1000).toInt() }) }
                .mapNotNull { t ->
                    t.artworkUrl100?.let { ArtworkChoice(it.replace("100x100bb", "600x600bb"), t.collectionName, t.artistName) }
                }
        }
        val fromDeezer = (deezerFields.await() + deezerPlain.await()).let { tracks ->
            bestFirst(tracks, wanted) { LookupMatcher.Candidate(it.title, it.artist?.name, it.duration) }
                .mapNotNull { t ->
                    (t.album?.cover_xl ?: t.album?.cover_big)?.let { ArtworkChoice(it, t.album?.title, t.artist?.name) }
                }
        }
        val seen = HashSet<String>()
        buildList {
            for (i in 0 until maxOf(fromITunes.size, fromDeezer.size)) {
                for (choice in listOfNotNull(fromITunes.getOrNull(i), fromDeezer.getOrNull(i))) {
                    val album = choice.album?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                    val key = if (album != null) "$album|${choice.artist?.trim()?.lowercase()}" else choice.artworkUrl
                    if (size < limit && seen.add(key) && seen.add(choice.artworkUrl)) add(choice)
                }
            }
        }
    }

    private fun <T> bestFirst(items: List<T>, wanted: LookupMatcher.Wanted, candidate: (T) -> LookupMatcher.Candidate): List<T> {
        val best = LookupMatcher.best(items.map(candidate), wanted) ?: return items
        return listOf(items[best]) + items.filterIndexed { i, _ -> i != best }
    }

    private suspend fun iTunesLookup(term: String, wanted: LookupMatcher.Wanted): MetadataResult? = runCatching {
        val results = NetworkModule.iTunesApi.search(term, limit = 10).results
        val top = results.getOrNull(LookupMatcher.best(results.map {
            LookupMatcher.Candidate(it.trackName, it.artistName, it.trackTimeMillis?.let { ms -> (ms / 1000).toInt() })
        }, wanted) ?: return null) ?: return null
        MetadataResult(top.trackName, top.artistName, top.collectionName, top.artworkUrl100?.replace("100x100bb", "600x600bb"))
    }.getOrNull()

    private suspend fun deezerLookup(query: String, wanted: LookupMatcher.Wanted): MetadataResult? = runCatching {
        val results = NetworkModule.deezerApi.search(query, limit = 10).data
        val top = results.getOrNull(LookupMatcher.best(results.map {
            LookupMatcher.Candidate(it.title, it.artist?.name, it.duration)
        }, wanted) ?: return null) ?: return null
        MetadataResult(top.title, top.artist?.name, top.album?.title, top.album?.cover_xl ?: top.album?.cover_big)
    }.getOrNull()

    private suspend fun musicBrainzLookup(term: String, wanted: LookupMatcher.Wanted): MetadataResult? = runCatching {
        val results = NetworkModule.musicBrainzApi.searchRecording("recording:$term").recordings.orEmpty()
        val top = results.getOrNull(LookupMatcher.best(results.map {
            LookupMatcher.Candidate(it.title, it.`artist-credit`?.firstOrNull()?.name, it.length?.let { ms -> (ms / 1000).toInt() })
        }, wanted) ?: return null) ?: return null
        MetadataResult(top.title, top.`artist-credit`?.firstOrNull()?.name, null, null)
    }.getOrNull()
}
