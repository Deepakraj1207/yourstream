package dev.yourstream.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import dev.yourstream.core.database.entity.IndexRunEntity

@Dao
interface IndexRunDao {
    @Insert
    suspend fun insert(run: IndexRunEntity): Long

    @Update
    suspend fun update(run: IndexRunEntity)

    @Query("SELECT * FROM index_run WHERE id = :id")
    suspend fun get(id: Long): IndexRunEntity?

    @Query("SELECT * FROM index_run WHERE chat_id = :chatId ORDER BY started_at DESC LIMIT :limit")
    suspend fun recent(chatId: Long, limit: Int): List<IndexRunEntity>

    /** Keeps history bounded. */
    @Query("DELETE FROM index_run WHERE chat_id = :chatId AND id NOT IN (SELECT id FROM index_run WHERE chat_id = :chatId ORDER BY started_at DESC LIMIT :keep)")
    suspend fun trim(chatId: Long, keep: Int)
}
