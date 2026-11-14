package dev.yourstream.media.indexer

import dev.yourstream.core.common.AppError
import dev.yourstream.media.api.model.DuplicateKind
import dev.yourstream.media.api.model.IndexMode
import dev.yourstream.media.api.model.IndexRunResult.Outcome
import dev.yourstream.media.api.model.IndexState
import dev.yourstream.media.api.model.MediaQuery
import dev.yourstream.telegram.api.model.MediaSearchFilter
import dev.yourstream.telegram.api.model.TelegramMediaContentType.VIDEO
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
class IndexingEngineTest {

    private val env = IndexerTestEnv()
    private val chat = 100L

    @After fun tearDown() = env.close()

    private suspend fun count(chatId: Long? = chat) = env.repository.count(MediaQuery(chatId = chatId)).first()

    /** 7 MKV documents, 2 videos, 1 subtitle, 1 PDF (not indexed). */
    private fun seed() {
        (1L..7L).forEach { env.telegram.add(mediaMessage(chat, it * 10, "Movie.$it.2020.1080p.mkv")) }
        env.telegram.add(mediaMessage(chat, 75, "clip.mp4", type = VIDEO))
        env.telegram.add(mediaMessage(chat, 85, "trailer.mp4", type = VIDEO))
        env.telegram.add(mediaMessage(chat, 90, "Movie.1.2020.srt", size = 40_000))
        env.telegram.add(mediaMessage(chat, 95, "manual.pdf", mime = "application/pdf"))
    }

    @Test
    fun filesOfAnAlbumShareItsCaption() = runTest {
        env.enable(chat)
        // Telegram stores an album's caption on one file only; the hashed names say nothing.
        env.telegram.add(mediaMessage(chat, 10, "7744f81a5b3063b96a3f54785bd51330.mkv", caption = "Vikram (2022) Tamil 1080p").copy(albumId = 77))
        env.telegram.add(mediaMessage(chat, 11, "74f2165fc2dbbe5d3747a4ccae8d27c4.mkv").copy(albumId = 77))
        env.engine.run(chat, IndexMode.INCREMENTAL, 5.minutes)
        listOf(10L, 11L).forEach { messageId ->
            val media = env.repository.getMedia(env.db.mediaDao().findKey(chat, messageId)!!.id)
            assertEquals("Vikram", media?.parsed?.title, "album file $messageId")
        }
    }

    @Test
    fun parallelFiltersIndexTheSameFiles() = runTest {
        val parallel = IndexerTestEnv(parallelFilters = true)
        parallel.enable(chat)
        (1L..7L).forEach { parallel.telegram.add(mediaMessage(chat, it * 10, "Movie.$it.2020.1080p.mkv")) }
        parallel.telegram.add(mediaMessage(chat, 75, "clip.mp4", type = VIDEO))
        parallel.telegram.add(mediaMessage(chat, 85, "trailer.mp4", type = VIDEO))
        parallel.telegram.add(mediaMessage(chat, 90, "Movie.1.2020.srt", size = 40_000))
        parallel.telegram.add(mediaMessage(chat, 95, "manual.pdf", mime = "application/pdf"))
        val result = parallel.engine.run(chat, IndexMode.INCREMENTAL, 5.minutes)
        assertEquals(Outcome.COMPLETED, result.outcome)
        assertEquals(10, result.mediaAdded)
        assertEquals(10, parallel.repository.count(MediaQuery(chatId = chat)).first())
        parallel.config.filters.forEach { f -> assertTrue(parallel.store.cursor(chat, f).backfillComplete, "$f done") }
        // A second run finds nothing new.
        assertEquals(0, parallel.engine.run(chat, IndexMode.INCREMENTAL, 5.minutes).mediaAdded)
        parallel.close()
    }

    @Test
    fun initialScanWalksEveryPageOfEveryFilter() = runTest {
        env.enable(chat); seed()
        val result = env.engine.run(chat, IndexMode.INCREMENTAL, 5.minutes)

        assertEquals(Outcome.COMPLETED, result.outcome)
        assertEquals(10, result.mediaAdded)
        assertEquals(10, count())
        val docCalls = env.telegram.calls.filter { it.startsWith("search:$chat:DOCUMENT") }
        // 9 documents (7 mkv, srt, pdf) in pages of 3, newest first.
        assertEquals(listOf("search:$chat:DOCUMENT:0", "search:$chat:DOCUMENT:70", "search:$chat:DOCUMENT:40"), docCalls)
        val cursor = env.store.cursor(chat, MediaSearchFilter.DOCUMENT)
        assertTrue(cursor.backfillComplete)
        assertEquals(95, cursor.newestMessageId)
        val status = env.repository.observeChat(chat).first()!!
        assertEquals(IndexState.COMPLETED, status.state)
        assertTrue(status.backfillComplete)
    }

