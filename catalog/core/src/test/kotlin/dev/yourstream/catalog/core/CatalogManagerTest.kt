package dev.yourstream.catalog.core

import dev.yourstream.catalog.api.CatalogDescriptor
import dev.yourstream.catalog.api.CatalogExtra
import dev.yourstream.catalog.api.CatalogItem
import dev.yourstream.catalog.api.CatalogItemRef
import dev.yourstream.catalog.api.CatalogMetadata
import dev.yourstream.catalog.api.CatalogPage
import dev.yourstream.catalog.api.CatalogProvider
import dev.yourstream.catalog.api.CatalogProviderId
import dev.yourstream.catalog.api.CatalogProviderInfo
import dev.yourstream.catalog.api.CatalogSource
import dev.yourstream.catalog.api.CatalogType
import dev.yourstream.catalog.api.ExternalMetadataFallback
import dev.yourstream.catalog.api.LibraryAvailability
import dev.yourstream.catalog.api.LibraryPresence
import dev.yourstream.catalog.api.LocalCatalogSource
import dev.yourstream.catalog.api.Sourced
import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import dev.yourstream.core.common.Clock
import dev.yourstream.core.common.NoOpLogger
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TestClock(var now: Long = 1_700_000_000_000L) : Clock { override fun nowMillis() = now }

/** Scriptable catalog provider. */
class FakeCatalog(
    val name: String,
    private val catalogs: List<Triple<String, CatalogType, List<String>>>,
    private val meta: Boolean = false,
    private val searchable: Boolean = false,
) : CatalogProvider {
    override val id = CatalogProviderId(name)
    override val displayName = name
    override val attribution = "by $name"
    var describeError: AppError? = null
    val errors = ArrayDeque<AppError>()
    val calls = mutableListOf<String>()
    val items = mutableMapOf<String, MutableList<CatalogItem>>()

    fun item(catalogId: String, title: String, imdb: String? = null, year: Int? = 2024, type: CatalogType = CatalogType.MOVIE) {
        val ref = CatalogItemRef(id, type, imdb ?: "$name:$title", imdb)
        items.getOrPut(catalogId) { mutableListOf() } += CatalogItem(ref, title, year, catalogId = catalogId,
            languages = catalogs.first { it.first == catalogId }.third, genres = listOf("Drama"))
    }

    private fun descriptors() = catalogs.map { (cid, type, langs) ->
        CatalogDescriptor(id, cid, type, cid, langs, if (cid.startsWith("trending")) listOf("trending") else emptyList(),
            listOfNotNull(CatalogExtra("skip"), if (searchable) CatalogExtra("search") else null))
    }

    override suspend fun describe(): AppResult<CatalogProviderInfo> {
        calls += "describe"
        describeError?.let { return AppResult.Err(it) }
        return AppResult.Ok(CatalogProviderInfo(id, displayName, "1", null, descriptors(), if (meta) listOf("catalog", "meta") else listOf("catalog"), if (meta) listOf("tt") else emptyList(), "https://$name"))
    }

    override suspend fun page(catalog: CatalogDescriptor, skip: Int, genre: String?): AppResult<CatalogPage> {
        calls += "page:${catalog.id}:$skip"
        errors.removeFirstOrNull()?.let { return AppResult.Err(it) }
        val all = items[catalog.id].orEmpty()
        val slice = all.drop(skip).take(2)
        return AppResult.Ok(CatalogPage(slice, if (skip + slice.size < all.size) skip + slice.size else null))
    }

    override suspend fun search(query: String, type: CatalogType?): AppResult<List<CatalogItem>> {
        calls += "search:$query"
        return AppResult.Ok(items.values.flatten().filter { it.title.contains(query, ignoreCase = true) })
    }

    override suspend fun metadata(ref: CatalogItemRef): AppResult<CatalogMetadata> {
        calls += "meta:${ref.id}"
        errors.removeFirstOrNull()?.let { return AppResult.Err(it) }
        val item = items.values.flatten().firstOrNull { it.ref.id == ref.id || it.ref.imdbId == ref.id } ?: return AppResult.Err(AppError.NotFound(ref.id))
        return AppResult.Ok(CatalogMetadata(ref, item.title, item.year, description = "Full plot of ${item.title} from $displayName"))
    }
}

class CatalogManagerTest {

