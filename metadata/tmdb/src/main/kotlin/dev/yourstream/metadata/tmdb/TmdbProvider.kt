package dev.yourstream.metadata.tmdb

import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import dev.yourstream.core.common.Clock
import dev.yourstream.core.common.map
import dev.yourstream.core.network.RateLimiter
import dev.yourstream.core.network.getJson
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
import dev.yourstream.metadata.api.SeasonSummary
import dev.yourstream.metadata.api.SeriesMetadata
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import kotlinx.serialization.KSerializer

/** TMDB credentials. Supplied at build time (see docs/METADATA.md); never committed. */
data class TmdbConfig(
    /** v3 API key (sent as ?api_key=). */
    val apiKey: String = "",
    /** v4 read access token (sent as Authorization: Bearer). Preferred when both are set. */
    val readToken: String = "",
    val baseUrl: String = "https://api.themoviedb.org/3",
    val imageBaseUrl: String = "https://image.tmdb.org/t/p",
) {
    val isConfigured: Boolean get() = apiKey.isNotBlank() || readToken.isNotBlank()
    override fun toString() = "TmdbConfig(apiKey=${if (apiKey.isBlank()) "missing" else "set"}, readToken=${if (readToken.isBlank()) "missing" else "set"})"
}

/**
 * themoviedb.org v3. Movies and series, seasons, episodes, images, cast, IMDb cross-ids.
 * Rate: TMDB allows ~50 requests/s; we stay at 20/s and honour 429 Retry-After (via the manager).
 */
