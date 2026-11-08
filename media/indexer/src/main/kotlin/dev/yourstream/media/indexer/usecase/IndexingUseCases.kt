package dev.yourstream.media.indexer.usecase

import androidx.paging.PagingData
import dev.yourstream.core.common.AppResult
import dev.yourstream.media.api.MediaIndexRepository
import dev.yourstream.media.api.MediaIndexer
import dev.yourstream.media.api.model.ChatIndexStatus
import dev.yourstream.media.api.model.IndexMode
import dev.yourstream.media.api.model.IndexedMedia
import dev.yourstream.media.api.model.MediaQuery
import dev.yourstream.media.api.model.MediaSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import javax.inject.Inject

/** Enables the chat if needed and queues a run in the given mode. */
class StartChatIndexingUseCase @Inject constructor(
    private val indexer: MediaIndexer,
    private val repository: MediaIndexRepository,
) {
    suspend operator fun invoke(chatId: Long, mode: IndexMode = IndexMode.INCREMENTAL): AppResult<Unit> {
        val enabled = repository.observeChatOnce(chatId)?.enabled == true
        if (!enabled) {
            val result = indexer.enableChat(chatId)
            if (result is AppResult.Err || mode == IndexMode.INCREMENTAL) return result
        }
        indexer.schedule(chatId, mode)
        return AppResult.Ok(Unit)
    }
}

class CancelChatIndexingUseCase @Inject constructor(private val indexer: MediaIndexer) {
    suspend operator fun invoke(chatId: Long) = indexer.cancel(chatId)
}

class DisableChatIndexingUseCase @Inject constructor(private val indexer: MediaIndexer) {
    suspend operator fun invoke(chatId: Long, purge: Boolean) = indexer.disableChat(chatId, purge)
}

class ObserveIndexStatusUseCase @Inject constructor(private val repository: MediaIndexRepository) {
    operator fun invoke(): Flow<List<ChatIndexStatus>> = repository.observeChats()
    operator fun invoke(chatId: Long): Flow<ChatIndexStatus?> = repository.observeChat(chatId)
}

class SearchIndexedMediaUseCase @Inject constructor(private val repository: MediaIndexRepository) {
    operator fun invoke(query: MediaQuery): Flow<PagingData<MediaSummary>> = repository.search(query)
    fun count(query: MediaQuery): Flow<Int> = repository.count(query)
}

data class MediaDetails(val media: IndexedMedia, val duplicates: List<MediaSummary>)

class GetMediaDetailsUseCase @Inject constructor(private val repository: MediaIndexRepository) {
    suspend operator fun invoke(mediaId: Long): MediaDetails? {
        val media = repository.getMedia(mediaId) ?: return null
        return MediaDetails(media, repository.duplicatesOf(mediaId))
    }
}

private suspend fun MediaIndexRepository.observeChatOnce(chatId: Long): ChatIndexStatus? =
    observeChat(chatId).firstOrNull()
