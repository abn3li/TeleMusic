package com.abn3li.telemusic.data.telegram

sealed class TelegramAuthState {
    data object LoggedOut : TelegramAuthState()
    data object Connecting : TelegramAuthState()
    data object WaitingForPhoneNumber : TelegramAuthState()
    data class WaitingForCode(val deliveryDescription: String) : TelegramAuthState()
    data object WaitingForPassword : TelegramAuthState()
    data object Ready : TelegramAuthState()
    data class Error(val message: String) : TelegramAuthState()
}

enum class TelegramConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTING_TO_PROXY,
    WAITING_FOR_NETWORK,
    UPDATING,
    CONNECTED
}

data class TelegramAudioMessage(
    val messageId: Long, val fileId: Int,
    val title: String, val performer: String, val durationSeconds: Int
)

data class TelegramChatInfo(
    val id: Long,
    val title: String,
    val isChannel: Boolean,
    // Only meaningful when isChannel - whether it has a public @username (t.me/name) vs being
    // invite-link-only. Requires a separate GetSupergroup call per channel (Chat itself doesn't
    // carry a username), so it's fetched once in listMyChats rather than on every render.
    val isPublic: Boolean = false
)