    @Test
    fun incrementalRunOnlyFetchesWhatIsNew() = runTest {
        env.enable(chat); seed()
        env.engine.run(chat, IndexMode.INCREMENTAL, 5.minutes)
        env.telegram.add(mediaMessage(chat, 200, "New.Show.S01E01.720p.mkv"))
        env.telegram.add(mediaMessage(chat, 210, "New.Show.S01E02.720p.mkv"))
        env.telegram.calls.clear()

        val result = env.engine.run(chat, IndexMode.INCREMENTAL, 5.minutes)

        assertEquals(2, result.mediaAdded)
        assertEquals(12, count())
        // One page per filter from the top; it reaches known messages, so the walk stops.
        assertEquals(
            listOf("search:$chat:VIDEO:0", "search:$chat:DOCUMENT:0", "search:$chat:AUDIO:0"),
            env.telegram.calls.filter { it.startsWith("search") },
        )
        assertEquals(210, env.store.cursor(chat, MediaSearchFilter.DOCUMENT).newestMessageId)
    }

    @Test
    fun aFilterThatWasEmptyStillFindsLaterMedia() = runTest {
        env.enable(chat); seed()
        env.engine.run(chat, IndexMode.INCREMENTAL, 5.minutes)
        env.telegram.add(mediaMessage(chat, 500, "song.mp3", type = dev.yourstream.telegram.api.model.TelegramMediaContentType.AUDIO))
        assertEquals(1, env.engine.run(chat, IndexMode.INCREMENTAL, 5.minutes).mediaAdded)
    }

    @Test
    fun headPassWalksDownAcrossSeveralNewPages() = runTest {
        env.enable(chat); seed()
        env.engine.run(chat, IndexMode.INCREMENTAL, 5.minutes)
        (1L..8L).forEach { env.telegram.add(mediaMessage(chat, 300 + it, "Batch.$it.mkv")) }

        val result = env.engine.run(chat, IndexMode.INCREMENTAL, 5.minutes)
        assertEquals(8, result.mediaAdded)
        assertEquals(18, count())
    }

    @Test
    fun timeBudgetStopsAndTheNextRunResumesFromTheCursor() = runTest {
        env.enable(chat); seed()
        env.telegram.millisPerCall = 1_000
        val first = env.engine.run(chat, IndexMode.INCREMENTAL, 2.5.seconds)
        assertEquals(Outcome.MORE_WORK, first.outcome)
        val partial = count()
        assertTrue(partial in 1..9, "partial progress was saved: $partial")
        assertEquals(IndexState.QUEUED, env.repository.observeChat(chat).first()!!.state, "the rest is queued, not paused")

        env.telegram.millisPerCall = 0
        env.telegram.calls.clear()
        val second = env.engine.run(chat, IndexMode.INCREMENTAL, 5.minutes)
        assertEquals(Outcome.COMPLETED, second.outcome)
        assertEquals(10, count())
        assertEquals(10 - partial, second.mediaAdded, "nothing was indexed twice")
    }

    @Test
    fun cancellationKeepsSavedPagesAndRecordsWhoCancelled() = runTest {
        env.enable(chat); seed()
        env.telegram.suspendOnCall = 3 // after two VIDEO/DOCUMENT pages
        val job = async(start = CoroutineStart.UNDISPATCHED) { env.engine.run(chat, IndexMode.INCREMENTAL, 5.minutes) }
        while (env.telegram.calls.size < 3) yield()
        env.tracker.requestCancel(chat)
        job.cancel()
        runCatching { job.await() }

        val status = env.repository.observeChat(chat).first()!!
        assertEquals(IndexState.CANCELLED, status.state)
        val saved = count()
        assertTrue(saved > 0)
        assertNull(env.tracker.progress.value[chat])

        env.telegram.suspendOnCall = null
        val resumed = env.engine.run(chat, IndexMode.INCREMENTAL, 5.minutes)
        assertEquals(Outcome.COMPLETED, resumed.outcome)
        assertEquals(10, count())
    }

