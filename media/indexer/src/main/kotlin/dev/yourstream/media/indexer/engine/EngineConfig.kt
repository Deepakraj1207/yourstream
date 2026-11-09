package dev.yourstream.media.indexer.engine

import dev.yourstream.telegram.api.model.MediaSearchFilter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

data class EngineConfig(
    /** Telegram search filters walked per chat, in order. */
    val filters: List<MediaSearchFilter> = listOf(MediaSearchFilter.VIDEO, MediaSearchFilter.DOCUMENT, MediaSearchFilter.AUDIO),
    val pageSize: Int = 100,
    /**
     * Pause between pages. Short: Telegram's FLOOD_WAIT answers are waited out (below), so a
     * big channel (100k+ files) is not slowed down for nothing.
     */
    val pageDelay: Duration = 30.milliseconds,
    /** Search filters of one chat are walked side by side instead of one after another. */
    val parallelFilters: Boolean = true,
    /** The library is told about new files every this many pages (not only when a run ends). */
    val notifyEveryPages: Int = 10,
    /** Network/timeout retries per request before the run gives up (WorkManager retries later). */
    val maxAttempts: Int = 5,
    val retryBaseDelay: Duration = 2.seconds,
    /** FLOOD_WAIT up to this long is waited out in-process; longer ones end the run with RETRY. */
    val maxInlineFloodWait: Duration = 120.seconds,
    val verifyBatch: Int = 100,
    /** Incremental runs check at most this many old messages for deletion per run. */
    val verifyMaxPerIncrementalRun: Int = 2_000,
    val verifyInterval: Duration = 24.hours,
    /** How many chats may scan at once (process-wide). */
    val maxConcurrentChats: Int = 3,
)
