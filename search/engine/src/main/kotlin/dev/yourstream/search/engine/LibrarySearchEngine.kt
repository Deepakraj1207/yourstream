package dev.yourstream.search.engine

import androidx.sqlite.db.SimpleSQLiteQuery
import dev.yourstream.core.database.YourStreamDatabase
import dev.yourstream.core.database.dao.SearchRow
import dev.yourstream.core.language.LanguageCatalog
import dev.yourstream.search.core.ItemKind
import dev.yourstream.search.core.ParsedQuery
import dev.yourstream.search.core.QueryParser
import dev.yourstream.search.core.Ranker
import dev.yourstream.search.core.SearchDoc
import dev.yourstream.search.core.SearchFilters
import dev.yourstream.search.core.SearchHit
import dev.yourstream.search.core.SearchPage
import dev.yourstream.search.core.SearchRequest
import dev.yourstream.search.core.SearchSort
import dev.yourstream.search.core.SoftFilter
import dev.yourstream.search.core.TermMatch
import dev.yourstream.search.core.TermMatchKind
import dev.yourstream.search.core.WatchState
import javax.inject.Inject
import javax.inject.Singleton

/** Offline library search (no network). */
interface LibrarySearch {
    suspend fun search(request: SearchRequest): SearchPage
    /** Library words starting with [prefix] (search-as-you-type suggestions). */
    suspend fun suggest(prefix: String, limit: Int = 8): List<String>
}

/**
 * Two paths:
 *
 * **With text** ("doremon", "tom jerry", "ben 10 s2"): each query word becomes an FTS group
 * `word* OR expansion1 OR …` (prefix plus typo/transliteration neighbours from the library
 * vocabulary); groups are ANDed (one `rowid IN (… MATCH …)` each), hard filters are SQL, at most
 * [candidateCap] rows come back and are ranked in Kotlin ([Ranker]). If nothing matches every
 * word, a relaxed pass takes items matching at least half of the words. The ranked list is cached
 * per query, so later pages are slices.
 *
 * **Without text** ("tamil comedy", "unwatched movies", filters only): pure SQL. Each soft filter
 * is "condition OR the word appears in the item's text"; ordering counts satisfied filters, then
 * the sort; COUNT + LIMIT/OFFSET page in the database. Cost stays flat however many items match.
 */
