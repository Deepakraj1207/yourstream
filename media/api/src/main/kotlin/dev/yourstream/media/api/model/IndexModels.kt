package dev.yourstream.media.api.model

enum class IndexMode {
    /** New messages since the last run, then continue the backfill of older history. */
    INCREMENTAL,
    /** Forget the cursors and walk the whole chat again (records are updated, not duplicated). */
    FULL_RESCAN,
    /** Re-check already indexed messages for deletion or changed files. */
    VERIFY,
}

/**
 * Where a chat's indexing stands. Persisted by name (indexed_chat.state).
 *
 * ```
 * IDLE → QUEUED → CONNECTING → SCANNING → PROCESSING → COMPLETED
 *                     ↘ network error / app killed → RECOVERING → (QUEUED …)
 *   any → PAUSED (user) → resume → QUEUED;  running → CANCELLED (user);  retries used up → FAILED
 * ```
 */
enum class IndexState {
    /** Never ran, or ran and nothing is pending. */
    IDLE,
    /** Waiting to run: its turn, the network (or Wi-Fi), the battery, or the next part of a long scan. */
    QUEUED,
    /** A run started and waits for Telegram to be signed in and connected. */
    CONNECTING,
    /** Reading the chat's messages: new ones, the older backlog, or re-checking known files. */
    SCANNING,
    /** Reading titles from the posts above files that have none of their own. */
    PROCESSING,
    COMPLETED,
    /** Paused by the user: nothing runs until resumed. */
    PAUSED,
    /** Stopped on an error with no automatic retry left; [ChatIndexStatus.lastError] says why. */
    FAILED,
    /** The user stopped this run; the next sync continues from where it stopped. */
    CANCELLED,
    /** Interrupted (network error, app closed by the system); continues from where it stopped. */
    RECOVERING;

    /** A run is working on the chat right now. */
    val isActive: Boolean get() = this == CONNECTING || this == SCANNING || this == PROCESSING

    /** More work will happen without the user doing anything. */
    val isPending: Boolean get() = this == QUEUED || this == RECOVERING || isActive
}

/** When background indexing may run. Applies to scheduled work (WorkManager constraints). */
data class IndexingPolicy(
    /** Only on unmetered networks (Wi-Fi, Ethernet). Off: any connection. */
    val wifiOnly: Boolean = false,
    /** Wait while the battery is low (and not charging). */
    val batteryAware: Boolean = true,
)

enum class IndexPhase {
    /** Newest messages down to the last known one: finds new media. */
    HEAD,
    /** From where the previous run stopped towards the oldest message. */
    BACKFILL,
    /** Re-checking known messages. */
    VERIFY,
    /** Reading the posts above files that have no title of their own. */
    TITLES,
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
    /** Paused by the user ([state] reads PAUSED). */
    val paused: Boolean = false,
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
