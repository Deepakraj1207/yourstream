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

/** TMDB credentials: built in (CI secret) or entered by the user in Settings → TMDB. */
data class TmdbConfig(
    /** v3 API key (sent as ?api_key=). */
    val apiKey: String = "",
    /** v4 read access token (sent as Authorization: Bearer). Preferred when both are set. */
    val readToken: String = "",
    val baseUrl: String = "https://api.themoviedb.org/3",
    val imageBaseUrl: String = "https://image.tmdb.org/t/p",
) {
    val isConfigured: Boolean get() = apiKey.isNotBlank() || readToken.isNotBlank()

    /** The same settings with [key] as the credential: a v4 read token ("eyJ…") or a v3 API key. */
    fun withKey(key: String): TmdbConfig = key.trim().let { k -> if (isReadToken(k)) copy(apiKey = "", readToken = k) else copy(apiKey = k, readToken = "") }

    companion object {
        /** v4 read access tokens are JWTs; v3 API keys are 32 hex characters. */
        fun isReadToken(key: String) = key.startsWith("eyJ") && key.count { it == '.' } == 2

        /** Plausible key format (pure; the TMDB check decides). */
        fun looksValid(key: String): Boolean = key.trim().let { k -> isReadToken(k) || Regex("[0-9a-fA-F]{32}").matches(k) }
    }
    override fun toString() = "TmdbConfig(apiKey=${if (apiKey.isBlank()) "missing" else "set"}, readToken=${if (readToken.isBlank()) "missing" else "set"})"
}

/**
 * themoviedb.org v3. Movies and series, seasons, episodes, images, cast, IMDb cross-ids.
 * Rate: TMDB allows ~50 requests/s; we stay at 20/s and honour 429 Retry-After (via the manager).
 */
