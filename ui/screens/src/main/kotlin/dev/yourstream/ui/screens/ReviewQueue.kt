package dev.yourstream.ui.screens

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Home's hero carousel, from the catalogs the user picked for it (empty: none picked; null: loading). */
fun interface HeroSlides {
    fun slides(): Flow<List<dev.yourstream.ui.screens.home.HeroSlide>?>
}

/** Catalog rows for Home, from the user's add-ons (null: loading). */
fun interface HomeCatalogs {
    fun rows(): Flow<List<dev.yourstream.ui.screens.home.CatalogRow>?>
}

/**
 * The add-ons for the Search tab: search results in one row per add-on and type (Stremio
 * "search" catalogs, else TMDB/Cinemeta), and Discover, their catalogs to browse by type,
 * catalog and genre.
 */
interface AddonSearch {
    suspend fun sections(query: String): AddonSections
    suspend fun catalogs(): List<DiscoverCatalog> = emptyList()
    suspend fun page(catalog: DiscoverCatalog, genre: String?, skip: Int): DiscoverPage = DiscoverPage(emptyList(), null)
}

/** One search row: [addon] null for the TMDB/Cinemeta fallback. */
data class SearchRow(val key: String, val addon: String?, val type: dev.yourstream.catalog.api.CatalogType, val tiles: List<Tile>)

/** [searchable]: some add-on offers search; [failed]: every add-on that does failed. */
data class AddonSections(val rows: List<SearchRow>, val searchable: Boolean = true, val failed: Boolean = false)

/** A catalog Discover can show; [genreRequired]: the add-on only answers with a genre. */
data class DiscoverCatalog(
    val key: String, val name: String, val addon: String, val type: dev.yourstream.catalog.api.CatalogType,
    val genres: List<String> = emptyList(), val genreRequired: Boolean = false,
)

data class DiscoverPage(val tiles: List<Tile>, val nextSkip: Int?)

@Module
@InstallIn(SingletonComponent::class)
object ScreensModule {
    private const val SEARCH_ROW_SIZE = 30

    @Provides
    fun searchMemory(prefs: dev.yourstream.ui.screens.search.SearchPrefs): dev.yourstream.ui.screens.search.SearchMemory = prefs

    @Provides
    fun addonSearch(addons: dev.yourstream.catalog.sync.CatalogAddons): AddonSearch = object : AddonSearch {
        override suspend fun sections(query: String): AddonSections = coroutineScope {
            val manager = addons.manager()
            val found = manager.searchSections(query)
            val rows = found.sections.map { s ->
                async {
                    val items = s.items.distinctBy { it.ref.id }.take(SEARCH_ROW_SIZE)
                    val tiles = runCatching { manager.withLibraryPresence(items) }.getOrElse { items.map { it to null } }.map { (item, p) -> Presentation.tile(item, p) }
                    SearchRow("${s.provider?.value ?: "external"}:${s.type}", s.providerName, s.type, tiles)
                }
            }.awaitAll().filter { it.tiles.isNotEmpty() }
            AddonSections(rows, found.searchable, found.failed)
        }

        override suspend fun catalogs(): List<DiscoverCatalog> {
            val names = addons.addons.value.associate { it.id to it.name }
            return addons.manager().catalogs().filter { c ->
                // Search-only catalogs, and ones needing something other than a genre, cannot be browsed.
                c.extras.none { it.isRequired && it.name != "genre" }
            }.map { c ->
                DiscoverCatalog(catalogKey(c), c.name, names[c.provider.value] ?: c.provider.value, c.type, c.genres,
                    genreRequired = c.extras.any { it.isRequired && it.name == "genre" })
            }
        }

        override suspend fun page(catalog: DiscoverCatalog, genre: String?, skip: Int): DiscoverPage {
            val manager = addons.manager()
            val c = manager.catalogs().firstOrNull { catalogKey(it) == catalog.key } ?: return DiscoverPage(emptyList(), null)
            return when (val r = manager.page(c, skip, genre)) {
                is dev.yourstream.core.common.AppResult.Ok -> {
                    val items = r.value.value.items.distinctBy { it.ref.id }
                    val tiles = runCatching { manager.withLibraryPresence(items) }.getOrElse { items.map { it to null } }.map { (item, p) -> Presentation.tile(item, p) }
                    DiscoverPage(tiles, r.value.value.nextSkip)
                }
                is dev.yourstream.core.common.AppResult.Err -> throw IllegalStateException(r.error.message)
            }
        }
    }

    private const val MAX_CATALOG_ROWS = 12
    private const val CATALOG_ROW_SIZE = 20

    /**
     * Reloaded when add-ons are switched on/off, added, removed or reordered, and when the user
     * hides, reorders or renames a catalog (Settings → Layout → Home layout); empty when none is enabled.
     */
    /** From [dev.yourstream.ui.screens.home.HomeSources]: kept while Home is not on screen. */
    @Provides
    fun homeCatalogs(sources: dev.yourstream.ui.screens.home.HomeSources): HomeCatalogs = HomeCatalogs { sources.catalogs }

