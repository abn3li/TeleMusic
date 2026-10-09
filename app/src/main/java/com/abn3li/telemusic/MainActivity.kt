package com.abn3li.telemusic

import com.abn3li.telemusic.ui.theme.SystemBarsState
import com.abn3li.telemusic.ui.theme.LightPalette
import androidx.compose.ui.graphics.toArgb
import com.abn3li.telemusic.data.settings.ThemeMode
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
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
        // Drawn behind the status and navigation bars, so the open player's cover can fill the
        // status bar. Every other screen keeps clear of the bars (see TgMusicNavGraph).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        askOlderUsersForNotifications()
        setContent {
            val themeMode by app.settingsStore.themeMode.collectAsState()
            val light = when (themeMode) {
                ThemeMode.DARK -> false
                ThemeMode.LIGHT -> true
                ThemeMode.SYSTEM -> !isSystemInDarkTheme()
            }
            // Status and navigation bar icons: dark on the light theme, light on the dark one.
            val view = LocalView.current
            // Read here, not inside the effect, so opening or closing the player re-runs it.
            // The open player and the welcome pages are always dark, so the bars around them are too.
            val heroPage = SystemBarsState.heroPages > 0
            val frostedHeaderPage = SystemBarsState.frostedHeaderPages > 0
            val lightBars = light && !SystemBarsState.playerOpen && !SystemBarsState.onboardingOpen
            // An artist's photo under the status bar keeps its icons light, in either theme.
            val lightStatusIcons = lightBars && !(heroPage && SystemBarsState.heroesOverPhoto.isNotEmpty())
            val groupedPage = SystemBarsState.groupedPages > 0
            // Read here too (not only inside the effect), so opening the player re-runs it in
            // the dark theme as well.
            val playerBars = SystemBarsState.playerOpen
            SideEffect {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = lightStatusIcons
                    isAppearanceLightNavigationBars = lightBars
                }
                val bar = if (lightBars) android.graphics.Color.WHITE else android.graphics.Color.BLACK
                window.decorView.setBackgroundColor(bar)
                // The open player shows through both bars: its cover at the top, its colours below.
                @Suppress("DEPRECATION")
                window.statusBarColor = when {
                    playerBars || heroPage || frostedHeaderPage -> android.graphics.Color.TRANSPARENT
                    lightBars && groupedPage -> LightPalette.groupedBackground.toArgb()
                    else -> bar
                }
                @Suppress("DEPRECATION")
                window.navigationBarColor = if (playerBars) android.graphics.Color.TRANSPARENT else bar
            }
            TgMusicTheme(light = light) {
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
