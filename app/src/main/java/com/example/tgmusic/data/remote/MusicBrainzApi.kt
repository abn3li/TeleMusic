package com.example.tgmusic.data.remote

import retrofit2.http.GET
import retrofit2.http.Query

interface MusicBrainzApi {
    @GET("ws/2/recording/?fmt=json")
    suspend fun searchRecording(@Query("query") query: String, @Query("limit") limit: Int = 5): MusicBrainzResponse
}

data class MusicBrainzResponse(val recordings: List<MusicBrainzRecording>?)
data class MusicBrainzRecording(val id: String, val title: String?, val length: Long?, val `artist-credit`: List<MusicBrainzArtistCredit>?)
data class MusicBrainzArtistCredit(val name: String?)
