package com.abn3li.telemusic.repository

import com.abn3li.telemusic.data.remote.NetworkModule

data class MetadataResult(val title: String?, val artist: String?, val album: String?, val artworkUrl: String?)

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
