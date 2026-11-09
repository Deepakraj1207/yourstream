package dev.yourstream.ui.screens

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.yourstream.metadata.sync.MatchStatus
import dev.yourstream.metadata.sync.MetadataRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** How many titles wait for the user's match check (live). */
fun interface ReviewQueue {
    fun count(): Flow<Int>
}

@Module
@InstallIn(SingletonComponent::class)
object ScreensModule {
    @Provides
    fun reviewQueue(metadata: MetadataRepository): ReviewQueue = ReviewQueue {
        metadata.statusCounts().map { it[MatchStatus.NEEDS_REVIEW.name] ?: 0 }.catch { emit(0) }
    }
}
