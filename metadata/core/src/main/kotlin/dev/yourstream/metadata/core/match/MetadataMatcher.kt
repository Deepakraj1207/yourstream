package dev.yourstream.metadata.core.match

import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import dev.yourstream.metadata.api.MetadataQuery
import dev.yourstream.metadata.core.MetadataProviderManager
import kotlinx.serialization.Serializable

enum class MatchDecision {
    /** Confident enough to apply automatically. */
    ACCEPTED,
    /** Plausible but not certain: stored as a suggestion, NOT applied. */
    NEEDS_REVIEW,
    /** Nothing plausible found. */
    NO_MATCH,
    /** Providers failed (network, rate limit): try again later. */
    FAILED,
}

data class MatchOutcome(
    val decision: MatchDecision,
    val best: ScoredCandidate?,
    /** Top candidates, best first (for review screens). */
    val candidates: List<ScoredCandidate>,
    val error: AppError? = null,
)

/**
 * Filename metadata → external metadata:
 *  1. queries: title + year, then title alone, then alternative titles;
 *  2. each provider in order (via the manager: cached, rate-limited, health-checked);
 *  3. every candidate scored by [MatchScorer]; stop as soon as one is acceptable;
 *  4. decide with [MatchThresholds]. Low-confidence matches are never accepted.
 */
class MetadataMatcher(
    private val manager: MetadataProviderManager,
    private val scorer: MatchScorer = MatchScorer(),
    val thresholds: MatchThresholds = MatchThresholds(),
) {
    suspend fun match(input: MatchInput, language: String? = null): MatchOutcome {
        val queries = buildList {
            val primary = input.titles.first()
            if (input.year != null) add(MetadataQuery(primary, input.year, input.type, language))
            add(MetadataQuery(primary, null, input.type, language))
            input.titles.drop(1).forEach { add(MetadataQuery(it, input.year, input.type, language)) }
        }.distinct()

        val seen = LinkedHashMap<String, ScoredCandidate>()
        var anySucceeded = false
        var lastError: AppError? = null

        for (provider in manager.searchProviders(input.type)) {
            for (query in queries) {
                when (val result = manager.search(query, provider.id)) {
                    is AppResult.Ok -> {
                        anySucceeded = true
                        scorer.score(input, result.value.value).forEach { scored ->
                            val key = "${scored.candidate.ref.provider}:${scored.candidate.ref.id}"
                            if ((seen[key]?.confidence ?: -1.0) < scored.confidence) seen[key] = scored
                        }
                    }
                    is AppResult.Err -> lastError = result.error
                }
                if ((seen.values.maxOfOrNull { it.confidence } ?: 0.0) >= thresholds.accept) break
            }
            if ((seen.values.maxOfOrNull { it.confidence } ?: 0.0) >= thresholds.accept) break
        }

        // Re-score the pooled candidates together so ambiguity across queries is noticed.
        val ranked = rescorePool(input, seen.values.toList())
        val best = ranked.firstOrNull()
        val decision = when {
            !anySucceeded -> MatchDecision.FAILED
            best == null -> MatchDecision.NO_MATCH
            best.confidence >= thresholds.accept -> MatchDecision.ACCEPTED
            best.confidence >= thresholds.review -> MatchDecision.NEEDS_REVIEW
            else -> MatchDecision.NO_MATCH
        }
        return MatchOutcome(decision, best.takeIf { decision != MatchDecision.FAILED }, ranked.take(5), lastError.takeIf { decision == MatchDecision.FAILED })
    }

    /** Ambiguity across queries, but only among one provider's answers (two providers agreeing is not doubt). */
    private fun rescorePool(input: MatchInput, pool: List<ScoredCandidate>): List<ScoredCandidate> =
        scorer.penaliseAmbiguity(pool.sortedByDescending { it.confidence }) { a, b -> a.candidate.ref.provider == b.candidate.ref.provider }
}
