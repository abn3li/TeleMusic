package com.abn3li.telemusic.data.quality

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class FlacUpgradePreferences(val enabled: Boolean = false, val username: String = "", val hasAccount: Boolean = false)
data class FlacPlaybackInfo(val songId: String, val sampleRate: Int, val bitDepth: Int,
    val channels: Int, val sizeBytes: Long, val downloadedBytes: StateFlow<Long>)

/** Account details stay in Android Keystore-backed storage and never enter logs. */
class FlacUpgradeStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context, "quality_account",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    private fun read() = FlacUpgradePreferences(
        prefs.getBoolean("enabled", false), prefs.getString("username", "").orEmpty(),
        !prefs.getString("username", "").isNullOrBlank() && !prefs.getString("password", "").isNullOrEmpty()
    )
    private val _preferences = MutableStateFlow(read())
    val preferences: StateFlow<FlacUpgradePreferences> = _preferences
    private val _upgradeStatus = MutableStateFlow(idleFlacStatus(null, _preferences.value.enabled, _preferences.value.hasAccount))
    val upgradeStatus: StateFlow<FlacUpgradeStatus> = _upgradeStatus
    private val _playingFlacSongId = MutableStateFlow<String?>(null)
    val playingFlacSongId: StateFlow<String?> = _playingFlacSongId
    private val _activeFlac = MutableStateFlow<FlacPlaybackInfo?>(null)
    val activeFlac: StateFlow<FlacPlaybackInfo?> = _activeFlac
    private var activeBuffer: FlacStreamBuffer? = null
    @Synchronized internal fun playingFlac(songId: String?, header: FlacHeader? = null, buffer: FlacStreamBuffer? = null) {
        activeBuffer = buffer
        _activeFlac.value = if (songId != null && header != null && buffer != null)
            FlacPlaybackInfo(songId, header.sampleRate, header.bitDepth, header.channels, buffer.totalSize, buffer.progress)
        else null
        _playingFlacSongId.value = songId
    }
    @Synchronized internal fun acquireDownload(songId: String): Pair<FlacStreamBuffer, java.io.Closeable>? {
        if (_activeFlac.value?.songId != songId) return null
        val buffer = activeBuffer ?: return null
        return buffer to buffer.retainFile()
    }
    internal fun status(songId: String?, stage: FlacUpgradeStage, detail: String, transfer: FlacTransferInfo? = null) {
        _upgradeStatus.value = if (_preferences.value.enabled) FlacUpgradeStatus(songId, stage, detail, transfer)
            else idleFlacStatus(songId, false, _preferences.value.hasAccount)
    }
    internal fun resetStatus(songId: String?) {
        _upgradeStatus.value = idleFlacStatus(songId, _preferences.value.enabled, _preferences.value.hasAccount)
    }
    internal fun credentials(): Pair<String, String>? = if (_preferences.value.hasAccount) {
        _preferences.value.username to prefs.getString("password", "").orEmpty()
    } else null
    fun saveAccount(username: String, password: String) {
        require(username.matches(Regex("[!-~]{1,30}")) && password.isNotEmpty())
        prefs.edit().putString("username", username).putString("password", password).apply()
        _preferences.value = read()
        resetStatus(_upgradeStatus.value.songId)
    }
    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("enabled", enabled && _preferences.value.hasAccount).apply()
        _preferences.value = read()
        resetStatus(_upgradeStatus.value.songId)
    }
    fun clear() {
        prefs.edit().clear().apply()
        _preferences.value = read()
        resetStatus(_upgradeStatus.value.songId)
    }
}
