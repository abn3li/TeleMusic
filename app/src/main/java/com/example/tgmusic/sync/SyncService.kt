package com.example.tgmusic.sync

import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.tgmusic.TgMusicApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Runs channel sync + metadata enrichment as a foreground service, so it survives the app
 * being backgrounded - a plain ViewModel coroutine was getting killed by Android (especially
 * on battery-optimization-heavy OEMs) partway through, silently. Progress is exposed via a
 * StateFlow the sync UI can collect while it's open.
 */
class SyncService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val chatId = intent?.getLongExtra(EXTRA_CHAT_ID, -1) ?: -1
        if (chatId == -1L) { stopSelf(); return START_NOT_STICKY }

        startForeground(NOTIFICATION_ID, buildNotification("Syncing..."))

        val app = application as TgMusicApp
        scope.launch {
            try {
                _progress.value = "Fetching musics..."
                updateNotification("Fetching musics...")
                app.musicRepository.syncFromChannel(chatId)

                if (app.settingsStore.enrichMetadataOnSync) {
                    app.musicRepository.enrichMissingMetadata { message ->
                        _progress.value = message
                        updateNotification(message)
                    }
                } else {
                    _progress.value = "Metadata sync is off - songs synced without extra info."
                }
                _progress.value = "Done!"
                updateNotification("Sync complete")
            } catch (e: Exception) {
                _progress.value = "Error: ${e.message}"
                updateNotification("Sync failed: ${e.message}")
            } finally {
                _isRunning.value = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        _isRunning.value = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job.cancel()
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Library sync", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TG Music").setContentText(text)
            .setSmallIcon(R.drawable.stat_notify_sync)
            .setOngoing(true).build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val CHANNEL_ID = "sync_channel"
        private const val NOTIFICATION_ID = 42
        private const val EXTRA_CHAT_ID = "chat_id"

        private val _progress = MutableStateFlow("")
        val progress: StateFlow<String> = _progress.asStateFlow()

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        fun start(context: Context, chatId: Long) {
            val intent = Intent(context, SyncService::class.java).putExtra(EXTRA_CHAT_ID, chatId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }
}