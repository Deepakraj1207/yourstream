package dev.yourstream.metadata.sync

import androidx.test.core.app.ApplicationProvider
import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import dev.yourstream.core.common.Clock
import dev.yourstream.core.common.NoOpLogger
import dev.yourstream.core.database.YourStreamDatabase
import dev.yourstream.core.database.entity.EpisodeEntity
import dev.yourstream.core.database.entity.LibraryItemEntity
import dev.yourstream.core.database.entity.LibraryItemLanguageEntity
import dev.yourstream.core.database.entity.SeasonEntity
import dev.yourstream.metadata.api.CastMember
import dev.yourstream.metadata.api.EpisodeMetadata
import dev.yourstream.metadata.api.MetadataCandidate
import dev.yourstream.metadata.api.MetadataProvider
import dev.yourstream.metadata.api.MetadataQuery
import dev.yourstream.metadata.api.MetadataRef
import dev.yourstream.metadata.api.MetadataType
import dev.yourstream.metadata.api.MovieMetadata
import dev.yourstream.metadata.api.ProviderCapability
import dev.yourstream.metadata.api.ProviderId
import dev.yourstream.metadata.api.SeasonMetadata
import dev.yourstream.metadata.api.SeriesMetadata
import dev.yourstream.metadata.core.MetadataProviderManager
import dev.yourstream.metadata.core.match.MetadataMatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TestClock(var now: Long = 1_700_000_000_000L) : Clock { override fun nowMillis() = now }

/** A tiny TMDB-like catalogue. */
class CatalogueProvider : MetadataProvider {
    override val id = ProviderId("tmdb")
    override val displayName = "Fake TMDB"
    override val capabilities = ProviderCapability.entries.toSet()
    override var isConfigured = true
    val calls = mutableListOf<String>()
    val errors = ArrayDeque<AppError>()
    private val items = mutableListOf<MetadataCandidate>()

    fun add(title: String, year: Int, type: MetadataType, language: String = "en", popularity: Double = 50.0) {
        items += MetadataCandidate(MetadataRef(id, type, "$title-$year"), title, year = year, originalLanguage = language,
            popularity = popularity, posterUrl = "https://img/$title.jpg")
    }

    override suspend fun search(query: MetadataQuery): AppResult<List<MetadataCandidate>> {
        calls += "search:${query.title}:${query.year}"
        errors.removeFirstOrNull()?.let { return AppResult.Err(it) }
        return AppResult.Ok(items.filter { it.ref.type == query.type && it.title.contains(query.title.split(' ').first(), ignoreCase = true) && (query.year == null || it.year == query.year) })
    }
    override suspend fun movie(id: String, language: String?): AppResult<MovieMetadata> {
        calls += "movie:$id"
        errors.removeFirstOrNull()?.let { return AppResult.Err(it) }
        val c = items.first { it.ref.id == id }
        return AppResult.Ok(MovieMetadata(c.ref.copy(imdbId = "tt$id"), c.title, overview = "Plot of ${c.title}", posterUrl = c.posterUrl,
            genres = listOf("Action", "Drama"), releaseDate = "${c.year}-05-01", year = c.year, originalLanguage = c.originalLanguage,
            runtimeMinutes = 150, cast = listOf(CastMember("Lead Actor", "Hero")), directors = listOf("Director")))
    }
    override suspend fun series(id: String, language: String?): AppResult<SeriesMetadata> {
        calls += "series:$id"
        val c = items.first { it.ref.id == id }
        return AppResult.Ok(SeriesMetadata(c.ref, c.title, overview = "Series ${c.title}", posterUrl = c.posterUrl, genres = listOf("Animation"), year = c.year))
    }
    override suspend fun season(seriesId: String, seasonNumber: Int, language: String?): AppResult<SeasonMetadata> {
        calls += "season:$seriesId:$seasonNumber"
        val ref = items.first { it.ref.id == seriesId }.ref
        return AppResult.Ok(SeasonMetadata(ref, seasonNumber, "Season $seasonNumber", episodes = (1..20).map {
            EpisodeMetadata(ref, seasonNumber, it, "Episode title $it", "Overview $it", "2006-01-%02d".format(it))
        }))
    }
    override suspend fun episode(seriesId: String, seasonNumber: Int, episodeNumber: Int, language: String?) = AppResult.Err(AppError.NotFound("x"))
    override suspend fun findByImdbId(imdbId: String, type: MetadataType) = AppResult.Err(AppError.NotFound(imdbId))
}

