package com.abn3li.telemusic

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.abn3li.telemusic.ui.navigation.TgMusicNavGraph
import com.abn3li.telemusic.ui.theme.TgMusicTheme

class MainActivity : ComponentActivity() {
    private val notificationRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The loading screen (Theme.TgMusic.Starting) - shown until the first frame is drawn.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        askOlderUsersForNotifications()
        setContent {
            TgMusicTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TgMusicNavGraph()
                }
            }
        }
    }

    /** New users are asked on the hello screens. Someone updating from a version that never
     * asked skips those, so they're asked here instead - once. */
    private fun askOlderUsersForNotifications() {
        val app = application as TgMusicApp
        val settings = app.settingsStore
        if (app.needsOnboarding() || settings.permissionsRequested) return
        settings.permissionsRequested = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onStart() {
        super.onStart()
        val app = application as TgMusicApp
        app.tdlibManager.onAppForegrounded()
    }
}