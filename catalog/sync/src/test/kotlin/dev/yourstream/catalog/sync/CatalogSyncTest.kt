package dev.yourstream.catalog.sync

import androidx.test.core.app.ApplicationProvider
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
import dev.yourstream.catalog.api.PresenceMatch
import dev.yourstream.catalog.core.AvailabilityState
import dev.yourstream.catalog.core.CatalogCacheEntry
import dev.yourstream.catalog.core.CatalogManager
import dev.yourstream.catalog.core.DiscoverRequest
import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import dev.yourstream.core.common.Clock
import dev.yourstream.core.common.NoOpLogger
import dev.yourstream.core.database.YourStreamDatabase
import dev.yourstream.core.database.entity.ItemMetadataEntity
import dev.yourstream.core.database.entity.LibraryItemEntity
import dev.yourstream.core.database.entity.LibraryItemLanguageEntity
import dev.yourstream.metadata.api.MetadataCandidate
import dev.yourstream.metadata.api.MetadataProvider
import dev.yourstream.metadata.api.MetadataQuery
import dev.yourstream.metadata.api.MetadataRef
import dev.yourstream.metadata.api.MetadataType
import dev.yourstream.metadata.api.MovieMetadata
import dev.yourstream.metadata.api.ProviderCapability
import dev.yourstream.metadata.api.ProviderId
import dev.yourstream.metadata.api.SeriesMetadata
import dev.yourstream.metadata.core.InMemoryMetadataCache
import dev.yourstream.metadata.core.MetadataProviderManager
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TestClock(var now: Long = 1_700_000_000_000L) : Clock { override fun nowMillis() = now }

/** TMDB-like metadata provider (mock answers, no network). */
class FakeTmdb : MetadataProvider {
    override val id = ProviderId("tmdb")
    override val displayName = "Fake TMDB"
    override val capabilities = ProviderCapability.entries.toSet()
    override val isConfigured = true
    val calls = mutableListOf<String>()
    val movies = mutableListOf<MovieMetadata>()

    fun add(id: String, title: String, year: Int, imdb: String? = null) {
        movies += MovieMetadata(MetadataRef(this.id, MetadataType.MOVIE, id, imdb), title, overview = "About $title", year = year,
            genres = listOf("Drama"), originalLanguage = "ta", runtimeMinutes = 140)
    }

    override suspend fun search(query: MetadataQuery): AppResult<List<MetadataCandidate>> {
        calls += "search:${query.title}"
        return AppResult.Ok(movies.filter { it.title.contains(query.title, ignoreCase = true) && (query.year == null || it.year == query.year) }
            .map { MetadataCandidate(it.ref, it.title, year = it.year, originalLanguage = it.originalLanguage) })
    }
    override suspend fun movie(id: String, language: String?): AppResult<MovieMetadata> {
        calls += "movie:$id"
        return movies.firstOrNull { it.ref.id == id }?.let { AppResult.Ok(it) } ?: AppResult.Err(AppError.NotFound(id))
    }
    override suspend fun series(id: String, language: String?): AppResult<SeriesMetadata> = AppResult.Err(AppError.NotFound(id))
    override suspend fun season(seriesId: String, seasonNumber: Int, language: String?) = AppResult.Err(AppError.NotFound(seriesId))
    override suspend fun episode(seriesId: String, seasonNumber: Int, episodeNumber: Int, language: String?) = AppResult.Err(AppError.NotFound(seriesId))
    override suspend fun findByImdbId(imdbId: String, type: MetadataType): AppResult<MetadataRef> {
        calls += "find:$imdbId"
        return movies.firstOrNull { it.ref.imdbId == imdbId }?.let { AppResult.Ok(it.ref) } ?: AppResult.Err(AppError.NotFound(imdbId))
    }
}

/** A catalog-only provider (like IndiaStreams: no meta, no search) that can be switched off. */
class FakeCatalogProvider : CatalogProvider {
    override val id = CatalogProviderId("fake")
    override val displayName = "Fake"
    override val attribution = "Fake catalog"
    var down = false
    private val catalog = CatalogDescriptor(id, "ta", CatalogType.MOVIE, "Tamil", languages = listOf("ta"), extras = listOf(CatalogExtra("skip")))
    val items = listOf(
        item("tt100", "Leo", 2023),
        item("tt200", "Jailer", 2023),
        item("x1", "Unknown Film", 2024, imdb = null),
    )

