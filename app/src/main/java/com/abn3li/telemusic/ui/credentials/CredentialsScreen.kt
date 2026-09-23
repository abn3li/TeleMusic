package com.abn3li.telemusic.ui.credentials

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.data.settings.AppSettingsStore
import com.abn3li.telemusic.data.settings.DnsResolver
import com.abn3li.telemusic.ui.library.AppAccent
import com.abn3li.telemusic.ui.library.DestructiveRed
import com.abn3li.telemusic.ui.library.GroupActionRow
import com.abn3li.telemusic.ui.library.GroupCard
import com.abn3li.telemusic.ui.library.GroupDivider
import com.abn3li.telemusic.ui.library.GroupFooter
import com.abn3li.telemusic.ui.library.GroupHeader
import com.abn3li.telemusic.ui.library.GroupOption
import com.abn3li.telemusic.ui.library.GroupRow
import com.abn3li.telemusic.ui.library.GroupSwitch
import com.abn3li.telemusic.ui.library.GroupTextField
import com.abn3li.telemusic.ui.library.GroupValue
import com.abn3li.telemusic.ui.library.LargeTitleList
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

    var selectedDns by remember { mutableStateOf(app.settingsStore.dnsResolver) }
    var customDnsInput by remember { mutableStateOf(app.settingsStore.customDnsIps) }
    var dnsOptionsOpen by remember { mutableStateOf(false) }

    val currentProxy = remember { app.settingsStore.proxySettings }
    var proxyEnabled by remember { mutableStateOf(currentProxy.enabled) }
    var proxyServer by remember { mutableStateOf(currentProxy.server) }
    var proxyPortText by remember { mutableStateOf(currentProxy.port.toString()) }
    var proxySecret by remember { mutableStateOf(currentProxy.secret) }
    var proxyPasteInput by remember { mutableStateOf("") }
    var proxyMessage by remember { mutableStateOf<String?>(null) }

    LargeTitleList(title = "Welcome") {
        item("api") {
            GroupHeader("Telegram API")
            GroupCard {
                GroupTextField(
                    value = apiId,
                    onValueChange = { apiId = it.filter { c -> c.isDigit() } },
                    placeholder = "12345678",
                    label = "API ID",
                    keyboardType = KeyboardType.Number
                )
                GroupDivider()
                GroupTextField(value = apiHash, onValueChange = { apiHash = it.trim() }, placeholder = "0123456789abcdef", label = "API Hash")
            }
            GroupFooter(
                "Get your own API ID and hash for free at my.telegram.org → API Development Tools. They identify this app, " +
                    "not your account - you'll still log in with your phone number next. Stored encrypted on this device only."
            )
        }

        item("dns") {
            GroupHeader("DNS")
            GroupCard {
                GroupRow(
                    title = "Resolver",
                    onClick = { dnsOptionsOpen = !dnsOptionsOpen },
                    trailing = { GroupValue(selectedDns.displayName.substringBefore(" (")) }
                )
                AnimatedVisibility(dnsOptionsOpen) {
                    Column {
                        DnsResolver.entries.forEach { resolver ->
                            GroupDivider()
                            GroupOption(
                                label = resolver.displayName,
                                detail = "${resolver.source} · ${resolver.description}",
                                selected = selectedDns == resolver,
                                startPadding = 15
                            ) {
                                selectedDns = resolver
                                dnsOptionsOpen = false
                            }
                        }
                    }
                }
                if (selectedDns == DnsResolver.CUSTOM) {
                    GroupDivider()
                    GroupTextField(value = customDnsInput, onValueChange = { customDnsInput = it }, placeholder = "1.1.1.1,8.8.8.8", label = "Servers")
                }
            }
            GroupFooter("Helps if your network blocks Telegram's addresses.")
        }

        item("proxy") {
            GroupHeader("MTProto Proxy")
            GroupCard {
                GroupRow(title = "Use Proxy", trailing = { GroupSwitch(proxyEnabled, { proxyEnabled = it }) })
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
                                        proxyMessage = "Filled in from the proxy link."
                                    } else {
                                        proxyMessage = "That isn't a valid Telegram proxy link."
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
            }
            GroupFooter(proxyMessage ?: "Optional - only needed if Telegram is blocked where you are.")
        }

        item("continue") {
            GroupHeader("")
            GroupCard {
                GroupActionRow("Continue") {
                    val id = apiId.toIntOrNull()
                    if (id == null || id == 0) {
                        error = "Enter a valid API ID (numbers only)."
                        return@GroupActionRow
                    }
                    if (apiHash.isBlank()) {
                        error = "Enter your API hash."
                        return@GroupActionRow
                    }
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
                }
            }
            error?.let { GroupFooter(it, color = DestructiveRed) }
        }
    }
}
