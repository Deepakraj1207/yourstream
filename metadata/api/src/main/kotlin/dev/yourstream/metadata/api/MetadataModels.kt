package dev.yourstream.metadata.api

import kotlinx.serialization.Serializable

/** Stable provider identifier ("tmdb", "cinemeta"); stored with every match. */
@Serializable
@JvmInline
value class ProviderId(val value: String) {
    override fun toString(): String = value
}

@Serializable
enum class MetadataType { MOVIE, SERIES }

/** A provider's own id for a title, plus cross-provider ids when known (IMDb id is the common one). */
@Serializable
data class MetadataRef(
    val provider: ProviderId,
    val type: MetadataType,
    val id: String,
    val imdbId: String? = null,
)

/** What to look for. [type] null = movies and series. */
@Serializable
data class MetadataQuery(
    val title: String,
    val year: Int? = null,
    val type: MetadataType? = null,
    /** Language for returned text (ISO 639-1, e.g. "en"); provider default when null. */
    val language: String? = null,
)

@Serializable
data class MetadataCandidate(
    val ref: MetadataRef,
    val title: String,
    val originalTitle: String? = null,
    val alternativeTitles: List<String> = emptyList(),
    val year: Int? = null,
    val overview: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    /** ISO 639-1 of the original production ("ta", "en", "ja"). */
    val originalLanguage: String? = null,
    /** Provider-specific popularity (only compared within one provider's results). */
    val popularity: Double? = null,
    val rating: Double? = null,
)

@Serializable
data class CastMember(
    val name: String,
    val character: String? = null,
    val profileUrl: String? = null,
    val order: Int = 0,
)

@Serializable
data class MovieMetadata(
    val ref: MetadataRef,
    val title: String,
    val originalTitle: String? = null,
    val overview: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val genres: List<String> = emptyList(),
    /** ISO date "2019-05-30" when known. */
    val releaseDate: String? = null,
    val year: Int? = null,
    val originalLanguage: String? = null,
    val spokenLanguages: List<String> = emptyList(),
    val runtimeMinutes: Int? = null,
    val rating: Double? = null,
    val cast: List<CastMember> = emptyList(),
    val directors: List<String> = emptyList(),
)

@Serializable
data class SeasonSummary(
    val number: Int,
    val name: String? = null,
    val episodeCount: Int? = null,
    val airDate: String? = null,
    val posterUrl: String? = null,
)

@Serializable
data class SeriesMetadata(
    val ref: MetadataRef,
    val title: String,
    val originalTitle: String? = null,
    val overview: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val genres: List<String> = emptyList(),
    val firstAirDate: String? = null,
    val year: Int? = null,
    val originalLanguage: String? = null,
    val rating: Double? = null,
    val status: String? = null,
    val cast: List<CastMember> = emptyList(),
    val seasons: List<SeasonSummary> = emptyList(),
)

@Serializable
data class EpisodeMetadata(
    val seriesRef: MetadataRef,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String? = null,
    val overview: String? = null,
    val airDate: String? = null,
    val stillUrl: String? = null,
    val runtimeMinutes: Int? = null,
    val rating: Double? = null,
)

@Serializable
data class SeasonMetadata(
    val seriesRef: MetadataRef,
    val number: Int,
    val name: String? = null,
    val overview: String? = null,
    val posterUrl: String? = null,
    val airDate: String? = null,
    val episodes: List<EpisodeMetadata> = emptyList(),
)

enum class ProviderCapability {
    SEARCH_MOVIES,
    SEARCH_SERIES,
    MOVIE_DETAILS,
    SERIES_DETAILS,
    SEASON_DETAILS,
    EPISODE_DETAILS,
    /** Can resolve an IMDb id to its own ref (cross-provider fallback). */
    FIND_BY_IMDB_ID,
    CAST,
    IMAGES,
}
