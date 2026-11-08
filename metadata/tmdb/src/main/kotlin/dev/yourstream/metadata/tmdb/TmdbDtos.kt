package dev.yourstream.metadata.tmdb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable internal data class TmdbPage<T>(val results: List<T> = emptyList())

@Serializable
internal data class TmdbSearchItem(
    val id: Long,
    val title: String? = null,
    val name: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("original_language") val originalLanguage: String? = null,
    val popularity: Double? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
)

@Serializable internal data class TmdbGenre(val name: String)

@Serializable
internal data class TmdbCast(
    val name: String,
    val character: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
    val order: Int = 0,
)

@Serializable
internal data class TmdbCrew(val name: String, val job: String? = null)

@Serializable internal data class TmdbCredits(val cast: List<TmdbCast> = emptyList(), val crew: List<TmdbCrew> = emptyList())

@Serializable internal data class TmdbExternalIds(@SerialName("imdb_id") val imdbId: String? = null)

@Serializable internal data class TmdbLanguage(@SerialName("iso_639_1") val code: String)

@Serializable
internal data class TmdbMovie(
    val id: Long,
    val title: String,
    @SerialName("original_title") val originalTitle: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val genres: List<TmdbGenre> = emptyList(),
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("original_language") val originalLanguage: String? = null,
    @SerialName("spoken_languages") val spokenLanguages: List<TmdbLanguage> = emptyList(),
    val runtime: Int? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("imdb_id") val imdbId: String? = null,
    val credits: TmdbCredits? = null,
)

@Serializable
internal data class TmdbSeasonSummary(
    @SerialName("season_number") val seasonNumber: Int,
    val name: String? = null,
    @SerialName("episode_count") val episodeCount: Int? = null,
    @SerialName("air_date") val airDate: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
)

@Serializable
internal data class TmdbTv(
    val id: Long,
    val name: String,
    @SerialName("original_name") val originalName: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val genres: List<TmdbGenre> = emptyList(),
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("original_language") val originalLanguage: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    val status: String? = null,
    val seasons: List<TmdbSeasonSummary> = emptyList(),
    val credits: TmdbCredits? = null,
    @SerialName("external_ids") val externalIds: TmdbExternalIds? = null,
)

@Serializable
internal data class TmdbEpisode(
    @SerialName("season_number") val seasonNumber: Int,
    @SerialName("episode_number") val episodeNumber: Int,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    @SerialName("still_path") val stillPath: String? = null,
    val runtime: Int? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
)

@Serializable
internal data class TmdbSeason(
    @SerialName("season_number") val seasonNumber: Int,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    val episodes: List<TmdbEpisode> = emptyList(),
)

@Serializable
internal data class TmdbFind(
    @SerialName("movie_results") val movies: List<TmdbSearchItem> = emptyList(),
    @SerialName("tv_results") val tv: List<TmdbSearchItem> = emptyList(),
)
