package com.abn3li.telemusic.data.telegram

import android.content.Context
import android.util.Log
import com.abn3li.telemusic.data.settings.DnsResolver
import com.abn3li.telemusic.data.settings.ProxySettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Coroutine wrapper around TDLib. Credentials come in at start() time (never baked into the
 * build). TDLib itself comes from a prebuilt JitPack AAR (see app/build.gradle.kts) - no
 * manual NDK build required.
 */
class TdlibManager(private val context: Context) {

    private var client: Client? = null
    private val _authState = MutableStateFlow<TelegramAuthState>(TelegramAuthState.LoggedOut)
    val authState: StateFlow<TelegramAuthState> = _authState

    private val _connectionState = MutableStateFlow<TelegramConnectionState>(TelegramConnectionState.CONNECTING)
    val connectionState: StateFlow<TelegramConnectionState> = _connectionState

    private var apiId: Int = 0
    private var apiHash: String = ""
    private var initialProxySettings: ProxySettings? = null
    private var initialDnsResolver: DnsResolver = DnsResolver.CLOUDFLARE
    private var initialCustomDnsIps: String = ""

    // Latest known download progress per fileId (updated from TdApi.UpdateFile on TDLib's own
    // thread, read from a different thread by TdlibDataSource - hence ConcurrentHashMap).
    private val fileProgress = ConcurrentHashMap<Int, TdApi.File>()

    // Fix for the streaming-loop bug: track which fileIds already have a download request in
    // flight so open() being called again (ExoPlayer retries/seeks) never re-issues
    // DownloadFile from byte 0 - it just reads current progress instead.
    private val downloadsStarted = ConcurrentHashMap.newKeySet<Int>()

    fun start(
        apiId: Int,
        apiHash: String,
        initialProxy: ProxySettings? = null,
        dnsResolver: DnsResolver = DnsResolver.CLOUDFLARE,
        customDnsIps: String = ""
    ) {
        this.apiId = apiId
        this.apiHash = apiHash
        this.initialProxySettings = initialProxy
        this.initialDnsResolver = dnsResolver
        this.initialCustomDnsIps = customDnsIps
        _connectionState.value = if (initialProxy?.enabled == true) TelegramConnectionState.CONNECTING_TO_PROXY else TelegramConnectionState.CONNECTING
        // TDLib's native logging defaults to a very verbose level that writes real message
        // content, chat/message ids and session details straight to Logcat under the "DLTD"
        // tag - readable via `adb logcat` by anyone with USB debugging access to the device, or
        // by any app holding log-read permissions on older/rooted Android. Level 1 keeps only
        // fatal/error output, which is what TDLib itself recommends for production use.
        runCatching { Client.execute(TdApi.SetLogVerbosityLevel(1)) }
        client = Client.create({ update -> handleUpdate(update) }, null, null)

        // Ultra-fast network options: disable IPv6 timeout delays on IPv4 networks
        client?.send(TdApi.SetOption("prefer_ipv6", TdApi.OptionValueBoolean(false))) { }
        client?.send(TdApi.SetOption("online", TdApi.OptionValueBoolean(true))) { }

        // Apply DNS & Proxy settings immediately upon client creation so TDLib uses them from byte 0
        applyDns(dnsResolver, customDnsIps)
        applyProxySettings(initialProxy)
    }

    /**
     * Called when the app returns to the foreground. Keeps TDLib's network socket active
     * without artificially resetting the connection state.
     */
    fun onAppForegrounded() {
        if (_authState.value is TelegramAuthState.Ready) {
            Log.d("TdlibManager", "App foregrounded, refreshing online option...")
            client?.send(TdApi.SetOption("online", TdApi.OptionValueBoolean(true))) { }
        }
    }

    /**
     * Applies MTProto proxy settings directly to TDLib and forces immediate proxy lock.
     */
    fun applyProxySettings(proxy: ProxySettings?) {
        if (proxy != null && proxy.enabled && proxy.server.isNotBlank()) {
            _connectionState.value = TelegramConnectionState.CONNECTING_TO_PROXY
            val proxyType = TdApi.ProxyTypeMtproto(proxy.secret.trim())
            client?.send(TdApi.AddProxy(proxy.server.trim(), proxy.port, true, proxyType)) { result ->
                if (result is TdApi.Proxy) {
                    Log.d("TdlibManager", "AddProxy succeeded with id=${result.id}, enabling proxy immediately...")
                    client?.send(TdApi.EnableProxy(result.id)) { }
                } else {
                    Log.d("TdlibManager", "AddProxy result: $result")
                }
            }
        } else {
            _connectionState.value = TelegramConnectionState.CONNECTING
            client?.send(TdApi.DisableProxy()) { }
        }
    }