    @Test
    fun forwardsAreExactDuplicatesAndReuploadsProbableOnes() = runTest {
        val other = 200L
        env.enable(chat); env.enable(other)
        env.telegram.add(mediaMessage(chat, 1, "Vikram.2022.1080p.mkv", size = 3_000_000, uniqueId = "same-upload"))
        env.telegram.add(mediaMessage(other, 5, "Vikram.2022.1080p.mkv", size = 3_000_000, uniqueId = "same-upload"))
        env.telegram.add(mediaMessage(other, 6, "Vikram 2022 1080p.mkv", size = 3_000_000, uniqueId = "re-upload"))
        env.engine.run(chat, IndexMode.INCREMENTAL, 5.minutes)
        env.engine.run(other, IndexMode.INCREMENTAL, 5.minutes)

        val original = env.repository.search(MediaQuery()).let { env.db.mediaDao().findKey(chat, 1)!! }
        val forward = env.repository.getMedia(env.db.mediaDao().findKey(other, 5)!!.id)!!
        val reupload = env.repository.getMedia(env.db.mediaDao().findKey(other, 6)!!.id)!!
        assertEquals(original.id, forward.canonicalMediaId)
        assertEquals(DuplicateKind.EXACT, forward.duplicateKind)
        assertEquals(original.id, reupload.canonicalMediaId)
        assertEquals(DuplicateKind.PROBABLE, reupload.duplicateKind)
        assertEquals(2, env.repository.duplicatesOf(original.id).size)
        assertEquals(1, env.repository.count(MediaQuery(includeDuplicates = false)).first())

        // Deleting the canonical copy re-groups the survivors: the oldest row (the re-upload,
        // indexed first because pages are newest-first) becomes canonical, and the forward is
        // now only a PROBABLE duplicate of it (different upload, same name and size).
        env.store.markDeleted(chat, listOf(1))
        assertNull(env.repository.getMedia(reupload.id)!!.canonicalMediaId)
        val forwardAfter = env.repository.getMedia(forward.id)!!
        assertEquals(reupload.id, forwardAfter.canonicalMediaId)
        assertEquals(DuplicateKind.PROBABLE, forwardAfter.duplicateKind)
    }

    @Test
    fun rescanningTheSameMessagesNeverDuplicatesRows() = runTest {
        env.enable(chat); seed()
        env.engine.run(chat, IndexMode.INCREMENTAL, 5.minutes)
        val rescan = env.engine.run(chat, IndexMode.FULL_RESCAN, 5.minutes)
        assertEquals(0, rescan.mediaAdded)
        assertEquals(10, count())
    }

    @Test
    fun verifyMarksDeletedAndEditedAwayMedia() = runTest {
        env.enable(chat); seed()
        env.engine.run(chat, IndexMode.INCREMENTAL, 5.minutes)
        env.telegram.remove(chat, 10)
        env.telegram.remove(chat, 75)
        // File replaced by a PDF: no longer indexable → treated as gone.
        env.telegram.add(mediaMessage(chat, 20, "replaced.pdf", mime = "application/pdf"))

        val result = env.engine.run(chat, IndexMode.VERIFY, 5.minutes)

        assertEquals(Outcome.COMPLETED, result.outcome)
        assertEquals(3, result.mediaDeleted)
        assertEquals(7, count())
        assertEquals(10, env.repository.count(MediaQuery(chatId = chat, includeDeleted = true)).first())
        assertEquals(3, env.repository.observeChat(chat).first()!!.deletedCount)
        assertEquals(0, env.db.indexedChatDao().get(chat)!!.verifyCursor, "finished check resets the cursor")
    }

    @Test
    fun floodWaitIsWaitedOutAndNetworkErrorsAreRetried() = runTest {
        env.enable(chat); seed()
        env.telegram.errors += listOf(AppError.RateLimited(3), AppError.Network(), AppError.Timeout("x"))
        val result = env.engine.run(chat, IndexMode.INCREMENTAL, 5.minutes)
        assertEquals(Outcome.COMPLETED, result.outcome)
        assertEquals(10, count())
    }

    @Test
    fun persistentNetworkFailureEndsTheRunForALaterRetry() = runTest {
        env.enable(chat); seed()
        repeat(10) { env.telegram.errors += AppError.Network() }
        val result = env.engine.run(chat, IndexMode.INCREMENTAL, 5.minutes)
        assertEquals(Outcome.RETRY, result.outcome)
        val status = env.repository.observeChat(chat).first()!!
        // Retried later by WorkManager: RECOVERING with the reason (FAILED only once retries run out).
        assertEquals(IndexState.RECOVERING, status.state)
        assertEquals("Network unavailable", status.lastError)
    }

    @Test
    fun longFloodWaitIsNotWaitedInProcess() = runTest {
        env.enable(chat); seed()
        env.telegram.errors += AppError.RateLimited(3_600)
        assertEquals(Outcome.RETRY, env.engine.run(chat, IndexMode.INCREMENTAL, 5.minutes).outcome)
    }

    @Test
    fun inaccessibleChatFailsPermanently() = runTest {
        env.enable(chat); seed()
        env.telegram.errors += AppError.Telegram(400, "CHANNEL_PRIVATE")
        assertEquals(Outcome.FAILED, env.engine.run(chat, IndexMode.INCREMENTAL, 5.minutes).outcome)
    }

    @Test
    fun disabledChatIsNotScanned() = runTest {
        seed()
        assertEquals(Outcome.FAILED, env.engine.run(chat, IndexMode.INCREMENTAL, 5.minutes).outcome)
        assertTrue(env.telegram.calls.isEmpty())
        assertFalse(env.store.isEnabled(chat))
    }
}
