package com.abn3li.telemusic.repository

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.abn3li.telemusic.MainActivity
import com.abn3li.telemusic.TgMusicApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps a Spotify import (or Update) running when you leave the app, with a progress
 * notification. The import itself runs in [SpotifyImporter]; this service only holds the app in
 * the foreground while it's Running - without it Android froze the app in the background and the
 * import stopped - and follows its progress. When the import ends, the progress notification is
 * swapped for the result and the service stops. It exists only during an import.
 */
class SpotifyImportService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watchJob: Job? = null
    private var lastUpdateMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        ServiceCompat.startForeground(
            this, PROGRESS_ID, progressNotification("Importing from Spotify…", 0, 0),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
        if (watchJob == null) {
            val importer = (application as TgMusicApp).spotifyImporter
            watchJob = scope.launch {
                importer.state.collect { state ->
                    when (state) {
                        is SpotifyImportState.Running -> showProgress(state)
                        is SpotifyImportState.Finished -> finish("Spotify import finished", state.summary)
                        is SpotifyImportState.Failed -> finish("Spotify import failed", state.message)
                        SpotifyImportState.Idle -> finish(null, null)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun showProgress(state: SpotifyImportState.Running) {
        // At most about once a second - the importer reports every song.
        val now = SystemClock.elapsedRealtime()
        if (state.done != 0 && state.done != state.total && now - lastUpdateMs < 900L) return
        lastUpdateMs = now
        val text = if (state.total == 0) "Reading \"${state.name}\" from Spotify…" else "Importing \"${state.name}\": ${state.done} of ${state.total}"
        notificationManager().notify(PROGRESS_ID, progressNotification(text, state.done, state.total))
    }

    /** Swaps the progress notification for the result (if any) and stops. */
    private fun finish(title: String?, text: String?) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (title != null && text != null) {
            notificationManager().notify(
                RESULT_ID,
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setContentIntent(openApp())
                    .setAutoCancel(true)
                    .build()
            )
        }
        watchJob?.cancel()
        stopSelf()
    }

    private fun progressNotification(text: String, done: Int, total: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Spotify import")
            .setContentText(text)
            .setProgress(total, done, total == 0)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(openApp())
            .build()

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager().createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Spotify import", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "spotify_import"
        private const val PROGRESS_ID = 51
        private const val RESULT_ID = 52

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, SpotifyImportService::class.java))
        }
    }
}
