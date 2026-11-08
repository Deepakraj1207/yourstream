package dev.yourstream.metadata.core.match

import dev.yourstream.metadata.api.MetadataCandidate
import dev.yourstream.metadata.api.MetadataType
import kotlinx.serialization.Serializable
import kotlin.math.abs

/** What we know about a library item from its files (Telegram name → parser → library). */
data class MatchInput(
    /** Display title first, then alternative titles ("aka"). */
    val titles: List<String>,
    val year: Int?,
    val type: MetadataType,
    /** ISO codes of languages found in the file names ("ta", "hi"). */
    val languages: List<String> = emptyList(),
    /** How sure the filename parser was about the title (0..1). */
    val titleConfidence: Float = 0.9f,
)

/** Why a candidate got its score; stored for review and debugging. */
@Serializable
data class ScoreBreakdown(
    val title: Double,
    val year: Double,
    val type: Double,
    val language: Double,
    val rank: Double,
    val inputReliability: Double,
    val ambiguityPenalty: Double,
    val capped: String? = null,
)

@Serializable
data class ScoredCandidate(
    val candidate: MetadataCandidate,
    val confidence: Double,
    val breakdown: ScoreBreakdown,
)

/** Thresholds deciding what happens with a best candidate. */
data class MatchThresholds(
    /** At or above: accepted automatically. */
    val accept: Double = 0.80,
    /** At or above (but below [accept]): kept as a suggestion for the user, never applied. */
    val review: Double = 0.55,
)

/**
 * Confidence = weighted evidence × input reliability − ambiguity:
 *
 * | evidence  | weight | values |
 * |-----------|--------|--------|
 * | title     | 0.60   | best [TitleSimilarity] over our titles × the candidate's titles |
 * | year      | 0.20   | movies: same 1.0, ±1 0.75, ±2 0.3, else 0; series: started on/before our year 0.7; unknown 0.5 |
 * | type      | 0.07   | movie/series as expected 1.0 else 0 |
 * | language  | 0.08   | candidate's original language among our file languages 1.0, else 0.4 (dubs are common), unknown 0.5 |
 * | rank      | 0.05   | provider's own ranking: 1st 1.0, 2nd 0.7, rest 0.4 |
 *
 * Input reliability = 0.75 + 0.25 × parser title confidence. Hard caps: title < 0.6 → ≤ 0.5;
 * movie years > 2 apart → ≤ 0.55. If another candidate scores within 0.05 and is not clearly
 * less popular (×3), the best one loses 0.12: two plausible answers must go to review.
 */
class MatchScorer {

    fun score(input: MatchInput, candidates: List<MetadataCandidate>): List<ScoredCandidate> {
        val raw = candidates.mapIndexed { index, c -> c to rawScore(input, c, index) }
        val reliability = 0.75 + 0.25 * input.titleConfidence.coerceIn(0f, 1f)
        val scored = raw.map { (c, parts) ->
            val (weighted, breakdown) = parts
            var value = weighted * reliability
            var capped: String? = null
            if (breakdown.title < 0.6) { value = minOf(value, 0.5); capped = "title too different" }
            val ourYear = input.year
            val theirYear = c.year
            if (input.type == MetadataType.MOVIE && ourYear != null && theirYear != null && abs(ourYear - theirYear) > 2) {
                value = minOf(value, 0.55); capped = "year mismatch"
            }
            ScoredCandidate(c, value, breakdown.copy(inputReliability = reliability, capped = capped))
        }.sortedByDescending { it.confidence }

        return penaliseAmbiguity(scored)
    }

    /**
     * Every candidate within 0.05 of the best (and not clearly less popular, ×3) forms the
     * "can't tell" group; if it has 2+ members, all lose 0.12. Penalising them all keeps their
     * order — penalising only the leader would hand the win to the runner-up.
     */
    fun penaliseAmbiguity(sorted: List<ScoredCandidate>, sameGroup: (ScoredCandidate, ScoredCandidate) -> Boolean = { _, _ -> true }): List<ScoredCandidate> {
        if (sorted.size < 2) return sorted
        val best = sorted[0]
        if (best.breakdown.ambiguityPenalty > 0) return sorted
        val bestPop = best.candidate.popularity
        val rivals = sorted.drop(1).filter { other ->
            sameGroup(best, other) &&
                other.candidate.ref != best.candidate.ref &&
                !(best.candidate.ref.imdbId != null && best.candidate.ref.imdbId == other.candidate.ref.imdbId) &&
                best.confidence - other.confidence < 0.05 &&
                !(bestPop != null && (other.candidate.popularity ?: 0.0) > 0 && bestPop / other.candidate.popularity!! >= 3)
        }
        if (rivals.isEmpty()) return sorted
        val group = (listOf(best) + rivals).toSet()
        return sorted.map { s ->
            if (s in group) s.copy(confidence = s.confidence - 0.12, breakdown = s.breakdown.copy(ambiguityPenalty = 0.12)) else s
        }.sortedByDescending { it.confidence }
    }

    private fun rawScore(input: MatchInput, c: MetadataCandidate, index: Int): Pair<Double, ScoreBreakdown> {
        val candidateTitles = listOfNotNull(c.title, c.originalTitle) + c.alternativeTitles
        val title = input.titles.maxOf { ours -> candidateTitles.maxOf { theirs -> TitleSimilarity.similarity(ours, theirs) } }
        val year = yearScore(input, c)
        val type = if (c.ref.type == input.type) 1.0 else 0.0
        val language = when {
            input.languages.isEmpty() || c.originalLanguage == null -> 0.5
            c.originalLanguage in input.languages -> 1.0
            else -> 0.4
        }
        val rank = when (index) { 0 -> 1.0; 1 -> 0.7; else -> 0.4 }
        val weighted = 0.60 * title + 0.20 * year + 0.07 * type + 0.08 * language + 0.05 * rank
        return weighted to ScoreBreakdown(title, year, type, language, rank, 1.0, 0.0)
    }

    private fun yearScore(input: MatchInput, c: MetadataCandidate): Double {
        val ours = input.year ?: return 0.5
        val theirs = c.year ?: return 0.5
        return if (input.type == MetadataType.SERIES) {
            // Our year is usually an episode's; the series must have started by then.
            if (theirs <= ours) 0.7 else 0.2
        } else when (abs(ours - theirs)) {
            0 -> 1.0
            1 -> 0.75
            2 -> 0.3
            else -> 0.0
        }
    }
}