    private val clock = TestClock()
    private val a = FakeCatalog("regional", listOf(Triple("ta", CatalogType.MOVIE, listOf("ta")), Triple("hi", CatalogType.MOVIE, listOf("hi"))), meta = true)
    private val b = FakeCatalog("streams", listOf(Triple("trending", CatalogType.MOVIE, emptyList()), Triple("ta_alt", CatalogType.MOVIE, listOf("ta"))))
    private val localItems = mutableListOf<CatalogItem>()
    private val local = object : LocalCatalogSource {
        override suspend fun discover(language: String?, type: CatalogType?, skip: Int, limit: Int) =
            localItems.filter { language == null || language in it.languages }.drop(skip).take(limit)
        override suspend fun search(query: String, type: CatalogType?, limit: Int) = localItems.filter { it.title.contains(query, true) }
        override suspend fun metadata(item: CatalogItem) = localItems.firstOrNull { it.title == item.title }?.let {
            CatalogMetadata(item.ref, it.title, description = "From your library")
        }
    }
    var externalCalls = 0
    var externalAnswers = true
    private val external = object : ExternalMetadataFallback {
        override suspend fun metadata(item: CatalogItem): AppResult<Sourced<CatalogMetadata>> {
            externalCalls++
            return if (externalAnswers && item.ref.imdbId != null) AppResult.Ok(Sourced(CatalogMetadata(item.ref, item.title, description = "TMDB plot"), CatalogSource.ExternalMetadata("tmdb")))
            else AppResult.Err(AppError.NotFound("x"))
        }
        override suspend fun search(query: String, type: CatalogType?): AppResult<Sourced<List<CatalogItem>>> {
            externalCalls++
            return if (!externalAnswers) AppResult.Err(AppError.Network()) else AppResult.Ok(Sourced(listOf(
                CatalogItem(CatalogItemRef(CatalogProviderId("cinemeta"), CatalogType.MOVIE, "tt9", "tt9"), "$query (Cinemeta)")), CatalogSource.ExternalMetadata("cinemeta")))
        }
    }
    private val library = object : LibraryAvailability {
        override suspend fun presence(items: List<CatalogItem>) = items.filter { it.ref.imdbId == "tt1" }.associate { it.ref to LibraryPresence(7, it.title, 2) }
    }
    private val cache = InMemoryCatalogCache()
    private fun manager(vararg providers: CatalogProvider = arrayOf(a, b)) =
        CatalogManager(providers.toList(), cache, clock, NoOpLogger, external, local, library)

    init {
        a.item("ta", "Vikram", "tt1"); a.item("ta", "Leo", "tt2"); a.item("ta", "Jailer", "tt3")
        a.item("hi", "Jawan", "tt4")
        b.item("trending", "Trending One", "tt5"); b.item("ta_alt", "Alt Tamil", "tt6")
    }

    @Test
    fun availabilityDetection() = runTest {
        b.describeError = AppError.Network()
        val states = manager().refreshAvailability().associate { it.provider.value to it.state }
        assertEquals(AvailabilityState.AVAILABLE, states["regional"])
        assertEquals(AvailabilityState.UNAVAILABLE, states["streams"])
    }

    @Test
    fun aKnownProviderThatGoesDownStaysUsableAsStale() = runTest {
        manager().refreshAvailability()
        clock.now += 25 * CatalogCachePolicy.HOUR
        a.describeError = AppError.Timeout("manifest")
        val m = manager()
        val state = m.refreshAvailability().first { it.provider.value == "regional" }
        assertEquals(AvailabilityState.STALE, state.state)
        assertTrue(m.catalogs().any { it.id == "ta" }, "cached manifest keeps its catalogs listed")
    }

    @Test
    fun languagesShowWhoOffersThem() = runTest {
        val langs = manager().languages().associate { it.first.code to it.second.map { p -> p.value } }
        assertEquals(listOf("regional", "streams"), langs["ta"])
        assertEquals(listOf("regional"), langs["hi"])
        assertEquals(emptyList(), langs["mr"], "Marathi listed (Indian) but no provider here offers it")
        assertTrue(langs.keys.containsAll(listOf("ta", "te", "hi", "ml", "kn", "bn", "mr", "gu", "pa")))
    }

    @Test
    fun discoverUsesTheFirstProviderAndPages() = runTest {
        val m = manager()
        val first = m.discover(DiscoverRequest(language = "ta"))
        assertEquals(CatalogSource.Provider(CatalogProviderId("regional")), first.source)
        assertEquals(listOf("Vikram", "Leo"), first.items.map { it.title })
        val second = m.discover(DiscoverRequest(language = "ta", cursor = first.next))
        assertEquals(listOf("Jailer"), second.items.map { it.title })
        assertEquals(null, second.next)
    }

    @Test
    fun providerAFailsThenProviderB() = runTest {
        a.errors += AppError.Remote(503, "down")
        val r = manager().discover(DiscoverRequest(language = "ta"))
        assertEquals(CatalogSource.Provider(CatalogProviderId("streams")), r.source)
        assertEquals(listOf("Alt Tamil"), r.items.map { it.title })
        assertTrue(r.skipped.any { it.startsWith("regional/ta") })
    }