    /**
     * Applies custom DNS resolver IPs to TDLib to bypass ISP DNS censorship / poisoning.
     * Sanitizes comma-separated IP strings and forces socket flush so TDLib re-resolves using the new DNS immediately.
     */
    fun applyDns(resolver: DnsResolver, customIps: String = "") {
        val rawIps = if (resolver == DnsResolver.CUSTOM) customIps else resolver.dnsIps
        val cleanIps = rawIps.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(",")

        Log.d("TdlibManager", "Applying clean DNS IPs to TDLib: '$cleanIps'")
        if (cleanIps.isNotBlank()) {
            client?.send(TdApi.SetOption("dns_ip_address", TdApi.OptionValueString(cleanIps))) { result ->
                Log.d("TdlibManager", "SetOption dns_ip_address=$cleanIps result=$result")
            }
        } else {
            client?.send(TdApi.SetOption("dns_ip_address", TdApi.OptionValueString(""))) { }
        }

        // Toggle online state to flush stale sockets and force TDLib C++ engine to re-resolve via new DNS
        client?.send(TdApi.SetOption("online", TdApi.OptionValueBoolean(false))) { }
        client?.send(TdApi.SetOption("online", TdApi.OptionValueBoolean(true))) { }
    }

    /**
     * Forces TDLib to reconnect to Telegram servers immediately and re-apply proxy & DNS settings if enabled.
     */
    fun reconnect(latestProxy: ProxySettings? = null, dnsResolver: DnsResolver? = null, customDns: String = "") {
        val proxy = latestProxy ?: initialProxySettings
        val dns = dnsResolver ?: initialDnsResolver
        val cDns = if (dnsResolver != null) customDns else initialCustomDnsIps

        applyDns(dns, cDns)
        applyProxySettings(proxy)
        client?.send(TdApi.SetOption("online", TdApi.OptionValueBoolean(true))) { }
    }

    private fun handleUpdate(update: TdApi.Object) {
        when (update) {
            is TdApi.UpdateAuthorizationState -> handleAuthState(update.authorizationState)
            is TdApi.UpdateConnectionState -> handleConnectionState(update.state)
            is TdApi.UpdateFile -> fileProgress[update.file.id] = update.file
        }
    }

    private fun handleConnectionState(state: TdApi.ConnectionState) {
        when (state) {
            is TdApi.ConnectionStateReady -> {
                Log.d("TdlibManager", "ConnectionStateReady received from TDLib C++ socket engine!")
                _connectionState.value = TelegramConnectionState.CONNECTED
            }
            is TdApi.ConnectionStateConnecting -> _connectionState.value = TelegramConnectionState.CONNECTING
            is TdApi.ConnectionStateConnectingToProxy -> _connectionState.value = TelegramConnectionState.CONNECTING_TO_PROXY
            is TdApi.ConnectionStateWaitingForNetwork -> _connectionState.value = TelegramConnectionState.WAITING_FOR_NETWORK
            is TdApi.ConnectionStateUpdating -> _connectionState.value = TelegramConnectionState.UPDATING
            else -> _connectionState.value = TelegramConnectionState.DISCONNECTED
        }
    }

