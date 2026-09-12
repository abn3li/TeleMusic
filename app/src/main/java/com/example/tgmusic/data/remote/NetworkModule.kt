package com.example.tgmusic.data.remote

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

// lyrics.ovh - free, no API key, plain-text-only lyrics fallback for whatever LRCLIB (the
// primary, synced-capable source) doesn't have. Verified reachable directly via curl before
// wiring this in - unlike the two providers this replaced (Lyrist, blocked by a Vercel bot
// checkpoint that returns HTML instead of JSON; LyricsPlus/pop-lyrics.vercel.app, whose
// deployment no longer exists at all), which were both silently dead ends in every request.
interface LyricsOvhApi {
    @GET("v1/{artist}/{title}")
    suspend fun getLyrics(
        @Path(value = "artist", encoded = true) artist: String,
        @Path(value = "title", encoded = true) title: String
    ): LyricsOvhResponse
}

data class LyricsOvhResponse(val lyrics: String?)

object NetworkModule {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        .build()

    private fun retrofit(baseUrl: String) = Retrofit.Builder().baseUrl(baseUrl).client(client)
        .addConverterFactory(GsonConverterFactory.create()).build()

    val lrcLibApi: LrcLibApi by lazy { retrofit("https://lrclib.net/").create(LrcLibApi::class.java) }
    val lyricsOvhApi: LyricsOvhApi by lazy { retrofit("https://api.lyrics.ovh/").create(LyricsOvhApi::class.java) }
    val iTunesApi: ITunesApi by lazy { retrofit("https://itunes.apple.com/").create(ITunesApi::class.java) }
    val deezerApi: DeezerApi by lazy { retrofit("https://api.deezer.com/").create(DeezerApi::class.java) }
    val musicBrainzApi: MusicBrainzApi by lazy { retrofit("https://musicbrainz.org/").create(MusicBrainzApi::class.java) }
}