    private fun item(id: String, title: String, year: Int, imdb: String? = id) =
        CatalogItem(CatalogItemRef(this.id, CatalogType.MOVIE, id, imdb), title, year, catalogId = "ta", languages = listOf("ta"))

    private fun err() = AppResult.Err(AppError.Network("offline"))
    override suspend fun describe(): AppResult<CatalogProviderInfo> =
        if (down) err() else AppResult.Ok(CatalogProviderInfo(id, "Fake", "1", null, listOf(catalog), listOf("catalog"), emptyList(), "https://fake"))
    override suspend fun page(catalog: CatalogDescriptor, skip: Int, genre: String?): AppResult<CatalogPage> =
        if (down) err() else AppResult.Ok(CatalogPage(items.drop(skip), null))
    override suspend fun search(query: String, type: CatalogType?) = AppResult.Err(AppError.InvalidState("no search"))
    override suspend fun metadata(ref: CatalogItemRef): AppResult<CatalogMetadata> = AppResult.Err(AppError.InvalidState("no meta"))
}

@RunWith(RobolectricTestRunner::class)
class CatalogSyncTest {

    private val clock = TestClock()
    private val db = YourStreamDatabase.inMemory(ApplicationProvider.getApplicationContext())
    private val tmdb = FakeTmdb()
    private val metadata = MetadataProviderManager(listOf(tmdb), InMemoryMetadataCache(), clock, NoOpLogger)

    @After fun close() = db.close()

    private suspend fun libraryItem(
        id: Long, title: String, year: Int?, kind: String = "MOVIE", files: Int = 1, hidden: Boolean = false,
        languages: List<String> = emptyList(), imdb: String? = null, tmdbId: String? = null, originalLanguage: String? = null,
    ) {
        val key = title.lowercase()
        db.libraryDao().insertItem(
            LibraryItemEntity(id = id, kind = kind, matchKey = "$kind:$key:$year", title = title, sortTitle = key, normalizedTitle = key,
                year = year, searchText = key, titleConfidence = 0.9f, hidden = hidden, addedAt = id, updatedAt = id,
                availableCount = files, latestAddedAt = id),
        )
        if (languages.isNotEmpty()) db.libraryDao().insertLanguages(languages.map { LibraryItemLanguageEntity(id, it) })
        if (imdb != null || tmdbId != null) {
            db.metadataDao().putItemMetadata(
                ItemMetadataEntity(id, "tmdb", tmdbId ?: "t$id", imdb, "$title (official)", null, "Overview of $title", "https://img/$id.jpg",
                    null, "Action,Drama", "$year-01-01", year, originalLanguage, 150, 7.5, """[{"name":"Vijay","order":0}]""", "Lokesh", null, 0),
            )
        }
    }

    private fun manager(provider: CatalogProvider) = CatalogManager(
        listOf(provider), RoomCatalogCache(db), clock, NoOpLogger,
        MetadataCatalogFallback(metadata), RoomLocalCatalogSource(db), RoomLibraryAvailability(db),
    )

    @Test
    fun roomCacheRoundTripAndPurge() = runTest {
        val cache = RoomCatalogCache(db)
        cache.put("p", "k", CatalogCacheEntry("{}", 1, 10))
        cache.put("p", "gone", CatalogCacheEntry(null, 1, 5))
        assertEquals(CatalogCacheEntry("{}", 1, 10), cache.get("p", "k"))
        assertNull(cache.get("p", "gone")!!.payload, "negative entries are stored")
        cache.purge(olderThan = 6)
        assertNull(cache.get("p", "gone"))
        assertEquals(1, db.catalogDao().cacheSize())
    }

    @Test
    fun persistedCacheKeepsCatalogsUsableAfterRestartWhileOffline() = runTest {
        val provider = FakeCatalogProvider()
        val first = manager(provider)
        assertEquals(3, first.discover(DiscoverRequest(language = "ta")).items.size)

        // "Restart" a day later with the provider down: a new manager over the same database.
        clock.now += 25 * 60 * 60 * 1000L
        provider.down = true
        val second = manager(provider)
        val availability = second.refreshAvailability().single()
        assertEquals(AvailabilityState.STALE, availability.state)
        val result = second.discover(DiscoverRequest(language = "ta"))
        assertEquals(CatalogSource.Provider(provider.id), result.source)
        assertTrue(result.stale)
        assertEquals(listOf("Leo", "Jailer", "Unknown Film"), result.items.map { it.title })
    }

