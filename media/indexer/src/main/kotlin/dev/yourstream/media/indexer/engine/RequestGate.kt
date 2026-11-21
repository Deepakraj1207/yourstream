package dev.yourstream.media.indexer.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * How many Telegram requests the indexer has in flight, for all chats together. Starts at [max];
 * every FLOOD_WAIT halves the limit (down to 1), and it grows back by one after [recoverAfter]
 * requests in a row succeed. So a big channel is read as fast as Telegram allows, and backs off
 * by itself when Telegram says "too fast" instead of hammering it.
 */
class RequestGate(private val max: Int, private val recoverAfter: Int = 20) {
    private val mutex = Mutex()
    private var active = 0
    private var streak = 0
    private val waiting = ArrayDeque<CompletableDeferred<Unit>>()

    /** The current limit (for progress and tests). */
    @Volatile var limit: Int = max
        private set

    suspend fun <T> withPermit(block: suspend () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            withContext(NonCancellable) { release() }
        }
    }

    /** Telegram answered FLOOD_WAIT: fewer requests at once from now on. */
    suspend fun onFlood() = mutex.withLock {
        limit = maxOf(1, limit / 2)
        streak = 0
    }

    /** A request succeeded: after enough in a row, one more may run at once. */
    suspend fun onSuccess() {
        val wake = mutex.withLock {
            if (limit >= max) return@withLock null
            if (++streak < recoverAfter) return@withLock null
            streak = 0
            limit++
            waiting.removeFirstOrNull()
        }
        wake?.complete(Unit)
    }

    private suspend fun acquire() {
        while (true) {
            val wait = mutex.withLock {
                if (active < limit) { active++; null } else CompletableDeferred<Unit>().also { waiting.addLast(it) }
            }
            wait ?: return
            try {
                wait.await()
            } catch (e: Throwable) {
                // Cancelled while waiting: pass the wake-up on so no slot is lost.
                withContext(NonCancellable) { mutex.withLock { waiting.remove(wait) } }
                if (wait.isCompleted) withContext(NonCancellable) { wakeNext() }
                throw e
            }
        }
    }

    private suspend fun release() {
        val wake = mutex.withLock {
            active--
            if (active < limit) waiting.removeFirstOrNull() else null
        }
        wake?.complete(Unit)
    }

    private suspend fun wakeNext() {
        val wake = mutex.withLock { if (active < limit) waiting.removeFirstOrNull() else null }
        wake?.complete(Unit)
    }
}
