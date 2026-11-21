package dev.yourstream.media.indexer

import androidx.test.core.app.ApplicationProvider
import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import dev.yourstream.core.common.NoOpLogger
import dev.yourstream.core.database.YourStreamDatabase
import dev.yourstream.core.database.entity.IndexedChatEntity
import dev.yourstream.media.api.model.IndexMode
import dev.yourstream.media.api.model.IndexRunResult
import dev.yourstream.media.indexer.engine.EngineConfig
import dev.yourstream.media.indexer.engine.IndexProgressTracker
import dev.yourstream.media.indexer.engine.IndexingEngine
import dev.yourstream.media.indexer.engine.RequestGate
import dev.yourstream.media.indexer.store.RoomIndexStore
import dev.yourstream.media.parser.MediaNormalizer
import dev.yourstream.telegram.api.TelegramMessageRepository
import dev.yourstream.telegram.api.model.MediaMessagePage
import dev.yourstream.telegram.api.model.MediaSearchFilter
import dev.yourstream.telegram.api.model.MessageLookup
import dev.yourstream.telegram.api.model.TelegramMediaContentType
import dev.yourstream.telegram.api.model.TelegramMediaMessage
import dev.yourstream.telegram.api.model.TelegramMessageEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Channels with very many files. Telegram's search answers at most 100 messages per request, so a
 * 100,000-file channel read one page after another costs about 1,000 round trips in a row. The
 * engine splits a large backfill into message-id ranges read side by side ([IndexingEngine.split]),
 * all requests share one [RequestGate] that backs off on FLOOD_WAIT, and titles from the post
 * above are looked up after the scan instead of during it.
 */
@RunWith(RobolectricTestRunner::class)
class LargeChannelIndexingTest {

    /** A big channel: one video every [step] message ids; each search waits [latencyMs] (virtual time). */
    private class BigChannel(val chatId: Long, count: Int, step: Long = 3, val latencyMs: Long = 0) : TelegramMessageRepository {
        /** Message ids, newest first. */
        val ids = LongArray(count) { (count - it) * step }
        val searches = AtomicInteger()
        val inFlight = AtomicInteger()
        var maxInFlight = 0
        /** FLOOD_WAIT answers to give before serving normally. */
        var floods = 0
        override val events = MutableSharedFlow<TelegramMessageEvent>()

        fun message(id: Long): TelegramMediaMessage = mediaMessage(chatId, id, "Show.Name.S01E${id % 90 + 1}.720p.mkv", type = TelegramMediaContentType.VIDEO)

        override suspend fun searchMedia(chatId: Long, filter: MediaSearchFilter, fromMessageId: Long, limit: Int): AppResult<MediaMessagePage> {
            if (filter != MediaSearchFilter.VIDEO) return AppResult.Ok(MediaMessagePage(emptyList(), 0, 0))
            searches.incrementAndGet()
            val now = inFlight.incrementAndGet()
            synchronized(this) { maxInFlight = maxOf(maxInFlight, now) }
            try {
                delay(latencyMs)
                synchronized(this) { if (floods > 0) { floods--; return AppResult.Err(AppError.RateLimited(retryAfterSeconds = 1)) } }
                // First id below fromMessageId (ids are descending): a binary search, like Telegram's index.
                var lo = 0; var hi = ids.size
                if (fromMessageId != 0L) while (lo < hi) { val mid = (lo + hi) ushr 1; if (ids[mid] >= fromMessageId) lo = mid + 1 else hi = mid }
                val end = minOf(ids.size, lo + limit)
                val page = (lo until end).map { message(ids[it]) }
                val next = if (end < ids.size && page.isNotEmpty()) page.last().messageId else 0L
                return AppResult.Ok(MediaMessagePage(page, next, ids.size))
            } finally {
                inFlight.decrementAndGet()
            }
        }

        override suspend fun lookupMessages(chatId: Long, messageIds: List<Long>): AppResult<List<MessageLookup>> =
            AppResult.Ok(messageIds.map { MessageLookup.Found(message(it)) })
    }

