package dev.yourstream.media.api.model

enum class IndexMode {
    /** New messages since the last run, then continue the backfill of older history. */
    INCREMENTAL,
    /** Forget the cursors and walk the whole chat again (records are updated, not duplicated). */
    FULL_RESCAN,
    /** Re-check already indexed messages for deletion or changed files. */
    VERIFY,
}

enum class IndexState {
    /** Never ran, or ran and nothing is pending. */
    IDLE,
    /** Waiting for WorkManager (constraints, backoff, another chat running). */
    QUEUED,
    RUNNING,
    /** Stopped by the time budget; a continuation is queued. */
    PAUSED,
    COMPLETED,
    CANCELLED,
    /** Last run failed; [ChatIndexStatus.lastError] says why. WorkManager may retry. */
    FAILED,
}

enum class IndexPhase {
    /** Newest messages down to the last known one: finds new media. */
    HEAD,
    /** From where the previous run stopped towards the oldest message. */
    BACKFILL,
    /** Re-checking known messages. */
    VERIFY,
}

data class IndexProgress(
    val chatId: Long,
    val mode: IndexMode,
    val phase: IndexPhase,
    val filter: String?,
    val pagesScanned: Int,
    val messagesSeen: Int,
    val mediaAdded: Int,
    val mediaUpdated: Int,
    val mediaDeleted: Int,
    /** Telegram's total estimate for the current filter, -1 if unknown. */
    val estimatedTotal: Int,
    val startedAt: Long,
)

data class ChatIndexStatus(
    val chatId: Long,
    val title: String,
    val enabled: Boolean,
    val state: IndexState,
    val mediaCount: Int,
    val deletedCount: Int,
    /** True once the whole history has been walked at least once (all filters). */
    val backfillComplete: Boolean,
    /** Epoch millis of the last successful run. */
    val lastIndexedAt: Long?,
    val lastVerifiedAt: Long?,
    val lastError: String?,
    /** Live progress while running, else null. */
    val progress: IndexProgress?,
)

/** Result of one engine run. */
data class IndexRunResult(
    val chatId: Long,
    val mode: IndexMode,
    val outcome: Outcome,
    val pagesScanned: Int,
    val messagesSeen: Int,
    val mediaAdded: Int,
    val mediaUpdated: Int,
    val mediaDeleted: Int,
    val error: String?,
) {
    enum class Outcome {
        /** Everything the mode asked for is done. */
        COMPLETED,
        /** Time budget used up; run again to continue (cursors are saved). */
        MORE_WORK,
        /** Transient failure (network, flood wait); retry later. */
        RETRY,
        /** Permanent failure (not authorised, chat inaccessible). */
        FAILED,
        CANCELLED,
    }
}
