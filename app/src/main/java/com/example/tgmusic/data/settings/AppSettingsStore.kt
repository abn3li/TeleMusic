package com.example.tgmusic.data.settings

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class AppThemeMode(val displayName: String, val subtitle: String) {
    SYSTEM("System Default", "Follow system settings"),
    LIGHT("Light", "Clean light theme"),
    DARK("Dark", "Comfortable dark theme"),
    AMOLED("AMOLED Black", "Pure black for OLED screens")
}

enum class AppColorScheme(
    val displayName: String,
    val description: String,
    val primaryHex: Long,
    val secondaryHex: Long
) {
    AUXIO("Auxio Indigo", "Classic blue theme", 0xFF4155B5, 0xFF8B9DFF),
    SAKURA_PINK("Sakura Pink 🌸", "Cute soft rose & pink", 0xFFE91E63, 0xFFFFB2C9),
    MATCHA_MINT("Matcha Mint 🍵", "Cute sage & fresh mint", 0xFF2E7D32, 0xFF9FD4AD),
    SUNSET_PEACH("Sunset Peach 🍑", "Cute warm coral & peach", 0xFFE65100, 0xFFFFB59D),
    CLOUD_VIOLET("Cloud Violet ☁️", "Cute soft lilac & lavender", 0xFF6750A4, 0xFFD4BBFF),
    AMOLED_NEON("AMOLED Neon ⚡", "Vibrant neon cyan & magenta", 0xFF00E5FF, 0xFFFF4081)
}

enum class DnsResolver(
    val displayName: String,
    val source: String,
    val dnsIps: String,
    val description: String
) {
    SYSTEM("System Default", "ISP", "", "Uses default network/ISP DNS"),
    CLOUDFLARE("Cloudflare (1.1.1.1)", "Telegram X", "1.1.1.1,1.0.0.1", "Fast, private global DNS resolver"),
    GOOGLE("Google Public (8.8.8.8)", "Telegram X", "8.8.8.8,8.8.4.4", "High-availability global DNS"),
    SHECAN("Shecan Anti-Censorship", "Nagram X", "178.22.122.100,185.51.200.2", "Bypasses Telegram & site censorship"),
    FOUR_OH_THREE("403.online Anti-Censorship", "Nagram X", "10.202.10.202,10.202.10.102", "Bypasses domain restrictions"),
    ELECTRO("ElectroTM DNS", "Nagram X", "78.157.42.100,78.157.42.101", "Electro anti-filter DNS"),
    ADGUARD("AdGuard DNS", "Telegram", "94.140.14.14,94.140.15.15", "Secure DNS with tracking protection"),
    QUAD9("Quad9 DNS", "Telegram", "9.9.9.9,149.112.112.112", "Privacy-focused secure DNS"),
    OPENDNS("Cisco OpenDNS", "Telegram", "208.67.222.222,208.67.220.220", "Reliable Cisco DNS resolver"),
    CUSTOM("Custom DNS", "Custom", "", "Specify your own custom DNS server IPs")
}

data class ProxySettings(
    val enabled: Boolean = false,
    val server: String = "",
    val port: Int = 443,
    val secret: String = "",
    val type: String = "MTPROTO"
)

