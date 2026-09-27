package com.abn3li.telemusic

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
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
        // The loading screen (Theme.TgMusic.Starting). On a cold start it stays until the first
        // screen has its data, but at least SPLASH_MIN_MS - long enough for the bars to bounce
        // instead of flashing past - and never more than SPLASH_MAX_MS. When the app was
        // already running (reopened from recents) it goes as soon as the screen is drawn.
        val splash = installSplashScreen()
        val app = application as TgMusicApp
        val coldStart = SystemClock.uptimeMillis() - Process.getStartUptimeMillis() < COLD_START_WINDOW_MS
        if (coldStart && savedInstanceState == null) {
            splash.setKeepOnScreenCondition {
                val sinceStart = SystemClock.uptimeMillis() - Process.getStartUptimeMillis()
                sinceStart < SPLASH_MAX_MS && (sinceStart < SPLASH_MIN_MS || !app.firstScreenReady)
            }
        }
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

    private companion object {
        // Measured from the process start, which is when the loading screen appears.
        const val SPLASH_MIN_MS = 1000L
        const val SPLASH_MAX_MS = 2500L
        // An activity created this soon after its process started is a cold start.
        const val COLD_START_WINDOW_MS = 5000L
    }

    override fun onStart() {
        super.onStart()
        val app = application as TgMusicApp
        app.tdlibManager.onAppForegrounded()
    }
}