@RunWith(RobolectricTestRunner::class)
class LibraryMetadataServiceTest {

    private val clock = TestClock()
    private val db = YourStreamDatabase.inMemory(ApplicationProvider.getApplicationContext())
    private val provider = CatalogueProvider()
    private fun service(p: MetadataProvider = provider): LibraryMetadataService {
        val manager = MetadataProviderManager(listOf(p), RoomMetadataCache(db), clock, NoOpLogger, defaultLanguage = "en")
        return LibraryMetadataService(db, manager, MetadataMatcher(manager), MetadataSettings("en"), clock, NoOpLogger)
    }
    private val dao = db.metadataDao()
    private val library = db.libraryDao()

    @After fun tearDown() = db.close()

    private suspend fun item(title: String, year: Int?, kind: String = "MOVIE", languages: List<String> = emptyList(), conf: Float = 0.95f): Long {
        val key = title.lowercase()
        val id = library.insertItem(LibraryItemEntity(kind = kind, matchKey = "${kind.lowercase()}:$key:${year ?: ""}", title = title,
            sortTitle = key, normalizedTitle = key, year = year, searchText = key, titleConfidence = conf, addedAt = clock.now,
            updatedAt = clock.now, availableCount = 1, latestAddedAt = clock.now))
        library.insertLanguages(languages.map { LibraryItemLanguageEntity(id, it) })
        return id
    }

    @Test
    fun confidentMatchIsStoredWithMetadata() = runTest {
        provider.add("Movie Name", 2024, MetadataType.MOVIE)
        provider.add("Movie Name", 1990, MetadataType.MOVIE, popularity = 3.0)
        val id = item("Movie Name", 2024)

        val run = service().processPending()
        assertEquals(1, run.accepted)

        val match = dao.match(id)!!
        assertEquals("ACCEPTED", match.status)
        assertEquals("tmdb", match.provider)
        assertEquals("Movie Name-2024", match.externalId)
        assertTrue(match.confidence >= 0.8)
        assertEquals(clock.now, match.matchedAt)

        val meta = dao.itemMetadata(id)!!
        assertEquals("Plot of Movie Name", meta.overview)
        assertEquals("Action,Drama", meta.genres)
        assertEquals("2024-05-01", meta.releaseDate)
        assertEquals("ttMovie Name-2024", meta.imdbId)
        assertEquals(listOf("Lead Actor"), service().decodeCast(meta.cast).map { it.name })
        assertEquals("https://img/Movie Name.jpg", meta.posterUrl)
    }

    @Test
    fun theBriefsDoraemonExampleGetsSeasonAndEpisodeMetadata() = runTest {
        // "Doraemon.S02E15.Tamil.720p" in the library: series Doraemon, season 2, episode 15, Tamil.
        provider.add("Doraemon", 2005, MetadataType.SERIES, language = "ja", popularity = 90.0)
        provider.add("Doraemon", 1979, MetadataType.SERIES, language = "ja", popularity = 5.0)
        val series = item("Doraemon", null, kind = "SERIES", languages = listOf("ta"))
        val season = library.insertSeason(SeasonEntity(seriesId = series, number = 2, title = null))
        val episode = library.insertEpisode(EpisodeEntity(seriesId = series, seasonId = season, seasonNumber = 2, number = 15, numberEnd = null, absoluteNumber = null, title = null, airDate = null))

        service().processPending()

        assertEquals("Doraemon-2005", dao.match(series)!!.externalId)
        assertEquals("Season 2", dao.seasonMetadata(season)!!.name)
        assertEquals("Episode title 15", dao.episodeMetadata(episode)!!.title)
    }