    private fun handleAuthState(state: TdApi.AuthorizationState) {
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                check(apiId != 0 && apiHash.isNotBlank()) { "TdlibManager.start() called without real credentials" }

                // Apply initial proxy and DNS settings if configured
                applyDns(initialDnsResolver, initialCustomDnsIps)
                applyProxySettings(initialProxySettings)

                val params = TdApi.SetTdlibParameters().apply {
                    databaseDirectory = context.filesDir.absolutePath + "/tdlib"
                    useMessageDatabase = true
                    useSecretChats = false
                    apiId = this@TdlibManager.apiId
                    apiHash = this@TdlibManager.apiHash
                    systemLanguageCode = "en"
                    deviceModel = "Android"
                    applicationVersion = "1.0.0"
                }
                client?.send(params) { result ->
                    if (result is TdApi.Error) {
                        _authState.value = TelegramAuthState.Error("TDLib params rejected (${result.code}): ${result.message}")
                    }
                }
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> _authState.value = TelegramAuthState.WaitingForPhoneNumber
            is TdApi.AuthorizationStateWaitCode -> _authState.value = TelegramAuthState.WaitingForCode(describeCodeDelivery(state.codeInfo))
            is TdApi.AuthorizationStateWaitPassword -> _authState.value = TelegramAuthState.WaitingForPassword
            is TdApi.AuthorizationStateReady -> {
                _authState.value = TelegramAuthState.Ready
            }
            is TdApi.AuthorizationStateClosed -> {
                _authState.value = TelegramAuthState.LoggedOut
                _connectionState.value = TelegramConnectionState.DISCONNECTED
            }
            else -> {
                _authState.value = TelegramAuthState.Connecting
            }
        }
    }

    private fun describeCodeDelivery(codeInfo: TdApi.AuthenticationCodeInfo): String = when (codeInfo.type) {
        is TdApi.AuthenticationCodeTypeTelegramMessage ->
            "Sent as a message inside Telegram on one of your other logged-in devices (look for the \"Telegram\" chat there) - not SMS."
        is TdApi.AuthenticationCodeTypeSms -> "Sent via SMS to your phone."
        is TdApi.AuthenticationCodeTypeCall -> "You'll get a phone call reading out the code."
        is TdApi.AuthenticationCodeTypeFlashCall -> "Check your recent call log - the code is the last digits of the incoming number."
        else -> "Code sent - check the Telegram app, SMS, or an incoming call."
    }

    suspend fun submitPhoneNumber(phone: String) = sendSuspend(TdApi.SetAuthenticationPhoneNumber(phone, null))
    suspend fun submitCode(code: String) = sendSuspend(TdApi.CheckAuthenticationCode(code))
    suspend fun submitPassword(password: String) = sendSuspend(TdApi.CheckAuthenticationPassword(password))

    /**
     * Applies MTProto proxy settings directly to TDLib and forces connection via proxy.
     */
    suspend fun applyProxy(enabled: Boolean, server: String, port: Int, secret: String): Boolean {
        return try {
            val settings = ProxySettings(enabled = enabled, server = server, port = port, secret = secret)
            applyProxySettings(settings)
            true
        } catch (e: Exception) {
            Log.e("TdlibManager", "Failed to apply MTProto proxy settings", e)
            false
        }
    }

    /** Lists channels the account already belongs to (including fully private ones with no
     * public username) - used instead of asking the user to type a username.
     *
     * TdApi.GetChats only returns whatever is already sitting in TDLib's LOCAL chat list
     * cache - it never talks to the server itself. TdApi.LoadChats is what actually populates
     * that cache, and per TDLib's own docs it has to be called REPEATEDLY: each call loads
     * some more chats, and it only throws (its documented signal for "nothing left to load")
     * once the list is completely populated. A single LoadChats call, or stopping the moment
     * GetChats returns ANY chats at all, can both return a list that's still missing the
     * specific channel the user wants - especially right after a fresh login, when the cache
     * starts out completely empty. So this calls LoadChats in a loop until it actually signals
     * done (or a generous attempt cap, as a safety net against a genuine unrelated failure),
     * and only THEN calls GetChats once, against a cache that's actually finished loading. */
    suspend fun listMyChats(limit: Int = 100): List<TelegramChatInfo> {
        for (i in 0 until 15) {
            try {
                sendSuspend(TdApi.LoadChats(TdApi.ChatListMain(), limit))
            } catch (e: Exception) {
                break // No more chats to load - the local list is now fully populated.
            }
        }

        val chatIds = (sendSuspend(TdApi.GetChats(TdApi.ChatListMain(), limit)) as TdApi.Chats).chatIds
        return chatIds.toList().mapNotNull { id ->
            val chat = try { sendSuspend(TdApi.GetChat(id)) as TdApi.Chat } catch (_: Exception) { null } ?: return@mapNotNull null
            val isChannel = (chat.type as? TdApi.ChatTypeSupergroup)?.isChannel == true
            TelegramChatInfo(chat.id, chat.title, isChannel)
        }
    }

    suspend fun fetchAudioMessages(chatId: Long, fromMessageId: Long = 0, limit: Int = 50): List<TelegramAudioMessage> {
        val result = sendSuspend(
            TdApi.SearchChatMessages(chatId, null, "", null, fromMessageId, 0, limit, TdApi.SearchMessagesFilterAudio())
        ) as TdApi.FoundChatMessages

        return result.messages.mapNotNull { message ->
            val audio = (message.content as? TdApi.MessageAudio)?.audio ?: return@mapNotNull null
            TelegramAudioMessage(message.id, audio.audio.id,
                audio.title.ifBlank { audio.fileName }, audio.performer, audio.duration)
        }
    }

    /** Fetches the current valid session-local file ID for a message from TDLib. */
    suspend fun getFreshFileId(chatId: Long, messageId: Long): Int? {
        return try {
            runCatching { sendSuspend(TdApi.GetChat(chatId)) }
            var message = runCatching { sendSuspend(TdApi.GetMessage(chatId, messageId)) as? TdApi.Message }.getOrNull()
            if (message == null) {
                val messages = sendSuspend(TdApi.GetMessages(chatId, longArrayOf(messageId))) as? TdApi.Messages
                message = messages?.messages?.firstOrNull()
            }
            val audio = (message?.content as? TdApi.MessageAudio)?.audio
            audio?.audio?.id
        } catch (_: Exception) {
            null
        }
    }

    /** Fetches the exact total file size for a fileId directly from TDLib's file metadata. */
    suspend fun getFreshFileSize(fileId: Int): Long? {
        return try {
            val file = sendSuspend(TdApi.GetFile(fileId)) as? TdApi.File
            val size = file?.expectedSize?.takeIf { it > 0 }?.toLong()
                ?: file?.size?.takeIf { it > 0 }?.toLong()
            size
        } catch (_: Exception) {
            null
        }
    }

    /** Finds the first channel chatId in the user's account if lastSyncedChatId isn't stored yet. */
    suspend fun findFirstChannelId(): Long? {
        return try {
            val chats = listMyChats().filter { it.isChannel }
            chats.firstOrNull()?.id
        } catch (_: Exception) {
            null
        }
    }

    /** Full blocking download - used only for the explicit "Download" button, not streaming. */
    suspend fun downloadFile(fileId: Int): String {
        sendSuspend(TdApi.DownloadFile(fileId, 1, 0, 0, true))
        var file = sendSuspend(TdApi.GetFile(fileId)) as TdApi.File
        var attempts = 0
        while (!file.local.isDownloadingCompleted && attempts < 120) {
            delay(500)
            file = sendSuspend(TdApi.GetFile(fileId)) as TdApi.File
            attempts++
        }
        check(file.local.isDownloadingCompleted) { "Download did not complete for file $fileId" }
        return file.local.path
    }

    /**
     * Starts (or affirms) a background streaming download WITHOUT blocking - safe to call
     * from DataSource.open() every time it's invoked, because it only actually issues
     * TdApi.DownloadFile the FIRST time for a given fileId (fixes the streaming-loop bug
     * where re-opening the DataSource kept re-requesting from byte 0).
     */
    suspend fun beginStreamingDownload(fileId: Int): TdApi.File {
        return try {
            if (downloadsStarted.add(fileId)) {
                val file = sendSuspend(TdApi.DownloadFile(fileId, 32, 0, 0, false)) as TdApi.File
                fileProgress[fileId] = file
                file
            } else {
                val file = sendSuspend(TdApi.GetFile(fileId)) as TdApi.File
                fileProgress[fileId] = file
                file
            }
        } catch (e: Exception) {
            downloadsStarted.remove(fileId)
            throw e
        }
    }

    /** Non-suspending - safe to call from a playback/loading thread. */
    fun getCachedFileProgress(fileId: Int): TdApi.File? = fileProgress[fileId]

    private suspend fun sendSuspend(function: TdApi.Function<*>): TdApi.Object =
        suspendCancellableCoroutine { cont ->
            client?.send(function) { result ->
                if (result is TdApi.Error) cont.resumeWithException(RuntimeException("TDLib error ${result.code}: ${result.message}"))
                else cont.resume(result)
            } ?: cont.resumeWithException(IllegalStateException("Client not started"))
        }

    /**
     * A real logout, not just disconnecting: Close() alone (what this previously used) leaves
     * TDLib's local session database on disk untouched - including the authenticated phone
     * number - so restarting TDLib against the same directory would silently restore the old
     * session instead of asking for a phone number again. This sends TdApi.LogOut() (which
     * actually invalidates the session server-side, unlike Close()) and then deletes the local
     * database directory outright as a guarantee, not just a best-effort cleanup.
     */
    suspend fun logOut() {
        try {
            sendSuspend(TdApi.LogOut())
        } catch (e: Exception) {
            Log.w("TdlibManager", "LogOut request failed, closing directly instead: ${e.message}")
            client?.send(TdApi.Close()) { }
        }
        // Brief pause so TDLib finishes flushing/closing its own database files before this
        // deletes the directory out from under it.
        delay(300)

        client = null
        fileProgress.clear()
        downloadsStarted.clear()

        val databaseDirectory = java.io.File(context.filesDir, "tdlib")
        runCatching { databaseDirectory.deleteRecursively() }

        _authState.value = TelegramAuthState.LoggedOut
        _connectionState.value = TelegramConnectionState.DISCONNECTED
    }
}