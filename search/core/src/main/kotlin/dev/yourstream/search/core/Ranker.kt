package dev.yourstream.search.core

/** What the ranker needs to know about one library item (built from the search index row). */
data class SearchDoc(
    val itemId: Long,
    val kind: ItemKind,
    val title: String,
    val year: Int?,
    val titleTokens: List<String>,
    val altTokens: Set<String>,
    val fileTokens: Set<String>,
    val chatTokens: Set<String>,
    val extraTokens: Set<String>,
    val languages: Set<String>,
    val genres: Set<String>,
    val seasons: Set<Int>,
    val maxResolution: Int?,
    val qualities: Set<String>,
    val providers: Set<String>,
    val watch: WatchState,
    val availableCount: Int,
    val addedAt: Long,
    val lastWatchedAt: Long?,
    val rating: Double?,
    val totalSize: Long,
)

/**
 * Scores an item against a parsed query.
 *
 * Per query word, the best match over the item's fields:
 *   exact word 1.0 · prefix 0.75 (0.5 for one/two letters) · typo 0.7 (d=1) / 0.55 (d=2) ·
 *   transliteration twin 0.6
 * weighted by field: title 1.0, alternative/official titles 0.85, chat name 0.5, genres/languages
 * 0.45, file names 0.4. Every word must match (coverage 1) unless the engine is in relaxed mode.
 *
 * On top: the whole title equal to the query +5, the title starting with the query words +2,
 * shorter titles that the query covers more completely up to +1.5, each satisfied soft filter
 * +2 (season/episode +3) and −1 when not satisfied, and small priors (files available,
 * matched metadata, rating).
 */
object Ranker {
    private const val W_TITLE = 1.0
    private const val W_ALT = 0.85
    private const val W_CHAT = 0.5
    private const val W_EXTRA = 0.45
    private const val W_FILE = 0.4

    fun score(doc: SearchDoc, q: ParsedQuery, expansions: Map<String, List<TermMatch>>, minCoverage: Double = 1.0): SearchHit? {
        val hits = mutableListOf<TermHit>()
        var sum = 0.0
        var matched = 0
        for (term in q.terms) {
            var best = 0.0
            var bestHit: TermHit? = null
            fun consider(tokens: Collection<String>, weight: Double, field: MatchField) {
                val titles = field == MatchField.TITLE || field == MatchField.ALT_TITLE
                for (t in tokens) {
                    val (s, kind) = matchToken(term, t, expansions[term]) ?: continue
                    if (kind == TermMatchKind.PREFIX && !titles && isShort(term)) continue
                    if (s * weight > best) { best = s * weight; bestHit = TermHit(term, t, kind, field) }
                }
            }
            consider(doc.titleTokens, W_TITLE, MatchField.TITLE)
            if (best < W_TITLE) consider(doc.altTokens, W_ALT, MatchField.ALT_TITLE)
            if (best < W_ALT) consider(doc.chatTokens, W_CHAT, MatchField.CHAT)
            if (best < W_CHAT) consider(doc.extraTokens, W_EXTRA, MatchField.EXTRA)
            if (best < W_EXTRA) consider(doc.fileTokens, W_FILE, MatchField.FILE_NAME)
            if (bestHit != null) { matched++; sum += best; hits += bestHit!! }
        }
        // `in:<chat>` words must be found in the chat names.
        for (w in q.chatWords) {
            if (doc.chatTokens.none { it == w || it.startsWith(w) || (expansions[w]?.any { e -> e.term == it } == true) }) return null
        }
        val coverage = if (q.terms.isEmpty()) 1.0 else matched.toDouble() / q.terms.size
        if (coverage < minCoverage) return null

        var score = if (q.terms.isEmpty()) 0.0 else sum / q.terms.size * 10 * coverage
        val titleNorm = doc.titleTokens.filter { it !in SearchText.STOPWORDS }
        if (q.terms.isNotEmpty()) {
            if (titleNorm == q.terms || doc.titleTokens == q.terms) score += 5
            else if (titleNorm.take(q.terms.size) == q.terms) score += 2
            val titleHits = hits.count { it.field == MatchField.TITLE }
            if (titleNorm.isNotEmpty()) score += 1.5 * (titleHits.toDouble() / titleNorm.size).coerceAtMost(1.0)
        }
        val satisfied = mutableListOf<SoftFilter>()
        for (f in q.soft) {
            val ok = satisfies(doc, f)
            if (ok) { satisfied += f; score += if (f is SoftFilter.Season || f is SoftFilter.Episode) 3 else 2 }
            else if (f.words.any { w -> w in doc.titleTokens }) score += 1 // the word is in the title ("Tamil Padam")
            else score -= 1
        }
        if (doc.availableCount > 0) score += 0.3
        if (doc.providers.isNotEmpty()) score += 0.2
        doc.rating?.let { score += it / 10 * 0.3 }
        val season = q.soft.filterIsInstance<SoftFilter.Season>().firstOrNull()?.takeIf { it.season in doc.seasons }
        val episode = season?.episode ?: q.soft.filterIsInstance<SoftFilter.Episode>().firstOrNull()?.episode
        return SearchHit(doc.itemId, doc.kind, doc.title, doc.year, score, hits, satisfied, season?.season, episodeNumber = episode.takeIf { doc.kind == ItemKind.SERIES })
    }

    /** Words that only prefix-match titles (too ambiguous as prefixes elsewhere). */
    fun isShort(term: String) = term.length < 4 || term.all(Char::isDigit)

    /** Score and match kind of [term] against one indexed word, or null. */
    fun matchToken(term: String, token: String, expansions: List<TermMatch>?): Pair<Double, TermMatchKind>? {
        if (token == term) return 1.0 to TermMatchKind.EXACT
        if (token.startsWith(term)) return (if (term.length >= 3) 0.75 else 0.5) to TermMatchKind.PREFIX
        expansions?.firstOrNull { it.term == token }?.let { e ->
            return when (e.kind) {
                TermMatchKind.PHONETIC -> 0.6 to TermMatchKind.PHONETIC
                else -> (if (e.distance <= 1) 0.7 else 0.55) to TermMatchKind.FUZZY
            }
        }
        return null
    }

    fun satisfies(doc: SearchDoc, f: SoftFilter): Boolean = when (f) {
        is SoftFilter.Year -> doc.year == f.year
        is SoftFilter.Season -> f.season in doc.seasons
        is SoftFilter.Episode -> doc.kind == ItemKind.SERIES
        is SoftFilter.Resolution -> (doc.maxResolution ?: 0) >= f.min
        is SoftFilter.Quality -> f.quality in doc.qualities
        is SoftFilter.Language -> f.code in doc.languages
        is SoftFilter.Genre -> f.genre in doc.genres
        is SoftFilter.Kind -> doc.kind == f.kind
        is SoftFilter.Watched -> doc.watch == f.state
        is SoftFilter.Provider -> if (f.provider == "unmatched") doc.providers.isEmpty() else f.provider in doc.providers
    }
}
