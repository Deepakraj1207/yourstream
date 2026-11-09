package dev.yourstream.watch.engine

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class WatchModule {
    @Binds abstract fun watchIntelligence(impl: RoomWatchIntelligence): WatchIntelligence
    @Binds abstract fun watchStatistics(impl: RoomWatchStatistics): WatchStatistics
}
