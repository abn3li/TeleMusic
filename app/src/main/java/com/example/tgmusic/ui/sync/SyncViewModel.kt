package com.example.tgmusic.ui.sync

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.tgmusic.data.telegram.TdlibManager
import com.example.tgmusic.data.telegram.TelegramAuthState
import com.example.tgmusic.data.telegram.TelegramChatInfo
import com.example.tgmusic.sync.SyncService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class SyncUiState(
    val authState: TelegramAuthState = TelegramAuthState.LoggedOut,
    val isLoadingChats: Boolean = false,
    val chats: List<TelegramChatInfo> = emptyList(),
    val loadChatsError: String? = null
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

    fun loadChats() = viewModelScope.launch {
        _uiState.value = _uiState.value.copy(isLoadingChats = true, loadChatsError = null)
        runCatching {
            val chats = tdlibManager.listMyChats().filter { it.isChannel }
            _uiState.value = _uiState.value.copy(chats = chats)
        }.onFailure { e ->
            // Previously swallowed silently, so a failed fetch (e.g. a transient error right
            // after a fresh login) looked identical to a real empty "no channels" state, with
            // no way to tell which one it was or retry without leaving the screen.
            _uiState.value = _uiState.value.copy(loadChatsError = e.message ?: "Couldn't load your channels")
        }
        _uiState.value = _uiState.value.copy(isLoadingChats = false)
    }

    fun syncChannel(context: Context, chatId: Long) {
        SyncService.start(context, chatId)
    }
}
