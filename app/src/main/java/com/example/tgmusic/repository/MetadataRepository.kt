package com.example.tgmusic.repository

import com.example.tgmusic.data.remote.NetworkModule

data class MetadataResult(val title: String?, val artist: String?, val album: String?, val artworkUrl: String?)

/** iTunes -> Deezer -> MusicBrainz, stop at first hit. Deezer covers international/Arabic
 * music much better than iTunes and always includes art; MusicBrainz is the last resort. */
class MetadataRepository {
    suspend fun enrich(guessQuery: String): MetadataResult? {
        iTunesLookup(guessQuery)?.let { return it }
        deezerLookup(guessQuery)?.let { return it }
        return musicBrainzLookup(guessQuery)
    }

    private suspend fun iTunesLookup(query: String): MetadataResult? = runCatching {
        val top = NetworkModule.iTunesApi.search(query).results.firstOrNull() ?: return null
        MetadataResult(top.trackName, top.artistName, top.collectionName, top.artworkUrl100?.replace("100x100bb", "600x600bb"))
    }.getOrNull()

    private suspend fun deezerLookup(query: String): MetadataResult? = runCatching {
        val top = NetworkModule.deezerApi.search(query).data.firstOrNull() ?: return null
        MetadataResult(top.title, top.artist?.name, top.album?.title, top.album?.cover_xl ?: top.album?.cover_big)
    }.getOrNull()

    private suspend fun musicBrainzLookup(query: String): MetadataResult? = runCatching {
        val top = NetworkModule.musicBrainzApi.searchRecording("recording:$query").recordings?.firstOrNull() ?: return null
        MetadataResult(top.title, top.`artist-credit`?.firstOrNull()?.name, null, null)
    }.getOrNull()
}
