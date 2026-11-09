package dev.yourstream.catalog.sync

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.yourstream.catalog.api.CatalogProvider
import dev.yourstream.catalog.api.ExternalMetadataFallback
import dev.yourstream.catalog.api.LibraryAvailability
import dev.yourstream.catalog.api.LocalCatalogSource
import dev.yourstream.catalog.core.CatalogCache
import dev.yourstream.catalog.core.CatalogManager
import dev.yourstream.core.common.Clock
import dev.yourstream.core.common.Logger
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CatalogModule {

    @Binds abstract fun cache(impl: RoomCatalogCache): CatalogCache
    @Binds abstract fun local(impl: RoomLocalCatalogSource): LocalCatalogSource
    @Binds abstract fun library(impl: RoomLibraryAvailability): LibraryAvailability
    @Binds abstract fun external(impl: MetadataCatalogFallback): ExternalMetadataFallback

    companion object {
        /** The manager for the user's enabled add-ons (rebuilt by [CatalogAddons] when they change). */
        @Provides
        fun manager(addons: CatalogAddons): CatalogManager = addons.manager()
    }
}
