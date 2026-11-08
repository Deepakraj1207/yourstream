package dev.yourstream.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.yourstream.core.database.entity.IndexedChatEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface IndexedChatDao {

    @Upsert
    suspend fun upsert(chat: IndexedChatEntity)

    @Query("SELECT * FROM indexed_chat WHERE chat_id = :chatId")
    suspend fun get(chatId: Long): IndexedChatEntity?

    @Query("SELECT chat_id FROM indexed_chat WHERE enabled = 1")
    suspend fun enabledChatIds(): List<Long>

    @Query("SELECT enabled FROM indexed_chat WHERE chat_id = :chatId")
    suspend fun isEnabled(chatId: Long): Boolean?

    @Query("UPDATE indexed_chat SET state = :state, last_error = :error, updated_at = :now WHERE chat_id = :chatId")
    suspend fun setState(chatId: Long, state: String, error: String?, now: Long)

    @Query("UPDATE indexed_chat SET last_indexed_at = :now, updated_at = :now WHERE chat_id = :chatId")
    suspend fun setIndexedAt(chatId: Long, now: Long)

    @Query("UPDATE indexed_chat SET verify_cursor = :cursor, last_verified_at = COALESCE(:verifiedAt, last_verified_at), updated_at = :now WHERE chat_id = :chatId")
    suspend fun setVerifyCursor(chatId: Long, cursor: Long, verifiedAt: Long?, now: Long)

    @Query("UPDATE indexed_chat SET enabled = :enabled, updated_at = :now WHERE chat_id = :chatId")
    suspend fun setEnabled(chatId: Long, enabled: Boolean, now: Long)

    @Query("DELETE FROM indexed_chat WHERE chat_id = :chatId")
    suspend fun delete(chatId: Long)

    /** Runs that died with the process stay RUNNING in the table; reset them on start. */
    @Query("UPDATE indexed_chat SET state = :to WHERE state IN (:from)")
    suspend fun resetStates(from: List<String>, to: String)

    @Query(STATUS_SELECT + " ORDER BY c.title COLLATE NOCASE")
    fun observeAll(): Flow<List<ChatStatusRow>>

    @Query(STATUS_SELECT + " WHERE c.chat_id = :chatId")
    fun observe(chatId: Long): Flow<ChatStatusRow?>

    private companion object {
        const val STATUS_SELECT = """
            SELECT c.*,
              (SELECT COUNT(*) FROM media m WHERE m.chat_id = c.chat_id AND m.is_deleted = 0) AS media_count,
              (SELECT COUNT(*) FROM media m WHERE m.chat_id = c.chat_id AND m.is_deleted = 1) AS deleted_count,
              COALESCE((SELECT MIN(s.backfill_complete) FROM scan_cursor s WHERE s.chat_id = c.chat_id), 0) AS backfill_complete
            FROM indexed_chat c"""
    }
}
