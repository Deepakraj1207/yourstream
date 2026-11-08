package dev.yourstream.core.database

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dev.yourstream.core.database.dao.IndexRunDao
import dev.yourstream.core.database.dao.IndexedChatDao
import dev.yourstream.core.database.dao.MediaDao
import dev.yourstream.core.database.dao.ScanCursorDao
import dev.yourstream.core.database.entity.IndexRunEntity
import dev.yourstream.core.database.entity.IndexedChatEntity
import dev.yourstream.core.database.entity.MediaEntity
import dev.yourstream.core.database.entity.MediaFtsEntity
import dev.yourstream.core.database.entity.ParsedMediaEntity
import dev.yourstream.core.database.entity.ScanCursorEntity

/**
 * Schema history is exported to core/database/schemas. Every version bump needs a migration
 * (auto or manual) and a test in MigrationTest; destructive fallback is not allowed.
 */
@Database(
    entities = [
        IndexedChatEntity::class,
        ScanCursorEntity::class,
        MediaEntity::class,
        ParsedMediaEntity::class,
        MediaFtsEntity::class,
        IndexRunEntity::class,
    ],
    version = 2,
    exportSchema = true,
    autoMigrations = [
        // v2: richer parsed metadata (media_parsed columns + normalized_title index).
        AutoMigration(from = 1, to = 2),
    ],
)
abstract class YourStreamDatabase : RoomDatabase() {
    abstract fun indexedChatDao(): IndexedChatDao
    abstract fun scanCursorDao(): ScanCursorDao
    abstract fun mediaDao(): MediaDao
    abstract fun indexRunDao(): IndexRunDao

    companion object {
        const val NAME = "yourstream.db"

        fun create(context: Context): YourStreamDatabase =
            Room.databaseBuilder(context.applicationContext, YourStreamDatabase::class.java, NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
