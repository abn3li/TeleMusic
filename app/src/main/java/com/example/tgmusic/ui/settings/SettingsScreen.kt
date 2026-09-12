package com.example.tgmusic.ui.settings

import android.content.Intent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.tgmusic.TgMusicApp
import com.example.tgmusic.data.settings.AppColorScheme
import com.example.tgmusic.data.settings.AppSettingsStore
import com.example.tgmusic.data.settings.AppThemeMode
import com.example.tgmusic.data.settings.DnsResolver
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onLoggedOut: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as TgMusicApp
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    var selectedThemeMode by remember { mutableStateOf(app.settingsStore.themeMode) }
    var selectedColorScheme by remember { mutableStateOf(app.settingsStore.colorScheme) }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ---- APPEARANCE & THEME SECTION ----
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Palette,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "Appearance & Theme",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    Text(
                        "Theme Mode",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(8.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            ThemeModeChip(
                                mode = AppThemeMode.SYSTEM,
                                isSelected = selectedThemeMode == AppThemeMode.SYSTEM,
                                modifier = Modifier.weight(1f)
                            ) {
                                selectedThemeMode = AppThemeMode.SYSTEM
                                app.settingsStore.themeMode = AppThemeMode.SYSTEM
                            }
                            ThemeModeChip(
                                mode = AppThemeMode.LIGHT,
                                isSelected = selectedThemeMode == AppThemeMode.LIGHT,
                                modifier = Modifier.weight(1f)
                            ) {
                                selectedThemeMode = AppThemeMode.LIGHT
                                app.settingsStore.themeMode = AppThemeMode.LIGHT
                            }
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            ThemeModeChip(
                                mode = AppThemeMode.DARK,
                                isSelected = selectedThemeMode == AppThemeMode.DARK,
                                modifier = Modifier.weight(1f)
                            ) {
                                selectedThemeMode = AppThemeMode.DARK
                                app.settingsStore.themeMode = AppThemeMode.DARK
                            }
                            ThemeModeChip(
                                mode = AppThemeMode.AMOLED,
                                isSelected = selectedThemeMode == AppThemeMode.AMOLED,
                                modifier = Modifier.weight(1f)
                            ) {
                                selectedThemeMode = AppThemeMode.AMOLED
                                app.settingsStore.themeMode = AppThemeMode.AMOLED
                            }
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    Text(
                        "Cute Color Palettes",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(8.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppColorScheme.entries.forEach { scheme ->
                            ColorSchemeRow(
                                scheme = scheme,
                                isSelected = selectedColorScheme == scheme,
                                onClick = {
                                    selectedColorScheme = scheme
                                    app.settingsStore.colorScheme = scheme
                                }
                            )
                        }
                    }
                }
            }

            // ---- DNS RESOLVERS SECTION (TELEGRAM X / NAGRAM X) ----
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
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
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
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
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
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
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
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
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
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

            Spacer(Modifier.height(16.dp))
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
}

@Composable
private fun ThemeModeChip(
    mode: AppThemeMode,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val containerColor by animateColorAsState(
        if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        label = "containerColor"
    )
    val contentColor by animateColorAsState(
        if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        label = "contentColor"
    )
    val border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        contentColor = contentColor,
        border = border,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            val icon = when (mode) {
                AppThemeMode.SYSTEM -> Icons.Default.SettingsSuggest
                AppThemeMode.LIGHT -> Icons.Default.LightMode
                AppThemeMode.DARK -> Icons.Default.DarkMode
                AppThemeMode.AMOLED -> Icons.Default.Contrast
            }
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                mode.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}

@Composable
private fun ColorSchemeRow(
    scheme: AppColorScheme,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surfaceContainerHigh

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        border = BorderStroke(if (isSelected) 2.dp else 1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Dual Color Preview Swatch
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(scheme.primaryHex)),
                    contentAlignment = Alignment.BottomEnd
                ) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(Color(scheme.secondaryHex))
                            .border(1.5.dp, Color.White, CircleShape)
                    )
                }

                Column {
                    Text(
                        scheme.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                    Text(
                        scheme.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (isSelected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}