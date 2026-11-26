package com.abn3li.telemusic.data.telegram

sealed class TelegramAuthState {
    data object LoggedOut : TelegramAuthState()
    data object Connecting : TelegramAuthState()
    data object WaitingForPhoneNumber : TelegramAuthState()
    /** [delivery] says where the code went, [nextDelivery] how a resend would send it (null when
     * Telegram offers none), [resendAtMillis] (elapsed time) when one is allowed. */
    data class WaitingForCode(
        val phoneNumber: String,
        val delivery: String,
        val nextDelivery: String?,
        val resendAtMillis: Long,
        val digits: Boolean = true,
        // Fragment's page, for a number bought there.
        val link: String? = null
    ) : TelegramAuthState() {
        fun secondsUntilResend(): Int =
            ((resendAtMillis - android.os.SystemClock.elapsedRealtime() + 999) / 1000).coerceAtLeast(0).toInt()
    }
    data class WaitingForQrScan(val link: String) : TelegramAuthState()
    data object WaitingForEmail : TelegramAuthState()
    data class WaitingForEmailCode(val address: String) : TelegramAuthState()
    data class WaitingForPassword(val hint: String) : TelegramAuthState()
    /** A step this app can't do; [message] says what to do instead. */
    data class Unsupported(val message: String) : TelegramAuthState()
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
    val title: String, val performer: String, val durationSeconds: Int,
    // The file's size and type, for comparing quality against another copy of the song.
    val sizeBytes: Long = 0L, val mimeType: String = "",
    // When it was posted (Unix seconds) and its small Telegram cover, for the chat's song page only.
    val dateSeconds: Int = 0, val thumbnailFileId: Int = 0
)

/** One page of a chat search: the songs found, and where the next page starts (0 = no more). */
class TelegramAudioPage(val songs: List<TelegramAudioMessage>, val nextFromMessageId: Long)

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