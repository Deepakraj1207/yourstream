package dev.yourstream.media.indexer.engine

import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import dev.yourstream.core.common.Clock
import dev.yourstream.core.common.Logger
import dev.yourstream.media.api.MediaIndexListener
import dev.yourstream.media.api.model.IndexMode
import dev.yourstream.media.api.model.IndexPhase
import dev.yourstream.media.api.model.IndexProgress
import dev.yourstream.media.api.model.IndexRunResult
import dev.yourstream.media.api.model.IndexRunResult.Outcome
import dev.yourstream.media.api.model.IndexState
import dev.yourstream.media.indexer.store.IndexStore
import dev.yourstream.media.indexer.store.ScanCursor
import dev.yourstream.media.parser.MediaNormalizer
import dev.yourstream.telegram.api.TelegramMessageRepository
import dev.yourstream.telegram.api.model.MediaMessagePage
import dev.yourstream.telegram.api.model.MediaSearchFilter
import dev.yourstream.telegram.api.model.MessageLookup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Telegram → message scanner → media extractor/normaliser → Room, for one chat per [run].
 *
 * Per chat and search filter a [ScanCursor] remembers two positions:
 *  - `newestMessageId`: everything at or below it (that existed when scanned) is indexed.
 *  - `backfillFromMessageId`: the walk towards the oldest message continues here.
 * A run first does the HEAD pass (newest → newestMessageId: new media only), then continues
 * the BACKFILL (resumable, one transaction per page including the cursor), then optionally a
 * VERIFY pass that re-checks known messages for deletion with a keyset cursor.
 *
 * Nothing is held in memory beyond one page (≤100 messages).
 */
