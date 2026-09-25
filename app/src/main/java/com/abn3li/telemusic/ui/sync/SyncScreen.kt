package com.abn3li.telemusic.ui.sync

import com.abn3li.telemusic.ui.library.CalmSpinner
import com.abn3li.telemusic.ui.library.AlertColor
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.data.telegram.TelegramAuthState
import com.abn3li.telemusic.data.telegram.TelegramConnectionState
import com.abn3li.telemusic.data.telegram.TelegramChatCategory
import com.abn3li.telemusic.data.telegram.TelegramChatInfo
import com.abn3li.telemusic.ui.library.AppAccent
import com.abn3li.telemusic.ui.library.ChevronIcon
import com.abn3li.telemusic.ui.library.DestructiveRed
import com.abn3li.telemusic.ui.library.FilterPill
import com.abn3li.telemusic.ui.library.GroupActionRow
import com.abn3li.telemusic.ui.library.GroupCard
import com.abn3li.telemusic.ui.library.GroupCardColor
import com.abn3li.telemusic.ui.library.GroupDivider
import com.abn3li.telemusic.ui.library.GroupFooter
import com.abn3li.telemusic.ui.library.GroupHeader
import com.abn3li.telemusic.ui.library.GroupLabelColor
import com.abn3li.telemusic.ui.library.GroupRow
import com.abn3li.telemusic.ui.library.GroupTextField
import com.abn3li.telemusic.ui.library.GroupValue
import com.abn3li.telemusic.ui.library.LargeTitleList
import com.abn3li.telemusic.ui.library.LibrarySearchField