    /** The rows' source; [dev.yourstream.ui.screens.home.HomeSources] keeps it running for the whole app. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    internal fun catalogRowsFlow(addons: dev.yourstream.catalog.sync.CatalogAddons, layout: dev.yourstream.ui.screens.home.HomeCatalogPrefs) =
        kotlinx.coroutines.flow.combine(addons.addons.map { list -> list.filter { it.enabled }.map { it.id } }.distinctUntilChanged(), layout.layout) { e, l -> e to l }
            .mapLatest { (enabled, l) -> if (enabled.isEmpty()) emptyList() else catalogRows(addons.manager(), l) }

    private const val HERO_SLIDES = 10

    /**
     * The hero carousel: the first titles of each hero catalog (with artwork), taken in turn so
     * every picked catalog shows up, at most [HERO_SLIDES]. Reloaded when the choice or the
     * add-ons change; catalog pages come from the catalog cache (the rows load them too).
     */
    @Provides
    fun heroSlides(sources: dev.yourstream.ui.screens.home.HomeSources): HeroSlides = HeroSlides { sources.hero }

    /** What the hero carousel depends on: the enabled add-ons and the hero catalogs. */
    internal fun heroKey(addons: dev.yourstream.catalog.sync.CatalogAddons, layout: dev.yourstream.ui.screens.home.HomeCatalogPrefs) =
        kotlinx.coroutines.flow.combine(addons.addons.map { list -> list.filter { it.enabled }.map { it.id } }.distinctUntilChanged(),
            layout.layout.map { it.hero }.distinctUntilChanged()) { e, h -> e to h }

    /** The carousel's slides for [enabled] add-ons and [hero] catalogs, with TMDB's art. */
    internal suspend fun heroSlidesFor(
        addons: dev.yourstream.catalog.sync.CatalogAddons, enabled: List<String>, hero: List<String>, details: dev.yourstream.metadata.api.TitleDetailsProvider,
    ) = if (enabled.isEmpty() || hero.isEmpty()) emptyList() else withTmdbArt(heroSlides(addons.manager(), hero), details)

    /** How long the carousel waits for TMDB's logos before showing the add-on's artwork instead. */
    private const val HERO_ART_WAIT_MS = 3_000L

    /**
     * Like Nuvio's hero: the title's logo over artwork without the title printed on it, from TMDB
     * (with its key). Slides TMDB does not answer for in time keep the add-on's artwork and the
     * title as text.
     */
    private suspend fun withTmdbArt(slides: List<dev.yourstream.ui.screens.home.HeroSlide>, details: dev.yourstream.metadata.api.TitleDetailsProvider) = coroutineScope {
        if (!details.isConfigured) return@coroutineScope slides
        val locale = java.util.Locale.getDefault()
        slides.map { s ->
            async {
                val route = (s.open as? dev.yourstream.ui.screens.navigation.OpenTarget.CatalogTitle)?.route ?: return@async s
                val imdb = route.imdb ?: route.id.takeIf { it.startsWith("tt") } ?: return@async s
                val type = if (s.type == dev.yourstream.catalog.api.CatalogType.SERIES) dev.yourstream.metadata.api.MetadataType.SERIES else dev.yourstream.metadata.api.MetadataType.MOVIE
                val d = kotlinx.coroutines.withTimeoutOrNull(HERO_ART_WAIT_MS) {
                    runCatching { (details.details(type, imdb, null, locale.toLanguageTag(), locale.country.ifBlank { null }) as? dev.yourstream.core.common.AppResult.Ok)?.value }.getOrNull()
                } ?: return@async s
                s.copy(logoUrl = d.logoUrl, textlessPosterUrl = d.textlessPosterUrl, backdropUrl = d.backdropUrl ?: s.backdropUrl)
            }
        }.awaitAll()
    }

    private suspend fun heroSlides(manager: dev.yourstream.catalog.core.CatalogManager, hero: List<String>) = coroutineScope {
        val byKey = manager.catalogs().associateBy(::catalogKey)
        val perCatalog = hero.mapNotNull { byKey[it] }.map { c ->
            async {
                val items = (manager.page(c) as? dev.yourstream.core.common.AppResult.Ok)?.value?.value?.items.orEmpty()
                    .filter { it.posterUrl != null || it.backdropUrl != null }.distinctBy { it.ref.id }.take(HERO_SLIDES)
                val presence = runCatching { manager.withLibraryPresence(items) }.getOrElse { items.map { it to null } }
                presence.map { (item, p) ->
                    dev.yourstream.ui.screens.home.HeroSlide(
                        "hero:${catalogKey(c)}:${item.ref.id}", item.title, item.posterUrl, item.backdropUrl, item.ref.type,
                        item.genres.firstOrNull(), item.runtimeMinutes, item.year, item.rating,
                        item.languages.ifEmpty { c.languages }, Presentation.tile(item, p).open,
                    )
                }
            }
        }.awaitAll()
        // In turn: first of each catalog, then the second of each, …
        (0 until HERO_SLIDES).flatMap { i -> perCatalog.mapNotNull { it.getOrNull(i) } }.distinctBy { it.title to it.year }.take(HERO_SLIDES)
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
}
