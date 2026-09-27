package com.abn3li.telemusic.ui.credentials

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.ui.library.DestructiveRed
import com.abn3li.telemusic.ui.library.GroupActionRow
import com.abn3li.telemusic.ui.library.GroupCard
import com.abn3li.telemusic.ui.library.GroupDivider
import com.abn3li.telemusic.ui.library.GroupFooter
import com.abn3li.telemusic.ui.library.GroupHeader
import com.abn3li.telemusic.ui.library.GroupTextField
import com.abn3li.telemusic.ui.settings.DnsResolverRows
import com.abn3li.telemusic.ui.settings.ProxyRows
import com.abn3li.telemusic.ui.settings.rememberTelegramNetworkForm

/**
 * The Telegram API ID/hash (plus DNS and proxy, for networks that block Telegram) - the first
 * step of the Sync tab until they're saved. Only Telegram needs them, so the rest of the app
 * (YouTube, Spotify, local files) works without ever filling this in. Saving starts TDLib, and
 * the Sync tab moves on to the phone-number step by itself. What's typed survives switching
 * tabs and rotating.
 */
@Composable
fun TelegramSetupStep(onStarted: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as TgMusicApp

    var apiId by rememberSaveable { mutableStateOf("") }
    var apiHash by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var proxyMessage by rememberSaveable { mutableStateOf<String?>(null) }
    val network = rememberTelegramNetworkForm(app.settingsStore)
    // TDLib must start exactly once: a second tap before this step goes away would start a
    // second client on the same database.
    var starting by rememberSaveable { mutableStateOf(false) }

    Column {
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

        GroupHeader("DNS")
        GroupCard { DnsResolverRows(network) }
        GroupFooter("Helps if your network blocks Telegram's addresses.")

        GroupHeader("MTProto Proxy")
        GroupCard { ProxyRows(network, onMessage = { proxyMessage = it }) }
        GroupFooter(proxyMessage ?: "Optional - only needed if Telegram is blocked where you are.")

        GroupHeader("")
        GroupCard {
            GroupActionRow("Continue", loading = starting) {
                if (starting) return@GroupActionRow
                val id = apiId.toIntOrNull()
                if (id == null || id == 0) {
                    error = "Enter a valid API ID (numbers only)."
                    return@GroupActionRow
                }
                if (apiHash.isBlank()) {
                    error = "Enter your API hash."
                    return@GroupActionRow
                }
                starting = true
                network.save(app.settingsStore)
                app.credentialsStore.save(id, apiHash)
                app.tdlibManager.start(
                    apiId = id,
                    apiHash = apiHash,
                    initialProxy = app.settingsStore.proxySettings,
                    dnsResolver = network.dns,
                    customDnsIps = network.customDns
                )
                onStarted()
            }
        }
        error?.let { GroupFooter(it, color = DestructiveRed) }
    }
}