@Composable
fun SyncScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as TgMusicApp
    val viewModel = remember { SyncViewModel(app.tdlibManager) }
    val state by viewModel.uiState.collectAsState()
    val syncing by viewModel.isSyncing.collectAsState()
    val progress by viewModel.syncProgress.collectAsState()
    val ready = state.authState == TelegramAuthState.Ready
    val connection by app.tdlibManager.connectionState.collectAsState()

    val haptics = LocalHapticFeedback.current
    val swipeThresholdPx = with(LocalDensity.current) { 72.dp.toPx() }
    val currentFilter by rememberUpdatedState(state.filter)
    val swipeModifier = if (ready) {
        Modifier.pointerInput(Unit) {
            var dragTotal = 0f
            detectHorizontalDragGestures(
                onDragStart = { dragTotal = 0f },
                onDragEnd = {
                    val filters = ChatCategoryFilter.entries
                    val index = filters.indexOf(currentFilter)
                    val target = when {
                        dragTotal <= -swipeThresholdPx -> index + 1
                        dragTotal >= swipeThresholdPx -> index - 1
                        else -> index
                    }
                    if (target != index && target in filters.indices) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.setFilter(filters[target])
                    }
                },
                onHorizontalDrag = { change, amount ->
                    change.consume()
                    dragTotal += amount
                }
            )
        }
    } else Modifier

    Box(Modifier.fillMaxSize().then(swipeModifier)) {
    LargeTitleList(
        title = "Sync",
        onBack = onBack,
        stickyContent = if (ready) {
            { LibrarySearchField(state.searchQuery, "Search Chats", viewModel::setSearchQuery) }
        } else null
    ) {
        if (ready && connection != TelegramConnectionState.CONNECTED) {
            item("connection") {
                GroupHeader("Telegram")
                GroupCard {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(horizontal = 15.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (connection != TelegramConnectionState.DISCONNECTED) {
                            CalmSpinner(color = AppAccent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(12.dp))
                        }
                        Text(
                            when (connection) {
                                TelegramConnectionState.CONNECTING_TO_PROXY -> "Connecting to proxy…"
                                TelegramConnectionState.WAITING_FOR_NETWORK -> "Waiting for network…"
                                TelegramConnectionState.UPDATING -> "Updating…"
                                TelegramConnectionState.DISCONNECTED -> "Disconnected"
                                else -> "Connecting…"
                            },
                            color = if (connection == TelegramConnectionState.DISCONNECTED) DestructiveRed else Color.White,
                            fontSize = 15.sp
                        )
                    }
                }
            }
        }
        if (progress.isNotBlank()) {
            item("progress") {
                GroupHeader(if (syncing) "Syncing" else "Last Sync")
                GroupCard {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(horizontal = 15.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (syncing) {
                            CalmSpinner(color = AppAccent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(12.dp))
                        }
                        Text(progress, color = Color.White, fontSize = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        when (val auth = state.authState) {
            TelegramAuthState.WaitingForPhoneNumber -> item("phone") { PhoneStep(viewModel) }
            is TelegramAuthState.WaitingForCode -> item("code") { CodeStep(viewModel, auth.deliveryDescription) }
            TelegramAuthState.WaitingForPassword -> item("password") { PasswordStep(viewModel) }
            TelegramAuthState.Ready -> chatPicker(
                state = state,
                syncing = syncing,
                onRetry = viewModel::loadChats,
                onPick = { chatId -> viewModel.syncChannel(context, chatId) },
                onFilterChange = viewModel::setFilter
            )
            is TelegramAuthState.Error -> item("error") { GroupFooter("Error: ${auth.message}", color = DestructiveRed) }
            else -> item("connecting") {
                Row(
                    Modifier.fillMaxWidth().padding(top = 40.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CalmSpinner(color = AppAccent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(10.dp))
                    Text("Connecting to Telegram…", color = GroupLabelColor, fontSize = 15.sp)
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
    // Blocks a second tap sending a second code before Telegram answers; this step unmounts
    // once the auth state moves on.
    var isSubmitting by remember { mutableStateOf(false) }

    GroupHeader("Phone Number")
    GroupCard {
        GroupRow(
            title = "Country",
            enabled = !isSubmitting,
            onClick = { showCountryPicker = true },
            trailing = { GroupValue("${selectedCountry.flagEmoji} ${selectedCountry.name}") }
        )
        GroupDivider()
        GroupTextField(
            value = nationalNumber,
            onValueChange = { nationalNumber = it.filter { c -> c.isDigit() || c == ' ' } },
            placeholder = "555 123 4567",
            keyboardType = KeyboardType.Phone,
            enabled = !isSubmitting,
            leading = { Text(selectedCountry.dialCode, color = Color.White, fontSize = 16.5.sp) }
        )
        GroupDivider()
        GroupActionRow("Continue", enabled = nationalNumber.any { it.isDigit() }, loading = isSubmitting) {
            isSubmitting = true
            viewModel.submitPhoneNumber("${selectedCountry.dialCode}${nationalNumber.filter { it.isDigit() }}")
        }
    }
    GroupFooter("Telegram will send a login code to this number.")

    if (showCountryPicker) {
        CountryPickerDialog(
            selected = selectedCountry,
            onDismiss = { showCountryPicker = false },
            onSelect = { selectedCountry = it; showCountryPicker = false }
        )
    }
}

/** Full-height country list, styled like an iOS picker page: Cancel + title bar, search, rows. */
@Composable
private fun CountryPickerDialog(selected: Country, onDismiss: () -> Unit, onSelect: (Country) -> Unit) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query) {
        if (query.isBlank()) COUNTRIES
        else COUNTRIES.filter { it.name.contains(query, ignoreCase = true) || it.dialCode.contains(query.trim()) }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.82f)
                .padding(horizontal = 14.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(AlertColor)
        ) {
            Box(Modifier.fillMaxWidth().height(52.dp)) {
                Text(
                    "Cancel",
                    color = AppAccent,
                    fontSize = 17.sp,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
                Text("Country", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center))
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .padding(bottom = 10.dp)
                    .height(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1A1A1C))
                    .padding(horizontal = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Search", color = Color.White.copy(alpha = 0.45f), fontSize = 16.sp)
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                        cursorBrush = SolidColor(AppAccent),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.14f)))
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                itemsIndexed(filtered, key = { _, country -> country.isoCode }) { index, country ->
                    if (index > 0) GroupDivider(start = 52.dp)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(country) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(country.flagEmoji, fontSize = 20.sp, modifier = Modifier.width(36.dp))
                        Text(country.name, color = Color.White, fontSize = 16.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(country.dialCode, color = GroupLabelColor, fontSize = 15.sp)
                        Box(Modifier.width(30.dp), contentAlignment = Alignment.CenterEnd) {
                            if (country.isoCode == selected.isoCode) {
                                Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = AppAccent, modifier = Modifier.size(19.dp))
                            }
                        }
                    }
                }
                if (filtered.isEmpty()) {
                    item("none") { GroupFooter("No countries match your search.") }
                }
            }
        }
    }
}

@Composable
private fun CodeStep(viewModel: SyncViewModel, deliveryDescription: String) {
    var code by remember { mutableStateOf("") }
    GroupHeader("Login Code")
    GroupCard {
        GroupTextField(value = code, onValueChange = { code = it }, placeholder = "12345", keyboardType = KeyboardType.Number)
        GroupDivider()
        GroupActionRow("Verify", enabled = code.isNotBlank()) { viewModel.submitCode(code) }
    }
    GroupFooter(deliveryDescription)
}

@Composable
private fun PasswordStep(viewModel: SyncViewModel) {
    var password by remember { mutableStateOf("") }
    GroupHeader("Two-Step Verification")
    GroupCard {
        GroupTextField(value = password, onValueChange = { password = it }, placeholder = "Password", password = true)
        GroupDivider()
        GroupActionRow("Unlock", enabled = password.isNotBlank()) { viewModel.submitPassword(password) }
    }
    GroupFooter("Your account has a cloud password. Enter it to continue.")
}

private fun LazyListScope.chatPicker(
    state: SyncUiState,
    syncing: Boolean,
    onRetry: () -> Unit,
    onPick: (Long) -> Unit,
    onFilterChange: (ChatCategoryFilter) -> Unit
) {
    item("filters") {
        val pillsState = rememberLazyListState()
        LaunchedEffect(state.filter) { pillsState.animateScrollToItem(state.filter.ordinal) }
        LazyRow(
            state = pillsState,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.5.dp),
            modifier = Modifier.padding(top = 16.dp, bottom = 2.dp)
        ) {
            items(ChatCategoryFilter.entries) { filter ->
                FilterPill(filter.label, selected = state.filter == filter, onClick = { onFilterChange(filter) })
            }
        }
    }
    when {
        state.isLoadingChats -> item("loading") {
            Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                CalmSpinner(color = AppAccent)
            }
        }
        state.loadChatsError != null -> item("load_error") {
            GroupHeader("Chats")
            GroupCard { GroupActionRow("Try Again", onClick = onRetry) }
            GroupFooter("Couldn't load your chats: ${state.loadChatsError}", color = DestructiveRed)
        }
        else -> {
            val filteredChats = state.chats.filter { chat ->
                val matchesFilter = when (state.filter) {
                    ChatCategoryFilter.ALL -> true
                    ChatCategoryFilter.CHANNELS -> chat.category == TelegramChatCategory.CHANNEL
                    ChatCategoryFilter.GROUPS -> chat.category == TelegramChatCategory.GROUP
                    ChatCategoryFilter.CHATS -> chat.category == TelegramChatCategory.CHAT
                    ChatCategoryFilter.BOTS -> chat.category == TelegramChatCategory.BOT
                }
                matchesFilter && (state.searchQuery.isBlank() || chat.title.contains(state.searchQuery, ignoreCase = true))
            }
            // Saved Messages sits outside the category filter - only the search can hide it.
            val savedMessages = state.savedMessagesChat?.takeIf {
                state.searchQuery.isBlank() || it.title.contains(state.searchQuery, ignoreCase = true) ||
                    "saved messages".contains(state.searchQuery.trim(), ignoreCase = true)
            }
            if (filteredChats.isEmpty() && savedMessages == null) {
                item("empty") {
                    GroupFooter(
                        if (state.chats.isEmpty()) "No chats found. Create a private channel in Telegram and upload some songs to it first."
                        else "Nothing matches your search."
                    )
                }
            } else {
                item("chats_header") { GroupHeader("Pick a Chat to Sync") }
                if (savedMessages != null) {
                    item("saved_messages") {
                        ChatCard(isFirst = true, isLast = filteredChats.isEmpty()) {
                            ChatRow(savedMessages.title, "Your own saved audio files", Icons.Rounded.Bookmark, Color(0xFF0A84FF), !syncing) {
                                onPick(savedMessages.id)
                            }
                        }
                    }
                }
                itemsIndexed(filteredChats, key = { _, chat -> chat.id }, contentType = { _, _ -> "chat" }) { index, chat ->
                    val style = chatRowStyle(chat)
                    ChatCard(isFirst = index == 0 && savedMessages == null, isLast = index == filteredChats.lastIndex) {
                        if (index > 0 || savedMessages != null) GroupDivider(start = 62.dp)
                        ChatRow(chat.title, style.subtitle, style.icon, style.tint, !syncing) { onPick(chat.id) }
                    }
                }
                item("chats_footer") { GroupFooter("Songs in the chat you pick are added to your library.") }
            }
        }
    }
}

/** One slice of the chat list's card - only the first and last slices get rounded corners, so
 * the lazily-composed rows still read as one card. */
@Composable
private fun ChatCard(isFirst: Boolean, isLast: Boolean, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(
        topStart = if (isFirst) 10.dp else 0.dp,
        topEnd = if (isFirst) 10.dp else 0.dp,
        bottomStart = if (isLast) 10.dp else 0.dp,
        bottomEnd = if (isLast) 10.dp else 0.dp
    )
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.5.dp)
            .clip(shape)
            .background(GroupCardColor)
    ) { content() }
}

@Composable
private fun ChatRow(title: String, subtitle: String, icon: ImageVector, tint: Color, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(tint), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp))
        }
        Column(Modifier.weight(1f).padding(start = 13.dp)) {
            Text(title, color = Color.White, fontSize = 16.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp)
        }
        ChevronIcon()
    }
}

private data class ChatRowStyle(val icon: ImageVector, val tint: Color, val subtitle: String)

private fun chatRowStyle(chat: TelegramChatInfo): ChatRowStyle = when (chat.category) {
    TelegramChatCategory.CHANNEL -> if (chat.isPublic) {
        ChatRowStyle(Icons.Rounded.Public, Color(0xFF30D158), "Public channel")
    } else {
        ChatRowStyle(Icons.Rounded.Lock, Color(0xFFFF9F0A), "Private channel")
    }
    TelegramChatCategory.GROUP -> ChatRowStyle(Icons.Rounded.Groups, Color(0xFF0A84FF), "Group")
    TelegramChatCategory.BOT -> ChatRowStyle(Icons.Rounded.SmartToy, Color(0xFFFFD60A), "Bot")
    TelegramChatCategory.CHAT -> ChatRowStyle(Icons.Rounded.Person, Color(0xFFBF5AF2), "Chat")
}
