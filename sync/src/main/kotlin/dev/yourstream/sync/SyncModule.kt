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
        fun scope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
