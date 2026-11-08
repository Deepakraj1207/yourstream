package dev.yourstream.telegram.core.chat

import dev.yourstream.telegram.api.model.ChatListType
import dev.yourstream.telegram.api.model.ChatPosition
import dev.yourstream.telegram.api.model.TelegramChat
import dev.yourstream.telegram.core.client.TelegramEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory mirror of the chats TDLib has told us about, kept current from [TelegramEvent]s.
 *
 * This is how TDLib expects clients to maintain chat lists: every chat arrives once as
 * updateNewChat, then position/title/last-message updates patch it. Thread-safe: every change
 * is an atomic replace of an immutable map.
 */
class ChatListStore {

    private val _chats = MutableStateFlow<Map<Long, TelegramChat>>(emptyMap())
    val chats: StateFlow<Map<Long, TelegramChat>> = _chats.asStateFlow()

    /** Applies a chat-related event. Returns false for events this store does not handle. */
    fun apply(event: TelegramEvent): Boolean {
        when (event) {
            is TelegramEvent.ChatAdded -> upsert(event.chat)
            is TelegramEvent.ChatTitleChanged -> patch(event.chatId) { it.copy(title = event.title) }
            is TelegramEvent.ChatPositionChanged -> patch(event.chatId) { it.withPosition(event.position) }
            is TelegramEvent.ChatLastMessageChanged -> patch(event.chatId) {
                it.copy(lastMessageDate = event.lastMessageDate, positions = event.positions.filter { p -> p.order != 0L })
            }
            is TelegramEvent.ChatUnreadCountChanged -> patch(event.chatId) { it.copy(unreadCount = event.unreadCount) }
            else -> return false
        }
        return true
    }

    fun upsert(chat: TelegramChat) {
        val clean = chat.copy(positions = chat.positions.filter { it.order != 0L })
        _chats.update { it + (clean.id to clean) }
    }

    fun clear() {
        _chats.value = emptyMap()
    }

    fun countIn(list: ChatListType): Int = _chats.value.values.count { it.positionIn(list) != null }

    private inline fun patch(chatId: Long, crossinline change: (TelegramChat) -> TelegramChat) {
        _chats.update { map ->
            val chat = map[chatId] ?: return@update map
            map + (chatId to change(chat))
        }
    }

    companion object {
        /** Chats of [list] in Telegram's order: descending position order, ties by id. */
        fun sortedIn(chats: Map<Long, TelegramChat>, list: ChatListType): List<TelegramChat> =
            chats.values
                .mapNotNull { chat -> chat.positionIn(list)?.let { chat to it.order } }
                .sortedWith(compareByDescending<Pair<TelegramChat, Long>> { it.second }.thenByDescending { it.first.id })
                .map { it.first }
    }
}

internal fun TelegramChat.withPosition(position: ChatPosition): TelegramChat {
    val others = positions.filterNot { it.list == position.list }
    return copy(positions = if (position.order == 0L) others else others + position)
}
