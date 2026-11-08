package dev.yourstream.media.indexer.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.Multibinds
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.yourstream.core.common.Clock
import dev.yourstream.core.common.Logger
import dev.yourstream.core.database.DatabaseOpener
import dev.yourstream.core.database.OpenedDatabase
import dev.yourstream.core.database.YourStreamDatabase
import dev.yourstream.media.api.MediaIndexListener
import dev.yourstream.media.api.MediaIndexRepository
import dev.yourstream.media.api.MediaIndexer
import dev.yourstream.media.indexer.DefaultMediaIndexer
import dev.yourstream.media.indexer.engine.EngineConfig
import dev.yourstream.media.indexer.repository.RoomMediaIndexRepository
import dev.yourstream.media.indexer.store.IndexStore
import dev.yourstream.media.indexer.store.RoomIndexStore
import dev.yourstream.media.parser.IndexingPolicy
import dev.yourstream.media.parser.MediaNormalizer
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class IndexerModule {

    @Binds abstract fun store(impl: RoomIndexStore): IndexStore
    @Binds abstract fun repository(impl: RoomMediaIndexRepository): MediaIndexRepository
    @Binds abstract fun indexer(impl: DefaultMediaIndexer): MediaIndexer

    /** Other modules (the library) add themselves; empty is fine. */
    @Multibinds abstract fun indexListeners(): Set<MediaIndexListener>

    companion object {
        /** Opened eagerly and safely: backup before upgrades, salvage instead of crashing (DatabaseOpener). */
        @Provides @Singleton
        fun openedDatabase(@ApplicationContext context: Context, logger: Logger): OpenedDatabase {
            val (db, report) = DatabaseOpener.open(context) { message, error ->
                if (error == null) logger.i("Database", message) else logger.e("Database", message, error)
            }
            return OpenedDatabase(db, report, context.getDatabasePath(YourStreamDatabase.NAME))
        }

        @Provides @Singleton
        fun database(opened: OpenedDatabase): YourStreamDatabase = opened.db

        @Provides @Singleton
        fun normalizer(): MediaNormalizer = MediaNormalizer(IndexingPolicy())

        @Provides @Singleton
        fun engineConfig(): EngineConfig = EngineConfig()

        @Provides
        fun clock(): Clock = Clock.System
    }
}
