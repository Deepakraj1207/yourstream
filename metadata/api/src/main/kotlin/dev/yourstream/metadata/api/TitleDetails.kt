package dev.yourstream.metadata.api

import dev.yourstream.core.common.AppResult

/**
 * Everything a title page shows about a movie or series (Nuvio's detail page, from TMDB):
 * logo, artwork, facts, crew, production, cast, trailers and similar titles.
 */
data class TitleDetails(
    val tmdbId: String,
    val type: MetadataType,
    val imdbId: String?,
    val title: String,
    val overview: String?,
    val genres: List<String>,
    val year: Int?,
    /** "2026-03-14" (series: first air date). */
    val releaseDate: String?,
    val runtimeMinutes: Int?,
    /** The age rating for the user's region, else the US one ("U/A 13+", "PG-13", "TV-MA"). */
    val certification: String?,
    /** "Released", "Returning Series", … as TMDB says it. */
    val status: String?,
    /** TMDB's user score, 0–10. */
    val tmdbRating: Double?,
    /** The title's logo (transparent PNG), in the user's language when there is one. */
    val logoUrl: String?,
    val backdropUrl: String?,
    val posterUrl: String?,
    val directors: List<String>,
    val writers: List<String>,
    /** Series: who created it. */
    val creators: List<String>,
    val companies: List<Company>,
    val cast: List<Person>,
    val videos: List<Video>,
    /** ISO 3166 codes ("IN"). */
    val originCountries: List<String>,
    /** ISO 639 code ("ta"). */
    val originalLanguage: String?,
    /** A poster without its title printed on it (for a hero that shows [logoUrl] over it). */
    val textlessPosterUrl: String? = null,
    val seasonCount: Int? = null,
    val episodeCount: Int? = null,
    val recommendations: List<Recommendation>,
) {
    data class Company(val name: String, val logoUrl: String?)
    data class Person(val name: String, val role: String?, val photoUrl: String?)

    /** A YouTube video: [type] is TMDB's ("Trailer", "Teaser", "Clip", "Featurette", …). */
    data class Video(val name: String, val youtubeKey: String, val type: String, val year: Int?, val official: Boolean) {
        val thumbnailUrl: String get() = "https://img.youtube.com/vi/$youtubeKey/hqdefault.jpg"
        val watchUrl: String get() = "https://www.youtube.com/watch?v=$youtubeKey"
    }

    data class Recommendation(val tmdbId: String, val type: MetadataType, val title: String, val year: Int?, val posterUrl: String?)
}

/** Where title pages get [TitleDetails] (TMDB; needs its key). */
interface TitleDetailsProvider {
    val isConfigured: Boolean

    /**
     * The title by its TMDB id, or found by its IMDb id. [language]: "en-US" (texts, logo);
     * [region]: "IN" (age rating).
     */
    suspend fun details(type: MetadataType, imdbId: String?, tmdbId: String?, language: String?, region: String?): AppResult<TitleDetails>
}
