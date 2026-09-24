package com.abn3li.telemusic

import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Build
import android.content.pm.PackageManager
import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.abn3li.telemusic.ui.navigation.TgMusicNavGraph
import com.abn3li.telemusic.ui.theme.TgMusicTheme

class MainActivity : ComponentActivity() {
    // The first-launch permission pop-ups. Nothing depends on the answer (local imports use the
    // folder picker, playback works without notifications), so they're simply asked once.
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestFirstLaunchPermissions()
        setContent {
            TgMusicTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TgMusicNavGraph()
                }
            }
        }
    }

    private fun requestFirstLaunchPermissions() {
        val settings = (application as TgMusicApp).settingsStore
        if (settings.permissionsRequested) return
        settings.permissionsRequested = true
        val wanted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val missing = wanted.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissionRequest.launch(missing.toTypedArray())
    }

    override fun onStart() {
        super.onStart()
        val app = application as TgMusicApp
        app.tdlibManager.onAppForegrounded()
    }
}