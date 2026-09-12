package com.abn3li.telemusic.ui.credentials

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.data.settings.AppSettingsStore
import com.abn3li.telemusic.data.settings.DnsResolver
import kotlinx.coroutines.launch

@Composable
fun CredentialsScreen(onSaved: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as TgMusicApp
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    var apiId by remember { mutableStateOf("") }
    var apiHash by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    // DNS Resolver State
    var selectedDns by remember { mutableStateOf(app.settingsStore.dnsResolver) }
    var customDnsInput by remember { mutableStateOf(app.settingsStore.customDnsIps) }
    var dnsMenuExpanded by remember { mutableStateOf(false) }

    // Proxy Setup for restricted regions
    var showProxySection by remember { mutableStateOf(false) }
    val currentProxy = remember { app.settingsStore.proxySettings }
    var proxyEnabled by remember { mutableStateOf(currentProxy.enabled) }
    var proxyServer by remember { mutableStateOf(currentProxy.server) }
    var proxyPortText by remember { mutableStateOf(currentProxy.port.toString()) }
    var proxySecret by remember { mutableStateOf(currentProxy.secret) }
    var proxyPasteInput by remember { mutableStateOf("") }
    var proxyMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Connect your Telegram account", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Get your own api_id and api_hash for free at my.telegram.org -> API Development Tools. " +
                "These identify this app, not your account - your login still needs your phone number and a code next. Stored encrypted on this device only.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = apiId,
            onValueChange = { apiId = it.filter { c -> c.isDigit() } },
            label = { Text("api_id") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = apiHash,
            onValueChange = { apiHash = it.trim() },
            label = { Text("api_hash") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(16.dp))

        // DNS Resolver Option
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("DNS Resolver (Bypass DNS Blocks)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }

                Spacer(Modifier.height(8.dp))

                Box {
                    OutlinedCard(
                        onClick = { dnsMenuExpanded = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(selectedDns.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer
                                    ) {
                                        Text(
                                            selectedDns.source,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                }
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
            }
        }

        Spacer(Modifier.height(12.dp))

        // Expandable Anti-Censorship Proxy Option
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.VpnKey, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("MTProto Proxy (Optional)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                    TextButton(onClick = { showProxySection = !showProxySection }) {
                        Text(if (showProxySection) "Hide" else "Configure")
                    }
                }

                if (showProxySection) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Enable Proxy", style = MaterialTheme.typography.bodySmall)
                        Switch(checked = proxyEnabled, onCheckedChange = { proxyEnabled = it })
                    }

                    if (proxyEnabled) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = proxyPasteInput,
                            onValueChange = { proxyPasteInput = it },
                            label = { Text("Paste Proxy Link (tg://proxy?...)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                IconButton(onClick = {
                                    val clipText = clipboardManager.getText()?.text
                                    val textToParse = if (!clipText.isNullOrBlank()) clipText else proxyPasteInput
                                    val parsed = AppSettingsStore.parseTelegramProxyUrl(textToParse)
                                    if (parsed != null) {
                                        proxyServer = parsed.server
                                        proxyPortText = parsed.port.toString()
                                        proxySecret = parsed.secret
                                        proxyMessage = "Auto-filled from proxy link!"
                                    } else {
                                        proxyMessage = "Invalid proxy link format"
                                    }
                                }) {
                                    Icon(Icons.Default.ContentPaste, contentDescription = "Auto-fill")
                                }
                            }
                        )

                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = proxyServer,
                            onValueChange = { proxyServer = it },
                            label = { Text("Server IP/Host") },
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
                                label = { Text("Secret") },
                                singleLine = true,
                                modifier = Modifier.weight(2f)
                            )
                        }
                    }

                    proxyMessage?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                val id = apiId.toIntOrNull()
                if (id == null || id == 0) { error = "Enter a valid api_id (numbers only)"; return@Button }
                if (apiHash.isBlank()) { error = "Enter your api_hash"; return@Button }

                app.settingsStore.dnsResolver = selectedDns
                app.settingsStore.customDnsIps = customDnsInput

                val port = proxyPortText.toIntOrNull() ?: 443
                app.settingsStore.updateProxy(proxyEnabled, proxyServer, port, proxySecret)

                app.credentialsStore.save(id, apiHash)
                scope.launch {
                    app.tdlibManager.start(
                        apiId = id,
                        apiHash = apiHash,
                        initialProxy = app.settingsStore.proxySettings,
                        dnsResolver = selectedDns,
                        customDnsIps = customDnsInput
                    )
                    onSaved()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Continue")
        }
    }
}