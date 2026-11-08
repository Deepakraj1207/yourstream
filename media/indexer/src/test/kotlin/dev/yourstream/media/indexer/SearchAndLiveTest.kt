package dev.yourstream.media.indexer

import androidx.paging.PagingSource
import dev.yourstream.core.common.NoOpLogger
import dev.yourstream.core.database.dao.MediaSummaryRow
import dev.yourstream.media.api.model.IndexMode
import dev.yourstream.media.api.model.MediaQuery
import dev.yourstream.media.api.model.MediaType
import dev.yourstream.media.indexer.live.LiveIndexUpdater
import dev.yourstream.media.parser.SearchText
import dev.yourstream.telegram.api.TelegramFileRepository
import dev.yourstream.telegram.api.model.TelegramMessageEvent
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

@RunWith(RobolectricTestRunner::class)
class SearchAndLiveTest {

    private val env = IndexerTestEnv()
    private val chat = 100L

    @After fun tearDown() = env.close()

    private suspend fun searchIds(text: String, types: Set<MediaType> = emptySet(), chatId: Long? = null): List<Long> {
        val match = SearchText.ftsQuery(text)!!
        val t = types.map { it.name }
        val source = env.db.mediaDao().search(match, chatId, t, t.size, includeDeleted = false, includeDuplicates = true)
        val page = source.load(PagingSource.LoadParams.Refresh(null, 50, false)) as PagingSource.LoadResult.Page<Int, MediaSummaryRow>
        return page.data.map { it.messageId }
    }

    private suspend fun indexSample() {
        env.enable(chat)
        env.telegram.add(mediaMessage(chat, 1, "Vikram.2022.1080p.WEB-DL.Tamil.mkv"))
        env.telegram.add(mediaMessage(chat, 2, "Vikram.2022.Tamil.srt", size = 50_000))
        env.telegram.add(mediaMessage(chat, 3, "Avengers.Endgame.2019.2160p.mkv"))
        env.telegram.add(mediaMessage(chat, 4, "video_2024.mp4", caption = "Amélie (2001) French 720p"))
        env.engine.run(chat, IndexMode.INCREMENTAL, 5.minutes)
    }

    @Test
    fun fullTextSearchUsesPrefixesAndFilters() = runTest {
        indexSample()
        assertEquals(listOf(2L, 1L), searchIds("vikr"), "newest first")
        assertEquals(listOf(3L), searchIds("aven endg"))
        assertEquals(listOf(2L), searchIds("vikram", types = setOf(MediaType.SUBTITLE)))
        assertEquals(listOf(4L), searchIds("amelie"), "caption title, accents folded")
        assertTrue(searchIds("vikram", chatId = 999).isEmpty())
        assertEquals(2, env.repository.count(MediaQuery(text = "Vikram")).first())
        assertEquals(4, env.repository.count(MediaQuery(text = "  ")).first(), "blank text browses everything")
    }

    @Test
    fun deletedMediaDropOutOfSearch() = runTest {
        indexSample()
        env.store.markDeleted(chat, listOf(1))
        assertEquals(listOf(2L), searchIds("vikram"))
    }

    @Test
    fun liveUpdatesInsertAndDeleteForEnabledChatsOnly() = runTest {
        indexSample()
        val live = LiveIndexUpdater(env.telegram, NoFiles, env.store, env.normalizer, NoOpLogger, emptySet())

        live.handle(TelegramMessageEvent.NewMediaMessage(mediaMessage(chat, 50, "Leo.2023.720p.mkv")))
        assertEquals(listOf(50L), searchIds("leo"))

        live.handle(TelegramMessageEvent.MessagesDeleted(chat, listOf(50)))
        assertTrue(searchIds("leo").isEmpty())

        live.handle(TelegramMessageEvent.NewMediaMessage(mediaMessage(777, 1, "Leo.2023.720p.mkv")))
        assertTrue(searchIds("leo").isEmpty(), "chat 777 is not indexed")
    }

    @Test
    fun parsedFieldsAreStoredAndInspectable() = runTest {
        indexSample()
        val id = env.db.mediaDao().findKey(chat, 1)!!.id
        val media = env.repository.getMedia(id)!!
        assertEquals("Chat $chat", media.chatTitle)
        assertEquals("mkv", media.extension)
        assertEquals("Vikram", media.parsed?.title)
        assertEquals(2022, media.parsed?.year)
        assertEquals(1080, media.parsed?.resolution)
        assertEquals(listOf("ta"), media.parsed?.languages)
        assertEquals(MediaType.VIDEO, media.mediaType)
    }

    @Test
    fun tensOfThousandsOfRowsArePagedNotLoaded() = runTest {
        env.enable(chat)
        val total = 20_000
        // Written the way the engine writes: batched pages in transactions.
        (1..total).chunked(500).forEach { chunk ->
            env.store.upsert(chunk.map { env.normalizer.normalize(mediaMessage(chat, it.toLong(), "Series.S01E${it % 100}.Episode.$it.mkv"))!! })
        }
        assertEquals(total, env.repository.count(MediaQuery(chatId = chat)).first())
        val first = searchIds("series")
        assertEquals(50, first.size, "only one page is materialised")
        assertEquals(total.toLong(), first.first())
        assertEquals(total, env.repository.observeChat(chat).first()!!.mediaCount)
    }

    private object NoFiles : TelegramFileRepository {
        override val fileUpdates = emptyFlow<dev.yourstream.telegram.api.model.TelegramFile>()
        override suspend fun getFile(fileId: Int) = throw UnsupportedOperationException()
        override fun observeFile(fileId: Int) = emptyFlow<dev.yourstream.telegram.api.model.TelegramFile>()
        override suspend fun download(fileId: Int, priority: Int, offset: Long, limit: Long) = throw UnsupportedOperationException()
        override suspend fun cancelDownload(fileId: Int) = throw UnsupportedOperationException()
        override suspend fun downloadedPrefixSize(fileId: Int, offset: Long) = throw UnsupportedOperationException()
        override suspend fun readPart(fileId: Int, offset: Long, count: Long) = throw UnsupportedOperationException()
        override suspend fun deleteLocalCopy(fileId: Int) = throw UnsupportedOperationException()
    }
}
