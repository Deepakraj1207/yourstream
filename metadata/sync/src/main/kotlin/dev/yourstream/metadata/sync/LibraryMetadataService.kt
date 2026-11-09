package dev.yourstream.metadata.sync

import androidx.room.withTransaction
import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import dev.yourstream.core.common.Clock
import dev.yourstream.core.common.Logger
import dev.yourstream.core.database.YourStreamDatabase
import dev.yourstream.core.database.dao.MatchWorkRow
import dev.yourstream.core.database.entity.EpisodeMetadataEntity
import dev.yourstream.core.database.entity.ItemMetadataEntity
import dev.yourstream.core.database.entity.MetadataMatchEntity
import dev.yourstream.core.database.entity.SeasonMetadataEntity
import dev.yourstream.metadata.api.CastMember
import dev.yourstream.metadata.api.MetadataRef
import dev.yourstream.metadata.api.MetadataType
import dev.yourstream.metadata.api.ProviderId
import dev.yourstream.metadata.core.MetadataProviderManager
import dev.yourstream.metadata.core.match.MatchDecision
import dev.yourstream.metadata.core.match.MatchInput
import dev.yourstream.metadata.core.match.MatchOutcome
import dev.yourstream.metadata.core.match.MetadataMatcher
import dev.yourstream.metadata.core.match.ScoredCandidate
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/** Settings from the build (see docs/METADATA.md). */
data class MetadataSettings(
    /** Text language for metadata ("en"); provider default when null. */
    val language: String? = "en",
    val enabled: Boolean = true,
)

/**
 * Library item → match → metadata. Pipeline per item:
 *  1. input from the library (title, year, kind, file languages, parser title confidence,
 *     alternative titles);
 *  2. [MetadataMatcher] (providers in order, cached, scored);
 *  3. ACCEPTED → fetch details (+ seasons/episodes the library has) and store them;
 *     NEEDS_REVIEW → keep the candidates, apply nothing; NO_MATCH / FAILED → retry later with
 *     backoff. User decisions (MANUAL / REJECTED) are never overwritten.
 */
