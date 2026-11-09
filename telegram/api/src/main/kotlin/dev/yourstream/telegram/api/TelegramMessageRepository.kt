package dev.yourstream.telegram.api

import dev.yourstream.core.common.AppResult
import dev.yourstream.telegram.api.model.ChatPost
import dev.yourstream.telegram.api.model.MediaMessagePage
import dev.yourstream.telegram.api.model.MediaSearchFilter
import dev.yourstream.telegram.api.model.MessageLookup
import dev.yourstream.telegram.api.model.TelegramMessageEvent
import kotlinx.coroutines.flow.Flow

/** Read-only access to media messages, for the indexer. */
public interface TelegramMessageRepository {

    /** Live media events for all chats (new, deleted, changed). Hot, never completes. */
    public val events: Flow<TelegramMessageEvent>

    /**
     * Media messages of [chatId] matching [filter], newest first, starting *before*
     * [fromMessageId] (0 = from the newest). Telegram may return fewer than [limit] (max 100).
     */
    public suspend fun searchMedia(
        chatId: Long,
        filter: MediaSearchFilter,
        fromMessageId: Long,
        limit: Int = MAX_PAGE_SIZE,
    ): AppResult<MediaMessagePage>

    /** Looks up known messages again, in the order given. Up to 100 ids per call. */
    public suspend fun lookupMessages(chatId: Long, messageIds: List<Long>): AppResult<List<MessageLookup>>

    /**
     * Messages of [chatId] from [fromMessageId] (included) towards older ones, newest first, all
     * kinds (up to [limit], max 100): the posts around a file, for its title.
     */
    public suspend fun history(chatId: Long, fromMessageId: Long, limit: Int = MAX_PAGE_SIZE): AppResult<List<ChatPost>> = AppResult.Ok(emptyList())

    public companion object {
        public const val MAX_PAGE_SIZE: Int = 100
    }
}
