package com.example.tgmusic.data.remote

import retrofit2.http.GET
import retrofit2.http.Query

interface ITunesApi {
    @GET("search")
    suspend fun search(
        @Query("term") term: String, @Query("media") media: String = "music",
        @Query("entity") entity: String = "song", @Query("limit") limit: Int = 5
    ): ITunesSearchResponse
}

data class ITunesSearchResponse(val resultCount: Int, val results: List<ITunesTrack>)
data class ITunesTrack(val trackName: String?, val artistName: String?, val collectionName: String?, val artworkUrl100: String?, val trackTimeMillis: Long?)
