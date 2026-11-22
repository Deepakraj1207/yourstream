package dev.yourstream.desktop.library

import dev.yourstream.core.common.Logger
import dev.yourstream.desktop.data.DesktopIndexStore
import dev.yourstream.media.api.model.IndexMode
import dev.yourstream.media.api.model.IndexRunResult.Outcome
import dev.yourstream.media.indexer.engine.IndexProgressTracker
import dev.yourstream.media.indexer.engine.IndexingEngine
import dev.yourstream.telegram.api.TelegramManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Runs the shared indexing engine for the chosen chats while the app is open (WorkManager does
 * this on Android). Each chat runs in slices of a few minutes, so a huge channel cannot hold up
 * the others; a slice that ends with more to do is continued in the next round. New posts are
 * picked up every 15 minutes, and at once when a chat is added or the user asks.
 */
class IndexRunner(
    private val engine: IndexingEngine,
    private val store: DesktopIndexStore,
    private val progress: IndexProgressTracker,
    private val telegram: TelegramManager,
    private val scope: CoroutineScope,
    private val logger: Logger,
) {
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null

    private val _current = MutableStateFlow<Long?>(null)

    /** The chat being indexed right now, if any. */
    val current: StateFlow<Long?> = _current.asStateFlow()

    val progressByChat get() = progress.progress

    /** Starts the loop (once Telegram is signed in). */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                val more = runRound()
                // More history to read: continue soon. Otherwise look for new posts later.
                withTimeoutOrNull(if (more) 2.seconds else 15.minutes) { wake.receive() }
            }
        }
    }

    /** Index now (after adding a chat, or when the user asks). */
    fun kick() { wake.trySend(Unit) }

    fun stop(chatId: Long) = progress.requestStop(chatId, IndexProgressTracker.StopReason.PAUSE)

    /** One pass over the enabled chats. True when a chat still has history left to read. */
    private suspend fun runRound(): Boolean {
        var more = false
        for (chatId in store.enabledChatIds()) {
            val chat = store.chat(chatId) ?: continue
            if (chat.paused) continue
            _current.value = chatId
            val result = try {
                engine.run(chatId, IndexMode.INCREMENTAL, SLICE)
            } catch (e: Exception) {
                logger.w(TAG, "indexing chat failed: ${e::class.simpleName}", e)
                null
            } finally {
                _current.value = null
            }
            when (result?.outcome) {
                Outcome.MORE_WORK -> more = true
                Outcome.RETRY, null -> { delay(30.seconds); more = true }
                else -> Unit
            }
        }
        return more
    }

    private companion object {
        const val TAG = "IndexRunner"
        val SLICE = 3.minutes
    }
}