class TmdbProvider(
    private val client: HttpClient,
    /** Read on every request, so a key entered in Settings applies at once. */
    private val configSource: () -> TmdbConfig,
    clock: Clock = Clock.System,
) : MetadataProvider, dev.yourstream.metadata.api.TitleDetailsProvider {
    constructor(client: HttpClient, config: TmdbConfig, clock: Clock = Clock.System) : this(client, { config }, clock)

    private val config: TmdbConfig get() = configSource()

    /** Asks TMDB whether [candidate]'s key is accepted (GET /authentication). */
    suspend fun check(candidate: TmdbConfig): AppResult<Unit> =
        when (val r = withHosts(candidate) { base -> client.getJson(base + "/authentication", kotlinx.serialization.json.JsonElement.serializer()) { auth(candidate) } }) {
            is AppResult.Ok -> AppResult.Ok(Unit)
            is AppResult.Err -> r
        }

    /** The base that answered last: requests go there first. */
    @Volatile private var activeBase: String? = null

    /**
     * TMDB answers on two domains. Some networks block api.themoviedb.org by name; on network
     * trouble the request is repeated on api.tmdb.org (and the one that worked is used first).
     */
    private suspend fun <T> withHosts(c: TmdbConfig, call: suspend (String) -> AppResult<T>): AppResult<T> {
        val bases = listOfNotNull(activeBase, c.baseUrl, ALT_BASE_URL).distinct()
        var last: AppResult<T>? = null
        for (base in bases) {
            val r = call(base)
            if (r is AppResult.Ok) { activeBase = base; return r }
            last = r
            val e = (r as AppResult.Err).error
            if (!(e is AppError.Network || e is AppError.Timeout || e is AppError.Unknown || (e is AppError.Remote && e.isServerError))) return r
        }
        return last!!
    }

    private fun HttpRequestBuilder.auth(c: TmdbConfig) {
        if (c.readToken.isNotBlank()) header("Authorization", "Bearer ${c.readToken}") else parameter("api_key", c.apiKey)
    }

    override val id = ID
    override val displayName = "TMDB"
    override val capabilities = ProviderCapability.entries.toSet()
    override val isConfigured: Boolean get() = config.isConfigured

    private val limiter = RateLimiter(permits = 20, perMillis = 1_000, clock = clock)

    private suspend fun <T> get(path: String, serializer: KSerializer<T>, block: HttpRequestBuilder.() -> Unit = {}): AppResult<T> {
        if (!isConfigured) return AppResult.Err(AppError.NotConfigured("TMDB API key missing"))
        limiter.acquire()
        val c = config
        return withHosts(c) { base ->
            client.getJson(base + path, serializer) {
                auth(c)
                block()
            }
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

    // ------------------------------------------------------------------ title pages

    /** Recent title pages, so going back and forth does not ask TMDB again. */
    private val detailsCache = object : LinkedHashMap<String, dev.yourstream.metadata.api.TitleDetails>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, dev.yourstream.metadata.api.TitleDetails>?) = size > DETAILS_CACHE
    }

    /** IMDb id → TMDB id, so a title seen before (the hero carousel, a revisit) needs no lookup. */
    private val tmdbIds = java.util.concurrent.ConcurrentHashMap<String, String>()

    override suspend fun details(type: MetadataType, imdbId: String?, tmdbId: String?, language: String?, region: String?): AppResult<dev.yourstream.metadata.api.TitleDetails> {
        val id = tmdbId?.removePrefix("tmdb:")?.takeIf { it.isNotBlank() }
            ?: imdbId?.let { imdb -> tmdbIds["$type:$imdb"] }
            ?: imdbId?.let { imdb -> when (val r = findByImdbId(imdb, type)) { is AppResult.Ok -> r.value.id.also { tmdbIds["$type:$imdb"] = it }; is AppResult.Err -> return r } }
            ?: return AppResult.Err(AppError.NotFound("no id"))
        val key = "$type:$id:$language:$region"
        synchronized(detailsCache) { detailsCache[key] }?.let { return AppResult.Ok(it) }
        val movie = type == MetadataType.MOVIE
        val lang = language?.substringBefore('-')
        return get(if (movie) "/movie/$id" else "/tv/$id", TmdbDetails.serializer()) {
            parameter("append_to_response", if (movie) "credits,videos,images,recommendations,similar,release_dates,external_ids" else "credits,videos,images,recommendations,similar,content_ratings,external_ids")
            language?.let { parameter("language", it) }
            // Logos and videos in the user's language, English, and without text.
            parameter("include_image_language", listOfNotNull(lang, "en", "null").distinct().joinToString(","))
            parameter("include_video_language", listOfNotNull(lang, "en", "null").distinct().joinToString(","))
        }.map { d -> toDetails(d, type, imdbId, lang, region).also { synchronized(detailsCache) { detailsCache[key] = it } } }
    }

    internal fun toDetails(d: TmdbDetails, type: MetadataType, imdbId: String?, lang: String?, region: String?): dev.yourstream.metadata.api.TitleDetails {
        val movie = type == MetadataType.MOVIE
        val date = (if (movie) d.releaseDate else d.firstAirDate)?.ifBlank { null }
        val crew = d.credits?.crew.orEmpty()
        val certification = if (movie) {
            val byCountry = d.releaseDates?.results.orEmpty().associate { c -> c.country to c.dates.map { it.certification.trim() }.firstOrNull { it.isNotEmpty() } }
            listOfNotNull(region, "US").firstNotNullOfOrNull { byCountry[it] } ?: byCountry.values.firstOrNull { it != null }
        } else {
            val byCountry = d.contentRatings?.results.orEmpty().associate { it.country to it.rating.trim().ifEmpty { null } }
            listOfNotNull(region, "US").firstNotNullOfOrNull { byCountry[it] } ?: byCountry.values.firstOrNull { it != null }
        }
        val logo = d.images?.logos.orEmpty().filter { !it.filePath.endsWith(".svg") }
            .sortedWith(compareBy<TmdbImage> { when (it.language) { lang -> 0; "en" -> 1; null -> 2; else -> 3 } }.thenByDescending { it.voteAverage })
            .firstOrNull()
        val videos = d.videos?.results.orEmpty().filter { it.site.equals("YouTube", true) && it.key.isNotBlank() }
            .sortedWith(compareBy<TmdbVideo> { VIDEO_ORDER.indexOf(it.type).let { i -> if (i < 0) VIDEO_ORDER.size else i } }.thenByDescending { it.official }.thenByDescending { it.publishedAt })
            .map { dev.yourstream.metadata.api.TitleDetails.Video(it.name, it.key, it.type, year(it.publishedAt), it.official) }
        val similar = (d.recommendations?.results.orEmpty().ifEmpty { d.similar?.results.orEmpty() }).take(MAX_SIMILAR).map { r ->
            dev.yourstream.metadata.api.TitleDetails.Recommendation(r.id.toString(), type, r.title ?: r.name ?: "", year(r.releaseDate ?: r.firstAirDate), image(r.posterPath, POSTER))
        }.filter { it.title.isNotBlank() }
        return dev.yourstream.metadata.api.TitleDetails(
            tmdbId = d.id.toString(), type = type,
            imdbId = (d.imdbId ?: d.externalIds?.imdbId)?.ifBlank { null } ?: imdbId,
            title = d.title ?: d.name ?: "",
            overview = d.overview?.ifBlank { null },
            genres = d.genres.map { it.name },
            year = year(date), releaseDate = date,
            runtimeMinutes = (d.runtime ?: d.episodeRunTime.firstOrNull())?.takeIf { it > 0 },
            certification = certification,
            status = d.status?.ifBlank { null },
            tmdbRating = d.voteAverage?.takeIf { (d.voteCount ?: 1) > 0 && it > 0 },
            logoUrl = logo?.let { image(it.filePath, LOGO) },
            // Phone-sized: the title page's artwork is under 1080 px wide on most phones, and
            // w780 loads about twice as fast as w1280.
            backdropUrl = image(d.backdropPath, BACKDROP_PAGE), posterUrl = image(d.posterPath, POSTER),
            directors = crew.filter { it.job == "Director" }.map { it.name }.distinct(),
            writers = crew.filter { it.department == "Writing" && it.job in WRITER_JOBS }.map { it.name }.distinct().take(3),
            creators = d.createdBy.map { it.name },
            companies = d.productionCompanies.take(MAX_COMPANIES).map { dev.yourstream.metadata.api.TitleDetails.Company(it.name, image(it.logoPath, LOGO_SMALL)) },
            cast = d.credits?.cast.orEmpty().sortedBy { it.order }.take(MAX_CAST)
                .map { dev.yourstream.metadata.api.TitleDetails.Person(it.name, it.character?.ifBlank { null }, image(it.profilePath, PROFILE)) },
            videos = videos,
            originCountries = d.originCountry.ifEmpty { d.productionCountries.map { it.code } },
            originalLanguage = d.originalLanguage?.ifBlank { null },
            // Posters with no language have no title printed on them.
            textlessPosterUrl = d.images?.posters.orEmpty().filter { it.language == null }.maxByOrNull { it.voteAverage }?.let { image(it.filePath, POSTER_LARGE) },
            seasonCount = d.numberOfSeasons, episodeCount = d.numberOfEpisodes,
            recommendations = similar,
        )
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
        /** TMDB's second API domain (same API), for networks that block the first by name. */
        const val ALT_BASE_URL = "https://api.tmdb.org/3"
        val ID = ProviderId("tmdb")
        private const val MAX_RESULTS = 10
        private const val MAX_CAST = 15
        /** w342 is sharp on phone/TV cards and a third of w500's size: faster lists. */
        private const val POSTER = "w342"
        private const val BACKDROP = "w1280"
        private const val STILL = "w300"
        private const val PROFILE = "w185"
        private const val LOGO = "w500"
        private const val BACKDROP_PAGE = "w780"
        private const val POSTER_LARGE = "w780"
        private const val LOGO_SMALL = "w185"
        private const val MAX_COMPANIES = 6
        private const val MAX_SIMILAR = 20
        private const val DETAILS_CACHE = 30
        private val VIDEO_ORDER = listOf("Trailer", "Teaser", "Clip", "Featurette", "Behind the Scenes")
        private val WRITER_JOBS = setOf("Writer", "Screenplay", "Story", "Novel", "Author", "Teleplay")
    }
}
