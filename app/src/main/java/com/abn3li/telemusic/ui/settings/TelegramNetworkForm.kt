package com.abn3li.telemusic.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.abn3li.telemusic.data.settings.AppSettingsStore
import com.abn3li.telemusic.data.settings.DnsResolver
import com.abn3li.telemusic.ui.library.AppAccent
import com.abn3li.telemusic.ui.library.GroupDivider
import com.abn3li.telemusic.ui.library.GroupOption
import com.abn3li.telemusic.ui.library.GroupRow
import com.abn3li.telemusic.ui.library.GroupSwitch
import com.abn3li.telemusic.ui.library.GroupTextField
import com.abn3li.telemusic.ui.library.GroupValue

/**
 * The DNS and MTProto proxy fields, as typed but not yet saved - shared by Settings and the
 * Sync tab's Telegram setup, which each decide when to save and apply them. Survives switching
 * tabs and rotating (see [rememberTelegramNetworkForm]).
 */
@Stable
internal class TelegramNetworkForm(
    dns: DnsResolver,
    customDns: String,
    proxyEnabled: Boolean,
    proxyServer: String,
    proxyPortText: String,
    proxySecret: String
) {
    var dns by mutableStateOf(dns)
    var customDns by mutableStateOf(customDns)
    var dnsOptionsOpen by mutableStateOf(false)
    var proxyEnabled by mutableStateOf(proxyEnabled)
    var proxyServer by mutableStateOf(proxyServer)
    var proxyPortText by mutableStateOf(proxyPortText)
    var proxySecret by mutableStateOf(proxySecret)
    var proxyLinkInput by mutableStateOf("")

    val proxyPort: Int get() = proxyPortText.toIntOrNull() ?: 443

    /** Fills the proxy fields from a t.me/proxy link; false when it isn't one. */
    fun fillFromProxyLink(text: String): Boolean {
        val parsed = AppSettingsStore.parseTelegramProxyUrl(text) ?: return false
        proxyServer = parsed.server
        proxyPortText = parsed.port.toString()
        proxySecret = parsed.secret
        return true
    }

    /** Saves both to settings (Settings and the setup screen then apply them their own way). */
    fun save(store: AppSettingsStore) {
        store.dnsResolver = dns
        store.customDnsIps = customDns
        store.updateProxy(proxyEnabled, proxyServer, proxyPort, proxySecret)
    }

    companion object {
        val Saver = listSaver<TelegramNetworkForm, Any>(
            save = { listOf(it.dns.name, it.customDns, it.proxyEnabled, it.proxyServer, it.proxyPortText, it.proxySecret, it.proxyLinkInput) },
            restore = {
                TelegramNetworkForm(
                    DnsResolver.valueOf(it[0] as String), it[1] as String, it[2] as Boolean,
                    it[3] as String, it[4] as String, it[5] as String
                ).apply { proxyLinkInput = it[6] as String }
            }
        )
    }
}

/** The saved DNS and proxy settings, as a form to edit. */
@Composable
internal fun rememberTelegramNetworkForm(store: AppSettingsStore): TelegramNetworkForm =
    rememberSaveable(saver = TelegramNetworkForm.Saver) {
        val proxy = store.proxySettings
        TelegramNetworkForm(store.dnsResolver, store.customDnsIps, proxy.enabled, proxy.server, proxy.port.toString(), proxy.secret)
    }

/** The DNS resolver row, its options and the custom-servers field - rows for a GroupCard. */
@Composable
internal fun ColumnScope.DnsResolverRows(form: TelegramNetworkForm, icon: (@Composable () -> Unit)? = null) {
    // Options line up with the row's title: past the icon when there is one.
    val indent = if (icon != null) 57 else 15
    GroupRow(
        title = "Resolver",
        icon = icon,
        onClick = { form.dnsOptionsOpen = !form.dnsOptionsOpen },
        trailing = { GroupValue(form.dns.displayName.substringBefore(" (")) }
    )
    AnimatedVisibility(form.dnsOptionsOpen) {
        Column {
            DnsResolver.entries.forEach { resolver ->
                GroupDivider(start = indent.dp)
                GroupOption(
                    label = resolver.displayName,
                    detail = "${resolver.source} · ${resolver.description}",
                    selected = form.dns == resolver,
                    startPadding = indent
                ) {
                    form.dns = resolver
                    form.dnsOptionsOpen = false
                }
            }
        }
    }
    if (form.dns == DnsResolver.CUSTOM) {
        GroupDivider()
        GroupTextField(value = form.customDns, onValueChange = { form.customDns = it }, placeholder = "1.1.1.1,8.8.8.8", label = "Servers")
    }
}

/** The Use Proxy switch and, when on, the link/server/port/secret fields - rows for a GroupCard.
 * [onMessage] gets the result of filling from a proxy link. */
@Composable
internal fun ColumnScope.ProxyRows(form: TelegramNetworkForm, onMessage: (String) -> Unit, icon: (@Composable () -> Unit)? = null) {
    val clipboardManager = LocalClipboardManager.current
    GroupRow(title = "Use Proxy", icon = icon, trailing = { GroupSwitch(form.proxyEnabled, { form.proxyEnabled = it }) })
    if (form.proxyEnabled) {
        GroupDivider()
        GroupTextField(
            value = form.proxyLinkInput,
            onValueChange = { form.proxyLinkInput = it },
            placeholder = "Paste a t.me/proxy link",
            trailing = {
                Icon(
                    Icons.Rounded.ContentPaste,
                    contentDescription = "Fill from link or clipboard",
                    tint = AppAccent,
                    modifier = Modifier.size(22.dp).clickable {
                        val clipText = clipboardManager.getText()?.text
                        val textToParse = if (!clipText.isNullOrBlank()) clipText else form.proxyLinkInput
                        onMessage(
                            if (form.fillFromProxyLink(textToParse)) "Filled in from the proxy link."
                            else "That isn't a valid Telegram proxy link."
                        )
                    }
                )
            }
        )
        GroupDivider()
        GroupTextField(value = form.proxyServer, onValueChange = { form.proxyServer = it }, placeholder = "Hostname or IP", label = "Server")
        GroupDivider()
        GroupTextField(
            value = form.proxyPortText,
            onValueChange = { form.proxyPortText = it.filter { c -> c.isDigit() } },
            placeholder = "443",
            label = "Port",
            keyboardType = KeyboardType.Number
        )
        GroupDivider()
        GroupTextField(value = form.proxySecret, onValueChange = { form.proxySecret = it }, placeholder = "Hex secret", label = "Secret")
    }
}
