package dev.yourstream.media.indexer

import androidx.test.core.app.ApplicationProvider
import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import dev.yourstream.core.common.Clock
import dev.yourstream.core.common.NoOpLogger
import dev.yourstream.core.database.YourStreamDatabase
import dev.yourstream.core.database.entity.IndexedChatEntity
import dev.yourstream.media.indexer.engine.EngineConfig
import dev.yourstream.media.indexer.engine.IndexProgressTracker
import dev.yourstream.media.indexer.engine.IndexingEngine
import dev.yourstream.media.indexer.repository.RoomMediaIndexRepository
import dev.yourstream.media.indexer.store.RoomIndexStore
import dev.yourstream.media.parser.MediaNormalizer
import dev.yourstream.telegram.api.TelegramMessageRepository
import dev.yourstream.telegram.api.model.MediaMessagePage
import dev.yourstream.telegram.api.model.MediaSearchFilter
import dev.yourstream.telegram.api.model.MessageLookup
import dev.yourstream.telegram.api.model.TelegramFile
import dev.yourstream.telegram.api.model.TelegramMediaContentType
import dev.yourstream.telegram.api.model.TelegramMediaFile
import dev.yourstream.telegram.api.model.TelegramMediaMessage
import dev.yourstream.telegram.api.model.TelegramMessageEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlin.time.Duration.Companion.milliseconds

class TestClock(var now: Long = 1_700_000_000_000L) : Clock {
    override fun nowMillis(): Long = now
}

/** A tiny Telegram: chats hold messages; searchMedia pages them newest-first like TDLib. */
class FakeMessageRepository(private val clock: TestClock) : TelegramMessageRepository {
    val chats = mutableMapOf<Long, MutableMap<Long, TelegramMediaMessage>>()
    val calls = mutableListOf<String>()
    val errors = ArrayDeque<AppError>()

    /** Advance the clock by this much per request (to exercise time budgets). */
    var millisPerCall = 0L

    /** If set, the n-th search call (1-based) suspends until [gate] completes. */
    var suspendOnCall: Int? = null
    val gate = CompletableDeferred<Unit>()
    private var searchCount = 0

    override val events = MutableSharedFlow<TelegramMessageEvent>()

    fun add(message: TelegramMediaMessage) {
        chats.getOrPut(message.chatId) { mutableMapOf() }[message.messageId] = message
    }

    fun remove(chatId: Long, messageId: Long) {
        chats[chatId]?.remove(messageId)
    }

    override suspend fun searchMedia(chatId: Long, filter: MediaSearchFilter, fromMessageId: Long, limit: Int): AppResult<MediaMessagePage> {
        calls += "search:$chatId:$filter:$fromMessageId"
        clock.now += millisPerCall
        searchCount++
        if (suspendOnCall == searchCount) gate.await()
        errors.removeFirstOrNull()?.let { return AppResult.Err(it) }
        val matching = chats[chatId].orEmpty().values
            .filter { it.contentType.name == filter.name }
            .filter { fromMessageId == 0L || it.messageId < fromMessageId }
            .sortedByDescending { it.messageId }
        val page = matching.take(limit)
        val next = if (matching.size > limit) page.last().messageId else 0L
        return AppResult.Ok(MediaMessagePage(page, next, chats[chatId].orEmpty().values.count { it.contentType.name == filter.name }))
    }

    override suspend fun lookupMessages(chatId: Long, messageIds: List<Long>): AppResult<List<MessageLookup>> {
        calls += "lookup:$chatId:${messageIds.size}"
        errors.removeFirstOrNull()?.let { return AppResult.Err(it) }
        return AppResult.Ok(messageIds.map { id -> chats[chatId]?.get(id)?.let { MessageLookup.Found(it) } ?: MessageLookup.Missing(id) })
    }
}

fun mediaMessage(
    chatId: Long,
    messageId: Long,
    fileName: String,
    type: TelegramMediaContentType = TelegramMediaContentType.DOCUMENT,
    size: Long = 2_000_000L + messageId,
    uniqueId: String = "u-$chatId-$messageId",
    mime: String = "application/octet-stream",
    caption: String = "",
) = TelegramMediaMessage(
    chatId = chatId,
    messageId = messageId,
    date = 1_600_000_000L + messageId,
    editDate = 0,
    albumId = 0,
    caption = caption,
    contentType = type,
    media = TelegramMediaFile(
        file = TelegramFile(
            id = messageId.toInt(), remoteUniqueId = uniqueId, remoteId = "remote-$uniqueId", size = size, expectedSize = size,
            localPath = null, canBeDownloaded = true, isDownloadingActive = false, isDownloadingCompleted = false,
            downloadOffset = 0, downloadedPrefixSize = 0, downloadedSize = 0,
        ),
        fileName = fileName,
        mimeType = mime,
        durationSeconds = if (type == TelegramMediaContentType.VIDEO) 3600 else null,
        width = if (type == TelegramMediaContentType.VIDEO) 1920 else null,
        height = if (type == TelegramMediaContentType.VIDEO) 1080 else null,
        supportsStreaming = type == TelegramMediaContentType.VIDEO,
        codec = null,
        title = null,
        performer = null,
        thumbnailFileId = null,
    ),
)

/** Real Room (in memory) + real engine + fake Telegram. */
class IndexerTestEnv {
    val clock = TestClock()
    val db: YourStreamDatabase = YourStreamDatabase.inMemory(ApplicationProvider.getApplicationContext())
    val telegram = FakeMessageRepository(clock)
    val tracker = IndexProgressTracker()
    val store = RoomIndexStore(db, clock)
    val normalizer = MediaNormalizer()
    val config = EngineConfig(pageSize = 3, pageDelay = 0.milliseconds, retryBaseDelay = 10.milliseconds, verifyBatch = 2)
    var indexChanges = 0
    val engine = IndexingEngine(telegram, store, normalizer, tracker, clock, NoOpLogger, config, setOf(dev.yourstream.media.api.MediaIndexListener { indexChanges++ }))
    val repository = RoomMediaIndexRepository(db, tracker)

    suspend fun enable(chatId: Long, title: String = "Chat $chatId") {
        db.indexedChatDao().upsert(
            IndexedChatEntity(chatId, title, "CHANNEL", enabled = true, state = "IDLE", lastError = null,
                lastIndexedAt = null, lastVerifiedAt = null, verifyCursor = 0, addedAt = clock.now, updatedAt = clock.now),
        )
    }

    fun close() = db.close()
}
