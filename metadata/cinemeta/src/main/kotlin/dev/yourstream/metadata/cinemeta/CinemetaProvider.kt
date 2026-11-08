package dev.yourstream.metadata.cinemeta

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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URLEncoder

@Serializable private data class CatalogResponse(val metas: List<CinemetaMeta> = emptyList())
@Serializable private data class MetaResponse(val meta: CinemetaMeta? = null)

@Serializable
private data class CinemetaVideo(
    val id: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val number: Int? = null,
    val name: String? = null,
    val title: String? = null,
    val overview: String? = null,
    val description: String? = null,
    val released: String? = null,
    @SerialName("firstAired") val firstAired: String? = null,
    val thumbnail: String? = null,
)

@Serializable
private data class CinemetaMeta(
    val id: String,
    val type: String? = null,
    val name: String = "",
    val description: String? = null,
    val releaseInfo: String? = null,
    val released: String? = null,
    val year: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val genres: List<String> = emptyList(),
    val genre: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val director: List<String> = emptyList(),
    val runtime: String? = null,
    val imdbRating: String? = null,
    val language: String? = null,
    val country: String? = null,
    val popularity: Double? = null,
    val videos: List<CinemetaVideo> = emptyList(),
)

/**
 * Cinemeta, the Stremio metadata add-on (IMDb-based, no key). Used as a fallback (and as a
 * provider of its own when no TMDB key is configured). Catalog and meta resources only; this
 * app never uses add-on stream resources.
 */
