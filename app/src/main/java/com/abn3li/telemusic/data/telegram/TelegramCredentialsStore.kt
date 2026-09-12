package com.abn3li.telemusic.data.telegram

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Stores the user's own Telegram api_id/api_hash encrypted on-device (Android Keystore). */
class TelegramCredentialsStore(context: Context) {
    private val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    private val prefs = EncryptedSharedPreferences.create(
        context, "telegram_credentials", masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun hasCredentials(): Boolean = prefs.contains(KEY_API_ID) && prefs.contains(KEY_API_HASH)
    fun getApiId(): Int = prefs.getInt(KEY_API_ID, 0)
    fun getApiHash(): String = prefs.getString(KEY_API_HASH, "") ?: ""
    fun save(apiId: Int, apiHash: String) {
        prefs.edit().putInt(KEY_API_ID, apiId).putString(KEY_API_HASH, apiHash).apply()
    }
    fun clear() = prefs.edit().clear().apply()

    companion object {
        private const val KEY_API_ID = "tg_api_id"
        private const val KEY_API_HASH = "tg_api_hash"
    }
}
