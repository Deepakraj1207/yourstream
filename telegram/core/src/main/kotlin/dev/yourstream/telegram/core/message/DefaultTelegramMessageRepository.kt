package dev.yourstream.telegram.core.message

import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import dev.yourstream.core.common.err
import dev.yourstream.telegram.api.TelegramMessageRepository
import dev.yourstream.telegram.api.model.MediaMessagePage
import dev.yourstream.telegram.api.model.MediaSearchFilter
import dev.yourstream.telegram.api.model.MessageLookup
import dev.yourstream.telegram.api.model.TelegramMessageEvent
import dev.yourstream.telegram.core.client.TelegramClient
import dev.yourstream.telegram.core.client.TelegramEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultTelegramMessageRepository @Inject constructor(
    private val client: TelegramClient,
) : TelegramMessageRepository {

    override val events: Flow<TelegramMessageEvent> = client.events.mapNotNull { event ->
        when (event) {
            is TelegramEvent.NewMessage -> event.media?.let { TelegramMessageEvent.NewMediaMessage(it) }
            // fromCache = TDLib merely forgot them locally; they still exist on the server.
            is TelegramEvent.MessagesDeleted ->
                if (event.isPermanent && !event.fromCache) TelegramMessageEvent.MessagesDeleted(event.chatId, event.messageIds) else null
            is TelegramEvent.MessageContentChanged -> TelegramMessageEvent.MessageChanged(event.chatId, event.messageId)
            else -> null
        }
    }

    override suspend fun searchMedia(
        chatId: Long,
        filter: MediaSearchFilter,
        fromMessageId: Long,
        limit: Int,
    ): AppResult<MediaMessagePage> {
        if (fromMessageId < 0) return AppError.InvalidInput("FROM_MESSAGE_ID", "fromMessageId must be >= 0").err()
        return client.searchChatMedia(chatId, filter, fromMessageId, limit.coerceIn(1, TelegramMessageRepository.MAX_PAGE_SIZE))
    }

    override suspend fun history(chatId: Long, fromMessageId: Long, limit: Int): AppResult<List<dev.yourstream.telegram.api.model.ChatPost>> =
        if (fromMessageId <= 0) AppResult.Ok(emptyList())
        else client.getChatHistory(chatId, fromMessageId, limit.coerceIn(1, TelegramMessageRepository.MAX_PAGE_SIZE))

    override suspend fun lookupMessages(chatId: Long, messageIds: List<Long>): AppResult<List<MessageLookup>> {
        if (messageIds.isEmpty()) return AppResult.Ok(emptyList())
        if (messageIds.size > TelegramMessageRepository.MAX_PAGE_SIZE) {
            return AppError.InvalidInput("TOO_MANY_IDS", "At most ${TelegramMessageRepository.MAX_PAGE_SIZE} ids per call").err()
        }
        return client.getMessages(chatId, messageIds)
    }
}
