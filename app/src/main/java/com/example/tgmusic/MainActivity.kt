package com.example.tgmusic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.example.tgmusic.ui.navigation.TgMusicNavGraph
import com.example.tgmusic.ui.theme.TgMusicTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as TgMusicApp
        setContent {
            val themeMode by app.settingsStore.themeModeFlow.collectAsState()
            val colorSchemeChoice by app.settingsStore.colorSchemeFlow.collectAsState()

            TgMusicTheme(themeMode = themeMode, colorSchemeChoice = colorSchemeChoice) {
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