    @Test
    fun localSourceListsOnlyWatchableTitlesByLanguageTypeAndPage() = runTest {
        libraryItem(1, "Leo", 2023, languages = listOf("ta", "te"), imdb = "tt100")
        libraryItem(2, "Premam", 2015, originalLanguage = "ml", imdb = "tt300")
        libraryItem(3, "Doraemon", null, kind = "SERIES", languages = listOf("ta"))
        libraryItem(4, "Hidden", 2020, hidden = true, languages = listOf("ta"))
        libraryItem(5, "Gone", 2020, files = 0, languages = listOf("ta"))
        libraryItem(6, "Home Video", null, kind = "VIDEO", languages = listOf("ta"))
        val local = RoomLocalCatalogSource(db)

        assertEquals(listOf("Doraemon", "Leo (official)"), local.discover("ta", null, 0, 10).map { it.title })
        assertEquals(listOf("Premam (official)"), local.discover("ml", null, 0, 10).map { it.title }, "original language counts too")
        assertEquals(listOf("Doraemon"), local.discover("ta", CatalogType.SERIES, 0, 10).map { it.title })
        assertEquals(listOf("Leo (official)"), local.discover("ta", null, 1, 10).map { it.title }, "paging by skip")

        val leo = local.discover("te", CatalogType.MOVIE, 0, 10).single()
        assertEquals(CatalogProviderId.LOCAL, leo.ref.provider)
        assertEquals("tt100", leo.ref.imdbId)
        assertEquals("t1", leo.ref.tmdbId)
        assertEquals("https://img/1.jpg", leo.posterUrl)
        assertEquals(listOf("Action", "Drama"), leo.genres)

        assertEquals(listOf("Leo (official)"), local.search("le", null, 10).map { it.title }, "FTS prefix search")
        assertEquals(emptyList(), local.search("hidden", null, 10))

        val meta = local.metadata(leo)!!
        assertEquals(listOf("Vijay"), meta.cast)
        assertEquals(listOf("Lokesh"), meta.directors)
        assertNull(local.metadata(local.discover("ta", CatalogType.SERIES, 0, 10).single()), "no applied metadata yet")
    }

    @Test
    fun libraryPresenceByImdbTmdbAndStrictTitleYear() = runTest {
        libraryItem(1, "Leo", 2023, imdb = "tt100")
        libraryItem(2, "Vikram", 2022, tmdbId = "555")
        libraryItem(3, "Jailer", 2023)
        libraryItem(4, "Drishyam", 2013)
        libraryItem(5, "Drishyam", 2015)
        libraryItem(6, "Kantara", 2022, kind = "SERIES")
        libraryItem(7, "Master", 2021, files = 0)
        fun item(title: String, year: Int?, imdb: String? = null, tmdbId: String? = null) =
            CatalogItem(CatalogItemRef(CatalogProviderId("p"), CatalogType.MOVIE, imdb ?: "$title:$year", imdb, tmdbId), title, year)
        val items = listOf(
            item("Leo", 2023, imdb = "tt100"),
            item("Vikram", 2022, tmdbId = "555"),
            item("Jailer", 2023),
            item("Jailer", 1990),
            item("Drishyam", null),
            item("Drishyam", 2015),
            item("Kantara", 2022),
            item("Master", 2021),
        )
        val presence = RoomLibraryAvailability(db).presence(items)
        assertEquals(PresenceMatch.IMDB_ID, presence[items[0].ref]!!.matchedBy)
        assertEquals(2L, presence[items[1].ref]!!.itemId)
        assertEquals(PresenceMatch.TMDB_ID, presence[items[1].ref]!!.matchedBy)
        assertEquals(PresenceMatch.TITLE_YEAR, presence[items[2].ref]!!.matchedBy)
        assertNull(presence[items[3].ref], "different year")
        assertNull(presence[items[4].ref], "ambiguous: two library items fit")
        assertEquals(5L, presence[items[5].ref]!!.itemId)
        assertNull(presence[items[6].ref], "different kind")
        assertNull(presence[items[7].ref], "no available files")
    }

