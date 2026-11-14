package dev.yourstream.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.yourstream.core.database.entity.StreamProgressEntity

@Dao
interface StreamProgressDao {
    @Query("SELECT * FROM stream_progress WHERE `key` = :key")
    suspend fun get(key: String): StreamProgressEntity?

    @Upsert
    suspend fun upsert(progress: StreamProgressEntity)

    @Query("DELETE FROM stream_progress WHERE `key` = :key")
    suspend fun delete(key: String)

    /** Keeps the newest [keep] entries (a bound on an otherwise ever-growing table). */
    @Query("DELETE FROM stream_progress WHERE `key` NOT IN (SELECT `key` FROM stream_progress ORDER BY updated_at DESC LIMIT :keep)")
    suspend fun trim(keep: Int)
}
