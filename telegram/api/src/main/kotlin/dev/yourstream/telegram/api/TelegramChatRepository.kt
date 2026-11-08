package dev.yourstream.telegram.api

import dev.yourstream.core.common.AppResult
import dev.yourstream.telegram.api.model.ChatFilter
import dev.yourstream.telegram.api.model.ChatListState
import dev.yourstream.telegram.api.model.ChatListType
import dev.yourstream.telegram.api.model.TelegramChat
import dev.yourstream.telegram.api.model.TelegramChatDetails
import kotlinx.coroutines.flow.Flow

/**
 * Chats of the logged-in account.
 *
 * Telegram sends chats in pages. [chatList] is a live, sorted view of everything loaded so far;
 * [loadMore] fetches the next page. Edits, new messages and reordering arrive automatically.
 */
public interface TelegramChatRepository {
    public fun chatList(list: ChatListType = ChatListType.Main): Flow<ChatListState>

    /** [chatList] narrowed to private chats, groups or channels. */
    public fun chatList(list: ChatListType, filter: ChatFilter): Flow<ChatListState>

    /** Loads about [pageSize] more chats. No-op once everything is loaded. */
    public suspend fun loadMore(list: ChatListType = ChatListType.Main, pageSize: Int = DEFAULT_PAGE_SIZE): AppResult<Unit>

    /** Loads at least [minCount] chats (or all, if fewer exist). */
    public suspend fun loadAtLeast(list: ChatListType, minCount: Int): AppResult<Unit>

    public fun observeChat(chatId: Long): Flow<TelegramChat?>

    public suspend fun getChat(chatId: Long): AppResult<TelegramChat>

    /** Description, member count, username etc. Fetched from Telegram on each call. */
    public suspend fun getChatDetails(chatId: Long): AppResult<TelegramChatDetails>

    public companion object {
        public const val DEFAULT_PAGE_SIZE: Int = 50
    }
}
