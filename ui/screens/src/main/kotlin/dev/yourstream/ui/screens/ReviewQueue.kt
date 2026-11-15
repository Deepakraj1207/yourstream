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

/** Titles from the add-ons' search (Stremio "search" catalogs, then TMDB/Cinemeta). */
fun interface AddonSearch {
    suspend fun search(query: String): List<Tile>
}

@Module
@InstallIn(SingletonComponent::class)
object ScreensModule {
    @Provides
    fun addonSearch(addons: dev.yourstream.catalog.sync.CatalogAddons): AddonSearch = AddonSearch { q ->
        val manager = addons.manager()
        val found = manager.search(q)
        // Library-only answers are already in the search results.
        if (found.source == dev.yourstream.catalog.api.CatalogSource.Local) emptyList()
        else manager.withLibraryPresence(found.value.distinctBy { it.ref.id }.take(30)).map { (item, p) -> Presentation.tile(item, p) }
    }

    private const val MAX_CATALOG_ROWS = 12
    private const val CATALOG_ROW_SIZE = 20

    /**
     * Reloaded when add-ons are switched on/off, added, removed or reordered, and when the user
     * hides, reorders or renames a catalog (Settings → Layout → Home layout); empty when none is enabled.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Provides
    fun homeCatalogs(addons: dev.yourstream.catalog.sync.CatalogAddons, layout: dev.yourstream.ui.screens.home.HomeCatalogPrefs): HomeCatalogs = HomeCatalogs {
        kotlinx.coroutines.flow.combine(addons.addons.map { list -> list.filter { it.enabled }.map { it.id } }.distinctUntilChanged(), layout.layout) { e, l -> e to l }
            .mapLatest { (enabled, l) -> if (enabled.isEmpty()) emptyList() else catalogRows(addons.manager(), l) }
    }

    /** The key Home and the settings use for a catalog. */
    fun catalogKey(c: dev.yourstream.catalog.api.CatalogDescriptor) = "cat:${c.provider.value}:${c.type}:${c.id}"

    private suspend fun catalogRows(manager: dev.yourstream.catalog.core.CatalogManager, layout: dev.yourstream.ui.screens.home.HomeCatalogLayout) = coroutineScope {
        val thisYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        layout.arrange(manager.catalogs(), ::catalogKey).take(MAX_CATALOG_ROWS).map { c ->
            async {
                val page = (manager.page(c) as? dev.yourstream.core.common.AppResult.Ok)?.value?.value ?: return@async null
                val items = page.items.distinctBy { it.ref.id }.take(CATALOG_ROW_SIZE)
                if (items.isEmpty()) return@async null
                val withPresence = runCatching { manager.withLibraryPresence(items) }.getOrElse { items.map { it to null } }
                val tiles = withPresence.map { (item, p) -> Presentation.tile(item, p) }
                val unreleased = withPresence.zip(tiles).filter { (ip, _) -> (ip.first.year ?: 0) > thisYear }.map { it.second.card.key }.toSet()
                dev.yourstream.ui.screens.home.CatalogRow(catalogKey(c), layout.nameOf(catalogKey(c), c.name), tiles, c.type, unreleased)
            }
        }.awaitAll().filterNotNull()
    }

    @Provides
    fun reviewQueue(metadata: MetadataRepository): ReviewQueue = ReviewQueue {
        metadata.statusCounts().map { it[MatchStatus.NEEDS_REVIEW.name] ?: 0 }.catch { emit(0) }
    }
}
