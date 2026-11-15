package dev.yourstream.metadata.tmdb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** /movie/{id} or /tv/{id} with credits, videos, ratings, images, recommendations and external ids. */
@Serializable
internal data class TmdbDetails(
    val id: Long,
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
    val genres: List<TmdbGenre> = emptyList(),
    val runtime: Int? = null,
    @SerialName("episode_run_time") val episodeRunTime: List<Int> = emptyList(),
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    val status: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("vote_count") val voteCount: Int? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("original_language") val originalLanguage: String? = null,
    @SerialName("origin_country") val originCountry: List<String> = emptyList(),
    @SerialName("production_countries") val productionCountries: List<TmdbCountry> = emptyList(),
    @SerialName("production_companies") val productionCompanies: List<TmdbCompany> = emptyList(),
    @SerialName("created_by") val createdBy: List<TmdbCreator> = emptyList(),
    @SerialName("number_of_seasons") val numberOfSeasons: Int? = null,
    @SerialName("number_of_episodes") val numberOfEpisodes: Int? = null,
    val credits: TmdbCredits? = null,
    val videos: TmdbPage<TmdbVideo>? = null,
    val images: TmdbImages? = null,
    val recommendations: TmdbPage<TmdbSearchItem>? = null,
    val similar: TmdbPage<TmdbSearchItem>? = null,
    @SerialName("external_ids") val externalIds: TmdbExternalIds? = null,
    @SerialName("release_dates") val releaseDates: TmdbPage<TmdbReleaseCountry>? = null,
    @SerialName("content_ratings") val contentRatings: TmdbPage<TmdbContentRating>? = null,
)

@Serializable internal data class TmdbCountry(@SerialName("iso_3166_1") val code: String)
@Serializable internal data class TmdbCompany(val name: String, @SerialName("logo_path") val logoPath: String? = null)
@Serializable internal data class TmdbCreator(val name: String)

@Serializable
internal data class TmdbVideo(
    val name: String = "",
    val key: String = "",
    val site: String = "",
    val type: String = "",
    val official: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
)

@Serializable internal data class TmdbImages(val logos: List<TmdbImage> = emptyList())

@Serializable
internal data class TmdbImage(
    @SerialName("file_path") val filePath: String,
    @SerialName("iso_639_1") val language: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
)

@Serializable
internal data class TmdbReleaseCountry(
    @SerialName("iso_3166_1") val country: String,
    @SerialName("release_dates") val dates: List<TmdbReleaseDate> = emptyList(),
)

@Serializable internal data class TmdbReleaseDate(val certification: String = "", val type: Int = 0)

@Serializable internal data class TmdbContentRating(@SerialName("iso_3166_1") val country: String, val rating: String = "")
