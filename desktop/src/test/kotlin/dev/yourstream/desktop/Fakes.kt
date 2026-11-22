package dev.yourstream.desktop

import dev.yourstream.core.common.AppResult
import dev.yourstream.telegram.api.TelegramMessageRepository
import dev.yourstream.telegram.api.model.ChatPost
import dev.yourstream.telegram.api.model.MediaMessagePage
import dev.yourstream.telegram.api.model.MediaSearchFilter
import dev.yourstream.telegram.api.model.MessageLookup
import dev.yourstream.telegram.api.model.TelegramFile
import dev.yourstream.telegram.api.model.TelegramMediaContentType
import dev.yourstream.telegram.api.model.TelegramMediaFile
import dev.yourstream.telegram.api.model.TelegramMediaMessage
import dev.yourstream.telegram.api.model.TelegramMessageEvent
import kotlinx.coroutines.flow.MutableSharedFlow

/** A tiny Telegram for tests: chats hold messages, searchMedia pages them newest first like TDLib. */
class FakeMessages : TelegramMessageRepository {
    val chats = mutableMapOf<Long, MutableMap<Long, TelegramMediaMessage>>()
    override val events = MutableSharedFlow<TelegramMessageEvent>()

    fun add(m: TelegramMediaMessage) { chats.getOrPut(m.chatId) { mutableMapOf() }[m.messageId] = m }

    override suspend fun searchMedia(chatId: Long, filter: MediaSearchFilter, fromMessageId: Long, limit: Int): AppResult<MediaMessagePage> {
        val matching = chats[chatId].orEmpty().values.filter { it.contentType.name == filter.name }
            .filter { fromMessageId == 0L || it.messageId < fromMessageId }.sortedByDescending { it.messageId }
        val page = matching.take(limit)
        return AppResult.Ok(MediaMessagePage(page, if (matching.size > limit) page.last().messageId else 0L, matching.size))
    }

    override suspend fun history(chatId: Long, fromMessageId: Long, limit: Int): AppResult<List<ChatPost>> =
        AppResult.Ok(chats[chatId].orEmpty().values.filter { it.messageId <= fromMessageId }.sortedByDescending { it.messageId }
            .take(limit).map { ChatPost(it.messageId, it.date, it.albumId, it.caption, hasFile = true) })

    override suspend fun lookupMessages(chatId: Long, messageIds: List<Long>): AppResult<List<MessageLookup>> =
        AppResult.Ok(messageIds.map { id -> chats[chatId]?.get(id)?.let { MessageLookup.Found(it) } ?: MessageLookup.Missing(id) })
}

fun mediaMessage(
    chatId: Long,
    messageId: Long,
    fileName: String,
    type: TelegramMediaContentType = TelegramMediaContentType.DOCUMENT,
    size: Long = 2_000_000L + messageId,
    mime: String = "application/octet-stream",
    height: Int? = if (type == TelegramMediaContentType.VIDEO) 1080 else null,
) = TelegramMediaMessage(
    chatId = chatId, messageId = messageId, date = 1_600_000_000L + messageId, editDate = 0, albumId = 0, caption = "",
    contentType = type,
    media = TelegramMediaFile(
        file = TelegramFile(
            id = (chatId * 100_000 + messageId).toInt(), remoteUniqueId = "u-$chatId-$messageId", remoteId = "remote-$chatId-$messageId",
            size = size, expectedSize = size, localPath = null, canBeDownloaded = true, isDownloadingActive = false,
            isDownloadingCompleted = false, downloadOffset = 0, downloadedPrefixSize = 0, downloadedSize = 0,
        ),
        fileName = fileName, mimeType = mime,
        durationSeconds = if (type == TelegramMediaContentType.VIDEO) 3600 else null,
        width = height?.let { it * 16 / 9 }, height = height,
        supportsStreaming = type == TelegramMediaContentType.VIDEO,
        codec = null, title = null, performer = null, thumbnailFileId = null,
    ),
)