    /**
     * The index without a database, for timing: Room runs on real threads, which the test's
     * virtual clock cannot measure (it would count database waits as free network time).
     */
    private class MemoryStore : dev.yourstream.media.indexer.store.IndexStore {
        val media = java.util.concurrent.ConcurrentHashMap<Pair<Long, Long>, dev.yourstream.media.parser.NormalizedMedia>()
        private val cursors = java.util.concurrent.ConcurrentHashMap<String, dev.yourstream.media.indexer.store.ScanCursor>()
        private fun key(c: dev.yourstream.media.indexer.store.ScanCursor) = "${c.chatId}:${c.filter}:${c.segmentLowerBound}"
        override suspend fun chat(chatId: Long) = dev.yourstream.media.indexer.store.ChatRecord(chatId, "Big", enabled = true, verifyCursor = 0, lastVerifiedAt = Long.MAX_VALUE / 2)
        override suspend fun cursor(chatId: Long, filter: MediaSearchFilter) = cursors["$chatId:$filter:null"] ?: dev.yourstream.media.indexer.store.ScanCursor(chatId, filter)
        override suspend fun segments(chatId: Long, filter: MediaSearchFilter) =
            cursors.values.filter { it.chatId == chatId && it.filter == filter && it.segmentLowerBound != null }.sortedByDescending { it.segmentLowerBound }
        override suspend fun deleteSegments(chatId: Long, filter: MediaSearchFilter) { cursors.values.removeIf { it.chatId == chatId && it.filter == filter && it.segmentLowerBound != null } }
        override suspend fun saveCursor(cursor: dev.yourstream.media.indexer.store.ScanCursor) { cursors[key(cursor)] = cursor }
        override suspend fun resetCursors(chatId: Long) { cursors.values.removeIf { it.chatId == chatId } }
        override suspend fun savePage(items: List<dev.yourstream.media.parser.NormalizedMedia>, cursor: dev.yourstream.media.indexer.store.ScanCursor?): dev.yourstream.media.indexer.store.WriteResult {
            var added = 0
            items.forEach { if (media.put(it.chatId to it.messageId, it) == null) added++ }
            cursor?.let { saveCursor(it) }
            return dev.yourstream.media.indexer.store.WriteResult(added = added)
        }
        override suspend fun markDeleted(chatId: Long, messageIds: List<Long>) = 0
        override suspend fun touch(chatId: Long, messageIds: List<Long>) {}
        override suspend fun liveMessageIdsAfter(chatId: Long, afterMessageId: Long, limit: Int) = emptyList<Long>()
        override suspend fun setVerifyCursor(chatId: Long, cursor: Long, finished: Boolean) {}
        override suspend fun setState(chatId: Long, state: dev.yourstream.media.api.model.IndexState, error: String?) {}
        override suspend fun markIndexed(chatId: Long) {}
        override suspend fun startRun(chatId: Long, mode: IndexMode) = 1L
        override suspend fun finishRun(runId: Long, result: IndexRunResult) {}
        override suspend fun isEnabled(chatId: Long) = true
        override suspend fun updateDownload(file: dev.yourstream.telegram.api.model.TelegramFile) {}
    }

    private val db: YourStreamDatabase = YourStreamDatabase.inMemory(ApplicationProvider.getApplicationContext())
    private val clock = TestClock()
    private val store = RoomIndexStore(db, clock)

    @After fun tearDown() = db.close()

    private fun engine(telegram: TelegramMessageRepository, config: EngineConfig) =
        IndexingEngine(telegram, store, MediaNormalizer(), IndexProgressTracker(), clock, NoOpLogger, config, emptySet())

    private suspend fun enable(chatId: Long) = db.indexedChatDao().upsert(
        IndexedChatEntity(chatId, "Big channel", "CHANNEL", enabled = true, state = "IDLE", lastError = null,
            lastIndexedAt = null, lastVerifiedAt = null, verifyCursor = 0, addedAt = clock.now, updatedAt = clock.now),
    )

    private suspend fun indexed(chatId: Long) = db.mediaDao().liveMessageIdsAfter(chatId, 0, Int.MAX_VALUE)

    @Test
    fun aSplitScanIndexesEveryFileOnceAndResumesAfterAStop() = runTest {
        val chat = -100L
        val telegram = BigChannel(chat, count = 600)
        // A small budget per run: each run stops part-way and the next continues every range.
        val config = EngineConfig(pageSize = 7, pageDelay = 0.milliseconds, segmentThreshold = 50, segmentsPerFilter = 4, parallelFilters = false)
        enable(chat)
        val engine = engine(telegram, config)
        clock.now = 0
        var runs = 0
        var result: IndexRunResult
        do {
            // 25 ms of clock per request: a 200 ms budget ends a run after a few pages.
            result = engine.run(chat, IndexMode.INCREMENTAL, 200.milliseconds.also { clock.now += 0 })
            clock.now += 25
            runs++
        } while (result.outcome == IndexRunResult.Outcome.MORE_WORK && runs < 500)
        assertEquals(IndexRunResult.Outcome.COMPLETED, result.outcome)
        assertEquals(telegram.ids.toList().sorted(), indexed(chat), "every file, none twice (the unique key would refuse a second row)")
        assertEquals(emptyList(), store.segments(chat, MediaSearchFilter.VIDEO), "ranges are dropped once all are done")
        assertTrue(store.cursor(chat, MediaSearchFilter.VIDEO).backfillComplete)
    }

