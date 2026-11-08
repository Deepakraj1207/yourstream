package dev.yourstream.metadata.sync

import dev.yourstream.metadata.api.CastMember
import dev.yourstream.metadata.core.match.ScoredCandidate

enum class MatchStatus {
    ACCEPTED,
    /** Suggestion kept for the user; nothing applied. */
    NEEDS_REVIEW,
    NO_MATCH,
    /** Providers unreachable; retried with backoff. */
    FAILED,
    /** Chosen by the user. */
    MANUAL,
    /** "Not this / leave it unmatched", chosen by the user. */
    REJECTED,
}

data class ItemMatch(
    val itemId: Long,
    val status: MatchStatus,
    val provider: String?,
    val externalId: String?,
    val imdbId: String?,
    val confidence: Double,
    val candidates: List<ScoredCandidate>,
    val attempts: Int,
    val matchedAt: Long?,
    val updatedAt: Long,
    val nextAttemptAt: Long?,
    val lastError: String?,
)

data class ItemMetadata(
    val itemId: Long,
    val provider: String,
    val externalId: String,
    val imdbId: String?,
    val title: String,
    val originalTitle: String?,
    val overview: String?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val genres: List<String>,
    val releaseDate: String?,
    val year: Int?,
    val originalLanguage: String?,
    val runtimeMinutes: Int?,
    val rating: Double?,
    val cast: List<CastMember>,
    val directors: List<String>,
    val fetchedAt: Long,
)

data class ReviewItem(val itemId: Long, val title: String, val year: Int?, val kind: String, val confidence: Double, val candidates: List<ScoredCandidate>)

data class MetadataRunResult(val processed: Int, val accepted: Int, val needsReview: Int, val noMatch: Int, val failed: Int)
