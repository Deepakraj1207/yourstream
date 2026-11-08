package dev.yourstream.metadata.core

import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import dev.yourstream.core.common.Clock
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

class TestClock(var now: Long = 1_700_000_000_000L) : Clock {
    override fun nowMillis() = now
}

/** Scriptable provider: a catalogue of candidates, queued errors, call log. */
class FakeProvider(
    name: String,
    override var isConfigured: Boolean = true,
    override val capabilities: Set<ProviderCapability> = ProviderCapability.entries.toSet(),
) : MetadataProvider {
    override val id = ProviderId(name)
    override val displayName = name

    val catalogue = mutableListOf<MetadataCandidate>()
    val errors = ArrayDeque<AppError>()
    val calls = mutableListOf<String>()
    /** imdb id → own id */
    val imdbIndex = mutableMapOf<String, String>()

    fun add(title: String, year: Int?, type: MetadataType = MetadataType.MOVIE, id: String = "$title-$year", language: String? = "en",
            popularity: Double? = 10.0, original: String? = null, imdb: String? = null) {
        catalogue += MetadataCandidate(MetadataRef(this.id, type, id, imdb), title, original, emptyList(), year, "about $title", "poster/$id", null, language, popularity)
        imdb?.let { imdbIndex[it] = id }
    }

    private fun fail(): AppError? = errors.removeFirstOrNull()

    override suspend fun search(query: MetadataQuery): AppResult<List<MetadataCandidate>> {
        calls += "search:${query.title}:${query.year}:${query.type}"
        fail()?.let { return AppResult.Err(it) }
        val words = dev.yourstream.metadata.core.match.TitleSimilarity.tokens(query.title).toSet()
        return AppResult.Ok(catalogue.filter { c ->
            (query.type == null || c.ref.type == query.type) &&
                (query.year == null || c.year == null || kotlin.math.abs(c.year!! - query.year!!) <= 1) &&
                dev.yourstream.metadata.core.match.TitleSimilarity.tokens(c.title).any { it in words }
        }.sortedByDescending { it.popularity ?: 0.0 })
    }

    override suspend fun movie(id: String, language: String?): AppResult<MovieMetadata> {
        calls += "movie:$id"
        fail()?.let { return AppResult.Err(it) }
        val c = catalogue.firstOrNull { it.ref.id == id } ?: return AppResult.Err(AppError.NotFound(id))
        return AppResult.Ok(MovieMetadata(c.ref, c.title, overview = c.overview, year = c.year, genres = listOf("Drama")))
    }

    override suspend fun series(id: String, language: String?): AppResult<SeriesMetadata> {
        calls += "series:$id"
        fail()?.let { return AppResult.Err(it) }
        val c = catalogue.firstOrNull { it.ref.id == id } ?: return AppResult.Err(AppError.NotFound(id))
        return AppResult.Ok(SeriesMetadata(c.ref, c.title, year = c.year))
    }

    override suspend fun season(seriesId: String, seasonNumber: Int, language: String?): AppResult<SeasonMetadata> {
        calls += "season:$seriesId:$seasonNumber"
        fail()?.let { return AppResult.Err(it) }
        val ref = catalogue.first { it.ref.id == seriesId }.ref
        return AppResult.Ok(SeasonMetadata(ref, seasonNumber, episodes = (1..3).map { EpisodeMetadata(ref, seasonNumber, it, "Episode $it") }))
    }

    override suspend fun episode(seriesId: String, seasonNumber: Int, episodeNumber: Int, language: String?): AppResult<EpisodeMetadata> {
        calls += "episode:$seriesId:$seasonNumber:$episodeNumber"
        fail()?.let { return AppResult.Err(it) }
        val ref = catalogue.first { it.ref.id == seriesId }.ref
        return AppResult.Ok(EpisodeMetadata(ref, seasonNumber, episodeNumber, "Episode $episodeNumber"))
    }

    override suspend fun findByImdbId(imdbId: String, type: MetadataType): AppResult<MetadataRef> {
        calls += "find:$imdbId"
        fail()?.let { return AppResult.Err(it) }
        val own = imdbIndex[imdbId] ?: return AppResult.Err(AppError.NotFound(imdbId))
        return AppResult.Ok(MetadataRef(id, type, own, imdbId))
    }
}