    @Test
    fun aBigChannelIsReadAboutFourTimesFasterAndStaysUnderTheRequestLimit() = runTest {
        val count = 12_000
        suspend fun timeToIndex(chatId: Long, segments: Int): Pair<Long, Int> {
            val telegram = BigChannel(chatId, count, latencyMs = 300)
            val memory = MemoryStore()
            val engine = IndexingEngine(telegram, memory, MediaNormalizer(), IndexProgressTracker(), clock, NoOpLogger,
                EngineConfig(pageDelay = 30.milliseconds, segmentsPerFilter = segments, maxRequestsInFlight = 6), emptySet())
            val started = testScheduler.currentTime
            val r = engine.run(chatId, IndexMode.INCREMENTAL, 60.minutes)
            assertEquals(IndexRunResult.Outcome.COMPLETED, r.outcome)
            assertEquals(count, memory.media.size)
            if (segments > 1) assertTrue(telegram.maxInFlight <= 6, "at most 6 requests at once, was ${telegram.maxInFlight}")
            return (testScheduler.currentTime - started) to telegram.searches.get()
        }
        val (oneByOne, oneByOneRequests) = timeToIndex(-201L, segments = 1)
        val (split, splitRequests) = timeToIndex(-202L, segments = 4)
        println("12,000 files at 300 ms per request: one by one ${oneByOne / 1000.0} s ($oneByOneRequests requests), split ${split / 1000.0} s ($splitRequests requests)")
        assertTrue(split * 3 < oneByOne, "split $split ms vs one by one $oneByOne ms")
        assertTrue(splitRequests <= oneByOneRequests + 8, "splitting adds only a request or two per range")
    }

    @Test
    fun floodWaitHalvesTheRequestsInFlightAndTheScanStillFinishes() = runTest {
        val chat = -300L
        val telegram = BigChannel(chat, count = 3_000, latencyMs = 50).apply { floods = 3 }
        enable(chat)
        val r = engine(telegram, EngineConfig(pageDelay = 0.milliseconds, maxRequestsInFlight = 8)).run(chat, IndexMode.INCREMENTAL, 60.minutes)
        assertEquals(IndexRunResult.Outcome.COMPLETED, r.outcome)
        assertEquals(3_000, indexed(chat).size)
    }

    @Test
    fun theGateBacksOffOnFloodAndRecovers() = runTest {
        val gate = RequestGate(max = 8, recoverAfter = 3)
        val running = AtomicInteger()
        var peak = 0
        suspend fun burst() = coroutineScope {
            (1..20).map { async { gate.withPermit { val n = running.incrementAndGet(); synchronized(this@LargeChannelIndexingTest) { peak = maxOf(peak, n) }; delay(10); running.decrementAndGet() } } }.awaitAll()
        }
        burst()
        assertEquals(8, peak)
        gate.onFlood(); gate.onFlood()
        assertEquals(2, gate.limit)
        peak = 0
        burst()
        assertEquals(2, peak, "after two FLOOD_WAITs: 8 → 4 → 2 at once")
        repeat(9) { gate.onSuccess() }
        assertEquals(5, gate.limit, "one more per 3 successes in a row")
        gate.onFlood()
        assertEquals(2, gate.limit)
    }

    @Test
    fun smallChatsAreNotSplit() = runTest {
        val e = engine(BigChannel(-400L, 10), EngineConfig(segmentThreshold = 1_500))
        val small = dev.yourstream.media.indexer.store.ScanCursor(-400L, MediaSearchFilter.VIDEO, newestMessageId = 3_000, backfillFromMessageId = 2_700, estimatedTotal = 1_000)
        assertEquals(emptyList(), e.split(small))
        val big = small.copy(estimatedTotal = 100_000)
        val ranges = e.split(big)
        assertEquals(4, ranges.size)
        assertEquals(listOf(2_700L, 2_025L, 1_350L, 675L), ranges.map { it.newestMessageId })
        assertEquals(listOf(2_025L, 1_350L, 675L, 0L), ranges.map { it.segmentLowerBound })
        assertEquals(ranges.map { it.newestMessageId + 1 }, ranges.map { it.backfillFromMessageId }, "each range starts just above its top")
        // TDLib's ids (message number × 2^20): boundaries and starts stay on its grid.
        val unit = 1L shl 20
        val tdlib = e.split(big.copy(newestMessageId = 3_000 * unit, backfillFromMessageId = 2_700 * unit))
        assertEquals(listOf(2_700L, 2_025L, 1_350L, 675L).map { it * unit }, tdlib.map { it.newestMessageId })
        assertTrue(tdlib.all { it.backfillFromMessageId % unit == 0L && (it.segmentLowerBound ?: 0) % unit == 0L })
        assertEquals(tdlib.map { it.newestMessageId + unit }, tdlib.map { it.backfillFromMessageId })
    }
}
