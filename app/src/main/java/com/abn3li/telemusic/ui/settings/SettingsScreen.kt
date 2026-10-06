package com.abn3li.telemusic.ui.settings

import com.abn3li.telemusic.data.settings.ThemeMode
import com.abn3li.telemusic.ui.theme.LocalPalette
import com.abn3li.telemusic.ui.theme.paper
import com.abn3li.telemusic.ui.theme.ink
import com.abn3li.telemusic.ui.library.AppAlert
import com.abn3li.telemusic.ui.library.AlertAction
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import com.abn3li.telemusic.ui.library.GroupLabelColor
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material.icons.outlined.Info
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.data.settings.AppSettingsStore
import com.abn3li.telemusic.data.update.UpdateCheckResult
import com.abn3li.telemusic.data.update.UpdateChecker
import com.abn3li.telemusic.repository.LocalAudioFile
import com.abn3li.telemusic.ui.library.AppAccent
import com.abn3li.telemusic.ui.library.DestructiveRed
import com.abn3li.telemusic.ui.library.GroupActionRow
import com.abn3li.telemusic.ui.library.GroupCard
import com.abn3li.telemusic.ui.library.GroupDivider
import com.abn3li.telemusic.ui.library.GroupFooter
import com.abn3li.telemusic.ui.library.GroupHeader
import com.abn3li.telemusic.ui.library.GroupOption
import com.abn3li.telemusic.ui.library.GroupIcon
import com.abn3li.telemusic.ui.library.GroupRow
import com.abn3li.telemusic.ui.library.GroupSwitch
import com.abn3li.telemusic.ui.library.GroupValue
import com.abn3li.telemusic.ui.library.LargeTitleList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A SAF tree URI's own document id looks like "primary:Music/TeleMusic" - the part after the
 * last "/" is the folder name the user actually picked. */
internal fun readableFolderName(uri: Uri): String =
    uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "Folder selected"

// iOS-style icon tile colours.
private val TilePink = Color(0xFFE64366)
private val TileBlue = Color(0xFF0A84FF)
private val TileGreen = Color(0xFF30D158)
private val TileOrange = Color(0xFFFF9F0A)
private val TilePurple = Color(0xFFBF5AF2)
private val TileGrey = Color(0xFF636366)
private val TileIndigo = Color(0xFF5E5CE6)

