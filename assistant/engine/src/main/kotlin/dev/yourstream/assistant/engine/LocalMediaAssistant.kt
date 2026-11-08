package dev.yourstream.assistant.engine

import androidx.sqlite.db.SimpleSQLiteQuery
import dev.yourstream.assistant.core.AssistantInterpreter
import dev.yourstream.assistant.core.Entities
import dev.yourstream.assistant.core.IntentModel
import dev.yourstream.assistant.core.IntentParser
import dev.yourstream.assistant.core.Interpretation
import dev.yourstream.assistant.core.Planner
import dev.yourstream.assistant.core.QueryPlan
import dev.yourstream.core.database.YourStreamDatabase
import dev.yourstream.core.database.dao.AssistantEpisodeRow
import dev.yourstream.core.database.dao.AssistantItemRow
import dev.yourstream.core.database.dao.WatchEntryRow
import dev.yourstream.search.core.ItemKind
import dev.yourstream.search.core.SearchFilters
import dev.yourstream.search.core.SearchHit
import dev.yourstream.search.core.SearchRequest
import dev.yourstream.search.engine.LibrarySearch
import dev.yourstream.search.engine.SearchIndexer
import dev.yourstream.search.core.WatchState
import kotlinx.coroutines.flow.first
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton

/** One result row: an item, or one episode of a series. */
data class AssistantItem(
    val itemId: Long,
    val kind: ItemKind,
    val title: String,
    val year: Int? = null,
    val episodeId: Long? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    /** Playback progress 0..1 when started. */
    val progress: Float? = null,
    /** Why it is in the list ("continue", "next episode", "Tamil · comedy"). */
    val reason: String? = null,
)

data class AssistantResponse(
    val interpretation: Interpretation,
    val plan: QueryPlan,
    /** A plain, deterministic sentence describing the result. */
    val message: String,
    val items: List<AssistantItem>,
    /** All matches (items holds at most the plan's limit). */
    val total: Int,
    val tookMs: Long,
)

interface MediaAssistant {
    suspend fun ask(text: String): AssistantResponse
}

/**
 * User query → [IntentParser] (optionally refined by an on-device [IntentModel]) → [Planner] →
 * local database → results. Everything runs on the device against the library database.
 */
