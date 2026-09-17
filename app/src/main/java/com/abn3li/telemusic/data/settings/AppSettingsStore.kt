package com.abn3li.telemusic.data.settings

import android.content.Context
import android.net.Uri

/** The Library/detail/Settings screens' own visual style - see the Settings screen's
 * "Appearance" row. CLASSIC is the original flat dark-card look; AMBIENT_BLUR is the blurred
 * ambient-glow/frosted-glass one. */
enum class AppearanceStyle { CLASSIC, AMBIENT_BLUR }

/** Which 5-color blob palette AmbientBlurBackground draws when AppearanceStyle.AMBIENT_BLUR is
 * selected - see the Settings screen's "Ambient colors" row and AmbientBackground.kt's own
 * blobColorsFor(). Purely a display label + selection key; the actual Color values live in the
 * UI layer (AmbientBackground.kt), not here. */
enum class AmbientColorSet(val label: String) {
    SUNSET("Sunset"),
    OCEAN("Ocean"),
    FOREST("Forest"),
    BERRY("Berry")
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

    val proxySettings: ProxySettings
        get() = ProxySettings(proxyEnabled, proxyServer, proxyPort, proxySecret)

    var dnsResolver: DnsResolver
        get() {
            val name = prefs.getString(KEY_DNS_RESOLVER, DnsResolver.CLOUDFLARE.name) ?: DnsResolver.CLOUDFLARE.name
            return runCatching { DnsResolver.valueOf(name) }.getOrDefault(DnsResolver.CLOUDFLARE)
        }
        // commit(), not apply(): the Settings screen calls this right before killing the process
        // outright (Runtime.getRuntime().exit(0), to force TDLib to reinit with the new DNS from
        // byte 0) - apply()'s write is asynchronous, so the process could (and reproducibly did)
        // die before it ever reached disk, and the old value came back on restart.
        set(value) {
            prefs.edit().putString(KEY_DNS_RESOLVER, value.name).commit()
        }

