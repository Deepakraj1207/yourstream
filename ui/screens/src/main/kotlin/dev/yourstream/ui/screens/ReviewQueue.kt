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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** How many titles wait for the user's match check (live). */
fun interface ReviewQueue {
    fun count(): Flow<Int>
}

/** Catalog rows for Home, from the user's add-ons. */
fun interface HomeCatalogs {
    fun rows(): Flow<List<dev.yourstream.ui.screens.home.CatalogRow>>
}

@Module
@InstallIn(SingletonComponent::class)
object ScreensModule {
    private const val MAX_CATALOG_ROWS = 8
    private const val CATALOG_ROW_SIZE = 20

    /** Reloaded when add-ons are switched on/off, added or removed; empty when none is enabled. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Provides
    fun homeCatalogs(addons: dev.yourstream.catalog.sync.CatalogAddons): HomeCatalogs = HomeCatalogs {
        addons.addons
            .map { list -> list.filter { it.enabled }.map { it.id } }
            .distinctUntilChanged()
            .mapLatest { enabled -> if (enabled.isEmpty()) emptyList() else catalogRows(addons.manager()) }
    }

    private suspend fun catalogRows(manager: dev.yourstream.catalog.core.CatalogManager) = coroutineScope {
        manager.catalogs().take(MAX_CATALOG_ROWS).map { c ->
            async {
                val page = (manager.page(c) as? dev.yourstream.core.common.AppResult.Ok)?.value?.value ?: return@async null
                val items = page.items.distinctBy { it.ref.id }.take(CATALOG_ROW_SIZE)
                if (items.isEmpty()) return@async null
                val withPresence = runCatching { manager.withLibraryPresence(items) }.getOrElse { items.map { it to null } }
                dev.yourstream.ui.screens.home.CatalogRow("cat:${c.provider.value}:${c.type}:${c.id}", c.name, withPresence.map { (item, p) -> Presentation.tile(item, p) })
            }
        }.awaitAll().filterNotNull()
    }

    @Provides
    fun reviewQueue(metadata: MetadataRepository): ReviewQueue = ReviewQueue {
        metadata.statusCounts().map { it[MatchStatus.NEEDS_REVIEW.name] ?: 0 }.catch { emit(0) }
    }
}
