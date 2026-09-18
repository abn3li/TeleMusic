package com.abn3li.telemusic.ui.sync

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.data.telegram.TelegramAuthState
import com.abn3li.telemusic.data.telegram.TelegramChatCategory
import com.abn3li.telemusic.data.telegram.TelegramChatInfo
import com.abn3li.telemusic.ui.library.AdaptiveScreenBackground
import com.abn3li.telemusic.ui.library.adaptivePanelFill
import com.abn3li.telemusic.ui.nowplaying.LocalMiniPlayerInset

// Same local dark palette the Library/Settings redesign uses (see LibraryScreen's own doc on
// why this is hardcoded per-screen rather than routed through MaterialTheme) - kept consistent
// here so the channel picker doesn't look like a different, unstyled screen bolted onto the
// rest of the app. BgColor/CardColor used to be hardcoded here too, which meant this screen
// stayed flat black even with "Ambient blur" selected in Settings - AdaptiveScreenBackground/
// adaptivePanelFill() (imported above) are what Library/detail screens use for that toggle, and
// now this screen goes through the same ones instead of its own disconnected copy.
private val TextSecondary = Color(0xFFA9A9A6)
private val TextMuted = Color(0xFF8B8B88)
private val AccentGreen = Color(0xFF1D9E75)
private val OnAccentGreen = Color(0xFF04342C)
private val DividerColor = Color(0xFF232326)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as TgMusicApp
    val viewModel = remember { SyncViewModel(app.tdlibManager) }
    val state by viewModel.uiState.collectAsState()
    val syncing by viewModel.isSyncing.collectAsState()
    val progress by viewModel.syncProgress.collectAsState()

    AdaptiveScreenBackground {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Telegram Sync", fontWeight = FontWeight.Bold, color = Color.White) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
                if (syncing) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = AccentGreen,
                        trackColor = DividerColor
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp),
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
                    onPick = { chatId -> viewModel.syncChannel(context, chatId) },
                    onFilterChange = { viewModel.setFilter(it) },
                    onSearchChange = { viewModel.setSearchQuery(it) }
                )
                is TelegramAuthState.Error -> Text("Error: ${auth.message}", color = MaterialTheme.colorScheme.error)
                else -> Text("Connecting to Telegram...", color = TextSecondary)
            }

            AnimatedVisibility(visible = progress.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = adaptivePanelFill(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (syncing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.5.dp, color = AccentGreen)
                            Spacer(Modifier.width(12.dp))
                        }
                        Text(
                            progress,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
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

    Text("Enter your phone number", color = Color.White)

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
    Text("Enter the code Telegram sent you", color = Color.White)
    Card(colors = CardDefaults.cardColors(containerColor = adaptivePanelFill())) {
        Text(deliveryDescription, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    }
    OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("12345") })
    Button(onClick = { viewModel.submitCode(code) }) { Text("Verify") }
}

@Composable
private fun PasswordStep(viewModel: SyncViewModel) {
    var password by remember { mutableStateOf("") }
    Text("Two-factor password", color = Color.White)
    OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password") })
    Button(onClick = { viewModel.submitPassword(password) }) { Text("Unlock") }
}

