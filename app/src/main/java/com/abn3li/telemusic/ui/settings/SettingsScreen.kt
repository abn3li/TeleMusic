package com.abn3li.telemusic.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.data.settings.AmbientColorSet
import com.abn3li.telemusic.data.settings.AppSettingsStore
import com.abn3li.telemusic.data.settings.AppearanceStyle
import com.abn3li.telemusic.data.settings.DnsResolver
import com.abn3li.telemusic.ui.library.blobColorsFor
import com.abn3li.telemusic.data.update.UpdateChecker
import com.abn3li.telemusic.ui.nowplaying.LocalMiniPlayerInset
import com.abn3li.telemusic.data.update.UpdateCheckResult
import com.abn3li.telemusic.repository.LocalAudioFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A SAF tree URI's own document id looks like "primary:Music/TeleMusic" - the part after the
 * last "/" is the actual folder name a user picked, which reads far better in Settings than the
 * raw content:// URI string. */
private fun readableFolderName(uri: Uri): String =
    uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "Folder selected"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onLoggedOut: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as TgMusicApp
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    var enrichEnabled by remember { mutableStateOf(app.settingsStore.enrichMetadataOnSync) }
    var cacheLimit by remember { mutableStateOf(app.settingsStore.maxCacheSizeBytes) }

    // DNS Resolver State
    var selectedDns by remember { mutableStateOf(app.settingsStore.dnsResolver) }
    var customDnsInput by remember { mutableStateOf(app.settingsStore.customDnsIps) }
    var dnsStatusMessage by remember { mutableStateOf<String?>(null) }
    var dnsMenuExpanded by remember { mutableStateOf(false) }

    // Proxy State
    val currentProxy = remember { app.settingsStore.proxySettings }
    var proxyEnabled by remember { mutableStateOf(currentProxy.enabled) }
    var proxyServer by remember { mutableStateOf(currentProxy.server) }
    var proxyPortText by remember { mutableStateOf(currentProxy.port.toString()) }
    var proxySecret by remember { mutableStateOf(currentProxy.secret) }
    var proxyPasteInput by remember { mutableStateOf("") }
    var proxyStatusMessage by remember { mutableStateOf<String?>(null) }
    var isApplyingProxy by remember { mutableStateOf(false) }

    // Storage Management Action State
    var showClearCacheConfirm by remember { mutableStateOf(false) }
    var showClearLibraryConfirm by remember { mutableStateOf(false) }
    var storageActionStatus by remember { mutableStateOf<String?>(null) }

    var showLogoutConfirm by remember { mutableStateOf(false) }
    var cacheMenuExpanded by remember { mutableStateOf(false) }

    // Update check state
    val updateChecker = remember { UpdateChecker() }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateCheckStatus by remember { mutableStateOf<String?>(null) }
    var availableUpdate by remember { mutableStateOf<UpdateCheckResult.UpdateAvailable?>(null) }

    // Import from local storage state
    var localAudioFiles by remember { mutableStateOf<List<LocalAudioFile>>(emptyList()) }
    var alreadyImportedSongIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var isScanningLocalFolder by remember { mutableStateOf(false) }
    var showImportSheet by remember { mutableStateOf(false) }

    // Opens the system file explorer's folder picker (Storage Access Framework) - no storage
    // permission needed at all, that grant is independent of READ_MEDIA_AUDIO/
    // READ_EXTERNAL_STORAGE. See MusicRepository.scanLocalFolder()'s own doc.
    val importFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            showImportSheet = true
            isScanningLocalFolder = true
            scope.launch {
                localAudioFiles = withContext(Dispatchers.IO) { app.musicRepository.scanLocalFolder(treeUri) }
                alreadyImportedSongIds = withContext(Dispatchers.IO) { app.musicRepository.getLocalImportSongIds() }
                isScanningLocalFolder = false
            }
        }
    }

    // Where every explicit download (YouTube or Telegram) also keeps a visible, real copy in
    // shared storage - see MusicRepository.exportToDownloadFolderIfConfigured's own doc. Needs
    // both read and write permission, unlike importFolderLauncher above which only ever reads.
    var downloadFolderUri by remember { mutableStateOf(app.settingsStore.downloadFolderUri?.let { Uri.parse(it) }) }
    val downloadFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
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

    // Root ambient-blur background only for now - the individual Card sections below still use
    // their existing MaterialTheme.colorScheme.surfaceVariant fill (they already read fine as
    // opaque cards over the blur; a full glass-surface reskin of every section here is future
    // work, not part of this pass).
    com.abn3li.telemusic.ui.library.AdaptiveScreenBackground {
    Scaffold(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 8.dp + LocalMiniPlayerInset.current),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ---- APPEARANCE (Classic dark cards vs. the ambient-blur/glass look) ----
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("Appearance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Choose the Library/Settings screens' look.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    val appearanceStyle by app.musicRepository.observeAppearanceStyle().collectAsState()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        AppearanceOptionChip(
                            label = "Classic",
                            selected = appearanceStyle == AppearanceStyle.CLASSIC,
                            modifier = Modifier.weight(1f),
                            onClick = { app.musicRepository.setAppearanceStyle(AppearanceStyle.CLASSIC) }
                        )
                        AppearanceOptionChip(
                            label = "Ambient blur",
                            selected = appearanceStyle == AppearanceStyle.AMBIENT_BLUR,
                            modifier = Modifier.weight(1f),
                            onClick = { app.musicRepository.setAppearanceStyle(AppearanceStyle.AMBIENT_BLUR) }
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    Text("Ambient colors", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Which blob palette the ambient-blur background uses.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    val ambientColorSet by app.musicRepository.observeAmbientColorSet().collectAsState()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        AmbientColorSet.entries.forEach { set ->
                            AmbientColorSetChip(
                                set = set,
                                selected = ambientColorSet == set,
                                modifier = Modifier.weight(1f),
                                onClick = { app.musicRepository.setAmbientColorSet(set) }
                            )
                        }
                    }
                }
            }

            // ---- DNS RESOLVERS SECTION (TELEGRAM X / NAGRAM X) ----
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Dns,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "DNS Resolvers (Anti-Censorship)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Bypass ISP DNS blocking & speed up connection. Presets sourced from Telegram, Telegram X, and Nagram X.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(12.dp))

                    Box {
                        OutlinedCard(
                            onClick = { dnsMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(selectedDns.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.primaryContainer
                                        ) {
                                            Text(
                                                selectedDns.source,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Text(selectedDns.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(Icons.Default.ArrowDropDown, contentDescription = "Select DNS")
                            }
                        }

                        DropdownMenu(
                            expanded = dnsMenuExpanded,
                            onDismissRequest = { dnsMenuExpanded = false }
                        ) {
                            DnsResolver.entries.forEach { resolver ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text(resolver.displayName, fontWeight = FontWeight.Bold)
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = MaterialTheme.colorScheme.surfaceVariant
                                                ) {
                                                    Text(
                                                        resolver.source,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                            Text(resolver.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    },
                                    onClick = {
                                        selectedDns = resolver
                                        dnsMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    if (selectedDns == DnsResolver.CUSTOM) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = customDnsInput,
                            onValueChange = { customDnsInput = it },
                            label = { Text("Custom DNS Server IPs (comma-separated)") },
                            placeholder = { Text("1.1.1.1,8.8.8.8") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    dnsStatusMessage?.let { status ->
                        Spacer(Modifier.height(8.dp))
                        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                    }

                    Spacer(Modifier.height(12.dp))

                    Button(
                        onClick = {
                            app.settingsStore.dnsResolver = selectedDns
                            app.settingsStore.customDnsIps = customDnsInput
                            app.tdlibManager.applyDns(selectedDns, customDnsInput)

                            // Restart app process cleanly so TDLib initializes fresh with the new DNS from byte 0
                            val pm = context.packageManager
                            val intent = pm.getLaunchIntentForPackage(context.packageName)
                            val componentName = intent?.component
                            val mainIntent = Intent.makeRestartActivityTask(componentName)
                            context.startActivity(mainIntent)
                            Runtime.getRuntime().exit(0)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Dns, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Apply DNS & Restart App")
                    }
                }
            }

            // ---- MTPROTO PROXY (ANTI-CENSORSHIP) SECTION ----
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.VpnKey,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "MTProto Proxy (Bypass Censorship)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Enable an MTProto proxy if Telegram is blocked or restricted in your region.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(12.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Use MTProto Proxy",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Switch(
                            checked = proxyEnabled,
                            onCheckedChange = { checked ->
                                proxyEnabled = checked
                            }
                        )
                    }

                    if (proxyEnabled) {
                        Spacer(Modifier.height(12.dp))

                        // Quick Paste Proxy Link
                        OutlinedTextField(
                            value = proxyPasteInput,
                            onValueChange = { proxyPasteInput = it },
                            label = { Text("Paste Proxy Link (tg://proxy?server=...)") },
                            placeholder = { Text("https://t.me/proxy?server=...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        val clipText = clipboardManager.getText()?.text
                                        val textToParse = if (!clipText.isNullOrBlank()) clipText else proxyPasteInput
                                        val parsed = AppSettingsStore.parseTelegramProxyUrl(textToParse)
                                        if (parsed != null) {
                                            proxyServer = parsed.server
                                            proxyPortText = parsed.port.toString()
                                            proxySecret = parsed.secret
                                            proxyStatusMessage = "Auto-filled proxy settings!"
                                        } else {
                                            proxyStatusMessage = "Invalid Telegram proxy URL"
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.ContentPaste, contentDescription = "Auto-fill from link or clipboard")
                                }
                            }
                        )

                        Spacer(Modifier.height(8.dp))

                        OutlinedTextField(
                            value = proxyServer,
                            onValueChange = { proxyServer = it },
                            label = { Text("Server IP or Hostname") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(8.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = proxyPortText,
                                onValueChange = { proxyPortText = it.filter { c -> c.isDigit() } },
                                label = { Text("Port") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = proxySecret,
                                onValueChange = { proxySecret = it },
                                label = { Text("Secret (hex)") },
                                singleLine = true,
                                modifier = Modifier.weight(2f)
                            )
                        }
                    }

                    proxyStatusMessage?.let { status ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            status,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (status.contains("Active") || status.contains("Auto-filled")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    Button(
                        onClick = {
                            val port = proxyPortText.toIntOrNull() ?: 443
                            app.settingsStore.updateProxy(proxyEnabled, proxyServer, port, proxySecret)
                            scope.launch {
                                isApplyingProxy = true
                                proxyStatusMessage = "Connecting to MTProto Proxy..."
                                val success = app.tdlibManager.applyProxy(proxyEnabled, proxyServer, port, proxySecret)
                                isApplyingProxy = false
                                proxyStatusMessage = if (proxyEnabled && success) "MTProto Proxy Active & Connected!" else if (!proxyEnabled) "Direct Connection Enabled" else "Failed to connect proxy"
                            }
                        },
                        enabled = !isApplyingProxy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isApplyingProxy) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Save & Connect Proxy")
                    }
                }
            }

            // ---- SYNC SECTION ----
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Sync,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "Sync & Metadata",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Auto-fetch song info & covers", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                "Looks up title, artist, album art, and lyrics automatically",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = enrichEnabled,
                            onCheckedChange = {
                                enrichEnabled = it
                                app.settingsStore.enrichMetadataOnSync = it
                            }
                        )
                    }
                }
            }

            // ---- STORAGE & LIBRARY MANAGEMENT SECTION ----
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Storage,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "Storage & Library Management",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Auto-cache limit", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                "Streamed tracks are cached up to this limit (oldest evicted first). Explicit downloads are never removed.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Box {
                            FilledTonalButton(onClick = { cacheMenuExpanded = true }) {
                                Text(AppSettingsStore.CACHE_PRESETS.firstOrNull { it.second == cacheLimit }?.first ?: "Custom")
                            }
                            DropdownMenu(
                                expanded = cacheMenuExpanded,
                                onDismissRequest = { cacheMenuExpanded = false }
                            ) {
                                AppSettingsStore.CACHE_PRESETS.forEach { (label, bytes) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            cacheLimit = bytes
                                            app.settingsStore.maxCacheSizeBytes = bytes
                                            cacheMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    Spacer(Modifier.height(16.dp))

                    // Import Local Songs Button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Import local songs", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Pick a folder on your device to add its audio files to your Tracks library.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { importFolderLauncher.launch(null) }) {
                            Icon(Icons.Default.LibraryMusic, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Import")
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    Spacer(Modifier.height(16.dp))

                    // Download Location (shared/media storage - see MediaFolderExporter)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Download location", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                downloadFolderUri?.let { uri -> readableFolderName(uri) }
                                    ?: "Not set - downloaded songs stay app-private only",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { downloadFolderLauncher.launch(null) }) {
                            Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (downloadFolderUri == null) "Choose" else "Change")
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    Spacer(Modifier.height(16.dp))

                    // Clear Cache Only Button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Clear Cache Only", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Deletes cached audio files from disk to free up space. Keeps song titles and library intact.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = { showClearCacheConfirm = true }
                        ) {
                            Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Clear Cache")
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Clear All Songs & Reset Library Button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Clear All Songs & Reset Library", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                            Text(
                                "Deletes all songs, playlists, and cached audio. Allows a 100% fresh sync from any channel.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { showClearLibraryConfirm = true },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Reset Library")
                        }
                    }

                    storageActionStatus?.let { status ->
                        Spacer(Modifier.height(10.dp))
                        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                    }
                }
            }

            // ---- TELEGRAM ACCOUNT SECTION ----
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.AccountCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "Telegram Account",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = { showLogoutConfirm = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Log out / clear credentials")
                    }
                }
            }

            // ---- ABOUT SECTION ----
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "About",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    val versionName = remember {
                        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
                            .getOrNull() ?: "1.0"
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("TeleMusic", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text("v$versionName", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    Spacer(Modifier.height(10.dp))

                    OutlinedButton(
                        onClick = {
                            isCheckingUpdate = true
                            updateCheckStatus = null
                            scope.launch {
                                when (val result = updateChecker.check(versionName)) {
                                    is UpdateCheckResult.UpdateAvailable -> {
                                        availableUpdate = result
                                    }
                                    UpdateCheckResult.UpToDate -> {
                                        updateCheckStatus = "You're on the latest version"
                                    }
                                    is UpdateCheckResult.Error -> {
                                        updateCheckStatus = "Couldn't check for updates: ${result.message}"
                                    }
                                }
                                isCheckingUpdate = false
                            }
                        },
                        enabled = !isCheckingUpdate,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isCheckingUpdate) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Checking...")
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Check for updates")
                        }
                    }

                    updateCheckStatus?.let { status ->
                        Spacer(Modifier.height(8.dp))
                        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    Spacer(Modifier.height(12.dp))

                    AboutLinkRow(
                        icon = Icons.Default.Code,
                        label = "GitHub",
                        value = "github.com/abn3li/TeleMusic",
                        url = "https://github.com/abn3li/TeleMusic"
                    )

                    Spacer(Modifier.height(10.dp))

                    AboutLinkRow(
                        icon = Icons.Default.Send,
                        label = "Developer",
                        value = "@hjil_l",
                        url = "https://t.me/hjil_l"
                    )

                    Spacer(Modifier.height(10.dp))

                    AboutLinkRow(
                        icon = Icons.Default.Groups,
                        label = "Community",
                        value = "t.me/telemusicco",
                        url = "https://t.me/telemusicco"
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
    }

    if (showClearCacheConfirm) {
        AlertDialog(
            onDismissRequest = { showClearCacheConfirm = false },
            title = { Text("Clear Streaming Cache?") },
            text = { Text("This deletes all auto-cached streaming audio files from device storage to free up disk space. Your song entries and playlists will remain in your library.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearCacheConfirm = false
                        scope.launch {
                            val cleared = app.musicRepository.clearStreamingCache()
                            storageActionStatus = "Cleared $cleared cached audio files from storage!"
                        }
                    }
                ) { Text("Clear Cache") }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showClearLibraryConfirm) {
        AlertDialog(
            onDismissRequest = { showClearLibraryConfirm = false },
            title = { Text("Reset Library?") },
            text = { Text("This will delete ALL songs, playlists, and audio files from your device. You can then perform a fresh sync from any Telegram channel.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearLibraryConfirm = false
                        scope.launch {
                            app.musicRepository.clearAllLibrarySongs()
                            storageActionStatus = "Library reset completely! Ready for a fresh sync."
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete Everything") }
            },
            dismissButton = {
                TextButton(onClick = { showClearLibraryConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text("Log out?") },
            text = { Text("This clears your saved Telegram credentials from this device. You'll need to re-enter your API credentials to sign back in.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutConfirm = false
                        scope.launch {
                            // A real logout (TdApi.LogOut + wiping the local session database),
                            // not just disconnecting - otherwise the phone number/session was
                            // still on disk and could get silently restored on the next login.
                            app.tdlibManager.logOut()
                            app.credentialsStore.clear()
                            onLoggedOut()
                        }
                    }
                ) { Text("Log out", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) { Text("Cancel") }
            }
        )
    }

    availableUpdate?.let { update ->
        val uriHandler = LocalUriHandler.current
        AlertDialog(
            onDismissRequest = { availableUpdate = null },
            icon = { Icon(Icons.Default.Refresh, contentDescription = null) },
            title = { Text("Update available: ${update.title}") },
            text = {
                Text(
                    update.notes?.trim()?.takeIf { it.isNotBlank() }
                        ?: "A newer version is available on GitHub."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        uriHandler.openUri(update.releaseUrl)
                        availableUpdate = null
                    }
                ) { Text("View on GitHub") }
            },
            dismissButton = {
                TextButton(onClick = { availableUpdate = null }) { Text("Later") }
            }
        )
    }

    if (showImportSheet) {
        ImportLocalMusicSheet(
            files = localAudioFiles,
            alreadyImportedIds = alreadyImportedSongIds,
            isScanning = isScanningLocalFolder,
            onImport = { selected ->
                val count = selected.size
                scope.launch {
                    // The import itself is just file copies + DB writes - fine on this
                    // screen-scoped coroutine even if the user navigates away right after.
                    // Enrichment is the part that can't live here: see
                    // TgMusicApp.enrichLibraryInBackground()'s own doc for why.
                    app.musicRepository.importLocalSongs(selected)
                    if (enrichEnabled) {
                        app.enrichLibraryInBackground()
                        storageActionStatus = "Imported $count song(s) - fetching artwork in the background..."
                    } else {
                        storageActionStatus = "Imported $count song(s)!"
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

/** One selectable pill in the Appearance section's Classic/Ambient blur choice. */
@Composable
private fun AppearanceOptionChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/** One selectable swatch in the Appearance section's "Ambient colors" picker - a small stack of
 * that set's own blob colors (see blobColorsFor) plus its label, so the choice is visible before
 * tapping instead of just a name. */
@Composable
private fun AmbientColorSetChip(set: AmbientColorSet, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = remember(set) { blobColorsFor(set).take(4) }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface)
            .then(
                if (selected) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))
                else Modifier
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
            colors.forEach { c ->
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape)
                        .background(c)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = set.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** One clickable row in the About card - opens [url] in the user's browser/Telegram app. */
@Composable
private fun AboutLinkRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    url: String
) {
    val uriHandler = LocalUriHandler.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { uriHandler.openUri(url) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
    }
}