@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenSpotify: () -> Unit, onLoggedOut: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as TgMusicApp
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    var enrichEnabled by remember { mutableStateOf(app.settingsStore.enrichMetadataOnSync) }
    val playerEffects by app.settingsStore.playerEffects.collectAsState()
    val themeMode by app.settingsStore.themeMode.collectAsState()
    var cacheLimit by remember { mutableStateOf(app.settingsStore.maxCacheSizeBytes) }
    var cacheOptionsOpen by remember { mutableStateOf(false) }
    var licensesOpen by remember { mutableStateOf(false) }

    // DNS and proxy save as they're changed - no Save buttons. Before Telegram is set up (Sync
    // tab) there's nothing to connect or restart.
    val network = rememberTelegramNetworkForm(app.settingsStore)
    val telegramRunning = app.tdlibManager.isStarted
    var proxyStatusMessage by remember { mutableStateOf<String?>(null) }
    // TDLib reads DNS only when it starts: a changed one offers a restart.
    val launchDns = remember { dnsAtLaunch ?: (app.settingsStore.dnsResolver to app.settingsStore.customDnsIps).also { dnsAtLaunch = it } }
    var savedDns by remember { mutableStateOf(app.settingsStore.dnsResolver to app.settingsStore.customDnsIps) }
    val dnsNeedsRestart = telegramRunning && savedDns != launchDns
    // Proxy fields typed but not connected yet - connected on leaving Settings.
    var proxyEditsPending by remember { mutableStateOf(false) }

    // DNS is written with a blocking commit() (see AppSettingsStore.dnsResolver), so never on the
    // main thread while typing: a picked resolver saves in the background, typed servers save
    // once on leaving Settings or on Restart.
    var dnsEditsPending by remember { mutableStateOf(false) }
    fun writeDns(resolver: com.abn3li.telemusic.data.settings.DnsResolver, custom: String) {
        app.settingsStore.dnsResolver = resolver
        app.settingsStore.customDnsIps = custom
    }
    fun saveDns(typing: Boolean) {
        savedDns = network.dns to network.customDns
        if (typing) { dnsEditsPending = true; return }
        dnsEditsPending = false
        val (resolver, custom) = savedDns
        scope.launch {
            withContext(Dispatchers.IO) { writeDns(resolver, custom) }
            network.markSaved(app.settingsStore)
        }
    }

    fun saveProxy(connectNow: Boolean) {
        val enabled = network.proxyEnabled
        val server = network.proxyServer
        val port = network.proxyPort
        val secret = network.proxySecret
        app.settingsStore.updateProxy(enabled, server, port, secret)
        network.markSaved(app.settingsStore)
        if (!telegramRunning) return
        if (!connectNow) { proxyEditsPending = true; return }
        proxyEditsPending = false
        if (enabled && server.isBlank()) { proxyStatusMessage = null; return }
        scope.launch {
            proxyStatusMessage = if (enabled) "Connecting…" else null
            val success = app.tdlibManager.applyProxy(enabled, server, port, secret)
            proxyStatusMessage = when {
                !enabled -> "Using a direct connection."
                success -> "Proxy connected."
                else -> "Couldn't connect to the proxy."
            }
        }
    }

    // Typed proxy details connect once, when leaving Settings, not on every keystroke.
    val latestProxyPending by rememberUpdatedState(proxyEditsPending)
    val latestDnsPending by rememberUpdatedState(dnsEditsPending)
    DisposableEffect(Unit) {
        onDispose {
            if (latestDnsPending) {
                val resolver = network.dns
                val custom = network.customDns
                app.workScope.launch(Dispatchers.IO) { writeDns(resolver, custom) }
            }
            if (latestProxyPending) {
                val enabled = network.proxyEnabled
                val server = network.proxyServer
                val port = network.proxyPort
                val secret = network.proxySecret
                app.workScope.launch { app.tdlibManager.applyProxy(enabled, server, port, secret) }
            }
        }
    }

    var showClearCacheConfirm by remember { mutableStateOf(false) }
    var showClearLibraryConfirm by remember { mutableStateOf(false) }
    var storageActionStatus by remember { mutableStateOf<String?>(null) }
    var showLogoutConfirm by remember { mutableStateOf(false) }

    val updateChecker = remember { UpdateChecker() }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateCheckStatus by remember { mutableStateOf<String?>(null) }
    var availableUpdate by remember { mutableStateOf<UpdateCheckResult.UpdateAvailable?>(null) }
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "1.0"
    }

    var localAudioFiles by remember { mutableStateOf<List<LocalAudioFile>>(emptyList()) }
    var alreadyImportedSongIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var isScanningLocalFolder by remember { mutableStateOf(false) }
    var showImportSheet by remember { mutableStateOf(false) }

    // The system folder picker (Storage Access Framework) - no storage permission needed.
    val importFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            showImportSheet = true
            isScanningLocalFolder = true
            scope.launch {
                localAudioFiles = withContext(Dispatchers.IO) { app.musicRepository.scanLocalFolder(treeUri) }
                alreadyImportedSongIds = withContext(Dispatchers.IO) { app.musicRepository.getLocalImportSongIds() }
                isScanningLocalFolder = false
            }
        }
    }

    // Where every explicit download also keeps a visible copy in shared storage - needs write.
    var downloadFolderUri by remember { mutableStateOf(app.settingsStore.downloadFolderUri?.let { Uri.parse(it) }) }
    val downloadFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri != null) {
            app.downloadGate.useFolder(context.contentResolver, treeUri)
            downloadFolderUri = treeUri
        }
    }
    var downloadOptionsOpen by remember { mutableStateOf(false) }

    LargeTitleList(title = "Settings", onBack = onBack, grouped = true) {
        item("spotify") {
            GroupHeader("Spotify")
            com.abn3li.telemusic.ui.spotify.SpotifySettingsGroup(
                onOpenPlaylists = onOpenSpotify,
                onHelp = { uriHandler.openUri("https://developer.spotify.com/dashboard") }
            )
        }

        item("appearance") {
            GroupHeader("Appearance")
            GroupCard {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    if (index > 0) GroupDivider()
                    GroupOption(label = mode.label, selected = themeMode == mode, startPadding = 15) { app.settingsStore.setThemeMode(mode) }
                }
            }
        }

        item("audio_quality") { FlacQualitySettingsGroup() }

        item("now_playing") {
            GroupHeader("Now Playing")
            GroupCard {
                GroupRow(
                    title = "Lyrics Glow",
                    icon = { GroupIcon(Icons.Rounded.AutoAwesome, TilePink) },
                    trailing = {
                        GroupSwitch(playerEffects.lyricsGlow, { on -> app.settingsStore.updatePlayerEffects { it.copy(lyricsGlow = on) } })
                    }
                )
                GroupDivider(start = 57.dp)
                GroupRow(
                    title = "Lyrics Blur",
                    icon = { GroupIcon(Icons.Rounded.BlurOn, TileIndigo) },
                    trailing = {
                        GroupSwitch(playerEffects.lyricsBlur, { on -> app.settingsStore.updatePlayerEffects { it.copy(lyricsBlur = on) } })
                    }
                )
                GroupDivider(start = 57.dp)
                GroupRow(
                    title = "Animated Background",
                    icon = { GroupIcon(Icons.Rounded.Wallpaper, TilePurple) },
                    trailing = {
                        GroupSwitch(playerEffects.animatedBackground, { on -> app.settingsStore.updatePlayerEffects { it.copy(animatedBackground = on) } })
                    }
                )
            }
            SettingsNote("These effects may increase CPU and GPU usage.")
        }

        item("library") {
            GroupHeader("Library")
            GroupCard {
                GroupRow(
                    title = "Auto-fetch Song Info",
                    icon = { GroupIcon(Icons.Rounded.Lyrics, TileBlue) },
                    trailing = {
                        GroupSwitch(enrichEnabled, { enrichEnabled = it; app.settingsStore.enrichMetadataOnSync = it })
                    }
                )
                GroupDivider(start = 57.dp)
                GroupRow(
                    title = "Import Local Songs",
                    icon = { GroupIcon(Icons.Rounded.LibraryMusic, TilePink) },
                    onClick = { importFolderLauncher.launch(null) },
                    trailing = { GroupValue(null) }
                )
                GroupDivider(start = 57.dp)
                GroupRow(
                    title = "Download Location",
                    icon = { GroupIcon(Icons.Rounded.Folder, TileBlue) },
                    onClick = { downloadOptionsOpen = !downloadOptionsOpen },
                    trailing = { GroupValue(downloadFolderUri?.let { readableFolderName(it) } ?: "App Storage") }
                )
                if (downloadOptionsOpen) {
                    GroupDivider(start = 57.dp)
                    GroupOption(label = "App Storage", selected = downloadFolderUri == null, startPadding = 57) {
                        app.settingsStore.downloadFolderUri = null
                        app.settingsStore.downloadLocationChosen = true
                        downloadFolderUri = null
                        downloadOptionsOpen = false
                    }
                    GroupDivider(start = 57.dp)
                    GroupOption(
                        label = downloadFolderUri?.let { "Folder: ${readableFolderName(it)}" } ?: "Choose Folder…",
                        selected = downloadFolderUri != null,
                        startPadding = 57
                    ) {
                        downloadOptionsOpen = false
                        downloadFolderLauncher.launch(null)
                    }
                }
            }
        }

        item("storage") {
            GroupHeader("Storage")
            GroupCard {
                val cacheLabel = AppSettingsStore.CACHE_PRESETS.firstOrNull { it.second == cacheLimit }?.first ?: "Custom"
                GroupRow(
                    title = "Cache Limit",
                    icon = { GroupIcon(Icons.Rounded.SdStorage, TileOrange) },
                    onClick = { cacheOptionsOpen = !cacheOptionsOpen },
                    trailing = { GroupValue(cacheLabel) }
                )
                AnimatedVisibility(cacheOptionsOpen) {
                    Column {
                        AppSettingsStore.CACHE_PRESETS.forEach { (label, bytes) ->
                            GroupDivider(start = 57.dp)
                            GroupOption(label = label, selected = cacheLimit == bytes, startPadding = 57) {
                                cacheLimit = bytes
                                app.settingsStore.maxCacheSizeBytes = bytes
                                scope.launch { app.musicRepository.enforceCacheLimit() }
                                cacheOptionsOpen = false
                            }
                        }
                    }
                }
                GroupDivider(start = 57.dp)
                GroupRow(
                    title = "Clear Cache",
                    icon = { GroupIcon(Icons.Rounded.CleaningServices, TileGrey) },
                    titleColor = AppAccent,
                    onClick = { showClearCacheConfirm = true }
                )
                GroupDivider()
                GroupActionRow("Reset Library", color = DestructiveRed) { showClearLibraryConfirm = true }
            }
            storageActionStatus?.let { GroupFooter(it) }
        }

        item("connection") {
            GroupHeader("Connection")
            GroupCard {
                DnsResolverRows(network, icon = { GroupIcon(Icons.Rounded.Dns, TileGreen) }, title = "DNS", onChange = ::saveDns)
                GroupDivider(start = 57.dp)
                ProxyRows(
                    network,
                    onMessage = { proxyStatusMessage = it },
                    icon = { GroupIcon(Icons.Rounded.VpnKey, TileIndigo) },
                    onChange = ::saveProxy
                )
                if (dnsNeedsRestart) {
                    GroupDivider()
                    GroupActionRow("Restart to Apply DNS") {
                        // Written right here, synchronously: the process ends next.
                        writeDns(network.dns, network.customDns)
                        app.tdlibManager.applyDns(network.dns, network.customDns)
                        // Restart the process so TDLib starts fresh with the new DNS.
                        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                        context.startActivity(Intent.makeRestartActivityTask(intent?.component))
                        Runtime.getRuntime().exit(0)
                    }
                }
            }
            proxyStatusMessage?.let { message ->
                GroupFooter(
                    message,
                    color = if (message.startsWith("Couldn't") || message.startsWith("That")) DestructiveRed else GroupLabelColor
                )
            }
        }

        // Nothing to log out of until Telegram is set up in the Sync tab.
        if (app.credentialsStore.hasCredentials()) {
            item("account") {
                GroupHeader("Telegram Account")
                GroupCard {
                    GroupActionRow("Log Out", color = DestructiveRed) { showLogoutConfirm = true }
                }
            }
        }

        item("about") {
            GroupHeader("About")
            GroupCard {
                GroupRow(
                    title = "TeleMusic",
                    icon = { GroupIcon(Icons.Rounded.Info, TileGrey) },
                    trailing = { GroupValue("v$versionName", showChevron = false) }
                )
                GroupDivider(start = 57.dp)
                GroupRow(
                    title = if (isCheckingUpdate) "Checking…" else "Check for Updates",
                    icon = { GroupIcon(Icons.Rounded.SystemUpdate, TileBlue) },
                    titleColor = AppAccent,
                    enabled = !isCheckingUpdate,
                    onClick = {
                        isCheckingUpdate = true
                        updateCheckStatus = null
                        scope.launch {
                            when (val result = updateChecker.check(versionName)) {
                                is UpdateCheckResult.UpdateAvailable -> availableUpdate = result
                                UpdateCheckResult.UpToDate -> updateCheckStatus = "You're on the latest version."
                                is UpdateCheckResult.Error -> updateCheckStatus = "Couldn't check for updates: ${result.message}"
                            }
                            isCheckingUpdate = false
                        }
                    }
                )
                GroupDivider(start = 57.dp)
                LinkRow("GitHub", "abn3li/TeleMusic", Icons.Rounded.Code, TileGrey) { uriHandler.openUri("https://github.com/abn3li/TeleMusic") }
                GroupDivider(start = 57.dp)
                GroupRow(
                    title = "Open-source Licenses",
                    icon = { GroupIcon(Icons.Rounded.Code, TileGrey) },
                    onClick = { licensesOpen = true },
                    trailing = { GroupValue("GPLv3") }
                )
                GroupDivider(start = 57.dp)
                LinkRow("Developer", "@hjil_l", Icons.AutoMirrored.Rounded.Send, TileBlue) { uriHandler.openUri("https://t.me/hjil_l") }
                GroupDivider(start = 57.dp)
                LinkRow("Community", "t.me/telemusicco", Icons.Rounded.Groups, TileGreen) { uriHandler.openUri("https://t.me/telemusicco") }
            }
            updateCheckStatus?.let { GroupFooter(it) }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (licensesOpen) LicensesSheet(onDismiss = { licensesOpen = false })

    if (showClearCacheConfirm) {
        AppAlert(
            title = "Clear Cache?",
            message = "Deletes cached and partially streamed songs, unfinished YouTube downloads and saved lyrics, to free up space. Lyrics are found again when you open them. Downloads, your songs and playlists stay in your library.",
            onDismiss = { showClearCacheConfirm = false },
            actions = listOf(
                AlertAction("Cancel") { showClearCacheConfirm = false },
                AlertAction("Clear", bold = true) {
                    showClearCacheConfirm = false
                    scope.launch {
                        val result = app.musicRepository.clearStreamingCache(keepSongId = app.playbackController.currentSongId())
                        val mb = result.freedBytes / (1024 * 1024)
                        storageActionStatus = "Cleared ${result.cachedSongs} cached songs, ${result.partialFiles} partial downloads (${mb} MB) and lyrics of ${result.lyricsCleared} songs."
                    }
                }
            )
        )
    }

    if (showClearLibraryConfirm) {
        AppAlert(
            title = "Reset Library?",
            message = "This deletes ALL songs, playlists, audio files, listening history, saved lyrics, covers and import links from this device. Your Telegram and Spotify sign-ins and your settings stay. You can then sync again from any Telegram chat.",
            onDismiss = { showClearLibraryConfirm = false },
            actions = listOf(
                AlertAction("Cancel", bold = true) { showClearLibraryConfirm = false },
                AlertAction("Delete All", destructive = true) {
                    showClearLibraryConfirm = false
                    scope.launch {
                        app.musicRepository.clearAllLibrarySongs()
                        storageActionStatus = "Library reset. Ready for a fresh sync."
                    }
                }
            )
        )
    }

    if (showLogoutConfirm) {
        AppAlert(
            title = "Log Out?",
            message = "This clears your saved Telegram credentials from this device. You'll need to enter your API credentials again to sign back in.",
            onDismiss = { showLogoutConfirm = false },
            actions = listOf(
                AlertAction("Cancel", bold = true) { showLogoutConfirm = false },
                AlertAction("Log Out", destructive = true) {
                    showLogoutConfirm = false
                    scope.launch {
                        // A real logout (TdApi.LogOut + wiping the local session), not just a
                        // disconnect - otherwise the old session could be silently restored.
                        val result = app.tdlibManager.logOut()
                        app.credentialsStore.clear()
                        // Only a logout that didn't fully happen says anything.
                        val problem = when {
                            !result.sessionEnded -> "Logged out on this phone, but Telegram couldn't be reached to end the session. To be sure, remove it in Telegram > Settings > Devices."
                            !result.localDataRemoved -> "Logged out, but some Telegram files couldn't be deleted from this phone. Reset Library or reinstalling removes them."
                            else -> null
                        }
                        problem?.let { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show() }
                        onLoggedOut()
                    }
                }
            )
        )
    }

    availableUpdate?.let { update ->
        AppAlert(
            title = "Update Available",
            message = update.title + "\n\n" + (update.notes?.trim()?.takeIf { it.isNotBlank() } ?: "A newer version is available on GitHub."),
            onDismiss = { availableUpdate = null },
            actions = listOf(
                AlertAction("Later") { availableUpdate = null },
                AlertAction("View", bold = true) {
                    uriHandler.openUri(update.releaseUrl)
                    availableUpdate = null
                }
            )
        )
    }

    if (showImportSheet) {
        ImportLocalMusicSheet(
            files = localAudioFiles,
            alreadyImportedIds = alreadyImportedSongIds,
            isScanning = isScanningLocalFolder,
            onImport = { selected ->
                val count = selected.size
                // Runs on the app-scoped coroutine, not this screen's - a large import can outlast
                // this screen if the user switches tabs.
                storageActionStatus = "Importing $count song(s)…"
                app.importLocalSongsInBackground(selected) {
                    storageActionStatus = if (enrichEnabled) {
                        app.enrichLibraryInBackground()
                        "Imported $count song(s). Fetching artwork in the background…"
                    } else {
                        "Imported $count song(s)."
                    }
                }
                showImportSheet = false
            },
            onDismiss = {
                showImportSheet = false
                localAudioFiles = emptyList()
            }
        )
    }
}

/** A small, secondary note under a group: a muted info icon and one line of text. */
@Composable
internal fun SettingsNote(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = GroupLabelColor.copy(alpha = 0.8f), modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(5.dp))
        Text(text, color = GroupLabelColor, fontSize = 12.sp, lineHeight = 15.sp)
    }
}

/** The DNS Telegram started with in this process - see SettingsScreen's restart row. */
private var dnsAtLaunch: Pair<com.abn3li.telemusic.data.settings.DnsResolver, String>? = null

@Composable
private fun LinkRow(title: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tile: Color, onClick: () -> Unit) {
    GroupRow(
        title = title,
        icon = { GroupIcon(icon, tile) },
        onClick = onClick,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(value, color = ink.copy(alpha = 0.45f), fontSize = 15.sp, modifier = Modifier.padding(end = 6.dp))
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = ink.copy(alpha = 0.3f), modifier = Modifier.size(15.dp))
            }
        }
    )
}
