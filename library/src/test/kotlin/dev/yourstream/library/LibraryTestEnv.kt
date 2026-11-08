package dev.yourstream.library

import androidx.paging.PagingData
import androidx.paging.testing.asSnapshot
import androidx.test.core.app.ApplicationProvider
import dev.yourstream.core.common.Clock
import dev.yourstream.core.common.NoOpLogger
import dev.yourstream.core.database.YourStreamDatabase
import dev.yourstream.core.database.entity.IndexedChatEntity
import dev.yourstream.library.data.LibrarySynchronizer
import dev.yourstream.library.data.RoomAudioTrackRepository
import dev.yourstream.library.data.RoomEpisodeRepository
import dev.yourstream.library.data.RoomMediaRepository
import dev.yourstream.library.data.RoomMovieRepository
import dev.yourstream.library.data.RoomSeriesRepository
import dev.yourstream.library.data.RoomSubtitleRepository
import dev.yourstream.library.data.RoomWatchHistoryRepository
import dev.yourstream.library.data.TrackPreferenceStore
import dev.yourstream.media.indexer.store.RoomIndexStore
import dev.yourstream.media.parser.MediaNormalizer
import dev.yourstream.telegram.api.model.TelegramFile
import dev.yourstream.telegram.api.model.TelegramMediaContentType
import dev.yourstream.telegram.api.model.TelegramMediaFile
import dev.yourstream.telegram.api.model.TelegramMediaMessage
import kotlinx.coroutines.flow.Flow

class TestClock(var now: Long = 1_700_000_000_000L) : Clock {
    override fun nowMillis(): Long = now
}

/**
 * Real database (with triggers), real index store and normaliser, real library. Files enter the
 * library exactly as in the app: index upsert → trigger → sync.
 */
class LibraryTestEnv {
    val clock = TestClock()
    val db = YourStreamDatabase.inMemory(ApplicationProvider.getApplicationContext())
    val store = RoomIndexStore(db, clock)
    val normalizer = MediaNormalizer()
    val sync = LibrarySynchronizer(db, clock, NoOpLogger)
    val watch = RoomWatchHistoryRepository(db, clock)
    val media = RoomMediaRepository(db, sync, watch, clock)
    val movies = RoomMovieRepository(media)
    val series = RoomSeriesRepository(db, media)
    val episodes = RoomEpisodeRepository(db)
    private val preferences = TrackPreferenceStore(db, clock)
    val subtitles = RoomSubtitleRepository(db, preferences, clock)
    val audio = RoomAudioTrackRepository(db, preferences)

    private val chats = mutableSetOf<Long>()
    private var nextMessage = 1L

    suspend fun chat(chatId: Long) {
        if (chats.add(chatId)) {
            db.indexedChatDao().upsert(IndexedChatEntity(chatId, "Chat $chatId", "CHANNEL", true, "IDLE", null, null, null, 0, 0, 0))
        }
    }

    /** Indexes one file like the indexer does. Returns its message id. */
    suspend fun file(
        name: String,
        chatId: Long = 1,
        messageId: Long = nextMessage++,
        type: TelegramMediaContentType = TelegramMediaContentType.DOCUMENT,
        size: Long = 700_000_000L + messageId,
        uniqueId: String = "u-$chatId-$messageId",
        caption: String = "",
    ): Long {
        chat(chatId)
        clock.now += 1_000
        val message = TelegramMediaMessage(
            chatId = chatId, messageId = messageId, date = 1_600_000_000L + messageId, editDate = 0, albumId = 0, caption = caption,
            contentType = type,
            media = TelegramMediaFile(
                file = TelegramFile(messageId.toInt(), uniqueId, "r-$uniqueId", size, size, null, true, false, false, 0, 0, 0),
                fileName = name, mimeType = "", durationSeconds = null, width = null, height = null, supportsStreaming = false,
                codec = null, title = null, performer = null, thumbnailFileId = null,
            ),
        )
        store.upsert(listOfNotNull(normalizer.normalize(message, "Chat $chatId")))
        return messageId
    }

    suspend fun mediaId(messageId: Long, chatId: Long = 1): Long = db.mediaDao().findKey(chatId, messageId)!!.id

    suspend fun itemIdOf(messageId: Long, chatId: Long = 1): Long = db.libraryDao().link(mediaId(messageId, chatId))!!.itemId

    suspend fun <T : Any> Flow<PagingData<T>>.all(): List<T> = asSnapshot { scrollTo(100_000) }

    fun close() = db.close()
}
