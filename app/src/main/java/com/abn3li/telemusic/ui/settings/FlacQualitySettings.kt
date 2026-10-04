package com.abn3li.telemusic.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.ui.library.*
import com.abn3li.telemusic.ui.theme.LocalPalette
import com.abn3li.telemusic.ui.theme.ink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun FlacQualitySettingsGroup() {
    val app = LocalContext.current.applicationContext as TgMusicApp
    val preferences by app.flacUpgradeStore.preferences.collectAsState()
    val scope = rememberCoroutineScope()
    var accountOpen by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    GroupHeader("Audio Quality")
    GroupCard {
        GroupRow(title = "Automatic FLAC Upgrade",
            icon = { GroupIcon(Icons.Rounded.GraphicEq, Color(0xFF30D158)) },
            trailing = {
                GroupSwitch(preferences.enabled, { enabled ->
                    if (enabled && !preferences.hasAccount) { username = ""; password = ""; error = null; accountOpen = true }
                    else scope.launch { withContext(Dispatchers.IO) { app.flacUpgradeStore.setEnabled(enabled) } }
                })
            })
        GroupDivider(start = 57.dp)
        GroupRow(title = "Soulseek Account",
            icon = { GroupIcon(Icons.Rounded.Person, Color(0xFF0A84FF)) },
            trailing = { GroupValue(preferences.username.ifBlank { "Set Up" }) },
            onClick = { username = preferences.username; password = ""; error = null; accountOpen = true })
    }
    GroupFooter("Finds a matching FLAC while the original audio plays. Tap the quality status below the seek bar for progress and details. Uses temporary storage and extra data. File sharing is off.")
    if (accountOpen) AppAlert(
        title = "Soulseek Account",
        message = "Use your own account. The password is stored encrypted on this device.",
        onDismiss = { if (!saving) { accountOpen = false; password = "" } },
        actions = listOf(
            AlertAction("Cancel", enabled = !saving) { accountOpen = false; password = "" },
            AlertAction("Save", bold = true, enabled = username.matches(Regex("[!-~]{1,30}")) && password.isNotEmpty(), loading = saving) {
                saving = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            app.flacUpgradeStore.saveAccount(username, password)
                            app.flacUpgradeStore.setEnabled(true)
                        }
                        accountOpen = false
                        password = ""
                    } catch (_: Exception) { error = "Couldn't save the account. Try again." }
                    finally { saving = false }
                }
            }
        ),
        content = {
            QualityAccountField(username, { username = it }, "Username", false, !saving)
            Spacer(Modifier.height(8.dp))
            QualityAccountField(password, { password = it }, "Password", true, !saving)
            if (error != null) AlertNote(error!!)
            if (preferences.hasAccount) {
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.TextButton(enabled = !saving, onClick = {
                    scope.launch { withContext(Dispatchers.IO) { app.flacUpgradeStore.clear() } }
                    password = ""
                    accountOpen = false
                }) { Text("Remove Account", color = DestructiveRed) }
            }
        }
    )
}

@Composable
private fun QualityAccountField(value: String, onChange: (String) -> Unit, hint: String, secret: Boolean, enabled: Boolean) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(LocalPalette.current.field)
        .padding(horizontal = 10.dp, vertical = 10.dp)) {
        if (value.isEmpty()) Text(hint, color = ink.copy(alpha = 0.35f), fontSize = 15.sp)
        BasicTextField(value, onChange, singleLine = true, enabled = enabled,
            textStyle = TextStyle(color = ink, fontSize = 15.sp), cursorBrush = SolidColor(AppAccent),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(autoCorrect = false,
                keyboardType = if (secret) KeyboardType.Password else KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth())
    }
}
