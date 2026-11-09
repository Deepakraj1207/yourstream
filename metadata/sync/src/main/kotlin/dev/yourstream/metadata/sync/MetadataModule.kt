package dev.yourstream.metadata.sync

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.yourstream.core.common.Clock
import dev.yourstream.core.common.Logger
import dev.yourstream.core.network.HttpClients
import dev.yourstream.media.api.MediaIndexListener
import dev.yourstream.metadata.api.MetadataProvider
import dev.yourstream.metadata.cinemeta.CinemetaProvider
import dev.yourstream.metadata.core.MetadataCache
import dev.yourstream.metadata.core.MetadataProviderManager
import dev.yourstream.metadata.core.match.MetadataMatcher
import dev.yourstream.metadata.tmdb.TmdbConfig
import dev.yourstream.metadata.tmdb.TmdbProvider
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * Which providers exist and in which order is configuration ([MetadataProviderOrder]), not
 * code: the manager receives a list. The app module provides [TmdbConfig], [MetadataSettings]
 * and [MetadataProviderOrder] from the build configuration.
 */
data class MetadataProviderOrder(val ids: List<String> = listOf("tmdb", "cinemeta"))

@Module
@InstallIn(SingletonComponent::class)
abstract class MetadataModule {

    @Binds abstract fun cache(impl: RoomMetadataCache): MetadataCache

    @Binds @IntoSet abstract fun indexListener(impl: MetadataSyncTrigger): MediaIndexListener

    companion object {
        @Provides @Singleton
        fun providers(tmdb: TmdbKeys, order: MetadataProviderOrder, clock: Clock): List<@JvmSuppressWildcards MetadataProvider> {
            val available = mapOf(
                // The key is read per request: one entered in Settings works without a restart.
                "tmdb" to { TmdbProvider(HttpClients.create(), { tmdb.current() }, clock) as MetadataProvider },
                "cinemeta" to { CinemetaProvider(HttpClients.create(), clock = clock) as MetadataProvider },
            )
            return order.ids.mapNotNull { available[it.trim().lowercase()]?.invoke() }
        }

        @Provides @Singleton
        fun manager(providers: List<@JvmSuppressWildcards MetadataProvider>, cache: MetadataCache, clock: Clock, logger: Logger, settings: MetadataSettings) =
            MetadataProviderManager(providers, cache, clock, logger, defaultLanguage = settings.language)

        @Provides @Singleton
        fun matcher(manager: MetadataProviderManager) = MetadataMatcher(manager)

        @Provides @Singleton @MetadataScope
        fun scope(logger: Logger): CoroutineScope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, t -> logger.e("Metadata", "uncaught", t) },
        )
    }
}