class TmdbProvider(
    private val client: HttpClient,
    private val config: TmdbConfig,
    clock: Clock = Clock.System,
) : MetadataProvider {

    override val id = ID
    override val displayName = "TMDB"
    override val capabilities = ProviderCapability.entries.toSet()
    override val isConfigured: Boolean get() = config.isConfigured

    private val limiter = RateLimiter(permits = 20, perMillis = 1_000, clock = clock)

    private suspend fun <T> get(path: String, serializer: KSerializer<T>, block: HttpRequestBuilder.() -> Unit = {}): AppResult<T> {
        if (!isConfigured) return AppResult.Err(AppError.NotConfigured("TMDB API key missing"))
        limiter.acquire()
        return client.getJson(config.baseUrl + path, serializer) {
            if (config.readToken.isNotBlank()) header("Authorization", "Bearer ${config.readToken}")
            else parameter("api_key", config.apiKey)
            block()
        }
    }

    override suspend fun search(query: MetadataQuery): AppResult<List<MetadataCandidate>> {
        val types = query.type?.let { listOf(it) } ?: listOf(MetadataType.MOVIE, MetadataType.SERIES)
        val all = mutableListOf<MetadataCandidate>()
        for (type in types) {
            val path = if (type == MetadataType.MOVIE) "/search/movie" else "/search/tv"
            val page = get(path, TmdbPage.serializer(TmdbSearchItem.serializer())) {
                parameter("query", query.title)
                parameter("include_adult", false)
                query.language?.let { parameter("language", it) }
                query.year?.let { parameter(if (type == MetadataType.MOVIE) "year" else "first_air_date_year", it) }
            }
            when (page) {
                is AppResult.Ok -> all += page.value.results.take(MAX_RESULTS).map { it.toCandidate(type) }
                is AppResult.Err -> return page
            }
        }
        return AppResult.Ok(all)
    }

    override suspend fun movie(id: String, language: String?): AppResult<MovieMetadata> =
        get("/movie/$id", TmdbMovie.serializer()) {
            parameter("append_to_response", "credits")
            language?.let { parameter("language", it) }
        }.map { m ->
            MovieMetadata(
                ref = MetadataRef(ID, MetadataType.MOVIE, m.id.toString(), m.imdbId?.ifBlank { null }),
                title = m.title,
                originalTitle = m.originalTitle,
                overview = m.overview?.ifBlank { null },
                posterUrl = image(m.posterPath, POSTER),
                backdropUrl = image(m.backdropPath, BACKDROP),
                genres = m.genres.map { it.name },
                releaseDate = m.releaseDate?.ifBlank { null },
                year = year(m.releaseDate),
                originalLanguage = m.originalLanguage,
                spokenLanguages = m.spokenLanguages.map { it.code },
                runtimeMinutes = m.runtime?.takeIf { it > 0 },
                rating = m.voteAverage,
                cast = cast(m.credits),
                directors = m.credits?.crew.orEmpty().filter { it.job == "Director" }.map { it.name },
            )
        }

    override suspend fun series(id: String, language: String?): AppResult<SeriesMetadata> =
        get("/tv/$id", TmdbTv.serializer()) {
            parameter("append_to_response", "credits,external_ids")
            language?.let { parameter("language", it) }
        }.map { t ->
            SeriesMetadata(
                ref = MetadataRef(ID, MetadataType.SERIES, t.id.toString(), t.externalIds?.imdbId?.ifBlank { null }),
                title = t.name,
                originalTitle = t.originalName,
                overview = t.overview?.ifBlank { null },
                posterUrl = image(t.posterPath, POSTER),
                backdropUrl = image(t.backdropPath, BACKDROP),
                genres = t.genres.map { it.name },
                firstAirDate = t.firstAirDate?.ifBlank { null },
                year = year(t.firstAirDate),
                originalLanguage = t.originalLanguage,
                rating = t.voteAverage,
                status = t.status,
                cast = cast(t.credits),
                seasons = t.seasons.map { SeasonSummary(it.seasonNumber, it.name, it.episodeCount, it.airDate, image(it.posterPath, POSTER)) },
            )
        }

    override suspend fun season(seriesId: String, seasonNumber: Int, language: String?): AppResult<SeasonMetadata> =
        get("/tv/$seriesId/season/$seasonNumber", TmdbSeason.serializer()) { language?.let { parameter("language", it) } }.map { s ->
            val ref = MetadataRef(ID, MetadataType.SERIES, seriesId)
            SeasonMetadata(ref, s.seasonNumber, s.name, s.overview?.ifBlank { null }, image(s.posterPath, POSTER), s.airDate, s.episodes.map { it.toModel(ref) })
        }

    override suspend fun episode(seriesId: String, seasonNumber: Int, episodeNumber: Int, language: String?): AppResult<EpisodeMetadata> =
        get("/tv/$seriesId/season/$seasonNumber/episode/$episodeNumber", TmdbEpisode.serializer()) { language?.let { parameter("language", it) } }
            .map { it.toModel(MetadataRef(ID, MetadataType.SERIES, seriesId)) }

    override suspend fun findByImdbId(imdbId: String, type: MetadataType): AppResult<MetadataRef> =
        when (val r = get("/find/$imdbId", TmdbFind.serializer()) { parameter("external_source", "imdb_id") }) {
            is AppResult.Err -> r
            is AppResult.Ok -> {
                val hit = if (type == MetadataType.MOVIE) r.value.movies.firstOrNull() else r.value.tv.firstOrNull()
                hit?.let { AppResult.Ok(MetadataRef(ID, type, it.id.toString(), imdbId)) } ?: AppResult.Err(AppError.NotFound(imdbId))
            }
        }

    private fun TmdbSearchItem.toCandidate(type: MetadataType) = MetadataCandidate(
        ref = MetadataRef(ID, type, id.toString()),
        title = title ?: name ?: "",
        originalTitle = originalTitle ?: originalName,
        year = year(releaseDate ?: firstAirDate),
        overview = overview?.ifBlank { null },
        posterUrl = image(posterPath, POSTER),
        backdropUrl = image(backdropPath, BACKDROP),
        originalLanguage = originalLanguage,
        popularity = popularity,
        rating = voteAverage,
    )

    private fun TmdbEpisode.toModel(ref: MetadataRef) = EpisodeMetadata(
        ref, seasonNumber, episodeNumber, name, overview?.ifBlank { null }, airDate, image(stillPath, STILL), runtime, voteAverage,
    )

    private fun cast(credits: TmdbCredits?) = credits?.cast.orEmpty().sortedBy { it.order }.take(MAX_CAST)
        .map { CastMember(it.name, it.character?.ifBlank { null }, image(it.profilePath, PROFILE), it.order) }

    private fun image(path: String?, size: String) = path?.takeIf { it.isNotBlank() }?.let { "${config.imageBaseUrl}/$size$it" }

    private fun year(date: String?) = date?.take(4)?.toIntOrNull()

    companion object {
        val ID = ProviderId("tmdb")
        private const val MAX_RESULTS = 10
        private const val MAX_CAST = 15
        /** w342 is sharp on phone/TV cards and a third of w500's size: faster lists. */
        private const val POSTER = "w342"
        private const val BACKDROP = "w1280"
        private const val STILL = "w300"
        private const val PROFILE = "w185"
    }
}