/** Plain (non-encrypted) settings - nothing sensitive lives here. */
class AppSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    private val _themeModeFlow = MutableStateFlow(themeMode)
    val themeModeFlow: StateFlow<AppThemeMode> = _themeModeFlow

    private val _colorSchemeFlow = MutableStateFlow(colorScheme)
    val colorSchemeFlow: StateFlow<AppColorScheme> = _colorSchemeFlow

    val proxySettings: ProxySettings
        get() = ProxySettings(proxyEnabled, proxyServer, proxyPort, proxySecret)

    var themeMode: AppThemeMode
        get() {
            val name = prefs.getString(KEY_THEME_MODE, AppThemeMode.SYSTEM.name) ?: AppThemeMode.SYSTEM.name
            return runCatching { AppThemeMode.valueOf(name) }.getOrDefault(AppThemeMode.SYSTEM)
        }
        set(value) {
            prefs.edit().putString(KEY_THEME_MODE, value.name).apply()
            _themeModeFlow.value = value
        }

    var colorScheme: AppColorScheme
        get() {
            val name = prefs.getString(KEY_COLOR_SCHEME, AppColorScheme.AUXIO.name) ?: AppColorScheme.AUXIO.name
            return runCatching { AppColorScheme.valueOf(name) }.getOrDefault(AppColorScheme.AUXIO)
        }
        set(value) {
            prefs.edit().putString(KEY_COLOR_SCHEME, value.name).apply()
            _colorSchemeFlow.value = value
        }

    var dnsResolver: DnsResolver
        get() {
            val name = prefs.getString(KEY_DNS_RESOLVER, DnsResolver.CLOUDFLARE.name) ?: DnsResolver.CLOUDFLARE.name
            return runCatching { DnsResolver.valueOf(name) }.getOrDefault(DnsResolver.CLOUDFLARE)
        }
        set(value) {
            prefs.edit().putString(KEY_DNS_RESOLVER, value.name).apply()
        }

    var customDnsIps: String
        get() = prefs.getString(KEY_CUSTOM_DNS_IPS, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_CUSTOM_DNS_IPS, value.trim()).apply()
        }

    var proxyEnabled: Boolean
        get() = prefs.getBoolean(KEY_PROXY_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_PROXY_ENABLED, value).apply()
        }

    var proxyServer: String
        get() = prefs.getString(KEY_PROXY_SERVER, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_PROXY_SERVER, value).apply()
        }

    var proxyPort: Int
        get() = prefs.getInt(KEY_PROXY_PORT, 443)
        set(value) {
            prefs.edit().putInt(KEY_PROXY_PORT, value).apply()
        }

    var proxySecret: String
        get() = prefs.getString(KEY_PROXY_SECRET, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_PROXY_SECRET, value).apply()
        }

    fun updateProxy(enabled: Boolean, server: String, port: Int, secret: String) {
        prefs.edit()
            .putBoolean(KEY_PROXY_ENABLED, enabled)
            .putString(KEY_PROXY_SERVER, server.trim())
            .putInt(KEY_PROXY_PORT, port)
            .putString(KEY_PROXY_SECRET, secret.trim())
            .apply()
    }

    /** Whether sync auto-fetches missing metadata/lyrics/artwork. Default ON. */
    var enrichMetadataOnSync: Boolean
        get() = prefs.getBoolean(KEY_ENRICH, true)
        set(value) = prefs.edit().putBoolean(KEY_ENRICH, value).apply()

    /** Max size of the AUTOMATIC streaming cache only - explicit downloads are never capped.
     * -1 means "Unlimited". Default 2GB. */
    var maxCacheSizeBytes: Long
        get() = prefs.getLong(KEY_CACHE_LIMIT, DEFAULT_CACHE_LIMIT)
        set(value) = prefs.edit().putLong(KEY_CACHE_LIMIT, value).apply()

    /** Last synced channel ID - used to look up fresh file IDs from TDLib if a song's fileId expires. */
    var lastSyncedChatId: Long
        get() = prefs.getLong(KEY_LAST_CHAT_ID, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_CHAT_ID, value).apply()

    companion object {
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_COLOR_SCHEME = "color_scheme"
        private const val KEY_DNS_RESOLVER = "dns_resolver"
        private const val KEY_CUSTOM_DNS_IPS = "custom_dns_ips"
        private const val KEY_PROXY_ENABLED = "proxy_enabled"
        private const val KEY_PROXY_SERVER = "proxy_server"
        private const val KEY_PROXY_PORT = "proxy_port"
        private const val KEY_PROXY_SECRET = "proxy_secret"
        private const val KEY_ENRICH = "enrich_metadata_on_sync"
        private const val KEY_CACHE_LIMIT = "max_cache_size_bytes"
        private const val KEY_LAST_CHAT_ID = "last_synced_chat_id"
        const val UNLIMITED = -1L
        const val DEFAULT_CACHE_LIMIT = 2L * 1024 * 1024 * 1024 // 2GB

        val CACHE_PRESETS = listOf(
            "500 MB" to 500L * 1024 * 1024,
            "1 GB" to 1L * 1024 * 1024 * 1024,
            "2 GB" to 2L * 1024 * 1024 * 1024,
            "5 GB" to 5L * 1024 * 1024 * 1024,
            "Unlimited" to UNLIMITED
        )

        fun parseTelegramProxyUrl(input: String): ProxySettings? {
            val trimmed = input.trim()
            if (!trimmed.contains("server=") || !trimmed.contains("port=")) return null

            val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null
            val server = uri.getQueryParameter("server") ?: return null
            val port = uri.getQueryParameter("port")?.toIntOrNull() ?: 443
            val secret = uri.getQueryParameter("secret") ?: ""

            return ProxySettings(enabled = true, server = server, port = port, secret = secret)
        }
    }
}