    @Test
    fun allProvidersFailThenLocalThenFallback() = runTest {
        a.errors += AppError.Network(); b.errors += AppError.Network()
        localItems += CatalogItem(CatalogItemRef(CatalogProviderId("local"), CatalogType.MOVIE, "library:1"), "My Tamil File", languages = listOf("ta"))
        val r = manager().discover(DiscoverRequest(language = "ta"))
        assertEquals(CatalogSource.Local, r.source)
        assertEquals(listOf("My Tamil File"), r.items.map { it.title })

        localItems.clear()
        a.errors += AppError.Network(); b.errors += AppError.Network()
        val none = manager(a, b).discover(DiscoverRequest(language = "kn"))
        assertEquals(CatalogSource.Fallback, none.source)
        assertTrue(none.items.isEmpty())
        assertTrue(none.skipped.isNotEmpty())
    }

    @Test
    fun pagesAreCachedAndStaleOnesServeOutages() = runTest {
        val m = manager()
        m.discover(DiscoverRequest(language = "hi")); m.discover(DiscoverRequest(language = "hi"))
        assertEquals(1, a.calls.count { it == "page:hi:0" }, "second answer from cache")

        clock.now += 5 * CatalogCachePolicy.HOUR
        a.errors += AppError.Network()
        val r = m.discover(DiscoverRequest(language = "hi"))
        assertEquals(listOf("Jawan"), r.items.map { it.title })
        assertTrue(r.stale, "expired page served because the provider failed")
        assertEquals(2, a.calls.count { it == "page:hi:0" })
    }

    @Test
    fun rateLimitedProviderIsPausedAndSkipped() = runTest {
        a.errors += AppError.RateLimited(60)
        val m = manager()
        assertEquals("streams", (m.discover(DiscoverRequest(language = "ta")).source as CatalogSource.Provider).id.value)
        assertEquals(AvailabilityState.PAUSED, m.availability().first { it.provider.value == "regional" }.state)
        val calls = a.calls.size
        m.discover(DiscoverRequest(language = "hi"))
        assertEquals(calls, a.calls.size, "not called while paused")
    }

    @Test
    fun tagAndTypeFilters() = runTest {
        assertEquals(listOf("Trending One"), manager().discover(DiscoverRequest(tag = "trending")).items.map { it.title })
        assertEquals(CatalogSource.Fallback, manager().discover(DiscoverRequest(type = CatalogType.SERIES, language = "te")).source)
    }

    @Test
    fun metadataChain() = runTest {
        val m = manager()
        val vikram = m.discover(DiscoverRequest(language = "ta")).items.first()
        // 1) owning provider
        assertEquals(CatalogSource.Provider(CatalogProviderId("regional")), m.metadata(vikram).source)

        // 2) another catalog provider that has meta for the same IMDb id
        val trending = m.discover(DiscoverRequest(tag = "trending")).items.first().copy(
            ref = CatalogItemRef(CatalogProviderId("streams"), CatalogType.MOVIE, "tt1", "tt1"))
        assertEquals(CatalogSource.Provider(CatalogProviderId("regional")), m.metadata(trending).source)

        // 3) external metadata when no catalog provider knows it
        val unknown = trending.copy(ref = CatalogItemRef(CatalogProviderId("streams"), CatalogType.MOVIE, "tt777", "tt777"), title = "Unknown")
        assertEquals(CatalogSource.ExternalMetadata("tmdb"), m.metadata(unknown).source)

        // 4) local metadata, 5) the listing itself
        externalAnswers = false
        localItems += unknown
        assertEquals(CatalogSource.Local, m.metadata(unknown).source)
        localItems.clear()
        val fallback = m.metadata(unknown)
        assertEquals(CatalogSource.Fallback, fallback.source)
        assertTrue(fallback.value.partial)
        assertEquals("Unknown", fallback.value.title)
    }

    @Test
    fun searchFallsBackWhenProvidersCannotSearch() = runTest {
        val r = manager().search("Leo")
        assertEquals(CatalogSource.ExternalMetadata("cinemeta"), r.source, "neither fake provider supports search")
        externalAnswers = false
        localItems += CatalogItem(CatalogItemRef(CatalogProviderId("local"), CatalogType.MOVIE, "library:2"), "Leo (my copy)")
        assertEquals(CatalogSource.Local, manager().search("Leo").source)

        val searchable = FakeCatalog("s", listOf(Triple("ta", CatalogType.MOVIE, listOf("ta"))), searchable = true).apply { item("ta", "Leo", "tt2") }
        assertEquals(CatalogSource.Provider(CatalogProviderId("s")), manager(searchable).search("Leo").source)
    }

    @Test
    fun catalogItemsAreNeverPlayableOnlyMarkedWhenInTheLibrary() = runTest {
        val m = manager()
        val items = m.discover(DiscoverRequest(language = "ta")).items
        val withPresence = m.withLibraryPresence(items)
        assertEquals(7L, withPresence.first { it.first.ref.imdbId == "tt1" }.second?.itemId)
        assertFalse(withPresence.first { it.first.ref.imdbId == "tt2" }.second != null)
    }
}
