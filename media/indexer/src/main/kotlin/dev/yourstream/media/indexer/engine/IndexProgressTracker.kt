package dev.yourstream.media.indexer.engine

import dev.yourstream.media.api.model.IndexProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live, in-memory progress of running indexing jobs (the database has the durable counts).
 * Also carries user cancel requests so a stopped run can tell "cancelled by the user" from
 * "stopped by the system, will resume".
 */
@Singleton
class IndexProgressTracker @Inject constructor() {
    private val _progress = MutableStateFlow<Map<Long, IndexProgress>>(emptyMap())
    val progress: StateFlow<Map<Long, IndexProgress>> = _progress.asStateFlow()

    /** Why the user stopped a run: decides the state it ends in (CANCELLED or PAUSED). */
    enum class StopReason { CANCEL, PAUSE }

    private val stopRequested = ConcurrentHashMap<Long, StopReason>()

    fun update(progress: IndexProgress) = _progress.update { it + (progress.chatId to progress) }
    fun clear(chatId: Long) = _progress.update { it - chatId }

    fun requestStop(chatId: Long, reason: StopReason) { stopRequested[chatId] = reason }
    fun requestCancel(chatId: Long) = requestStop(chatId, StopReason.CANCEL)

    /** The user's reason for stopping [chatId]'s run, if they asked (consumed). Null: stopped by the system. */
    fun consumeStopRequest(chatId: Long): StopReason? = stopRequested.remove(chatId)
}
