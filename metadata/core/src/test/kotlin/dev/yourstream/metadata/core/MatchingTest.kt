package dev.yourstream.metadata.core

import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.NoOpLogger
import dev.yourstream.metadata.api.MetadataType
import dev.yourstream.metadata.core.match.MatchDecision
import dev.yourstream.metadata.core.match.MatchInput
import dev.yourstream.metadata.core.match.MetadataMatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatchingTest {

    private val clock = TestClock()
    private val tmdb = FakeProvider("tmdb")
    private val cinemeta = FakeProvider("cinemeta")
    private val manager = MetadataProviderManager(listOf(tmdb, cinemeta), InMemoryMetadataCache(), clock, NoOpLogger)
    private val matcher = MetadataMatcher(manager)

    private suspend fun match(title: String, year: Int? = null, type: MetadataType = MetadataType.MOVIE, langs: List<String> = emptyList(), conf: Float = 0.95f, alts: List<String> = emptyList()) =
        matcher.match(MatchInput(listOf(title) + alts, year, type, langs, conf))

    @Test
    fun exactTitleAndYearIsAccepted() = runTest {
        tmdb.add("Movie Name", 2024)
        tmdb.add("Movie Name", 1998, popularity = 2.0)
        val m = match("Movie Name", 2024)
        assertEquals(MatchDecision.ACCEPTED, m.decision)
        assertEquals(2024, m.best!!.candidate.year)
        assertTrue(m.best!!.confidence >= 0.9, "${m.best}")
    }

    @Test
    fun theBriefsExampleDoraemonSeries() = runTest {
        // "Doraemon.S02E15.Tamil.720p" → title Doraemon, series, Tamil.
        tmdb.add("Doraemon", 2005, MetadataType.SERIES, language = "ja", popularity = 80.0)
        tmdb.add("Doraemon", 1979, MetadataType.SERIES, language = "ja", popularity = 9.0)
        tmdb.add("Doraemon: Nobita's Dinosaur", 2006, MetadataType.MOVIE, language = "ja")
        val m = match("Doraemon", type = MetadataType.SERIES, langs = listOf("ta"))
        assertEquals(MatchDecision.ACCEPTED, m.decision, "${m.candidates}")
        assertEquals("Doraemon-2005", m.best!!.candidate.ref.id, "the far more popular of two same-named series")
    }

    @Test
    fun twoEquallyPlausibleAnswersGoToReview() = runTest {
        // Same title, no year, similar popularity: we cannot know which one the file is.
        tmdb.add("The Thing", 1982, popularity = 20.0)
        tmdb.add("The Thing", 2011, popularity = 15.0)
        val m = match("The Thing")
        assertEquals(MatchDecision.NEEDS_REVIEW, m.decision)
        assertTrue(m.candidates.all { it.breakdown.ambiguityPenalty > 0 }, "both rivals penalised, order kept")
        assertEquals(1982, m.best!!.candidate.year)
        assertEquals(2, m.candidates.size)
    }

    @Test
    fun theYearDisambiguates() = runTest {
        tmdb.add("The Thing", 1982, popularity = 20.0)
        tmdb.add("The Thing", 2011, popularity = 15.0)
        val m = match("The Thing", 2011)
        assertEquals(MatchDecision.ACCEPTED, m.decision)
        assertEquals(2011, m.best!!.candidate.year)
    }

    @Test
    fun lowConfidenceIsNeverAccepted() = runTest {
        tmdb.add("Leo", 2023)
        val wrongYear = match("Leo", 2015)
        assertTrue(wrongYear.decision != MatchDecision.ACCEPTED, "year 8 years off: ${wrongYear.best}")
        tmdb.add("Doraemon: The Movie", 2020)
        val partial = match("Doraemon Stand by Me", 2020)
        assertTrue(partial.decision != MatchDecision.ACCEPTED, "${partial.best}")
    }

    @Test
    fun unreliableParsesNeedStrongerEvidence() = runTest {
        tmdb.add("Ben 10", 2005, MetadataType.SERIES, popularity = 50.0)
        val sure = match("Ben 10", type = MetadataType.SERIES, conf = 0.95f)
        val unsure = match("Ben 10", type = MetadataType.SERIES, conf = 0.3f)
        assertTrue(unsure.best!!.confidence < sure.best!!.confidence)
    }

    @Test
    fun originalLanguageBreaksTies() = runTest {
        tmdb.add("Drishyam", 2013, language = "ml", popularity = 10.0)
        tmdb.add("Drishyam", 2015, language = "hi", popularity = 10.0)
        val hindi = match("Drishyam", langs = listOf("hi"))
        assertEquals(2015, hindi.best!!.candidate.year, "the Hindi remake ranks first for a Hindi file")
        assertEquals(MatchDecision.NEEDS_REVIEW, hindi.decision, "…but a remake pair without a year is still for the user to confirm")
    }

    @Test
    fun alternativeTitlesAreTried() = runTest {
        tmdb.add("Panda Kung Fu", 2008)
        val m = match("Kung Fu Bear", 2008, alts = listOf("Panda Kung Fu"))
        assertEquals(MatchDecision.ACCEPTED, m.decision)
    }

    @Test
    fun aWeakFirstProviderFallsBackToTheNext() = runTest {
        tmdb.add("Some Other Film", 2020)
        cinemeta.add("Local Indie Film", 2020)
        val m = match("Local Indie Film", 2020)
        assertEquals(MatchDecision.ACCEPTED, m.decision)
        assertEquals("cinemeta", m.best!!.candidate.ref.provider.value)
    }

    @Test
    fun nothingFoundIsNoMatch() = runTest {
        val m = match("Completely Unknown Title", 2020)
        assertEquals(MatchDecision.NO_MATCH, m.decision)
        assertNull(m.best)
    }

    @Test
    fun providerOutageIsFailedNotNoMatch() = runTest {
        repeat(10) { tmdb.errors += AppError.Network(); cinemeta.errors += AppError.Timeout("search") }
        val m = match("Movie Name", 2024)
        assertEquals(MatchDecision.FAILED, m.decision, "do not record 'no match' because the network was down")
        assertTrue(m.error != null)
    }
}