class CinemetaProvider(
    private val client: HttpClient,
    private val baseUrl: String = "https://v3-cinemeta.strem.io",
    clock: Clock = Clock.System,
) : MetadataProvider {

    override val id = ID
    override val displayName = "Cinemeta"
    override val isConfigured = true
    override val capabilities = setOf(
        ProviderCapability.SEARCH_MOVIES, ProviderCapability.SEARCH_SERIES, ProviderCapability.MOVIE_DETAILS,
        ProviderCapability.SERIES_DETAILS, ProviderCapability.SEASON_DETAILS, ProviderCapability.EPISODE_DETAILS,
        ProviderCapability.FIND_BY_IMDB_ID, ProviderCapability.CAST, ProviderCapability.IMAGES,
    )

    /** No published limit: be gentle. */
    private val limiter = RateLimiter(permits = 5, perMillis = 1_000, clock = clock)

    override suspend fun search(query: MetadataQuery): AppResult<List<MetadataCandidate>> {
        val types = query.type?.let { listOf(it) } ?: listOf(MetadataType.MOVIE, MetadataType.SERIES)
        val all = mutableListOf<MetadataCandidate>()
        for (type in types) {
            limiter.acquire()
            val text = URLEncoder.encode(query.title, "UTF-8").replace("+", "%20")
            when (val r = client.getJson("$baseUrl/catalog/${typePath(type)}/top/search=$text.json", CatalogResponse.serializer())) {
                is AppResult.Ok -> all += r.value.metas.take(10).mapIndexed { i, m ->
                    MetadataCandidate(
                        ref = MetadataRef(ID, type, m.id, m.id.takeIf { it.startsWith("tt") }),
                        title = m.name, year = year(m), overview = m.description, posterUrl = m.poster, backdropUrl = m.background,
                        // Cinemeta has no popularity; keep its own ranking as a weak signal.
                        popularity = m.popularity ?: (10.0 - i), rating = m.imdbRating?.toDoubleOrNull(),
                    )
                }
                is AppResult.Err -> return r
            }
        }
        // Cinemeta search ignores years; filter loosely so title+year queries stay precise.
        val filtered = query.year?.let { y -> all.filter { it.year == null || kotlin.math.abs(it.year!! - y) <= 1 } } ?: all
        return AppResult.Ok(filtered)
    }

    private suspend fun meta(type: MetadataType, id: String): AppResult<CinemetaMeta> {
        limiter.acquire()
        return when (val r = client.getJson("$baseUrl/meta/${typePath(type)}/$id.json", MetaResponse.serializer())) {
            is AppResult.Ok -> r.value.meta?.let { AppResult.Ok(it) } ?: AppResult.Err(AppError.NotFound(id))
            is AppResult.Err -> r
        }
    }

    override suspend fun movie(id: String, language: String?): AppResult<MovieMetadata> = meta(MetadataType.MOVIE, id).map { m ->
        MovieMetadata(
            ref = MetadataRef(ID, MetadataType.MOVIE, m.id, m.id.takeIf { it.startsWith("tt") }),
            title = m.name, overview = m.description, posterUrl = m.poster, backdropUrl = m.background,
            genres = (m.genres + m.genre).distinct(), releaseDate = m.released?.take(10), year = year(m),
            runtimeMinutes = m.runtime?.filter(Char::isDigit)?.toIntOrNull(), rating = m.imdbRating?.toDoubleOrNull(),
            cast = m.cast.mapIndexed { i, name -> CastMember(name, order = i) }, directors = m.director,
        )
    }

    override suspend fun series(id: String, language: String?): AppResult<SeriesMetadata> = meta(MetadataType.SERIES, id).map { m ->
        val seasons = m.videos.mapNotNull { it.season }.filter { it >= 0 }.groupingBy { it }.eachCount()
        SeriesMetadata(
            ref = MetadataRef(ID, MetadataType.SERIES, m.id, m.id.takeIf { it.startsWith("tt") }),
            title = m.name, overview = m.description, posterUrl = m.poster, backdropUrl = m.background,
            genres = (m.genres + m.genre).distinct(), firstAirDate = m.released?.take(10), year = year(m),
            rating = m.imdbRating?.toDoubleOrNull(), cast = m.cast.mapIndexed { i, name -> CastMember(name, order = i) },
            seasons = seasons.toSortedMap().map { (n, count) -> SeasonSummary(n, "Season $n", count) },
        )
    }

    /** Cinemeta has no season endpoint: seasons and episodes come from the series' video list. */
    override suspend fun season(seriesId: String, seasonNumber: Int, language: String?): AppResult<SeasonMetadata> =
        meta(MetadataType.SERIES, seriesId).map { m ->
            val ref = MetadataRef(ID, MetadataType.SERIES, m.id, m.id.takeIf { it.startsWith("tt") })
            val episodes = m.videos.filter { it.season == seasonNumber }.sortedBy { it.episode ?: it.number ?: 0 }.map { it.toEpisode(ref) }
            SeasonMetadata(ref, seasonNumber, "Season $seasonNumber", episodes = episodes)
        }.let { r -> if (r is AppResult.Ok && r.value.episodes.isEmpty()) AppResult.Err(AppError.NotFound("season $seasonNumber")) else r }

    override suspend fun episode(seriesId: String, seasonNumber: Int, episodeNumber: Int, language: String?): AppResult<EpisodeMetadata> =
        when (val s = season(seriesId, seasonNumber, language)) {
            is AppResult.Err -> s
            is AppResult.Ok -> s.value.episodes.firstOrNull { it.episodeNumber == episodeNumber }?.let { AppResult.Ok(it) }
                ?: AppResult.Err(AppError.NotFound("episode $seasonNumber/$episodeNumber"))
        }

    /** Cinemeta ids are IMDb ids. */
    override suspend fun findByImdbId(imdbId: String, type: MetadataType): AppResult<MetadataRef> =
        AppResult.Ok(MetadataRef(ID, type, imdbId, imdbId))

    private fun CinemetaVideo.toEpisode(ref: MetadataRef) = EpisodeMetadata(
        ref, season ?: 0, episode ?: number ?: 0, name ?: title, overview ?: description, (released ?: firstAired)?.take(10), thumbnail,
    )

    private fun typePath(type: MetadataType) = if (type == MetadataType.MOVIE) "movie" else "series"

    private fun year(m: CinemetaMeta): Int? =
        (m.year ?: m.releaseInfo ?: m.released)?.take(4)?.toIntOrNull()

    companion object {
        val ID = ProviderId("cinemeta")
    }
}