    @Test
    fun externalFallbackUsesIdsAndOnlyUnambiguousTitles() = runTest {
        tmdb.add("1", "Leo", 2023, imdb = "tt100")
        tmdb.add("2", "Jailer", 2023, imdb = "tt200")
        tmdb.add("3", "Twin", 2020)
        tmdb.add("4", "Twin", 2020)
        val fallback = MetadataCatalogFallback(metadata)
        fun item(title: String, year: Int?, imdb: String? = null, tmdbId: String? = null) =
            CatalogItem(CatalogItemRef(CatalogProviderId("indiastreams"), CatalogType.MOVIE, imdb ?: title, imdb, tmdbId), title, year)

        val byTmdb = assertIs<AppResult.Ok<*>>(fallback.metadata(item("x", null, tmdbId = "1"))).value
        assertEquals(CatalogSource.ExternalMetadata("tmdb"), (byTmdb as dev.yourstream.catalog.api.Sourced<*>).source)

        // IMDb id only: Cinemeta is not configured, the metadata manager translates via TMDB.
        val byImdb = fallback.metadata(item("Jailer", 2023, imdb = "tt200"))
        assertEquals("About Jailer", (byImdb as AppResult.Ok).value.value.description)
        assertEquals(CatalogProviderId("indiastreams"), byImdb.value.value.ref.provider, "keeps the catalog's ref")
        assertTrue("find:tt200" in tmdb.calls)

        val byTitle = fallback.metadata(item("Leo", 2023)) as AppResult.Ok
        assertEquals("tt100", byTitle.value.value.ref.imdbId)
        assertIs<AppResult.Err>(fallback.metadata(item("Twin", 2020)), "two candidates: not guessed")
        assertIs<AppResult.Err>(fallback.metadata(item("Leo", null)), "no year: not guessed")
        assertIs<AppResult.Err>(fallback.metadata(item("Leo", 2019)), "different year")

        val search = fallback.search("Jailer", CatalogType.MOVIE) as AppResult.Ok
        val hit = search.value.value.single()
        assertEquals(CatalogProviderId("tmdb"), hit.ref.provider)
        assertEquals("2", hit.ref.tmdbId)
        // A search hit resolves again through its own provider.
        assertEquals("About Jailer", (fallback.metadata(hit) as AppResult.Ok).value.value.description)
    }

    @Test
    fun managerChainEndToEnd() = runTest {
        tmdb.add("1", "Leo", 2023, imdb = "tt100")
        libraryItem(10, "Jailer", 2023, imdb = "tt200", languages = listOf("ta"))
        val provider = FakeCatalogProvider()
        val manager = manager(provider)

        val page = manager.discover(DiscoverRequest(language = "ta"))
        val withPresence = manager.withLibraryPresence(page.items)
        assertEquals(listOf(null, 10L, null), withPresence.map { it.second?.itemId }, "only Jailer is on Telegram")

        // No catalog meta → TMDB by IMDb id.
        assertEquals(CatalogSource.ExternalMetadata("tmdb"), manager.metadata(page.items[0]).source)
        // TMDB doesn't know it → the library's applied metadata.
        val jailer = manager.metadata(page.items[1])
        assertEquals(CatalogSource.Local, jailer.source)
        assertEquals("Overview of Jailer", jailer.value.description)
        // Nothing anywhere → the listing itself, flagged partial.
        val unknown = manager.metadata(page.items[2])
        assertEquals(CatalogSource.Fallback, unknown.source)
        assertTrue(unknown.value.partial)

        // Library items resolve locally without any network call.
        val calls = tmdb.calls.size
        val local = manager.discover(DiscoverRequest(language = "ta", type = CatalogType.SERIES))
        assertEquals(CatalogSource.Fallback, local.source, "no series anywhere")
        val localJailer = RoomLocalCatalogSource(db).discover("ta", null, 0, 10).single()
        assertEquals(CatalogSource.Local, manager.metadata(localJailer).source)
        assertEquals(calls, tmdb.calls.size)

        // Search: no provider search → TMDB → library.
        assertEquals(CatalogSource.ExternalMetadata("tmdb"), manager.search("Leo").source)
        assertEquals(CatalogSource.Local, manager.search("Jailer").source)
    }
}