    @Test
    fun uncertainMatchesAreSuggestedButNotApplied() = runTest {
        provider.add("The Thing", 1982, MetadataType.MOVIE, popularity = 20.0)
        provider.add("The Thing", 2011, MetadataType.MOVIE, popularity = 15.0)
        val id = item("The Thing", null)
        val s = service()
        assertEquals(1, s.processPending().needsReview)
        assertEquals("NEEDS_REVIEW", dao.match(id)!!.status)
        assertNull(dao.itemMetadata(id), "nothing applied without the user")
        val review = MetadataRepository(db, s, MetadataProviderManager(listOf(provider), RoomMetadataCache(db), clock, NoOpLogger)).reviewQueue().first()
        assertEquals(listOf(1982, 2011), review.single().candidates.map { it.candidate.year })

        // The user picks 2011.
        val ref = review.single().candidates.first { it.candidate.year == 2011 }.candidate.ref
        assertTrue(s.confirm(id, ref) is AppResult.Ok)
        assertEquals("MANUAL", dao.match(id)!!.status)
        assertEquals(2011, dao.itemMetadata(id)!!.year)
        assertTrue(library.item(id)!!.locked, "user decisions lock the item")
        clock.now += 365L * 24 * 3600 * 1000
        assertEquals(0, s.processPending().processed, "manual matches are never revisited")
    }

    @Test
    fun rejectedItemsStayUnmatched() = runTest {
        provider.add("Leo", 2023, MetadataType.MOVIE)
        val id = item("Leo", 2023)
        val s = service()
        s.processPending()
        s.reject(id)
        assertNull(dao.itemMetadata(id))
        assertEquals("REJECTED", dao.match(id)!!.status)
        clock.now += 365L * 24 * 3600 * 1000
        assertEquals(0, s.processPending().processed)
    }

    @Test
    fun outagesAreRetriedWithBackoffNotRecordedAsNoMatch() = runTest {
        provider.add("Vikram", 2022, MetadataType.MOVIE)
        val id = item("Vikram", 2022)
        repeat(10) { provider.errors += AppError.Network() }
        val s = service()
        assertEquals(1, s.processPending().failed)
        val failed = dao.match(id)!!
        assertEquals("FAILED", failed.status)
        assertEquals(clock.now + 15 * 60_000, failed.nextAttemptAt)
        assertEquals(0, s.processPending().processed, "not before the retry time")

        provider.errors.clear()
        clock.now += 16 * 60_000
        assertEquals(1, s.processPending().accepted)
        assertEquals("ACCEPTED", dao.match(id)!!.status)
    }

    @Test
    fun noMatchIsRetriedMuchLater() = runTest {
        val id = item("Totally Unknown Film", 2020)
        val s = service()
        assertEquals(1, s.processPending().noMatch)
        assertEquals(clock.now + 7L * 24 * 3600 * 1000, dao.match(id)!!.nextAttemptAt)
    }

    @Test
    fun answersAreCachedInTheDatabaseAcrossRestarts() = runTest {
        provider.add("Inception", 2010, MetadataType.MOVIE)
        val a = item("Inception", 2010)
        service().processPending()
        val callsAfterFirst = provider.calls.size
        assertTrue(dao.cacheSize() >= 2)

        // A second item with the same title (e.g. another copy) after an app restart: new
        // manager, same database → no new HTTP calls.
        db.metadataDao().putMatch(dao.match(a)!!.copy(inputSignature = "changed"))
        service().processPending()
        assertEquals(callsAfterFirst, provider.calls.size, "served from the Room cache")
    }

    @Test
    fun editingAnItemTitleTriggersARematch() = runTest {
        provider.add("KGF Chapter 2", 2022, MetadataType.MOVIE, language = "kn")
        val id = item("K G F 2", 2022, languages = listOf("kn"))
        val s = service()
        s.processPending()
        assertTrue(dao.match(id)!!.status != "ACCEPTED")

        library.updateItem(library.item(id)!!.copy(title = "KGF Chapter 2"))
        assertEquals(1, s.processPending().accepted)
        assertEquals("KGF Chapter 2-2022", dao.match(id)!!.externalId)
        assertTrue(library.item(id)!!.searchText.split(" ").containsAll(listOf("kgf", "chapter", "2")), "official title words are searchable")
    }

    @Test
    fun withoutConfiguredProvidersNothingHappens() = runTest {
        provider.isConfigured = false
        item("Anything", 2020)
        assertEquals(0, service().processPending().processed)
        assertTrue(provider.calls.isEmpty())
    }
}
