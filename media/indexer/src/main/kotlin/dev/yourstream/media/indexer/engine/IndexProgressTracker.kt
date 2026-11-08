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

    private val cancelRequested = ConcurrentHashMap.newKeySet<Long>()

    fun update(progress: IndexProgress) = _progress.update { it + (progress.chatId to progress) }
    fun clear(chatId: Long) = _progress.update { it - chatId }

    fun requestCancel(chatId: Long) { cancelRequested += chatId }
    fun consumeCancelRequest(chatId: Long): Boolean = cancelRequested.remove(chatId)
}
