package com.abn3li.telemusic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.abn3li.telemusic.ui.navigation.TgMusicNavGraph
import com.abn3li.telemusic.ui.theme.TgMusicTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // The loading screen (Theme.TgMusic.Starting) - shown until the first frame is drawn.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent {
            TgMusicTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TgMusicNavGraph()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val app = application as TgMusicApp
        app.tdlibManager.onAppForegrounded()
    }
}