@Singleton
class IndexingEngine @Inject constructor(
    private val messages: TelegramMessageRepository,
    private val store: IndexStore,
    private val normalizer: MediaNormalizer,
    private val tracker: IndexProgressTracker,
    private val clock: Clock,
    private val logger: Logger,
    private val config: EngineConfig,
    private val listeners: Set<@JvmSuppressWildcards MediaIndexListener>,
) {
    private val concurrency = Semaphore(config.maxConcurrentChats)

    /** Ends the run early but successfully: cursors are saved, the caller re-queues. */
    private class BudgetExhausted : Exception()

    /** Transient: the caller should retry the whole run later. */
    private class RetryLater(val error: AppError) : Exception(error.message)

    /** Permanent for this run: e.g. chat no longer accessible. */
    private class Fatal(val error: AppError) : Exception(error.message)

    private inner class Run(val chatId: Long, val chatTitle: String?, val mode: IndexMode, val deadline: Long) {
        val startedAt = clock.nowMillis()
        var phase = IndexPhase.HEAD
        var filter: MediaSearchFilter? = null
        var pages = 0
        var seen = 0
        var added = 0
        var updated = 0
        var deleted = 0
        var estimatedTotal = -1
        /** Pages with new/changed files since the library was last told. */
        var pagesSinceNotify = 0
        /** Filters write and count from parallel coroutines. */
        val lock = kotlinx.coroutines.sync.Mutex()

        fun publish() = tracker.update(
            IndexProgress(chatId, mode, phase, filter?.name, pages, seen, added, updated, deleted, estimatedTotal, startedAt),
        )

        fun checkBudget() {
            if (clock.nowMillis() >= deadline) throw BudgetExhausted()
        }

        fun result(outcome: Outcome, error: String? = null) =
            IndexRunResult(chatId, mode, outcome, pages, seen, added, updated, deleted, error)
    }

    suspend fun run(chatId: Long, mode: IndexMode, timeBudget: Duration): IndexRunResult = concurrency.withPermit {
        val chat = store.chat(chatId)
        if (chat == null || !chat.enabled) {
            return@withPermit IndexRunResult(chatId, mode, Outcome.FAILED, 0, 0, 0, 0, 0, "Chat is not enabled for indexing")
        }
        val run = Run(chatId, chat.title, mode, deadline = clock.nowMillis() + timeBudget.inWholeMilliseconds)
        val runId = store.startRun(chatId, mode)
        store.setState(chatId, IndexState.RUNNING)
        run.publish()
        logger.i(TAG, "run $mode for chat $chatId")

        val result = try {
            if (mode == IndexMode.FULL_RESCAN) store.resetCursors(chatId)
            if (mode != IndexMode.VERIFY) {
                if (config.parallelFilters) coroutineScope { config.filters.map { f -> async { scanFilter(run, f) } }.awaitAll() }
                else for (filter in config.filters) scanFilter(run, filter)
            }
            val verifyDue = mode == IndexMode.VERIFY || chat.verifyCursor > 0 ||
                clock.nowMillis() - (chat.lastVerifiedAt ?: 0L) >= config.verifyInterval.inWholeMilliseconds
            if (verifyDue) {
                verify(run, if (mode == IndexMode.VERIFY) Int.MAX_VALUE else config.verifyMaxPerIncrementalRun)
            }
            run.result(Outcome.COMPLETED)
        } catch (e: BudgetExhausted) {
            run.result(Outcome.MORE_WORK)
        } catch (e: RetryLater) {
            run.result(Outcome.RETRY, e.error.message)
        } catch (e: Fatal) {
            run.result(Outcome.FAILED, e.error.message)
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                val byUser = tracker.consumeCancelRequest(chatId)
                val cancelled = run.result(Outcome.CANCELLED, if (byUser) "Cancelled" else "Stopped by the system")
                finish(runId, cancelled, if (byUser) IndexState.CANCELLED else IndexState.PAUSED)
            }
            throw e
        }

        finish(
            runId,
            result,
            when (result.outcome) {
                Outcome.COMPLETED -> IndexState.COMPLETED
                Outcome.MORE_WORK -> IndexState.PAUSED
                Outcome.RETRY, Outcome.FAILED -> IndexState.FAILED
                Outcome.CANCELLED -> IndexState.CANCELLED
            },
        )
        logger.i(TAG, "chat $chatId $mode -> ${result.outcome}: +${result.mediaAdded} ~${result.mediaUpdated} -${result.mediaDeleted} (${result.pagesScanned} pages)")
        result
    }

    private suspend fun finish(runId: Long, result: IndexRunResult, state: IndexState) {
        store.finishRun(runId, result)
        if (result.outcome == Outcome.COMPLETED) store.markIndexed(result.chatId)
        store.setState(result.chatId, state, result.error.takeIf { state == IndexState.FAILED || state == IndexState.CANCELLED })
        tracker.clear(result.chatId)
        if (result.mediaAdded + result.mediaUpdated + result.mediaDeleted > 0) listeners.forEach { it.onIndexChanged() }
    }

    // ---------------- scanning ----------------

    private suspend fun scanFilter(run: Run, filter: MediaSearchFilter): Unit = coroutineScope {
        run.filter = filter
        var cursor = store.cursor(run.chatId, filter)

        // HEAD: newest → previous high-water mark. Only new messages are written. Also runs
        // when a finished backfill found nothing (mark 0), or new media of an empty filter
        // would never be discovered.
        if (cursor.newestMessageId > 0 || cursor.backfillComplete) {
            run.phase = IndexPhase.HEAD
            val boundary = cursor.newestMessageId
            var from = 0L
            var maxSeen = boundary
            while (true) {
                run.checkBudget()
                val page = fetchPage(run, filter, from)
                val fresh = page.messages.filter { it.messageId > boundary }
                maxSeen = maxOf(maxSeen, page.messages.maxOfOrNull { it.messageId } ?: 0)
                write(run, page, normalize(page.messages, fresh, run.chatTitle), cursor = null)
                // Stop as soon as the page reaches already indexed territory.
                if (page.messages.isEmpty() || page.nextFromMessageId == 0L || page.nextFromMessageId <= boundary || fresh.size < page.messages.size) break
                from = page.nextFromMessageId
                delay(config.pageDelay)
            }
            // Only advance the mark once the whole gap is covered: an interrupted head pass
            // repeats (idempotently) instead of leaving a hole.
            cursor = cursor.copy(newestMessageId = maxSeen)
            store.saveCursor(cursor)
        }

        // BACKFILL: continue towards the oldest message. The next page is fetched while the
        // current one is parsed and written (network and database overlap).
        if (!cursor.backfillComplete) {
            run.phase = IndexPhase.BACKFILL
            var from = cursor.backfillFromMessageId
            var pending = async { fetchPage(run, filter, from) }
            while (true) {
                run.checkBudget()
                val page = pending.await()
                if (page.nextFromMessageId != 0L && page.messages.isNotEmpty()) {
                    val next = page.nextFromMessageId
                    pending = async { delay(config.pageDelay); fetchPage(run, filter, next) }
                }
                val pageNewest = page.messages.maxOfOrNull { it.messageId } ?: 0
                val done = page.nextFromMessageId == 0L || page.messages.isEmpty()
                cursor = cursor.copy(
                    // First page of a first scan sets the high-water mark for future head passes.
                    newestMessageId = if (cursor.newestMessageId == 0L) pageNewest else cursor.newestMessageId,
                    backfillFromMessageId = if (done) 0 else page.nextFromMessageId,
                    backfillComplete = done,
                    estimatedTotal = page.totalCount,
                )
                write(run, page, normalize(page.messages, page.messages, run.chatTitle), cursor)
                if (done) break
                from = page.nextFromMessageId
            }
        }
    }

    private suspend fun write(run: Run, page: MediaMessagePage, items: List<dev.yourstream.media.parser.NormalizedMedia>, cursor: ScanCursor?) {
        val result = if (items.isEmpty() && cursor != null) {
            store.saveCursor(cursor); null
        } else if (items.isNotEmpty() || cursor != null) {
            store.savePage(items, cursor)
        } else null
        val notify = run.lock.withLock {
            run.pages++
            run.seen += page.messages.size
            run.estimatedTotal = page.totalCount
            result?.let { run.added += it.added; run.updated += it.updated }
            run.publish()
            if (result != null && result.added + result.updated > 0) run.pagesSinceNotify++
            (run.pagesSinceNotify >= config.notifyEveryPages).also { if (it) run.pagesSinceNotify = 0 }
        }
        // New titles show up while a big channel is still being read.
        if (notify) listeners.forEach { it.onIndexChanged() }
    }

    /** Normalises [which] of a page; files of an album without a caption get the album's caption. */
    private fun normalize(page: List<dev.yourstream.telegram.api.model.TelegramMediaMessage>, which: List<dev.yourstream.telegram.api.model.TelegramMediaMessage>, chatTitle: String?) =
        page.filter { it.albumId != 0L && it.caption.isNotBlank() }.associate { it.albumId to it.caption }.let { albums ->
            which.mapNotNull { normalizer.normalize(it, chatTitle, albums[it.albumId]) }
        }

    private suspend fun fetchPage(run: Run, filter: MediaSearchFilter, from: Long): MediaMessagePage =
        withRetry("searchMedia") { messages.searchMedia(run.chatId, filter, from, config.pageSize) }

    // ---------------- deletion check ----------------

    private suspend fun verify(run: Run, maxMessages: Int) {
        run.phase = IndexPhase.VERIFY
        run.filter = null
        run.publish()
        var cursor = store.chat(run.chatId)?.verifyCursor ?: 0L
        var checked = 0
        while (checked < maxMessages) {
            run.checkBudget()
            val ids = store.liveMessageIdsAfter(run.chatId, cursor, config.verifyBatch)
            if (ids.isEmpty()) {
                store.setVerifyCursor(run.chatId, 0, finished = true)
                return
            }
            val lookups = withRetry("lookupMessages") { messages.lookupMessages(run.chatId, ids) }
            val gone = lookups.filter { it is MessageLookup.Missing || it is MessageLookup.NoMedia }.map { it.messageId }
            val alive = lookups.filterIsInstance<MessageLookup.Found>()
            // Re-upsert the living: catches replaced files and refreshes session file ids.
            // Album files keep the album's caption (from this batch, else from the index).
            val albums = alive.filter { it.message.albumId != 0L && it.message.caption.isNotBlank() }.associate { it.message.albumId to it.message.caption }.toMutableMap()
            for (f in alive) {
                val a = f.message.albumId
                if (a != 0L && f.message.caption.isBlank() && a !in albums) store.albumCaption(run.chatId, a)?.let { albums[a] = it }
            }
            val normalized = alive.associate { it.messageId to normalizer.normalize(it.message, run.chatTitle, albums[it.message.albumId]) }
            val written = store.upsert(normalized.values.filterNotNull())
            val removedByPolicy = normalized.filterValues { it == null }.keys.toList()
            val removed = store.markDeleted(run.chatId, gone + removedByPolicy)
            cursor = ids.last()
            store.setVerifyCursor(run.chatId, cursor, finished = false)
            checked += ids.size
            run.pages++
            run.seen += ids.size
            run.updated += written.updated
            run.deleted += removed
            run.publish()
            delay(config.pageDelay)
        }
    }

    // ---------------- retry policy ----------------

    private suspend fun <T> withRetry(operation: String, block: suspend () -> AppResult<T>): T {
        var attempt = 0
        var floodWaited = 0L
        while (true) {
            when (val result = block()) {
                is AppResult.Ok -> return result.value
                is AppResult.Err -> when (val error = result.error) {
                    is AppError.RateLimited -> {
                        val wait = maxOf(1, error.retryAfterSeconds).seconds
                        if (wait > config.maxInlineFloodWait || floodWaited > config.maxInlineFloodWait.inWholeSeconds * 3) {
                            throw RetryLater(error)
                        }
                        logger.w(TAG, "$operation: flood wait $wait")
                        floodWaited += wait.inWholeSeconds
                        delay(wait)
                    }
                    is AppError.Network, is AppError.Timeout, is AppError.InvalidState -> {
                        attempt++
                        if (attempt >= config.maxAttempts) throw RetryLater(error)
                        val backoff = config.retryBaseDelay.inWholeMilliseconds * (1L shl min(attempt - 1, 6))
                        val jitter = Random.nextLong(0, config.retryBaseDelay.inWholeMilliseconds / 2 + 1)
                        logger.w(TAG, "$operation failed (${error.message}); retry $attempt in ${backoff + jitter}ms")
                        delay(backoff + jitter)
                    }
                    else -> throw Fatal(error)
                }
            }
        }
    }

    private companion object {
        const val TAG = "Indexer"
    }
}
