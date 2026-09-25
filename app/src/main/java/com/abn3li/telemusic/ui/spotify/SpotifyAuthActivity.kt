package com.abn3li.telemusic.ui.spotify

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.abn3li.telemusic.MainActivity
import com.abn3li.telemusic.TgMusicApp

/**
 * Where Spotify's login page sends the browser back to (telemusic://spotify?code=...). It hands
 * the answer to [com.abn3li.telemusic.data.spotify.SpotifyAccount] (which finishes signing in and
 * shows a toast), returns to the app and closes - it never draws anything.
 */
class SpotifyAuthActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.let { (application as TgMusicApp).spotifyAccount.completeSignIn(it) }
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }
}