    var customDnsIps: String
        get() = prefs.getString(KEY_CUSTOM_DNS_IPS, "") ?: ""
        // commit(), not apply() - same reasoning as dnsResolver above, written right before the
        // same process-killing restart.
        set(value) {
            prefs.edit().putString(KEY_CUSTOM_DNS_IPS, value.trim()).commit()
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

    /** A SAF tree the user picked (via ACTION_OPEN_DOCUMENT_TREE) to keep a visible, real copy
     * of every explicit download in shared/media storage - separate from the app-private copy
     * every download also keeps for playback (see MusicRepository's own doc on why both exist).
     * Null until the user has picked one, either from the YouTube download screen's first-run
     * prompt or from Settings directly. */
    var downloadFolderUri: String?
        get() = prefs.getString(KEY_DOWNLOAD_FOLDER_URI, null)
        set(value) = prefs.edit().putString(KEY_DOWNLOAD_FOLDER_URI, value).apply()

    /** Plain ISO 3166-1 alpha-2 country code ("US", "GB", "JP", ...) sent as the `gl` field on
     * every YouTube Music Innertube browse call (see InnertubeBrowseClient's own doc) - changes
     * which region's Home feed/Charts/New Releases/Genres actually come back, same as picking a
     * country on music.youtube.com itself. Default US since that's what every request already
     * used before this was configurable. */
    var youtubeRegion: String
        get() = prefs.getString(KEY_YOUTUBE_REGION, "US") ?: "US"
        set(value) = prefs.edit().putString(KEY_YOUTUBE_REGION, value).apply()

    // The three smart (dynamic) playlists' own "Hide from tracks" button - Liked/Telegram/
    // Downloaded aren't real PlaylistEntity rows (see SmartPlaylistKind's own doc), just a
    // different filter over the same songs table, so their own hidden-from-tracks flag lives
    // here instead of on a playlist row. Default OFF for all three (Tracks shows everything,
    // same as before this existed).
    var hideLikedFromTracks: Boolean
        get() = prefs.getBoolean(KEY_HIDE_LIKED, false)
        set(value) = prefs.edit().putBoolean(KEY_HIDE_LIKED, value).apply()

    var hideTelegramFromTracks: Boolean
        get() = prefs.getBoolean(KEY_HIDE_TELEGRAM, false)
        set(value) = prefs.edit().putBoolean(KEY_HIDE_TELEGRAM, value).apply()

    var hideDownloadedFromTracks: Boolean
        get() = prefs.getBoolean(KEY_HIDE_DOWNLOADED, false)
        set(value) = prefs.edit().putBoolean(KEY_HIDE_DOWNLOADED, value).apply()

    var appearanceStyle: AppearanceStyle
        get() {
            val name = prefs.getString(KEY_APPEARANCE_STYLE, AppearanceStyle.CLASSIC.name) ?: AppearanceStyle.CLASSIC.name
            return runCatching { AppearanceStyle.valueOf(name) }.getOrDefault(AppearanceStyle.CLASSIC)
        }
        set(value) = prefs.edit().putString(KEY_APPEARANCE_STYLE, value.name).apply()

    var ambientColorSet: AmbientColorSet
        get() {
            val name = prefs.getString(KEY_AMBIENT_COLOR_SET, AmbientColorSet.SUNSET.name) ?: AmbientColorSet.SUNSET.name
            return runCatching { AmbientColorSet.valueOf(name) }.getOrDefault(AmbientColorSet.SUNSET)
        }
        set(value) = prefs.edit().putString(KEY_AMBIENT_COLOR_SET, value.name).apply()

    companion object {
        private const val KEY_DNS_RESOLVER = "dns_resolver"
        private const val KEY_CUSTOM_DNS_IPS = "custom_dns_ips"
        private const val KEY_PROXY_ENABLED = "proxy_enabled"
        private const val KEY_PROXY_SERVER = "proxy_server"
        private const val KEY_PROXY_PORT = "proxy_port"
        private const val KEY_PROXY_SECRET = "proxy_secret"
        private const val KEY_ENRICH = "enrich_metadata_on_sync"
        private const val KEY_CACHE_LIMIT = "max_cache_size_bytes"
        private const val KEY_LAST_CHAT_ID = "last_synced_chat_id"
        private const val KEY_DOWNLOAD_FOLDER_URI = "download_folder_uri"
        private const val KEY_YOUTUBE_REGION = "youtube_region"
        private const val KEY_HIDE_LIKED = "hide_liked_from_tracks"
        private const val KEY_HIDE_TELEGRAM = "hide_telegram_from_tracks"
        private const val KEY_HIDE_DOWNLOADED = "hide_downloaded_from_tracks"
        private const val KEY_APPEARANCE_STYLE = "appearance_style"
        private const val KEY_AMBIENT_COLOR_SET = "ambient_color_set"
        const val UNLIMITED = -1L
        const val DEFAULT_CACHE_LIMIT = 2L * 1024 * 1024 * 1024 // 2GB

        // Region code -> display name, for the YouTube Region picker in Settings. A short,
        // common list rather than the full ISO 3166 table - the region only affects Discovery
        // content (Home/Charts/New Releases/Genres), not search or downloads, so it's not worth
        // the scroll length of every country YouTube Music technically supports.
        val YOUTUBE_REGIONS = listOf(
            "US" to "United States", "GB" to "United Kingdom", "CA" to "Canada",
            "AU" to "Australia", "DE" to "Germany", "FR" to "France", "ES" to "Spain",
            "IT" to "Italy", "NL" to "Netherlands", "SE" to "Sweden", "BR" to "Brazil",
            "MX" to "Mexico", "AR" to "Argentina", "JP" to "Japan", "KR" to "South Korea",
            "IN" to "India", "ID" to "Indonesia", "PH" to "Philippines", "TH" to "Thailand",
            "VN" to "Vietnam", "TR" to "Turkey", "EG" to "Egypt", "SA" to "Saudi Arabia",
            "AE" to "United Arab Emirates", "IQ" to "Iraq", "JO" to "Jordan", "NG" to "Nigeria",
            "ZA" to "South Africa", "RU" to "Russia", "PL" to "Poland"
        )

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