@Singleton
class LocalMediaAssistant @Inject constructor(
    db: YourStreamDatabase,
    private val search: LibrarySearch,
    private val indexer: SearchIndexer,
    model: Optional<IntentModel>,
) : MediaAssistant {
    private val dao = db.assistantDao()
    private val watchDao = db.watchDao()
    private val searchDao = db.searchDao()
    var interpreter = AssistantInterpreter(IntentParser(), model.orElse(null))

    override suspend fun ask(text: String): AssistantResponse {
        val t0 = System.nanoTime()
        val interpretation = interpreter.interpret(text)
        val plan = Planner.plan(interpretation)
        val (message, items, total) = run(plan, interpretation.entities)
        return AssistantResponse(interpretation, plan, message, items, total, (System.nanoTime() - t0) / 1_000_000)
    }

    private data class Result(val message: String, val items: List<AssistantItem>, val total: Int)

    private suspend fun run(plan: QueryPlan, e: Entities): Result = when (plan) {
        is QueryPlan.Search -> runSearch(plan.request, e)
        is QueryPlan.Episodes -> episodes(plan, e)
        is QueryPlan.ContinueWatching -> continueWatching(plan)
        is QueryPlan.Recommend -> recommend(plan)
        is QueryPlan.Play -> play(plan)
    }

    // ------------------------------------------------------------------ search

    private suspend fun runSearch(request: SearchRequest, e: Entities): Result {
        val page = search.search(request)
        val what = Describe.entities(e)
        val message = if (page.total == 0) "No $what in your library." else "${page.total} $what."
        return Result(message, page.hits.map { it.toItem() }, page.total)
    }

    private fun SearchHit.toItem() = AssistantItem(itemId, kind, title, year, episodeId = episodeId, season = season, episode = episodeNumber)

    // ------------------------------------------------------------------ episodes

    private suspend fun episodes(plan: QueryPlan.Episodes, e: Entities): Result {
        val series = plan.seriesTitle?.let { resolve(it, ItemKind.SERIES) }
        if (plan.seriesTitle != null && series == null) {
            // Not a series in the library: fall back to an ordinary search for the words.
            return runSearch(Planner.searchRequest(e.copy(kinds = emptySet())), e).let { r ->
                r.copy(message = "No series called \"${plan.seriesTitle}\". " + r.message)
            }
        }
        val where = EpisodeWhere(series?.itemId, plan.season, plan.episode, plan.watch, plan.languages)
        val rows = dao.episodes(where.query("ORDER BY i.sort_title, e.season_number, e.number LIMIT ${plan.limit}"))
        val total = dao.count(where.countQuery())
        val subject = buildString {
            append(Describe.watchAdjective(plan.watch)?.let { "$it " } ?: "")
            append(if (total == 1) "episode" else "episodes")
            plan.season?.let { append(" in season $it") }
            plan.episode?.let { append(if (plan.season != null) ", episode $it" else " numbered $it") }
            series?.let { append(" of ${it.title}") }
        }
        val message = when {
            total == 0 -> "No $subject."
            series == null && rows.map { it.seriesId }.distinct().size > 1 -> "$total $subject across ${rows.map { it.seriesId }.distinct().size} series."
            else -> "$total $subject."
        }
        return Result(message, rows.map { it.toItem() }, total)
    }

    private fun AssistantEpisodeRow.toItem(reason: String? = null) = AssistantItem(
        itemId = seriesId, kind = ItemKind.SERIES, title = seriesTitle, episodeId = episodeId, season = seasonNumber, episode = number,
        episodeTitle = title, progress = progress(positionMs, durationMs, completed), reason = reason,
    )

    /** Builds the episode WHERE clause with bound arguments only. */
    private class EpisodeWhere(seriesId: Long?, season: Int?, episode: Int?, watch: WatchState, languages: Set<String>) {
        private val clauses = mutableListOf("i.hidden = 0", "e.available_count > 0")
        private val args = mutableListOf<Any>()

        init {
            seriesId?.let { clauses += "e.series_id = ?"; args += it }
            season?.let { clauses += "e.season_number = ?"; args += it }
            episode?.let { clauses += "(e.number = ? OR e.absolute_number = ?)"; args += it; args += it }
            when (watch) {
                WatchState.ANY -> Unit
                WatchState.UNWATCHED -> clauses += "IFNULL(ws.completed, 0) = 0 AND IFNULL(ws.position_ms, 0) = 0"
                WatchState.IN_PROGRESS -> clauses += "ws.completed = 0 AND ws.position_ms > 0"
                WatchState.WATCHED -> clauses += "ws.completed = 1"
            }
            if (languages.isNotEmpty()) {
                clauses += "EXISTS (SELECT 1 FROM library_item_language l WHERE l.item_id = e.series_id AND l.language IN (${languages.joinToString { "?" }}))"
                args.addAll(languages)
            }
        }

        private val from = """FROM episode e JOIN library_item i ON i.id = e.series_id
            LEFT JOIN watch_state ws ON ws.item_id = e.series_id AND ws.episode_key = e.id
            WHERE ${clauses.joinToString(" AND ")}"""

        fun query(tail: String) = SimpleSQLiteQuery(
            """SELECT e.id AS episode_id, e.series_id, i.title AS series_title, e.season_number, e.number, e.title, e.available_count,
                ws.position_ms, ws.duration_ms, IFNULL(ws.completed, 0) AS completed, ws.last_watched_at $from $tail""",
            args.toTypedArray(),
        )

        fun countQuery() = SimpleSQLiteQuery("SELECT COUNT(*) $from", args.toTypedArray())
    }

    private suspend fun seriesEpisodes(seriesId: Long) =
        dao.episodes(EpisodeWhere(seriesId, null, null, WatchState.ANY, emptySet()).query("ORDER BY e.season_number < 0, e.season_number, e.number"))

    /** The episode to watch next: the one in progress, else the one after the last finished, else the first unwatched. */
    private suspend fun nextEpisode(seriesId: Long): AssistantEpisodeRow? {
        val eps = seriesEpisodes(seriesId)
        eps.filter { !it.completed && (it.positionMs ?: 0) > 0 }.maxByOrNull { it.lastWatchedAt ?: 0 }?.let { return it }
        val last = eps.withIndex().filter { it.value.completed }.maxByOrNull { it.value.lastWatchedAt ?: 0 }
        if (last != null) eps.drop(last.index + 1).firstOrNull { !it.completed }?.let { return it }
        return eps.firstOrNull { !it.completed }
    }

    // ------------------------------------------------------------------ continue watching

    private suspend fun continueWatching(plan: QueryPlan.ContinueWatching): Result {
        val title = plan.title
        if (title != null) {
            val item = resolve(title, null) ?: return Result("Nothing called \"$title\" in your library.", emptyList(), 0)
            val inProgress = inProgress(100).firstOrNull { it.itemId == item.itemId }
            if (inProgress != null) return Result("Resume ${label(inProgress)}.", listOf(inProgress.toItem("continue")), 1)
            if (item.kind == ItemKind.SERIES) {
                val next = nextEpisode(item.itemId) ?: return Result("You have watched every episode of ${item.title}.", emptyList(), 0)
                return Result("Next: ${item.title} ${se(next.seasonNumber, next.number)}.", listOf(next.toItem("next episode")), 1)
            }
            return Result("You haven't started ${item.title}: play it from the beginning.", listOf(AssistantItem(item.itemId, item.kind, item.title, item.year, reason = "start")), 1)
        }
        val started = inProgress(plan.limit).map { it.toItem("continue") }
        val nextUp = nextUp(plan.limit - started.size, exclude = started.map { it.itemId }.toSet())
        val all = (started + nextUp).take(plan.limit)
        val message = when {
            all.isEmpty() -> "Nothing in progress. Try \"what should I watch next?\""
            nextUp.isEmpty() -> "${started.size} in progress."
            else -> "${started.size} in progress, ${nextUp.size} series with a next episode."
        }
        return Result(message, all, all.size)
    }

    private suspend fun inProgress(limit: Int): List<WatchEntryRow> = watchDao.continueWatching(limit).first()

    private fun WatchEntryRow.toItem(reason: String) = AssistantItem(
        itemId = itemId, kind = ItemKind.valueOf(kind), title = title, year = year, episodeId = episodeKey.takeIf { it != 0L },
        season = seasonNumber, episode = episodeNumber, episodeTitle = episodeTitle, progress = progress(positionMs, durationMs, completed), reason = reason,
    )

    /** Series started (an episode finished) but not finished, with nothing in progress: their next episode. */
    private suspend fun nextUp(limit: Int, exclude: Set<Long>): List<AssistantItem> {
        if (limit <= 0) return emptyList()
        val series = dao.items(
            SimpleSQLiteQuery(
                "$ITEM_SELECT WHERE d.hidden = 0 AND d.kind = 'SERIES' AND i.watched_count > 0 AND i.watched_count < i.episode_count ORDER BY i.last_watched_at DESC LIMIT ?",
                arrayOf(limit + exclude.size),
            ),
        )
        return series.filter { it.itemId !in exclude }.mapNotNull { s -> nextEpisode(s.itemId)?.toItem("next episode") }.take(limit)
    }

    // ------------------------------------------------------------------ recommendations

    private suspend fun recommend(plan: QueryPlan.Recommend): Result {
        indexer.flush()
        val out = mutableListOf<AssistantItem>()
        val filtered = plan.languages.isNotEmpty() || plan.genres.isNotEmpty() || plan.kinds.isNotEmpty()
        if (!filtered) {
            // Unfinished things first: the most likely "next" is what was already started.
            out += inProgress(2).map { it.toItem("continue") }
            out += nextUp(2, exclude = out.map { it.itemId }.toSet())
        }
        val profile = Profile.of(dao.watchProfile())
        val clauses = mutableListOf("d.hidden = 0", "d.available_count > 0", "NOT EXISTS (SELECT 1 FROM watch_state w WHERE w.item_id = d.item_id)")
        val args = mutableListOf<Any>()
        if (plan.kinds.isNotEmpty()) { clauses += "d.kind IN (${plan.kinds.joinToString { "?" }})"; args.addAll(plan.kinds.map { it.name }) }
        if (plan.languages.isNotEmpty()) { clauses += "(" + plan.languages.joinToString(" OR ") { "d.languages LIKE ?" } + ")"; args.addAll(plan.languages.map { "%,$it,%" }) }
        if (plan.genres.isNotEmpty()) { clauses += "(" + plan.genres.joinToString(" OR ") { "d.genres LIKE ?" } + ")"; args.addAll(plan.genres.map { "%,$it,%" }) }
        val candidates = dao.items(
            SimpleSQLiteQuery("$ITEM_SELECT WHERE ${clauses.joinToString(" AND ")} ORDER BY IFNULL(d.rating, 0) DESC, d.added_at DESC LIMIT 2000", args.toTypedArray()),
        )
        val fresh = candidates
            .map { it to profile.score(it) }
            .sortedWith(compareByDescending<Pair<AssistantItemRow, Double>> { it.second }.thenByDescending { it.first.addedAt })
            .take(plan.limit - out.size)
            .map { (row, _) -> AssistantItem(row.itemId, ItemKind.valueOf(row.kind), row.title, row.year, reason = profile.reason(row)) }
        out += fresh
        val message = when {
            out.isEmpty() && filtered -> "Nothing unwatched matches that."
            out.isEmpty() -> "Nothing new to suggest: everything has been watched."
            profile.isEmpty -> "Highest rated titles you haven't watched yet."
            else -> "Based on what you watch (${profile.summary()})."
        }
        return Result(message, out.take(plan.limit), out.size.coerceAtMost(plan.limit))
    }

    /** What the user tends to watch: language, genre and type shares, recent viewing counting more. */
    private class Profile(val languages: Map<String, Double>, val genres: Map<String, Double>, val kinds: Map<String, Double>) {
        val isEmpty get() = languages.isEmpty() && genres.isEmpty()

        fun score(r: AssistantItemRow): Double {
            val langs = split(r.languages)
            val gens = split(r.genres)
            return 2.0 * (langs.maxOfOrNull { languages[it] ?: 0.0 } ?: 0.0) +
                1.5 * gens.sumOf { genres[it] ?: 0.0 }.coerceAtMost(1.0) +
                0.5 * (kinds[r.kind] ?: 0.0) +
                (r.rating ?: 5.0) / 10.0
        }

        fun reason(r: AssistantItemRow): String {
            val parts = mutableListOf<String>()
            split(r.languages).filter { (languages[it] ?: 0.0) >= 0.2 }.maxByOrNull { languages[it]!! }?.let { parts += dev.yourstream.core.language.LanguageCatalog.displayName(it) }
            split(r.genres).filter { (genres[it] ?: 0.0) >= 0.2 }.take(2).forEach { parts += it }
            r.rating?.let { parts += "★ %.1f".format(java.util.Locale.US, it) }
            return parts.joinToString(" · ").ifEmpty { "new" }
        }

        fun summary() = (languages.entries.sortedByDescending { it.value }.take(2).map { dev.yourstream.core.language.LanguageCatalog.displayName(it.key) } +
            genres.entries.sortedByDescending { it.value }.take(2).map { it.key }).joinToString(", ")

        companion object {
            fun split(s: String) = s.split(',').filter { it.isNotEmpty() }

            fun of(rows: List<dev.yourstream.core.database.dao.WatchProfileRow>): Profile {
                val lang = HashMap<String, Double>()
                val gen = HashMap<String, Double>()
                val kind = HashMap<String, Double>()
                var total = 0.0
                rows.forEachIndexed { i, r ->
                    val w = (if (r.completed) 1.0 else 0.6) / (1 + i / 20.0) // recent viewing weighs more
                    total += w
                    split(r.languages).forEach { lang.merge(it, w, Double::plus) }
                    split(r.genres).forEach { gen.merge(it, w, Double::plus) }
                    kind.merge(r.kind, w, Double::plus)
                }
                if (total == 0.0) return Profile(emptyMap(), emptyMap(), emptyMap())
                return Profile(lang.mapValues { it.value / total }, gen.mapValues { it.value / total }, kind.mapValues { it.value / total })
            }
        }
    }

    // ------------------------------------------------------------------ play

    private suspend fun play(plan: QueryPlan.Play): Result {
        val item = resolve(plan.title, null, season = plan.season) ?: return Result("Nothing called \"${plan.title}\" in your library.", emptyList(), 0)
        if (item.kind != ItemKind.SERIES) return Result("Play ${item.title}.", listOf(AssistantItem(item.itemId, item.kind, item.title, item.year, reason = "play")), 1)
        val eps = seriesEpisodes(item.itemId)
        val ep = when {
            plan.season != null && plan.episode != null -> eps.firstOrNull { it.seasonNumber == plan.season && it.number == plan.episode }
            plan.season != null -> eps.firstOrNull { it.seasonNumber == plan.season }
            plan.episode != null -> eps.firstOrNull { it.number == plan.episode }
            else -> nextEpisode(item.itemId)
        } ?: return Result("${item.title} has no ${se(plan.season, plan.episode).ifEmpty { "episode" }} in your library.", emptyList(), 0)
        return Result("Play ${item.title} ${se(ep.seasonNumber, ep.number)}.", listOf(ep.toItem("play")), 1)
    }

    // ------------------------------------------------------------------ helpers

    /** The library item the user means by [title] (best search hit), optionally of one kind. */
    private suspend fun resolve(title: String, kind: ItemKind?, season: Int? = null): SearchHit? =
        search.search(SearchRequest(title, SearchFilters(kinds = setOfNotNull(kind), season = season), limit = 1)).hits.firstOrNull()
            ?: if (season != null) resolve(title, kind) else null

    private fun label(w: WatchEntryRow) = if (w.seasonNumber != null && w.episodeNumber != null) "${w.title} ${se(w.seasonNumber, w.episodeNumber)}" else w.title

    private fun progress(position: Long?, duration: Long?, completed: Boolean): Float? = when {
        completed -> 1f
        position == null || duration == null || duration <= 0 || position <= 0 -> null
        else -> (position.toFloat() / duration).coerceIn(0f, 1f)
    }

    private fun se(season: Int?, episode: Int?) = buildString {
        season?.let { append("S%02d".format(it)) }
        episode?.let { append("E%02d".format(it)) }
    }

    private companion object {
        const val ITEM_SELECT = """SELECT d.item_id, d.kind, d.title, d.year, d.languages, d.genres, d.rating, d.added_at, i.episode_count, i.watched_count, i.last_watched_at
            FROM search_doc d JOIN library_item i ON i.id = d.item_id"""
    }
}
