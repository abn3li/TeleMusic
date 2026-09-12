package com.example.tgmusic.ui.sync

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.tgmusic.TgMusicApp
import com.example.tgmusic.data.telegram.TelegramAuthState
import com.example.tgmusic.data.telegram.TelegramChatInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as TgMusicApp
    val viewModel = remember { SyncViewModel(app.tdlibManager) }
    val state by viewModel.uiState.collectAsState()
    val syncing by viewModel.isSyncing.collectAsState()
    val progress by viewModel.syncProgress.collectAsState()

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Telegram Sync", fontWeight = FontWeight.Bold) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } }
                )
                if (syncing) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(24.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (val auth = state.authState) {
                TelegramAuthState.WaitingForPhoneNumber -> PhoneStep(viewModel)
                is TelegramAuthState.WaitingForCode -> CodeStep(viewModel, auth.deliveryDescription)
                TelegramAuthState.WaitingForPassword -> PasswordStep(viewModel)
                TelegramAuthState.Ready -> ChannelPickerStep(
                    state = state,
                    syncing = syncing,
                    onRetry = { viewModel.loadChats() },
                    onPick = { chatId -> viewModel.syncChannel(context, chatId) }
                )
                is TelegramAuthState.Error -> Text("Error: ${auth.message}", color = MaterialTheme.colorScheme.error)
                else -> Text("Connecting to Telegram...")
            }

            AnimatedVisibility(visible = progress.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (syncing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.5.dp)
                            Spacer(Modifier.width(12.dp))
                        }
                        Text(
                            progress,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PhoneStep(viewModel: SyncViewModel) {
    val context = LocalContext.current
    var selectedCountry by remember { mutableStateOf(defaultCountry(context)) }
    var nationalNumber by remember { mutableStateOf("") }
    var showCountryPicker by remember { mutableStateOf(false) }
    // Coarse flag, not tied to any per-frame animation - just disables the button and swaps
    // its label for a spinner the instant it's tapped, so a second tap before Telegram's
    // response comes back can't fire a second submitPhoneNumber() and send two codes. This
    // composable unmounts once authState moves off WaitingForPhoneNumber (success or error),
    // so there's no separate "reset" case to handle.
    var isSubmitting by remember { mutableStateOf(false) }

    Text("Enter your phone number")

    // Country selector lives INSIDE the text field's own leadingIcon slot instead of as a
    // separate button next to it - a Button and an OutlinedTextField never line up cleanly as
    // two independent siblings (different default heights, different corner shapes, the
    // field's floating label shifting its effective content box), no matter how much manual
    // height/shape tweaking is applied to force them to match. One field, one set of paddings,
    // nothing to misalign.
    OutlinedTextField(
        value = nationalNumber,
        onValueChange = { nationalNumber = it.filter { c -> c.isDigit() || c == ' ' } },
        label = { Text("Phone number") },
        placeholder = { Text("555 123 4567") },
        enabled = !isSubmitting,
        singleLine = true,
        leadingIcon = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickable(enabled = !isSubmitting) { showCountryPicker = true }
                    .padding(start = 12.dp, end = 4.dp)
            ) {
                Text(
                    "${selectedCountry.flagEmoji} ${selectedCountry.dialCode}",
                    style = MaterialTheme.typography.bodyLarge
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = "Change country")
            }
        },
        modifier = Modifier.fillMaxWidth()
    )

    Button(
        onClick = {
            isSubmitting = true
            viewModel.submitPhoneNumber("${selectedCountry.dialCode}${nationalNumber.filter { it.isDigit() }}")
        },
        enabled = !isSubmitting && nationalNumber.any { it.isDigit() }
    ) {
        if (isSubmitting) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
            Spacer(Modifier.width(8.dp))
        }
        Text("Continue")
    }

    if (showCountryPicker) {
        CountryPickerDialog(
            onDismiss = { showCountryPicker = false },
            onSelect = { selectedCountry = it; showCountryPicker = false }
        )
    }
}

@Composable
private fun CountryPickerDialog(onDismiss: () -> Unit, onSelect: (Country) -> Unit) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query) {
        if (query.isBlank()) COUNTRIES
        else COUNTRIES.filter {
            it.name.contains(query, ignoreCase = true) || it.dialCode.contains(query)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select country") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(filtered, key = { it.isoCode }) { country ->
                        ListItem(
                            headlineContent = { Text(country.name) },
                            leadingContent = { Text(country.flagEmoji) },
                            trailingContent = { Text(country.dialCode) },
                            modifier = Modifier.clickable { onSelect(country) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun CodeStep(viewModel: SyncViewModel, deliveryDescription: String) {
    var code by remember { mutableStateOf("") }
    Text("Enter the code Telegram sent you")
    Card { Text(deliveryDescription, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
    OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("12345") })
    Button(onClick = { viewModel.submitCode(code) }) { Text("Verify") }
}

@Composable
private fun PasswordStep(viewModel: SyncViewModel) {
    var password by remember { mutableStateOf("") }
    Text("Two-factor password")
    OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password") })
    Button(onClick = { viewModel.submitPassword(password) }) { Text("Unlock") }
}

@Composable
private fun ChannelPickerStep(state: SyncUiState, syncing: Boolean, onRetry: () -> Unit, onPick: (Long) -> Unit) {
    Text("Logged in - pick the channel with your music:")
    if (state.isLoadingChats) {
        CircularProgressIndicator()
    } else if (state.loadChatsError != null) {
        // Previously a failed fetch (e.g. a transient error right after a fresh login, when
        // TDLib's local chat list often isn't populated yet) looked identical to a genuinely
        // empty account - same "No channels found" text, no way to tell which one it was or
        // retry without leaving the screen entirely.
        Text(
            "Couldn't load your channels: ${state.loadChatsError}",
            color = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(onClick = onRetry) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Retry")
        }
    } else if (state.chats.isEmpty()) {
        Text("No channels found. Create a private channel in Telegram and upload some songs to it first.")
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.chats, key = { it.id }) { chat ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ListItem(
                        headlineContent = { Text(chat.title, fontWeight = FontWeight.SemiBold) },
                        supportingContent = { Text("Tap to sync songs from this channel") },
                        modifier = Modifier.clickable(enabled = !syncing) { onPick(chat.id) }
                    )
                }
            }
        }
    }
}