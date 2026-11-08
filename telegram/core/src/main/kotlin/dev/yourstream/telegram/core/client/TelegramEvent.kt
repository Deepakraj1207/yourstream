package dev.yourstream.telegram.core.client

import dev.yourstream.telegram.api.model.AuthState
import dev.yourstream.telegram.api.model.ChatPosition
import dev.yourstream.telegram.api.model.TelegramChat
import dev.yourstream.telegram.api.model.TelegramConnectionState
import dev.yourstream.telegram.api.model.TelegramFile
import dev.yourstream.telegram.api.model.TelegramUser

/** TDLib updates the app cares about, already translated to our models. */
sealed interface TelegramEvent {
    data class AuthStateChanged(val state: AuthState) : TelegramEvent
    data class ConnectionStateChanged(val state: TelegramConnectionState) : TelegramEvent

    /** First time TDLib tells us about a chat (also re-sent after every restart). */
    data class ChatAdded(val chat: TelegramChat) : TelegramEvent
    data class ChatTitleChanged(val chatId: Long, val title: String) : TelegramEvent

    /** [position] with order 0 means the chat left that list. */
    data class ChatPositionChanged(val chatId: Long, val position: ChatPosition) : TelegramEvent

    /** New last message; [positions] replaces all positions of the chat. */
    data class ChatLastMessageChanged(
        val chatId: Long,
        val lastMessageDate: Long?,
        val positions: List<ChatPosition>,
    ) : TelegramEvent

    data class ChatUnreadCountChanged(val chatId: Long, val unreadCount: Int) : TelegramEvent
    data class UserChanged(val user: TelegramUser) : TelegramEvent
    data class FileChanged(val file: TelegramFile) : TelegramEvent
}
