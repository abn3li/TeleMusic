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

/** Which channels ChannelPickerStep shows - independent of search, so a filter and a query can
 * be combined (e.g. "public channels matching 'music'"). */
enum class ChannelFilter(val label: String) {
    ALL("All"),
    PUBLIC("Public"),
    PRIVATE("Private")
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
    val filter: ChannelFilter = ChannelFilter.ALL,
    val searchQuery: String = ""
)

/** No metadata toggle here anymore - that lives in the app's Settings screen. This screen is
 * purely: log in, pick a channel, kick off the (foreground-service-backed) sync. */
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

    fun submitPhoneNumber(phone: String) = viewModelScope.launch { runCatching { tdlibManager.submitPhoneNumber(phone) } }
    fun submitCode(code: String) = viewModelScope.launch { runCatching { tdlibManager.submitCode(code) } }
    fun submitPassword(password: String) = viewModelScope.launch { runCatching { tdlibManager.submitPassword(password) } }

    fun loadChats() {
        // Two independent launches, not one fetch after another - the Saved Messages lookup
        // doesn't depend on the channel list (or vice versa), so there's no reason for the UI
        // to wait on both round trips back to back. Each updates its own slice of the state and
        // a failure in one can never block or blank out the other.
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingChats = true, loadChatsError = null)
            runCatching {
                val chats = tdlibManager.listMyChats().filter { it.isChannel }
                _uiState.value = _uiState.value.copy(chats = chats)
            }.onFailure { e ->
                // Previously swallowed silently, so a failed fetch (e.g. a transient error
                // right after a fresh login) looked identical to a real empty "no channels"
                // state, with no way to tell which one it was or retry without leaving the
                // screen.
                _uiState.value = _uiState.value.copy(loadChatsError = e.message ?: "Couldn't load your channels")
            }
            _uiState.value = _uiState.value.copy(isLoadingChats = false)
        }
        viewModelScope.launch {
            runCatching { tdlibManager.getSavedMessagesChat() }.getOrNull()?.let { saved ->
                _uiState.value = _uiState.value.copy(savedMessagesChat = saved)
            }
        }
    }

    fun setFilter(filter: ChannelFilter) {
        _uiState.value = _uiState.value.copy(filter = filter)
    }

    fun setSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    fun syncChannel(context: Context, chatId: Long) {
        SyncService.start(context, chatId)
    }
}
