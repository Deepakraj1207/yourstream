package dev.yourstream.media.indexer.local

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.yourstream.media.api.model.IndexRunResult.Outcome
import dev.yourstream.media.indexer.store.IndexStore
import dev.yourstream.media.indexer.work.IndexChatWorker
import dev.yourstream.telegram.api.TelegramAuthManager
import dev.yourstream.telegram.api.model.AuthState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * Scans one local source (queued by `IndexScheduler` under the source's id, like a chat, so
 * pause, resume and stop work the same). Only while signed in: the library belongs to the
 * signed-in account and a sign-out empties it, so a scan while signed out would only be wiped
 * again. The source is scanned after the next sign-in ([dev.yourstream.media.indexer.DefaultMediaIndexer.scheduleAll]).
 */
@HiltWorker
class LocalScanWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val sources: LocalSourceStore,
    private val scanner: LocalScanner,
    private val store: IndexStore,
    private val auth: TelegramAuthManager,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getLong(IndexChatWorker.KEY_CHAT_ID, 0L)
        val source = sources.get(id) ?: return Result.success() // removed meanwhile
        val chat = store.chat(id)
        if (chat != null && (chat.paused || !chat.enabled)) return Result.success()

        val state = withTimeoutOrNull(30.seconds) { auth.state.first { it != AuthState.Unknown && it != AuthState.WaitParameters } }
        if (state != AuthState.Ready) return Result.success()

        return when (scanner.scan(source).outcome) {
            Outcome.COMPLETED -> Result.success()
            else -> Result.failure() // the reason is on the source's row; the next sync tries again
        }
    }
}
