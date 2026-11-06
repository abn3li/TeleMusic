package com.abn3li.telemusic.ui.sync

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abn3li.telemusic.data.telegram.TdlibManager
import com.abn3li.telemusic.data.telegram.TelegramAuthState
import com.abn3li.telemusic.data.telegram.TelegramChatInfo
import com.abn3li.telemusic.sync.SyncService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Which chats ChannelPickerStep shows - independent of search, so a filter and a query can be
 * combined (e.g. "groups matching 'music'"). Replaces the old channel-only Public/Private
 * filter now that every chat category (not just channels) is fetched and shown. */
enum class ChatCategoryFilter(val label: String) {
    ALL("All"),
    CHANNELS("Channels"),
    GROUPS("Groups"),
    CHATS("Chats"),
    BOTS("Bots")
}

data class SyncUiState(
    val authState: TelegramAuthState = TelegramAuthState.LoggedOut,
    val isLoadingChats: Boolean = false,
    val chats: List<TelegramChatInfo> = emptyList(),
    val loadChatsError: String? = null,
    // Null until fetched (or if the lookup genuinely failed) - the Saved Messages row is only
    // shown once this is non-null, same "don't show it if we can't back it" rule the channel
    // list already follows for a failed fetch.
    val savedMessagesChat: TelegramChatInfo? = null,
    val filter: ChatCategoryFilter = ChatCategoryFilter.ALL,
    val searchQuery: String = ""
)

/** No metadata toggle here anymore - that lives in the app's Settings screen. This screen is
 * purely: log in, pick a channel, kick off the (foreground-service-backed) sync. */
private const val LOGIN_TIMEOUT_MS = 30_000L

class SyncViewModel(private val tdlibManager: TdlibManager) : ViewModel() {

    private val _uiState = MutableStateFlow(SyncUiState())
    val uiState: StateFlow<SyncUiState> = _uiState

    val syncProgress: StateFlow<String> = SyncService.progress
    val isSyncing: StateFlow<Boolean> = SyncService.isRunning

    init {
        viewModelScope.launch {
            tdlibManager.authState.collect { state ->
                _uiState.value = _uiState.value.copy(authState = state)
                if (state is TelegramAuthState.Ready) loadChats()
            }
        }
    }

    fun submitPhoneNumber(phone: String, onError: (String) -> Unit) =
        submitLogin(onError) { tdlibManager.submitPhoneNumber(phone) }

    fun submitCode(code: String, onError: (String) -> Unit) =
        submitLogin(onError) { tdlibManager.submitCode(code) }

    fun submitPassword(password: String, onError: (String) -> Unit) =
        submitLogin(onError) { tdlibManager.submitPassword(password) }

    /** [onError] gets a sentence to show. Without a time limit, a connection lost after tapping
     * would leave the step waiting forever with nothing said. */
    private fun submitLogin(onError: (String) -> Unit, submit: suspend () -> Any) = viewModelScope.launch {
        try {
            val answered = kotlinx.coroutines.withTimeoutOrNull(LOGIN_TIMEOUT_MS) { submit() }
            if (answered == null) onError("Telegram didn't answer. Check the connection and try again.")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(loginErrorText(e.message.orEmpty()))
        }
    }

    private fun loginErrorText(error: String): String = when {
        "PHONE_NUMBER_INVALID" in error -> "That phone number isn't valid."
        "PHONE_NUMBER_BANNED" in error -> "Telegram has banned this number."
        "API_ID" in error -> "Your API ID or hash is wrong."
        "PHONE_CODE_EXPIRED" in error -> "That code has expired. Go back and ask for a new one."
        "PHONE_CODE" in error -> "That code isn't right."
        "PASSWORD_HASH_INVALID" in error -> "That password isn't right."
        "FLOOD" in error -> "Too many tries. Wait a while and try again."
        else -> "Telegram refused this. $error"
    }


    fun loadChats() {
        // Two independent launches, not one fetch after another - the Saved Messages lookup
        // doesn't depend on the channel list (or vice versa), so there's no reason for the UI
        // to wait on both round trips back to back. Each updates its own slice of the state and
        // a failure in one can never block or blank out the other.
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingChats = true, loadChatsError = null)
            runCatching {
                // No category filter here any more - channels, groups, regular chats, and bots
                // are all real places audio can be shared, so listMyChats()'s own full result
                // (already classified per chat - see its own doc) goes straight to the UI, which
                // filters by category itself for display.
                val chats = tdlibManager.listMyChats()
                _uiState.value = _uiState.value.copy(chats = chats)
            }.onFailure { e ->
                // Previously swallowed silently, so a failed fetch (e.g. a transient error
                // right after a fresh login) looked identical to a real empty "no channels"
                // state, with no way to tell which one it was or retry without leaving the
                // screen.
                _uiState.value = _uiState.value.copy(loadChatsError = e.message ?: "Couldn't load your chats")
            }
            _uiState.value = _uiState.value.copy(isLoadingChats = false)
        }
        viewModelScope.launch {
            runCatching { tdlibManager.getSavedMessagesChat() }.getOrNull()?.let { saved ->
                _uiState.value = _uiState.value.copy(savedMessagesChat = saved)
            }
        }
    }

    fun setFilter(filter: ChatCategoryFilter) {
        _uiState.value = _uiState.value.copy(filter = filter)
    }

    fun setSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    fun syncChannel(context: Context, chatId: Long) {
        SyncService.start(context, chatId)
    }
}
