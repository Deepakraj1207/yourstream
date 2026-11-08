package dev.yourstream.core.database

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.yourstream.core.database.dao.IndexRunDao
import dev.yourstream.core.database.dao.IndexedChatDao
import dev.yourstream.core.database.dao.LibraryDao
import dev.yourstream.core.database.dao.MetadataDao
import dev.yourstream.core.database.dao.MediaDao
import dev.yourstream.core.database.dao.ScanCursorDao
import dev.yourstream.core.database.dao.TrackDao
import dev.yourstream.core.database.dao.WatchDao
import dev.yourstream.core.database.entity.AudioTrackEntity
import dev.yourstream.core.database.entity.EpisodeMetadataEntity
import dev.yourstream.core.database.entity.ItemMetadataEntity
import dev.yourstream.core.database.entity.MetadataCacheEntity
import dev.yourstream.core.database.entity.MetadataMatchEntity
import dev.yourstream.core.database.entity.SeasonMetadataEntity
import dev.yourstream.core.database.entity.EpisodeEntity
import dev.yourstream.core.database.entity.FavoriteEntity
import dev.yourstream.core.database.entity.IndexRunEntity
import dev.yourstream.core.database.entity.IndexedChatEntity
import dev.yourstream.core.database.entity.LibraryAliasEntity
import dev.yourstream.core.database.entity.LibraryDirtyItemEntity
import dev.yourstream.core.database.entity.LibraryItemEntity
import dev.yourstream.core.database.entity.LibraryItemFtsEntity
import dev.yourstream.core.database.entity.LibraryItemLanguageEntity
import dev.yourstream.core.database.entity.LibraryPendingEntity
import dev.yourstream.core.database.entity.MediaEntity
import dev.yourstream.core.database.entity.MediaFtsEntity
import dev.yourstream.core.database.entity.MediaLinkEntity
import dev.yourstream.core.database.entity.ParsedMediaEntity
import dev.yourstream.core.database.entity.ScanCursorEntity
import dev.yourstream.core.database.entity.SeasonEntity
import dev.yourstream.core.database.entity.SubtitleTrackEntity
import dev.yourstream.core.database.entity.TrackPreferenceEntity
import dev.yourstream.core.database.entity.WatchHistoryEntity
import dev.yourstream.core.database.entity.WatchStateEntity

/**
 * Schema history is exported to core/database/schemas. Every version bump needs a migration
 * (auto or manual) and a test in MigrationTest; destructive fallback is never enabled, so a
 * missing migration fails loudly instead of wiping user data.
 *
 * v1 index · v2 parsed metadata · v3 library (items, seasons, episodes, links, tracks) and user
 * data (favorites, watch state/history, track preferences) · v4 external metadata.
 */
@Database(
    entities = [
        IndexedChatEntity::class,
        ScanCursorEntity::class,
        MediaEntity::class,
        ParsedMediaEntity::class,
        MediaFtsEntity::class,
        IndexRunEntity::class,
        LibraryItemEntity::class,
        LibraryItemFtsEntity::class,
        LibraryAliasEntity::class,
        LibraryItemLanguageEntity::class,
        SeasonEntity::class,
        EpisodeEntity::class,
        MediaLinkEntity::class,
        LibraryPendingEntity::class,
        LibraryDirtyItemEntity::class,
        FavoriteEntity::class,
        WatchStateEntity::class,
        WatchHistoryEntity::class,
        TrackPreferenceEntity::class,
        SubtitleTrackEntity::class,
        AudioTrackEntity::class,
        MetadataCacheEntity::class,
        MetadataMatchEntity::class,
        ItemMetadataEntity::class,
        SeasonMetadataEntity::class,
        EpisodeMetadataEntity::class,
    ],
    version = 4,
    exportSchema = true,
    autoMigrations = [
        // v2: richer parsed metadata (media_parsed columns + normalized_title index).
        AutoMigration(from = 1, to = 2),
        // v3: library + user data tables. Only adds tables; existing rows are untouched.
        AutoMigration(from = 2, to = 3, spec = YourStreamDatabase.V3::class),
        // v4: external metadata (cache, matches, applied item/season/episode metadata). Adds tables only.
        AutoMigration(from = 3, to = 4),
    ],
)
abstract class YourStreamDatabase : RoomDatabase() {
    abstract fun indexedChatDao(): IndexedChatDao
    abstract fun scanCursorDao(): ScanCursorDao
    abstract fun mediaDao(): MediaDao
    abstract fun indexRunDao(): IndexRunDao
    abstract fun libraryDao(): LibraryDao
    abstract fun watchDao(): WatchDao
    abstract fun trackDao(): TrackDao
    abstract fun metadataDao(): MetadataDao

    /** After creating the v3 tables: queue every already indexed file for the first library sync. */
    class V3 : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            LibraryTriggers.create(db)
            db.execSQL("INSERT OR IGNORE INTO library_pending (media_id) SELECT id FROM media")
        }
    }

    companion object {
        const val NAME = "yourstream.db"

        /** Triggers are not part of Room's schema model; make sure they exist on every open. */
        val CALLBACK: Callback = object : Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) = LibraryTriggers.create(db)
        }

        fun create(context: Context): YourStreamDatabase =
            Room.databaseBuilder(context.applicationContext, YourStreamDatabase::class.java, NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addCallback(CALLBACK)
                .build()

        /** For tests. */
        fun inMemory(context: Context): YourStreamDatabase =
            Room.inMemoryDatabaseBuilder(context, YourStreamDatabase::class.java)
                .allowMainThreadQueries()
                .addCallback(CALLBACK)
                .build()
    }
}
