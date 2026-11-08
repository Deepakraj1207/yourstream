package dev.yourstream.media.indexer.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.yourstream.core.common.Logger
import dev.yourstream.media.api.model.IndexMode
import dev.yourstream.media.api.model.IndexRunResult.Outcome
import dev.yourstream.media.api.model.IndexState
import dev.yourstream.media.indexer.engine.IndexingEngine
import dev.yourstream.media.indexer.store.IndexStore
import dev.yourstream.telegram.api.TelegramAuthManager
import dev.yourstream.telegram.api.model.AuthState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Runs the engine for one chat inside WorkManager's ~10 minute window. */
@HiltWorker
class IndexChatWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val engine: IndexingEngine,
    private val store: IndexStore,
    private val scheduler: IndexScheduler,
    private val auth: TelegramAuthManager,
    private val logger: Logger,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val chatId = inputData.getLong(KEY_CHAT_ID, 0L).takeIf { it != 0L } ?: return Result.failure()
        val mode = runCatching { IndexMode.valueOf(inputData.getString(KEY_MODE).orEmpty()) }.getOrDefault(IndexMode.INCREMENTAL)

        // TDLib may still be starting (the process was launched just for this work).
        val ready = withTimeoutOrNull(READY_TIMEOUT) { auth.state.first { it == AuthState.Ready } }
        if (ready == null) {
            logger.w(TAG, "Telegram not ready; retrying chat $chatId later")
            store.setState(chatId, IndexState.QUEUED, "Waiting for Telegram login/connection")
            return if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }

        val result = engine.run(chatId, mode, RUN_BUDGET)
        return when (result.outcome) {
            Outcome.COMPLETED -> Result.success()
            Outcome.MORE_WORK -> {
                // Same mode, except a finished full rescan's remainder is an ordinary backfill.
                scheduler.scheduleContinuation(chatId, if (mode == IndexMode.FULL_RESCAN) IndexMode.INCREMENTAL else mode)
                Result.success()
            }
            Outcome.RETRY -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
            Outcome.FAILED, Outcome.CANCELLED -> Result.failure()
        }
    }

    companion object {
        const val KEY_CHAT_ID = "chatId"
        const val KEY_MODE = "mode"
        private const val TAG = "IndexWorker"
        private const val MAX_ATTEMPTS = 6
        private val READY_TIMEOUT = 90.seconds
        /** Below WorkManager's 10 minute limit, with room to save state. */
        private val RUN_BUDGET = 8.minutes
    }
}
