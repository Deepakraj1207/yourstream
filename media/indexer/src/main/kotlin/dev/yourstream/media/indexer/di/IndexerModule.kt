package dev.yourstream.media.indexer.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.yourstream.core.common.Clock
import dev.yourstream.core.database.YourStreamDatabase
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

    companion object {
        @Provides @Singleton
        fun database(@ApplicationContext context: Context): YourStreamDatabase = YourStreamDatabase.create(context)

        @Provides @Singleton
        fun normalizer(): MediaNormalizer = MediaNormalizer(IndexingPolicy())

        @Provides @Singleton
        fun engineConfig(): EngineConfig = EngineConfig()

        @Provides
        fun clock(): Clock = Clock.System
    }
}
