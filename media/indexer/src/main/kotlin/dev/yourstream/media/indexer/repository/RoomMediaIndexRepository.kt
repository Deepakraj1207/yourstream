package dev.yourstream.media.indexer.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import dev.yourstream.core.database.YourStreamDatabase
import dev.yourstream.media.api.MediaIndexRepository
import dev.yourstream.media.api.model.ChatIndexStatus
import dev.yourstream.media.api.model.IndexedMedia
import dev.yourstream.media.api.model.MediaQuery
import dev.yourstream.media.api.model.MediaSummary
import dev.yourstream.media.indexer.engine.IndexProgressTracker
import dev.yourstream.media.indexer.store.toModel
import dev.yourstream.media.parser.SearchText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomMediaIndexRepository @Inject constructor(
    db: YourStreamDatabase,
    private val tracker: IndexProgressTracker,
) : MediaIndexRepository {

    private val media = db.mediaDao()
    private val chats = db.indexedChatDao()

    /**
     * Paged straight from SQLite: only the visible window (plus prefetch) is in memory, however
     * many rows match. maxSize drops pages scrolled far away.
     */
    override fun search(query: MediaQuery, pageSize: Int): Flow<PagingData<MediaSummary>> {
        val match = SearchText.ftsQuery(query.text)
        val types = query.types.map { it.name }
        return Pager(PagingConfig(pageSize = pageSize, enablePlaceholders = false, maxSize = pageSize * 8)) {
            if (match == null) {
                media.browse(query.chatId, types, types.size, query.includeDeleted, query.includeDuplicates)
            } else {
                media.search(match, query.chatId, types, types.size, query.includeDeleted, query.includeDuplicates)
            }
        }.flow.map { data -> data.map { it.toModel() } }
    }

    override fun count(query: MediaQuery): Flow<Int> {
        val match = SearchText.ftsQuery(query.text)
        val types = query.types.map { it.name }
        return if (match == null) {
            media.countBrowse(query.chatId, types, types.size, query.includeDeleted, query.includeDuplicates)
        } else {
            media.countSearch(match, query.chatId, types, types.size, query.includeDeleted, query.includeDuplicates)
        }
    }

    override suspend fun getMedia(id: Long): IndexedMedia? {
        val row = media.getWithParsed(id) ?: return null
        return row.toModel(chats.get(row.media.chatId)?.title)
    }

    override suspend fun duplicatesOf(id: Long): List<MediaSummary> {
        val root = media.canonicalOf(id) ?: id
        return media.duplicateGroup(root).map { it.toModel() }.filter { it.id != id }
    }

    override fun observeChats(): Flow<List<ChatIndexStatus>> =
        combine(chats.observeAll(), tracker.progress) { rows, progress -> rows.map { it.toModel(progress[it.chat.chatId]) } }

    override fun observeChat(chatId: Long): Flow<ChatIndexStatus?> =
        combine(chats.observe(chatId), tracker.progress) { row, progress -> row?.toModel(progress[chatId]) }
}
