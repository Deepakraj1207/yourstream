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
    /** Pause between pages so a big backfill stays clear of FLOOD_WAIT. */
    val pageDelay: Duration = 200.milliseconds,
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
    val maxConcurrentChats: Int = 2,
)