@Singleton
class LibraryMetadataService @Inject constructor(
    private val db: YourStreamDatabase,
    private val manager: MetadataProviderManager,
    private val matcher: MetadataMatcher,
    private val settings: MetadataSettings,
    private val clock: Clock,
    private val logger: Logger,
) {
    private val dao = db.metadataDao()
    private val library = db.libraryDao()
    private val json = Json { ignoreUnknownKeys = true }
    private val candidatesSerializer = ListSerializer(ScoredCandidate.serializer())
    private val castSerializer = ListSerializer(CastMember.serializer())
    private val mutex = Mutex()

    /** Matches up to [limit] items that need it. Call repeatedly until processed == 0. */
    suspend fun processPending(limit: Int = 40): MetadataRunResult = mutex.withLock {
        if (!settings.enabled || manager.searchProviders(null).isEmpty()) return MetadataRunResult(0, 0, 0, 0, 0)
        val work = dao.matchWork(clock.nowMillis(), limit)
        // Several titles at once: a big library is matched in minutes, not hours. Providers keep
        // their own rate limits; this only overlaps the waiting.
        val gate = kotlinx.coroutines.sync.Semaphore(PARALLEL_MATCHES)
        val decisions = kotlinx.coroutines.coroutineScope {
            work.map { row -> async { gate.withPermit { matchItem(row).decision } } }.map { it.await() }
        }
        val accepted = decisions.count { it == MatchDecision.ACCEPTED }
        val review = decisions.count { it == MatchDecision.NEEDS_REVIEW }
        val none = decisions.count { it == MatchDecision.NO_MATCH }
        val failed = decisions.count { it == MatchDecision.FAILED }
        val result = MetadataRunResult(work.size, accepted, review, none, failed)
        if (work.isNotEmpty()) logger.i(TAG, "metadata: $result")
        result
    }

    private suspend fun matchItem(row: MatchWorkRow): MatchOutcome {
        val input = input(row)
        val outcome = matcher.match(input, settings.language)
        val now = clock.nowMillis()
        val previous = dao.match(row.id)
        val attempts = if (outcome.decision == MatchDecision.ACCEPTED) 0 else (previous?.attempts ?: 0) + 1
        val best = outcome.best
        val status = when (outcome.decision) {
            MatchDecision.ACCEPTED -> MatchStatus.ACCEPTED
            MatchDecision.NEEDS_REVIEW -> MatchStatus.NEEDS_REVIEW
            MatchDecision.NO_MATCH -> MatchStatus.NO_MATCH
            MatchDecision.FAILED -> MatchStatus.FAILED
        }
        dao.putMatch(
            MetadataMatchEntity(
                itemId = row.id,
                status = status.name,
                provider = best?.candidate?.ref?.provider?.value,
                externalId = best?.candidate?.ref?.id,
                externalType = best?.candidate?.ref?.type?.name,
                imdbId = best?.candidate?.ref?.imdbId,
                confidence = best?.confidence ?: 0.0,
                candidates = json.encodeToString(candidatesSerializer, outcome.candidates),
                inputSignature = signature(row),
                attempts = attempts,
                matchedAt = if (status == MatchStatus.ACCEPTED) now else previous?.matchedAt,
                updatedAt = now,
                nextAttemptAt = nextAttempt(status, attempts, now),
                lastError = outcome.error?.message,
            ),
        )
        if (status == MatchStatus.ACCEPTED) apply(row.id, best!!.candidate.ref)
        else if (previous?.status == MatchStatus.ACCEPTED.name) dao.deleteItemMetadata(row.id) // inputs changed: old metadata no longer trusted
        return outcome
    }

    private suspend fun input(row: MatchWorkRow): MatchInput = MatchInput(
        titles = (listOf(row.title) + library.itemAlternativeTitles(row.id)).distinct(),
        year = row.year,
        type = if (row.kind == "SERIES") MetadataType.SERIES else MetadataType.MOVIE,
        languages = library.languages(row.id),
        titleConfidence = row.titleConfidence,
    )

    /** Fetches and stores details for an accepted/confirmed ref. */
    private suspend fun apply(itemId: Long, ref: MetadataRef): AppResult<Unit> {
        val now = clock.nowMillis()
        val item = library.item(itemId) ?: return AppResult.Err(AppError.NotFound("item $itemId"))
        val entity = when (ref.type) {
            MetadataType.MOVIE -> when (val r = manager.movie(ref, settings.language)) {
                is AppResult.Err -> return r
                is AppResult.Ok -> r.value.value.let { m ->
                    ItemMetadataEntity(itemId, m.ref.provider.value, m.ref.id, m.ref.imdbId ?: ref.imdbId, m.title, m.originalTitle, m.overview,
                        m.posterUrl, m.backdropUrl, m.genres.joinToString(","), m.releaseDate, m.year, m.originalLanguage, m.runtimeMinutes,
                        m.rating, json.encodeToString(castSerializer, m.cast), m.directors.joinToString(","), null, now)
                }
            }
            MetadataType.SERIES -> when (val r = manager.series(ref, settings.language)) {
                is AppResult.Err -> return r
                is AppResult.Ok -> r.value.value.let { s ->
                    ItemMetadataEntity(itemId, s.ref.provider.value, s.ref.id, s.ref.imdbId ?: ref.imdbId, s.title, s.originalTitle, s.overview,
                        s.posterUrl, s.backdropUrl, s.genres.joinToString(","), s.firstAirDate, s.year, s.originalLanguage, null, s.rating,
                        json.encodeToString(castSerializer, s.cast), "", s.status, now)
                }
            }
        }
        db.withTransaction {
            dao.putItemMetadata(entity)
            // Official and original titles become searchable next to the file-derived ones.
            val extra = listOfNotNull(entity.title, entity.originalTitle).map(::searchKey)
            dao.setItemSearchText(itemId, (item.searchText.split(' ') + extra.flatMap { it.split(' ') }).distinct().joinToString(" "))
        }
        if (ref.type == MetadataType.SERIES) applyEpisodes(itemId, MetadataRef(ProviderId(entity.provider), MetadataType.SERIES, entity.externalId, entity.imdbId))
        return AppResult.Ok(Unit)
    }

    /** Season/episode metadata for the seasons the library actually has (numbered seasons only). */
    private suspend fun applyEpisodes(seriesId: Long, ref: MetadataRef) {
        val now = clock.nowMillis()
        for (season in library.seasons(seriesId).filter { it.number >= 0 }) {
            val result = manager.season(ref, season.number, settings.language)
            val meta = (result as? AppResult.Ok)?.value?.value ?: continue
            val episodes = library.episodesOf(seriesId).filter { it.seasonId == season.id }
            val byNumber = meta.episodes.associateBy { it.episodeNumber }
            db.withTransaction {
                dao.putSeasonMetadata(SeasonMetadataEntity(season.id, meta.name, meta.overview, meta.posterUrl, meta.airDate, now))
                dao.putEpisodeMetadata(episodes.mapNotNull { e ->
                    byNumber[e.number]?.let { m -> EpisodeMetadataEntity(e.id, m.title, m.overview, m.stillUrl, m.airDate, m.runtimeMinutes, m.rating, now) }
                })
            }
        }
    }

    // ------------------------------------------------------------------ user actions

    /** Re-runs matching for one item now (e.g. from a review screen), unless the user decided. */
    suspend fun rematch(itemId: Long): MatchOutcome? {
        val item = library.item(itemId) ?: return null
        return mutex.withLock {
            matchItem(MatchWorkRow(item.id, item.kind, item.title, item.year, item.titleConfidence, null, 0))
        }
    }

    /** Candidates for a custom search (review UI): nothing is stored. */
    suspend fun searchCandidates(itemId: Long, title: String? = null, year: Int? = null): MatchOutcome? {
        val item = library.item(itemId) ?: return null
        val row = MatchWorkRow(item.id, item.kind, title ?: item.title, year ?: item.year, item.titleConfidence, null, 0)
        return matcher.match(input(row), settings.language)
    }

    /** The user picks a candidate: applied, locked against automatic changes. */
    suspend fun confirm(itemId: Long, ref: MetadataRef): AppResult<Unit> {
        val item = library.item(itemId) ?: return AppResult.Err(AppError.NotFound("item $itemId"))
        val applied = apply(itemId, ref)
        if (applied is AppResult.Err) return applied
        val now = clock.nowMillis()
        dao.putMatch(
            MetadataMatchEntity(itemId, MatchStatus.MANUAL.name, ref.provider.value, ref.id, ref.type.name, ref.imdbId, 1.0,
                dao.match(itemId)?.candidates ?: "[]", "${item.kind}|${item.title}|${item.year ?: ""}", 0, now, now, null, null),
        )
        dao.lockItem(itemId)
        return AppResult.Ok(Unit)
    }

    /** The user says none of the candidates is right: no metadata, never auto-matched again. */
    suspend fun reject(itemId: Long) {
        val item = library.item(itemId) ?: return
        val now = clock.nowMillis()
        db.withTransaction {
            dao.deleteItemMetadata(itemId)
            dao.putMatch(
                MetadataMatchEntity(itemId, MatchStatus.REJECTED.name, null, null, null, null, 0.0, dao.match(itemId)?.candidates ?: "[]",
                    "${item.kind}|${item.title}|${item.year ?: ""}", 0, null, now, null, null),
            )
            dao.lockItem(itemId)
        }
    }

    /** Re-applies metadata older than [maxAgeMillis] for matched items (uses the cache TTLs). */
    suspend fun refreshStale(maxAgeMillis: Long = 30L * 24 * 60 * 60 * 1000, limit: Int = 20): Int {
        val ids = dao.staleItemMetadata(clock.nowMillis() - maxAgeMillis, limit)
        var done = 0
        for (id in ids) {
            val m = dao.match(id) ?: continue
            val ref = MetadataRef(ProviderId(m.provider ?: continue), MetadataType.valueOf(m.externalType ?: continue), m.externalId ?: continue, m.imdbId)
            if (apply(id, ref) is AppResult.Ok) done++
        }
        return done
    }

    fun decodeCandidates(encoded: String): List<ScoredCandidate> =
        runCatching { json.decodeFromString(candidatesSerializer, encoded) }.getOrDefault(emptyList())

    fun decodeCast(encoded: String): List<CastMember> = runCatching { json.decodeFromString(castSerializer, encoded) }.getOrDefault(emptyList())

    private fun signature(row: MatchWorkRow) = "${row.kind}|${row.title}|${row.year ?: ""}"

    /**
     * FAILED: 15 min doubling to 1 day (providers come back). NO_MATCH / NEEDS_REVIEW: a week
     * doubling to 60 days (catalogues grow; a cached search expires after a week anyway).
     */
    private fun nextAttempt(status: MatchStatus, attempts: Int, now: Long): Long? = when (status) {
        MatchStatus.FAILED -> now + min(DAY, 15 * MINUTE shl min(attempts - 1, 10).coerceAtLeast(0))
        MatchStatus.NO_MATCH, MatchStatus.NEEDS_REVIEW -> now + min(60 * DAY, 7 * DAY shl min(attempts - 1, 4).coerceAtLeast(0))
        else -> null
    }

    private fun searchKey(text: String) = text.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private companion object {
        const val TAG = "MetadataSync"
        /** Titles matched at the same time. */
        const val PARALLEL_MATCHES = 4
        const val MINUTE = 60_000L
        const val DAY = 24 * 60 * MINUTE
    }
}