@Composable
private fun ChannelPickerStep(
    state: SyncUiState,
    syncing: Boolean,
    onRetry: () -> Unit,
    onPick: (Long) -> Unit,
    onFilterChange: (ChatCategoryFilter) -> Unit,
    onSearchChange: (String) -> Unit
) {
    var isSearchActive by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Pick a chat to sync", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            IconButton(
                onClick = {
                    isSearchActive = !isSearchActive
                    if (!isSearchActive) onSearchChange("")
                },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    if (isSearchActive) Icons.Default.Close else Icons.Default.Search,
                    contentDescription = if (isSearchActive) "Close search" else "Search chats",
                    tint = if (isSearchActive) AccentGreen else TextSecondary
                )
            }
        }

        AnimatedVisibility(visible = isSearchActive, enter = expandVertically(), exit = shrinkVertically()) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = onSearchChange,
                placeholder = { Text("Search chats...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = AccentGreen) },
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = adaptivePanelFill(),
                    unfocusedContainerColor = adaptivePanelFill(),
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ChatCategoryFilter.entries) { filter ->
                ChannelFilterChip(
                    label = filter.label,
                    selected = state.filter == filter,
                    onClick = { onFilterChange(filter) }
                )
            }
        }

        when {
            state.isLoadingChats -> Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentGreen)
            }
            state.loadChatsError != null -> {
                // Previously a failed fetch (e.g. a transient error right after a fresh login,
                // when TDLib's local chat list often isn't populated yet) looked identical to a
                // genuinely empty account - same "No chats found" text, no way to tell which
                // one it was or retry without leaving the screen entirely.
                Text(
                    "Couldn't load your chats: ${state.loadChatsError}",
                    color = MaterialTheme.colorScheme.error
                )
                FilledTonalButton(onClick = onRetry, colors = ButtonDefaults.filledTonalButtonColors(containerColor = adaptivePanelFill(), contentColor = AccentGreen)) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Retry")
                }
            }
            else -> {
                val filteredChats = remember(state.chats, state.filter, state.searchQuery) {
                    state.chats.filter { chat ->
                        val matchesFilter = when (state.filter) {
                            ChatCategoryFilter.ALL -> true
                            ChatCategoryFilter.CHANNELS -> chat.category == TelegramChatCategory.CHANNEL
                            ChatCategoryFilter.GROUPS -> chat.category == TelegramChatCategory.GROUP
                            ChatCategoryFilter.CHATS -> chat.category == TelegramChatCategory.CHAT
                            ChatCategoryFilter.BOTS -> chat.category == TelegramChatCategory.BOT
                        }
                        val matchesQuery = state.searchQuery.isBlank() ||
                            chat.title.contains(state.searchQuery, ignoreCase = true)
                        matchesFilter && matchesQuery
                    }
                }
                val savedMessages = state.savedMessagesChat?.takeIf {
                    // Saved Messages sits outside the category filter entirely (it's not really
                    // a Channel/Group/Chat/Bot, just your own account) - only the search query
                    // (if any) can hide it, same as before.
                    state.searchQuery.isBlank() || it.title.contains(state.searchQuery, ignoreCase = true) ||
                        "saved messages".contains(state.searchQuery.trim(), ignoreCase = true)
                }

                if (filteredChats.isEmpty() && savedMessages == null) {
                    Text(
                        if (state.chats.isEmpty()) "No chats found. Create a private channel in Telegram and upload some songs to it first."
                        else "Nothing matches your search.",
                        color = TextMuted
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                            .background(adaptivePanelFill())
                    ) {
                        LazyColumn(contentPadding = PaddingValues(bottom = LocalMiniPlayerInset.current)) {
                            if (savedMessages != null) {
                                item(key = "saved_messages") {
                                    ChannelRow(
                                        title = savedMessages.title,
                                        subtitle = "Your own saved audio files",
                                        icon = Icons.Default.Bookmark,
                                        iconTint = Color(0xFF85B7EB),
                                        iconBg = Color(0xFF042C53),
                                        enabled = !syncing,
                                        onClick = { onPick(savedMessages.id) }
                                    )
                                    HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
                                }
                            }
                            itemsIndexed(filteredChats, key = { _, chat -> chat.id }) { index, chat ->
                                val style = chatRowStyle(chat)
                                ChannelRow(
                                    title = chat.title,
                                    subtitle = style.subtitle,
                                    icon = style.icon,
                                    iconTint = style.iconTint,
                                    iconBg = style.iconBg,
                                    enabled = !syncing,
                                    onClick = { onPick(chat.id) }
                                )
                                if (index < filteredChats.lastIndex) {
                                    HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Icon/color/subtitle for a chat row, keyed off its real [TelegramChatInfo.category] - Public/
 * Private is still shown for a channel specifically (the only category that distinction applies
 * to), everything else gets its own fixed look. */
private data class ChatRowStyle(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val iconTint: Color,
    val iconBg: Color,
    val subtitle: String
)

private fun chatRowStyle(chat: TelegramChatInfo): ChatRowStyle = when (chat.category) {
    TelegramChatCategory.CHANNEL -> if (chat.isPublic) {
        ChatRowStyle(Icons.Default.Public, Color(0xFF97C459), Color(0xFF173404), "Public channel")
    } else {
        ChatRowStyle(Icons.Default.Lock, Color(0xFFF0997B), Color(0xFF4A1B0C), "Private channel")
    }
    TelegramChatCategory.GROUP -> ChatRowStyle(Icons.Default.Groups, Color(0xFF85B7EB), Color(0xFF042C53), "Group")
    TelegramChatCategory.BOT -> ChatRowStyle(Icons.Default.SmartToy, Color(0xFFD9A441), Color(0xFF4A3A0C), "Bot")
    TelegramChatCategory.CHAT -> ChatRowStyle(Icons.Default.Person, Color(0xFFC79BE0), Color(0xFF33204A), "Chat")
}

@Composable
private fun ChannelFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(100),
        color = if (selected) AccentGreen else adaptivePanelFill()
    ) {
        Text(
            text = label,
            color = if (selected) OnAccentGreen else TextSecondary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun ChannelRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    iconBg: Color,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(44.dp).clip(CircleShape).background(iconBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = MaterialTheme.typography.bodyLarge.fontSize, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = TextMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
