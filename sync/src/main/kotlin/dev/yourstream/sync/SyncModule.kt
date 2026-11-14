package dev.yourstream.sync

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncModule {
    @Binds abstract fun settings(impl: AndroidSyncSettings): SyncSettings

    companion object {
        @Provides @Singleton @SyncScope
        fun scope(logger: dev.yourstream.core.common.Logger): CoroutineScope = CoroutineScope(
            // A failure in background sync is logged; without a handler it would crash the app.
            SupervisorJob() + Dispatchers.IO + kotlinx.coroutines.CoroutineExceptionHandler { _, t -> logger.e("ProgressSync", "uncaught", t) },
        )
    }
}