@Singleton
class LibrarySearchEngine @Inject constructor(
    db: YourStreamDatabase,
    private val indexer: SearchIndexer,
) : LibrarySearch, dev.yourstream.core.common.MemoryTrimmable {
    private val dao = db.searchDao()
    var candidateCap = 1_500
    private val cache = object : LinkedHashMap<String, List<SearchHit>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<SearchHit>>?) = size > 8
    }

    init { dev.yourstream.core.common.MemoryPressure.register(this) }

    /** Cached rankings are rebuilt on the next search; the vocabulary reloads lazily. */
    override fun trimMemory(critical: Boolean) {
        synchronized(cache) { cache.clear() }
        if (critical) indexer.dropVocabulary()
    }

    override suspend fun suggest(prefix: String, limit: Int): List<String> {
        indexer.flush()
        val p = dev.yourstream.search.core.SearchText.normalize(prefix).substringAfterLast(' ')
        return if (p.isEmpty()) emptyList() else indexer.terms().completions(p, limit)
    }

    override suspend fun search(request: SearchRequest): SearchPage {
        val started = System.nanoTime()
        indexer.flush()
        val q = QueryParser(indexer.genres().ifEmpty { QueryParser.DEFAULT_GENRES }).parse(request.text)
        val filters = merge(request.filters, q.operators)
        val terms = indexer.terms()
        val expansions: Map<String, List<TermMatch>> = (q.terms + q.chatWords).associateWith { terms.expand(it) }
        val notes = interpretation(q, expansions)
        val page = if (q.terms.isEmpty() && q.chatWords.isEmpty()) sqlPath(q, filters, request)
        else textPath(q, filters, request, expansions)
        return page.copy(interpretation = notes, tookMicros = (System.nanoTime() - started) / 1_000)
    }

    // ------------------------------------------------------------------ text path

    private suspend fun textPath(q: ParsedQuery, filters: SearchFilters, request: SearchRequest, expansions: Map<String, List<TermMatch>>): SearchPage {
        val key = listOf(request.text, filters, request.sort, request.reversed, indexer.version.get()).joinToString("|")
        val ranked = synchronized(cache) { cache[key] } ?: run {
            fun rank(rows: List<SearchRow>, min: Double) = rows.mapNotNull { r -> doc(r).let { d -> Ranker.score(d, q, expansions, min)?.let { it to d } } }
            var hits = rank(candidates(q, filters, expansions, all = true), 1.0)
            if (hits.isEmpty() && q.terms.size > 1) {
                // Relaxed: any word, ranked by coverage; at least half of the words must match.
                hits = rank(candidates(q, filters, expansions, all = false), 0.5)
            }
            sort(hits, request.sort, request.reversed).also { synchronized(cache) { cache[key] = it } }
        }
        val slice = ranked.drop(request.offset).take(request.limit).map { withEpisode(it) }
        val next = (request.offset + slice.size).takeIf { it < ranked.size }
        return SearchPage(slice, ranked.size, request.offset, next, emptyList(), 0)
    }

    private suspend fun candidates(q: ParsedQuery, filters: SearchFilters, expansions: Map<String, List<TermMatch>>, all: Boolean): List<SearchRow> {
        val where = Where().apply { hard(filters) }
        val groups = q.terms.map { t -> group(t, expansions[t].orEmpty(), column = null) } + q.chatWords.map { w -> group(w, expansions[w].orEmpty(), column = "text_chats") }
        if (all) groups.forEach { where.add("d.item_id IN (SELECT rowid FROM search_fts WHERE search_fts MATCH ?)", it) }
        else where.add("d.item_id IN (SELECT rowid FROM search_fts WHERE search_fts MATCH ?)", groups.joinToString(" OR "))
        return dao.rows(SimpleSQLiteQuery("$SELECT ${where.sql()} LIMIT $candidateCap", where.args.toTypedArray()))
    }

    /** `word* OR neighbour OR …` (optionally limited to one FTS column). */
    private fun group(word: String, expansions: List<TermMatch>, column: String?): String {
        val prefix = if (column != null) "$column:" else ""
        // Short or numeric words ("ben", "10") match as prefixes only in titles; elsewhere ("bengali" in
        // language names, "1080" in file names) only as whole words.
        val head = if (column == null && Ranker.isShort(word)) listOf("text_title:$word*", "text_alt:$word*", word) else listOf("$prefix$word*")
        return (head + expansions.filter { it.kind != TermMatchKind.EXACT }.map { "$prefix${it.term}" }).distinct().joinToString(" OR ")
    }

    private fun sort(hits: List<Pair<SearchHit, SearchDoc>>, sort: SearchSort, reversed: Boolean): List<SearchHit> {
        val byScore = compareByDescending<Pair<SearchHit, SearchDoc>> { it.first.score }.thenBy { it.first.title.lowercase() }
        val order: Comparator<Pair<SearchHit, SearchDoc>> = when (sort) {
            SearchSort.RELEVANCE -> byScore
            SearchSort.TITLE -> compareBy { it.first.title.lowercase() }
            SearchSort.YEAR -> compareByDescending<Pair<SearchHit, SearchDoc>> { it.first.year ?: 0 }.then(byScore)
            SearchSort.RECENTLY_ADDED -> compareByDescending<Pair<SearchHit, SearchDoc>> { it.second.addedAt }.then(byScore)
            SearchSort.RECENTLY_WATCHED -> compareByDescending<Pair<SearchHit, SearchDoc>> { it.second.lastWatchedAt ?: 0 }.then(byScore)
            SearchSort.RATING -> compareByDescending<Pair<SearchHit, SearchDoc>> { it.second.rating ?: -1.0 }.then(byScore)
            SearchSort.SIZE -> compareByDescending<Pair<SearchHit, SearchDoc>> { it.second.totalSize }.then(byScore)
        }
        val sorted = hits.sortedWith(order).map { it.first }
        return if (reversed && sort != SearchSort.RELEVANCE) sorted.reversed() else sorted
    }

    // ------------------------------------------------------------------ filter-only path

    private suspend fun sqlPath(q: ParsedQuery, filters: SearchFilters, request: SearchRequest): SearchPage {
        val where = Where().apply { hard(filters) }
        // Each soft filter: satisfied, or its word appears in the item's text ("Tamil Padam").
        val satisfiedTerms = mutableListOf<Pair<String, List<Any?>>>()
        for (f in q.soft) {
            val (cond, args) = softSql(f)
            val words = f.words.flatMap(dev.yourstream.search.core.SearchText::tokens).filter { it.length >= 3 && it.toIntOrNull() == null }
            if (words.isEmpty()) where.add("($cond)", *args.toTypedArray())
            else where.add("(($cond) OR d.item_id IN (SELECT rowid FROM search_fts WHERE search_fts MATCH ?))", *(args + words.joinToString(" ") { "$it*" }).toTypedArray())
            satisfiedTerms += cond to args
        }
        val relevance = if (satisfiedTerms.isEmpty()) "0" else satisfiedTerms.joinToString(" + ") { "(CASE WHEN ${it.first} THEN 1 ELSE 0 END)" }
        val order = when (request.sort) {
            // (A bare constant would be read by SQLite as a column number.)
            SearchSort.RELEVANCE -> if (satisfiedTerms.isEmpty()) "d.latest_added_at DESC" else "$relevance DESC, d.latest_added_at DESC"
            SearchSort.TITLE -> "d.sort_title ASC"
            SearchSort.YEAR -> "d.year IS NULL, d.year DESC"
            SearchSort.RECENTLY_ADDED -> "d.latest_added_at DESC"
            SearchSort.RECENTLY_WATCHED -> "i.last_watched_at IS NULL, i.last_watched_at DESC"
            SearchSort.RATING -> "d.rating IS NULL, d.rating DESC"
            SearchSort.SIZE -> "d.total_size DESC"
        }.let { if (request.reversed && request.sort != SearchSort.RELEVANCE) reverse(it) else it } + ", d.item_id"
        val relevanceArgs = if (request.sort == SearchSort.RELEVANCE) satisfiedTerms.flatMap { it.second } else emptyList()
        val total = dao.count(SimpleSQLiteQuery("SELECT COUNT(*) AS n $FROM ${where.sql()}", where.args.toTypedArray())).n
        val rows = dao.rows(SimpleSQLiteQuery("$SELECT ${where.sql()} ORDER BY $order LIMIT ? OFFSET ?", (where.args + relevanceArgs + request.limit + request.offset).toTypedArray()))
        val hits = rows.map { r ->
            val d = doc(r)
            val ok = q.soft.filter { Ranker.satisfies(d, it) }
            withEpisode(SearchHit(r.itemId, d.kind, r.title, r.year, ok.size.toDouble(), emptyList(), ok,
                q.soft.filterIsInstance<SoftFilter.Season>().firstOrNull()?.takeIf { it.season in d.seasons }?.season))
        }
        val next = (request.offset + hits.size).takeIf { it < total }
        return SearchPage(hits, total, request.offset, next, emptyList(), 0)
    }

    private fun reverse(order: String) = order.split(", ").joinToString(", ") { part ->
        when {
            part.endsWith(" DESC") -> part.removeSuffix(" DESC") + " ASC"
            part.endsWith(" ASC") -> part.removeSuffix(" ASC") + " DESC"
            else -> part
        }
    }

    private fun softSql(f: SoftFilter): Pair<String, List<Any?>> = when (f) {
        is SoftFilter.Year -> "d.year = ?" to listOf(f.year)
        is SoftFilter.Season -> "d.seasons LIKE ?" to listOf("%,${f.season},%")
        is SoftFilter.Episode -> "d.kind = 'SERIES'" to emptyList()
        is SoftFilter.Resolution -> "d.max_resolution >= ?" to listOf(f.min)
        is SoftFilter.Quality -> "d.qualities LIKE ?" to listOf("%,${f.quality},%")
        is SoftFilter.Language -> "d.languages LIKE ?" to listOf("%,${f.code},%")
        is SoftFilter.Genre -> "d.genres LIKE ?" to listOf("%,${f.genre},%")
        is SoftFilter.Kind -> "d.kind = ?" to listOf(f.kind.name)
        is SoftFilter.Watched -> "$WATCH = ?" to listOf(f.state.name)
        is SoftFilter.Provider -> if (f.provider == "unmatched") "d.providers = ','" to emptyList() else "d.providers LIKE ?" to listOf("%,${f.provider},%")
    }

    // ------------------------------------------------------------------ shared

    private inner class Where {
        private val parts = mutableListOf<String>()
        val args = mutableListOf<Any?>()
        fun add(sql: String, vararg a: Any?) { parts += sql; args.addAll(a) }
        fun sql() = if (parts.isEmpty()) "" else "WHERE " + parts.joinToString(" AND ")

        fun hard(f: SearchFilters) {
            if (!f.includeHidden) add("d.hidden = 0")
            if (!f.includeUnavailable) add("d.available_count > 0")
            if (f.kinds.isNotEmpty()) add("d.kind IN (${f.kinds.joinToString(",") { "?" }})", *f.kinds.map { it.name }.toTypedArray())
            anyLike("d.languages", f.languages)
            anyLike("d.genres", f.genres)
            anyLike("d.qualities", f.qualities)
            anyLike("d.chat_ids", f.chatIds.map(Long::toString).toSet())
            f.yearFrom?.let { add("d.year >= ?", it) }
            f.yearTo?.let { add("d.year <= ?", it) }
            f.season?.let { add("d.seasons LIKE ?", "%,$it,%") }
            f.minResolution?.let { add("d.max_resolution >= ?", it) }
            if (f.providers.isNotEmpty()) {
                val named = f.providers - "none"
                val conds = named.map { "d.providers LIKE ?" } + if ("none" in f.providers) listOf("d.providers = ','") else emptyList()
                add("(${conds.joinToString(" OR ")})", *named.map { "%,$it,%" }.toTypedArray())
            }
            if (f.watch != WatchState.ANY) add("$WATCH = ?", f.watch.name)
        }

        private fun anyLike(column: String, values: Set<String>) {
            if (values.isEmpty()) return
            add("(" + values.joinToString(" OR ") { "$column LIKE ?" } + ")", *values.map { "%,$it,%" }.toTypedArray())
        }
    }

    private fun merge(a: SearchFilters, b: SearchFilters) = a.copy(
        kinds = a.kinds + b.kinds, languages = a.languages + b.languages, genres = a.genres + b.genres,
        yearFrom = a.yearFrom ?: b.yearFrom, yearTo = a.yearTo ?: b.yearTo, season = a.season ?: b.season,
        minResolution = a.minResolution ?: b.minResolution, qualities = a.qualities + b.qualities, chatIds = a.chatIds + b.chatIds,
        providers = a.providers + b.providers, watch = if (a.watch != WatchState.ANY) a.watch else b.watch,
    )

    private suspend fun withEpisode(hit: SearchHit): SearchHit {
        val season = hit.season ?: return hit
        val ep = hit.episodeNumber ?: return hit
        return hit.copy(episodeId = dao.episodeId(hit.itemId, season, ep))
    }

    private fun doc(r: SearchRow) = SearchDoc(
        itemId = r.itemId, kind = runCatching { ItemKind.valueOf(r.kind) }.getOrDefault(ItemKind.VIDEO), title = r.title, year = r.year,
        titleTokens = words(r.textTitle), altTokens = words(r.textAlt).toSet(), fileTokens = words(r.textFiles).toSet(),
        chatTokens = words(r.textChats).toSet(), extraTokens = words(r.textExtra).toSet(), languages = set(r.languages), genres = set(r.genres),
        seasons = set(r.seasons).mapNotNull(String::toIntOrNull).toSet(), maxResolution = r.maxResolution, qualities = set(r.qualities),
        providers = set(r.providers), watch = runCatching { WatchState.valueOf(r.watchState) }.getOrDefault(WatchState.UNWATCHED),
        availableCount = r.availableCount, addedAt = r.addedAt, lastWatchedAt = r.lastWatchedAt, rating = r.rating, totalSize = r.totalSize,
    )

    private fun words(s: String) = s.split(' ').filter { it.isNotEmpty() }
    private fun set(s: String) = s.split(',').filter { it.isNotEmpty() }.toSet()

    private fun interpretation(q: ParsedQuery, expansions: Map<String, List<TermMatch>>): List<String> = buildList {
        q.terms.forEach { t ->
            val alt = expansions[t].orEmpty().filter { it.kind != TermMatchKind.EXACT }.map { it.term }
            add(if (alt.isEmpty()) "\"$t\"" else "\"$t\" ≈ ${alt.joinToString(", ")}")
        }
        q.soft.forEach { f ->
            add(when (f) {
                is SoftFilter.Language -> "${f.words.joinToString(" ")} = ${LanguageCatalog.displayName(f.code)} (language)"
                is SoftFilter.Genre -> "${f.words.joinToString(" ")} = ${f.genre} (genre)"
                is SoftFilter.Season -> "season ${f.season}" + (f.episode?.let { ", episode $it" } ?: "")
                is SoftFilter.Episode -> "episode ${f.episode}"
                is SoftFilter.Year -> "year ${f.year}"
                is SoftFilter.Resolution -> "≥ ${f.min}p"
                is SoftFilter.Quality -> "quality ${f.quality}"
                is SoftFilter.Kind -> f.kind.name.lowercase()
                is SoftFilter.Watched -> f.state.name.lowercase().replace('_', ' ')
                is SoftFilter.Provider -> "provider ${f.provider}"
            })
        }
        if (q.chatWords.isNotEmpty()) add("in chat \"${q.chatWords.joinToString(" ")}\"")
    }

    private companion object {
        const val WATCH = """(CASE WHEN (d.kind = 'SERIES' AND i.episode_count > 0 AND i.watched_count >= i.episode_count) OR (d.kind != 'SERIES' AND ws.completed = 1) THEN 'WATCHED'
            WHEN (d.kind = 'SERIES' AND i.watched_count > 0) OR (d.kind != 'SERIES' AND IFNULL(ws.position_ms, 0) > 0) THEN 'IN_PROGRESS' ELSE 'UNWATCHED' END)"""
        const val FROM = "FROM search_doc d JOIN library_item i ON i.id = d.item_id LEFT JOIN watch_state ws ON ws.item_id = d.item_id AND ws.episode_key = 0"
        const val SELECT = """SELECT d.item_id, d.kind, d.title, d.year, d.languages, d.genres, d.seasons, d.max_resolution, d.qualities, d.providers,
            d.rating, d.added_at, d.latest_added_at, d.available_count, d.total_size, d.text_title, d.text_alt, d.text_files, d.text_chats, d.text_extra,
            $WATCH AS watch_state, i.last_watched_at $FROM"""
    }
}
