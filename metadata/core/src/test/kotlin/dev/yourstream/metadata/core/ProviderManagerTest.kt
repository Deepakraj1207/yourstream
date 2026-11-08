package dev.yourstream.metadata.core

import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import dev.yourstream.core.common.NoOpLogger
import dev.yourstream.metadata.api.MetadataQuery
import dev.yourstream.metadata.api.MetadataRef
import dev.yourstream.metadata.api.MetadataType
import dev.yourstream.metadata.api.ProviderCapability
import dev.yourstream.metadata.api.ProviderId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProviderManagerTest {

    private val clock = TestClock()
    private val cache = InMemoryMetadataCache()
    private val primary = FakeProvider("tmdb").apply { add("Inception", 2010, imdb = "tt1375666") }
    private val secondary = FakeProvider("cinemeta").apply { add("Inception", 2010, id = "tt1375666", imdb = "tt1375666") }
    private val manager = MetadataProviderManager(listOf(primary, secondary), cache, clock, NoOpLogger)
    private val q = MetadataQuery("Inception", 2010, MetadataType.MOVIE)

    private fun <T> AppResult<T>.ok(): T = (this as AppResult.Ok).value

    @Test
    fun firstProviderAnswers() = runTest {
        val r = manager.search(q).ok()
        assertEquals("tmdb", r.provider.value)
        assertTrue(secondary.calls.isEmpty())
    }

    @Test
    fun failuresFallBackToTheNextProvider() = runTest {
        primary.errors += AppError.Network()
        assertEquals("cinemeta", manager.search(q).ok().provider.value)
    }

    @Test
    fun aProviderThatThrowsIsAnErrorNotACrash() = runTest {
        primary.throwNext = NullPointerException("field 'results' missing")
        assertEquals("cinemeta", manager.search(q).ok().provider.value, "falls back")
        assertTrue(manager.status().first { it.provider.value == "tmdb" }.lastError!!.contains("NullPointerException"))
    }

    @Test
    fun emptyResultsFallBackToo() = runTest {
        primary.catalogue.clear()
        assertEquals("cinemeta", manager.search(q).ok().provider.value)
    }

    @Test
    fun unconfiguredOrIncapableProvidersAreSkipped() = runTest {
        primary.isConfigured = false
        assertEquals("cinemeta", manager.search(q).ok().provider.value)
        assertTrue(primary.calls.isEmpty())
        val noSeries = FakeProvider("x", capabilities = setOf(ProviderCapability.SEARCH_MOVIES))
        assertEquals(emptyList(), MetadataProviderManager(listOf(noSeries), cache, clock, NoOpLogger).searchProviders(MetadataType.SERIES))
    }

    @Test
    fun answersAreCachedIncludingNotFound() = runTest {
        manager.search(q); manager.search(q)
        assertEquals(1, primary.calls.count { it.startsWith("search") }, "second search from cache")
        assertTrue(manager.search(q).ok().fromCache)

        val ref = MetadataRef(ProviderId("tmdb"), MetadataType.MOVIE, "missing")
        assertIs<AppError.NotFound>((manager.movie(ref) as AppResult.Err).error)
        assertIs<AppError.NotFound>((manager.movie(ref) as AppResult.Err).error)
        assertEquals(1, primary.calls.count { it == "movie:missing" }, "negative answers are cached too")
    }

    @Test
    fun expiredEntriesAreRefetched() = runTest {
        manager.search(q)
        clock.now += CachePolicy().searchTtlMillis + 1
        manager.search(q)
        assertEquals(2, primary.calls.count { it.startsWith("search") })
    }

    @Test
    fun staleCacheIsServedWhenEveryoneFails() = runTest {
        val ref = MetadataRef(ProviderId("tmdb"), MetadataType.MOVIE, "Inception-2010")
        manager.movie(ref)
        clock.now += CachePolicy().detailsTtlMillis + 1
        primary.errors += AppError.Remote(503, "Service Unavailable")
        val stale = manager.movie(ref).ok()
        assertEquals("Inception", stale.value.title)
        assertTrue(stale.fromCache)
    }

    @Test
    fun rateLimitedProviderCoolsDownAndIsSkipped() = runTest {
        primary.errors += AppError.RateLimited(30)
        assertEquals("cinemeta", manager.search(q).ok().provider.value)
        // During the cool-down tmdb is not even called.
        val calls = primary.calls.size
        manager.search(q.copy(title = "Inception 2"))
        assertEquals(calls, primary.calls.size)
        assertEquals(ProviderState.COOLING_DOWN, manager.health.status(ProviderId("tmdb")).state)
        clock.now += 31_000
        manager.search(q.copy(title = "Inception 3"))
        assertTrue(primary.calls.size > calls, "back after Retry-After")
    }

    @Test
    fun repeatedFailuresOpenTheCircuit() = runTest {
        repeat(3) { i ->
            primary.errors += AppError.Timeout("search")
            manager.search(q.copy(title = "Film $i"))
        }
        assertEquals(ProviderState.CIRCUIT_OPEN, manager.health.status(ProviderId("tmdb")).state)
        val before = primary.calls.size
        manager.search(q.copy(title = "Another"))
        assertEquals(before, primary.calls.size)
        clock.now += 31_000
        manager.search(q.copy(title = "Yet another"))
        assertEquals(ProviderState.AVAILABLE, manager.health.status(ProviderId("tmdb")).state)
    }

    @Test
    fun badKeyDisablesTheProvider() = runTest {
        primary.errors += AppError.NotConfigured("401")
        assertEquals("cinemeta", manager.search(q).ok().provider.value)
        assertEquals(ProviderState.DISABLED, manager.health.status(ProviderId("tmdb")).state)
    }

    @Test
    fun detailsFallBackThroughTheImdbId() = runTest {
        val ref = MetadataRef(ProviderId("tmdb"), MetadataType.MOVIE, "Inception-2010", imdbId = "tt1375666")
        primary.errors += AppError.Remote(502, "Bad Gateway")
        val r = manager.movie(ref).ok()
        assertEquals("cinemeta", r.provider.value)
        assertEquals("tt1375666", r.value.ref.id)
        assertTrue("find:tt1375666" in secondary.calls)
    }

    @Test
    fun identicalConcurrentRequestsShareOneCall() = runTest {
        val results = (1..20).map { async { manager.search(q) } }.awaitAll()
        assertTrue(results.all { it is AppResult.Ok })
        assertEquals(1, primary.calls.count { it.startsWith("search") })
    }

    @Test
    fun allFailingReturnsTheError() = runTest {
        primary.errors += AppError.Network(); secondary.errors += AppError.Network()
        assertIs<AppError.Network>((manager.search(q) as AppResult.Err).error)
    }
}
