package dev.yourstream.library.data

import androidx.sqlite.db.SimpleSQLiteQuery
import dev.yourstream.library.api.LibraryKind
import dev.yourstream.library.api.LibraryQuery
import dev.yourstream.library.api.LibrarySort
import dev.yourstream.library.api.WatchFilter
import java.text.Normalizer
import java.util.Locale

/**
 * Builds list SQL from a [LibraryQuery]. Only fixed fragments are concatenated (sorts and
 * filters are whitelisted); every user value is a bound argument. Each ORDER BY ends with the
 * primary key so pages are stable when titles or dates tie.
 */
object LibraryQueryBuilder {

    data class Sql(val sql: String, val args: List<Any?>) {
        fun query() = SimpleSQLiteQuery(sql, args.toTypedArray())
    }

    private const val COLUMNS = """i.id, i.kind, i.title, i.year, i.available_count, i.season_count, i.episode_count, i.watched_count,
        i.max_resolution, i.total_size, i.added_at, i.latest_added_at, i.last_watched_at,
        (f.item_id IS NOT NULL) AS is_favorite, ws.position_ms, ws.duration_ms, COALESCE(ws.completed, 0) AS completed,
        im.poster_url, im.title AS metadata_title"""

    private const val SERIES_WATCHED = "(i.kind = 'SERIES' AND i.episode_count > 0 AND i.watched_count >= i.episode_count)"
    private const val ITEM_WATCHED = "(i.kind != 'SERIES' AND ws.completed = 1)"

    fun list(q: LibraryQuery, favoritesOrder: Boolean = false): Sql {
        val args = mutableListOf<Any?>()
        val where = mutableListOf<String>()
        val joins = StringBuilder("LEFT JOIN favorite f ON f.item_id = i.id ")
        joins.append("LEFT JOIN watch_state ws ON ws.item_id = i.id AND ws.episode_key = 0 ")
        joins.append("LEFT JOIN item_metadata im ON im.item_id = i.id ")

        val match = ftsMatch(q.text)
        if (match != null) {
            joins.append("JOIN library_item_fts ON library_item_fts.rowid = i.id ")
            where += "library_item_fts MATCH ?"; args += match
        }
        if (!q.includeHidden) where += "i.hidden = 0"
        if (!q.includeUnavailable) where += "i.available_count > 0"
        if (q.kinds.isNotEmpty() && q.kinds.size < LibraryKind.entries.size) {
            where += "i.kind IN (${q.kinds.joinToString(",") { "?" }})"; args.addAll(q.kinds.map { it.name })
        }
        if (q.favoritesOnly || favoritesOrder) where += "f.item_id IS NOT NULL"
        when (q.watch) {
            WatchFilter.ANY -> Unit
            WatchFilter.UNWATCHED -> where += "i.last_watched_at IS NULL"
            WatchFilter.IN_PROGRESS -> where += "i.last_watched_at IS NOT NULL AND NOT $SERIES_WATCHED AND NOT $ITEM_WATCHED"
            WatchFilter.WATCHED -> where += "($SERIES_WATCHED OR $ITEM_WATCHED)"
        }
        q.yearFrom?.let { where += "i.year >= ?"; args += it }
        q.yearTo?.let { where += "i.year <= ?"; args += it }
        q.language?.let { where += "EXISTS (SELECT 1 FROM library_item_language lang WHERE lang.item_id = i.id AND lang.language = ?)"; args += it }
        q.minResolution?.let { where += "i.max_resolution >= ?"; args += it }

        val order = if (favoritesOrder) "f.added_at DESC, i.id DESC" else orderBy(q.sort, q.reversed)
        val sql = "SELECT $COLUMNS FROM library_item i $joins" +
            (if (where.isEmpty()) "" else "WHERE ${where.joinToString(" AND ")} ") +
            "ORDER BY $order"
        return Sql(sql, args)
    }

    /** One item by id, unfiltered (details screens). */
    fun byId(id: Long): Sql = Sql(
        "SELECT $COLUMNS FROM library_item i LEFT JOIN favorite f ON f.item_id = i.id " +
            "LEFT JOIN watch_state ws ON ws.item_id = i.id AND ws.episode_key = 0 LEFT JOIN item_metadata im ON im.item_id = i.id WHERE i.id = ?",
        listOf(id),
    )

    fun count(q: LibraryQuery): Sql {
        val list = list(q)
        val from = list.sql.substringAfter("FROM library_item i").substringBefore("ORDER BY")
        return Sql("SELECT COUNT(*) FROM library_item i$from", list.args)
    }

    private fun orderBy(sort: LibrarySort, reversed: Boolean): String {
        fun dir(naturalDesc: Boolean) = if (naturalDesc != reversed) "DESC" else "ASC"
        return when (sort) {
            LibrarySort.TITLE -> "i.sort_title ${dir(false)}, i.id ${dir(false)}"
            LibrarySort.RECENTLY_ADDED -> "i.latest_added_at ${dir(true)}, i.id ${dir(true)}"
            LibrarySort.RECENTLY_WATCHED -> "i.last_watched_at IS NULL, i.last_watched_at ${dir(true)}, i.id ${dir(true)}"
            LibrarySort.YEAR -> "i.year IS NULL, i.year ${dir(true)}, i.sort_title, i.id"
            LibrarySort.SIZE -> "i.total_size ${dir(true)}, i.id ${dir(true)}"
        }
    }

    private val LATIN_MARKS = Regex("(?<=\\p{IsLatin})\\p{Mn}+")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}\\p{M}]+")

    /** "Tom & Jérry" → "tom* and* jerry*". Only letters/digits survive: no FTS syntax injection. */
    fun ftsMatch(text: String): String? {
        val terms = normalize(text).split(' ').filter { it.isNotEmpty() }.take(8)
        return if (terms.isEmpty()) null else terms.joinToString(" ") { "$it*" }
    }

    fun normalize(text: String): String {
        val folded = Normalizer.normalize(LATIN_MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFKD), ""), Normalizer.Form.NFC)
        return NON_WORD.replace(folded.lowercase(Locale.ROOT).replace("&", " and ").replace("'", ""), " ").trim()
    }

    /** "The Matrix" → "matrix"; used for alphabetical order. */
    fun sortTitle(title: String): String {
        val n = normalize(title)
        return listOf("the ", "a ", "an ").firstOrNull { n.startsWith(it) && n.length > it.length }?.let { n.removePrefix(it) } ?: n
    }
}
