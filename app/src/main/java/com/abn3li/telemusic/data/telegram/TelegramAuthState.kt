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

/** Which real Telegram chat kind a [TelegramChatInfo] is - channels aren't the only place audio
 * lives; a group, a bot, or a regular 1:1 chat can all have music shared in them too, and the
 * Sync screen now offers all of them instead of only channels. */
enum class TelegramChatCategory(val label: String) {
    CHANNEL("Channel"),
    GROUP("Group"),
    CHAT("Chat"),
    BOT("Bot")
}

data class TelegramChatInfo(
    val id: Long,
    val title: String,
    val category: TelegramChatCategory,
    // Only meaningful when category == CHANNEL - whether it has a public @username (t.me/name)
    // vs being invite-link-only. Requires a separate GetSupergroup call per channel (Chat itself
    // doesn't carry a username), so it's fetched once in listMyChats rather than on every render.
    val isPublic: Boolean = false
)