package com.abn3li.telemusic.data.remote

import retrofit2.http.GET
import retrofit2.http.Query

interface DeezerApi {
    @GET("search")
    suspend fun search(@Query("q") query: String, @Query("limit") limit: Int = 5): DeezerSearchResponse
}

data class DeezerSearchResponse(val data: List<DeezerTrack>)
data class DeezerTrack(val title: String?, val artist: DeezerArtist?, val album: DeezerAlbum?, val duration: Int?)
data class DeezerArtist(val name: String?)
data class DeezerAlbum(val title: String?, val cover_big: String?, val cover_xl: String?)
