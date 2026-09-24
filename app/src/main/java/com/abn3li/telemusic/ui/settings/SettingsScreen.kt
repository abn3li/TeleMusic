package com.abn3li.telemusic.ui.settings

import com.abn3li.telemusic.ui.library.AppAlert
import com.abn3li.telemusic.ui.library.AlertAction
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.ContentPaste
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.data.settings.AppSettingsStore
import com.abn3li.telemusic.data.settings.DnsResolver
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
import com.abn3li.telemusic.ui.library.GroupTextField
import com.abn3li.telemusic.ui.library.GroupValue
import com.abn3li.telemusic.ui.library.LargeTitleList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A SAF tree URI's own document id looks like "primary:Music/TeleMusic" - the part after the
 * last "/" is the folder name the user actually picked. */
private fun readableFolderName(uri: Uri): String =
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
fun SettingsScreen(onBack: () -> Unit, onLoggedOut: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as TgMusicApp
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current

    var enrichEnabled by remember { mutableStateOf(app.settingsStore.enrichMetadataOnSync) }
    val playerEffects by app.settingsStore.playerEffects.collectAsState()
    var cacheLimit by remember { mutableStateOf(app.settingsStore.maxCacheSizeBytes) }
    var cacheOptionsOpen by remember { mutableStateOf(false) }

    var selectedDns by remember { mutableStateOf(app.settingsStore.dnsResolver) }
    var customDnsInput by remember { mutableStateOf(app.settingsStore.customDnsIps) }
    var dnsOptionsOpen by remember { mutableStateOf(false) }

    val currentProxy = remember { app.settingsStore.proxySettings }
    var proxyEnabled by remember { mutableStateOf(currentProxy.enabled) }
    var proxyServer by remember { mutableStateOf(currentProxy.server) }
    var proxyPortText by remember { mutableStateOf(currentProxy.port.toString()) }
    var proxySecret by remember { mutableStateOf(currentProxy.secret) }
    var proxyPasteInput by remember { mutableStateOf("") }
    var proxyStatusMessage by remember { mutableStateOf<String?>(null) }
    var isApplyingProxy by remember { mutableStateOf(false) }

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
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            app.settingsStore.downloadFolderUri = treeUri.toString()
            downloadFolderUri = treeUri
        }
    }

    LargeTitleList(title = "Settings", onBack = onBack) {
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
            GroupFooter("Glow makes lyrics shine over the artwork. Blur softens the lines around the one being sung (Android 12+) and uses more GPU. The background drifts slowly while music plays.")
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
                    onClick = { downloadFolderLauncher.launch(null) },
                    trailing = { GroupValue(downloadFolderUri?.let { readableFolderName(it) } ?: "Not Set") }
                )
            }
            GroupFooter("Auto-fetch looks up titles, artists, covers and lyrics. Downloads also get a visible copy in the download location, if one is set.")
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
            GroupFooter(
                storageActionStatus
                    ?: "Streamed songs are cached up to the limit, oldest first. Downloads are never removed. Reset Library deletes all songs, playlists and audio."
            )
        }

        item("dns") {
            GroupHeader("DNS")
            GroupCard {
                GroupRow(
                    title = "Resolver",
                    icon = { GroupIcon(Icons.Rounded.Dns, TileGreen) },
                    onClick = { dnsOptionsOpen = !dnsOptionsOpen },
                    trailing = { GroupValue(selectedDns.displayName.substringBefore(" (")) }
                )
                AnimatedVisibility(dnsOptionsOpen) {
                    Column {
                        DnsResolver.entries.forEach { resolver ->
                            GroupDivider(start = 57.dp)
                            GroupOption(
                                label = resolver.displayName,
                                detail = "${resolver.source} · ${resolver.description}",
                                selected = selectedDns == resolver,
                                startPadding = 57
                            ) {
                                selectedDns = resolver
                                dnsOptionsOpen = false
                            }
                        }
                    }
                }
                if (selectedDns == DnsResolver.CUSTOM) {
                    GroupDivider()
                    GroupTextField(
                        value = customDnsInput,
                        onValueChange = { customDnsInput = it },
                        placeholder = "1.1.1.1,8.8.8.8",
                        label = "Servers"
                    )
                }
                GroupDivider()
                GroupActionRow("Apply & Restart App") {
                    app.settingsStore.dnsResolver = selectedDns
                    app.settingsStore.customDnsIps = customDnsInput
                    app.tdlibManager.applyDns(selectedDns, customDnsInput)
                    // Restart the process so TDLib starts fresh with the new DNS.
                    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                    context.startActivity(Intent.makeRestartActivityTask(intent?.component))
                    Runtime.getRuntime().exit(0)
                }
            }
            GroupFooter("Bypasses ISP DNS blocking and can speed up connecting. Presets from Telegram, Telegram X and Nagram X.")
        }

        item("proxy") {
            GroupHeader("MTProto Proxy")
            GroupCard {
                GroupRow(
                    title = "Use Proxy",
                    icon = { GroupIcon(Icons.Rounded.VpnKey, TileIndigo) },
                    trailing = { GroupSwitch(proxyEnabled, { proxyEnabled = it }) }
                )
                if (proxyEnabled) {
                    GroupDivider()
                    GroupTextField(
                        value = proxyPasteInput,
                        onValueChange = { proxyPasteInput = it },
                        placeholder = "Paste a t.me/proxy link",
                        trailing = {
                            Icon(
                                Icons.Rounded.ContentPaste,
                                contentDescription = "Fill from link or clipboard",
                                tint = AppAccent,
                                modifier = Modifier.size(22.dp).clickable {
                                    val clipText = clipboardManager.getText()?.text
                                    val textToParse = if (!clipText.isNullOrBlank()) clipText else proxyPasteInput
                                    val parsed = AppSettingsStore.parseTelegramProxyUrl(textToParse)
                                    if (parsed != null) {
                                        proxyServer = parsed.server
                                        proxyPortText = parsed.port.toString()
                                        proxySecret = parsed.secret
                                        proxyStatusMessage = "Filled in from the proxy link."
                                    } else {
                                        proxyStatusMessage = "That isn't a valid Telegram proxy link."
                                    }
                                }
                            )
                        }
                    )
                    GroupDivider()
                    GroupTextField(value = proxyServer, onValueChange = { proxyServer = it }, placeholder = "Hostname or IP", label = "Server")
                    GroupDivider()
                    GroupTextField(
                        value = proxyPortText,
                        onValueChange = { proxyPortText = it.filter { c -> c.isDigit() } },
                        placeholder = "443",
                        label = "Port",
                        keyboardType = KeyboardType.Number
                    )
                    GroupDivider()
                    GroupTextField(value = proxySecret, onValueChange = { proxySecret = it }, placeholder = "Hex secret", label = "Secret")
                }
                GroupDivider()
                GroupActionRow("Save & Connect", loading = isApplyingProxy) {
                    val port = proxyPortText.toIntOrNull() ?: 443
                    app.settingsStore.updateProxy(proxyEnabled, proxyServer, port, proxySecret)
                    scope.launch {
                        isApplyingProxy = true
                        proxyStatusMessage = "Connecting…"
                        val success = app.tdlibManager.applyProxy(proxyEnabled, proxyServer, port, proxySecret)
                        isApplyingProxy = false
                        proxyStatusMessage = when {
                            !proxyEnabled -> "Using a direct connection."
                            success -> "Proxy connected."
                            else -> "Couldn't connect to the proxy."
                        }
                    }
                }
            }
            GroupFooter(
                proxyStatusMessage ?: "Use an MTProto proxy if Telegram is blocked or restricted where you are.",
                color = if (proxyStatusMessage?.startsWith("Couldn't") == true || proxyStatusMessage?.startsWith("That") == true) DestructiveRed
                else androidx.compose.ui.graphics.Color(0xFF8E8D93)
            )
        }

        item("account") {
            GroupHeader("Telegram Account")
            GroupCard {
                GroupActionRow("Log Out", color = DestructiveRed) { showLogoutConfirm = true }
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
                LinkRow("Developer", "@hjil_l", Icons.AutoMirrored.Rounded.Send, TileBlue) { uriHandler.openUri("https://t.me/hjil_l") }
                GroupDivider(start = 57.dp)
                LinkRow("Community", "t.me/telemusicco", Icons.Rounded.Groups, TileGreen) { uriHandler.openUri("https://t.me/telemusicco") }
            }
            updateCheckStatus?.let { GroupFooter(it) }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (showClearCacheConfirm) {
        AppAlert(
            title = "Clear Cache?",
            message = "Deletes cached and partially streamed songs, and unfinished YouTube downloads, to free up space. Downloads, your songs and playlists stay in your library.",
            onDismiss = { showClearCacheConfirm = false },
            actions = listOf(
                AlertAction("Cancel") { showClearCacheConfirm = false },
                AlertAction("Clear", bold = true) {
                    showClearCacheConfirm = false
                    scope.launch {
                        val result = app.musicRepository.clearStreamingCache(keepSongId = app.playbackController.currentSongId())
                        val mb = result.freedBytes / (1024 * 1024)
                        storageActionStatus = "Cleared ${result.cachedSongs} cached songs and ${result.partialFiles} partial downloads (${mb} MB)."
                    }
                }
            )
        )
    }

    if (showClearLibraryConfirm) {
        AppAlert(
            title = "Reset Library?",
            message = "This deletes ALL songs, playlists and audio files from this device. You can then sync again from any Telegram chat.",
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
                        app.tdlibManager.logOut()
                        app.credentialsStore.clear()
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

@Composable
private fun LinkRow(title: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tile: Color, onClick: () -> Unit) {
    GroupRow(
        title = title,
        icon = { GroupIcon(icon, tile) },
        onClick = onClick,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(value, color = Color.White.copy(alpha = 0.45f), fontSize = 15.sp, modifier = Modifier.padding(end = 6.dp))
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(15.dp))
            }
        }
    )
}
