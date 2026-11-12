package dev.yourstream.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * v15: where the user stopped in an add-on stream (web or torrent), per catalog title and
 * episode, so reopening it (from any stream source) resumes there. Account data: wiped on
 * sign-out with the rest of the watch history.
 */
@Entity(tableName = "stream_progress", indices = [Index(value = ["updated_at"])])
data class StreamProgressEntity(
    /** Catalog title (+ season/episode), e.g. "series:tt0141842:S01E02". */
    @PrimaryKey val key: String,
    val title: String,
    val subtitle: String?,
    @ColumnInfo(name = "position_ms") val positionMs: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    val completed: Boolean,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
