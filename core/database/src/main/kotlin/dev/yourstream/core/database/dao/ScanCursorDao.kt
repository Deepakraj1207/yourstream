package dev.yourstream.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.yourstream.core.database.entity.ScanCursorEntity

@Dao
interface ScanCursorDao {
    @Query("SELECT * FROM scan_cursor WHERE chat_id = :chatId AND filter = :filter")
    suspend fun get(chatId: Long, filter: String): ScanCursorEntity?

    @Query("SELECT * FROM scan_cursor WHERE chat_id = :chatId")
    suspend fun forChat(chatId: Long): List<ScanCursorEntity>

    @Upsert
    suspend fun upsert(cursor: ScanCursorEntity)

    @Query("DELETE FROM scan_cursor WHERE chat_id = :chatId")
    suspend fun deleteForChat(chatId: Long)
}
