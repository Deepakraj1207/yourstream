package dev.yourstream.e2e

import androidx.test.core.app.ApplicationProvider
import dev.yourstream.catalog.core.DiscoverRequest
import dev.yourstream.library.api.LibraryQuery
import dev.yourstream.library.api.PlaybackTarget
import dev.yourstream.search.core.SearchRequest
import dev.yourstream.telegram.api.model.TelegramFile
import dev.yourstream.telegram.api.model.TelegramMediaContentType
import androidx.paging.testing.asSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Locale
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Thousands of media records through the whole pipeline, timed per stage, plus a query-plan audit
 * of every SQL statement Room executed: full scans of large tables are reported.
 */
@RunWith(RobolectricTestRunner::class)
class ScaleAuditTest {
    private lateinit var p: Pipeline
    @After fun close() = p.close()

    private val lines = mutableListOf<String>()
    private fun metric(name: String, ms: Double, extra: String = "") { lines += "| $name | ${"%.1f".format(Locale.US, ms)} | $extra |" }
    private inline fun <T> timed(name: String, extra: String = "", block: () -> T): T {
        val s = System.nanoTime(); val r = block(); metric(name, (System.nanoTime() - s) / 1e6, extra); return r
    }

    private val words = ("raja rani veera singam thalaivan kadhal nanban amma appa vettai pandian love story night city king queen war road " +
        "dark light blood fire river mountain secret house family brother sister friend enemy hunter police doctor kingdom legend journey " +
        "revenge escape island storm shadow ghost dream heart power time star tiger lion eagle wolf magic sword gold silver").split(' ')
    private val langs = listOf("Tamil", "Telugu", "Hindi", "Malayalam", "Tam.Tel.Hin", "English")
    private val quals = listOf("480p.HDRip", "720p.WEB-DL", "1080p.BluRay", "2160p.WEB-DL", "720p.HDTV")

    /** Timings without query recording (it slows every statement down). */
    @Test fun thousandsOfRecords() = scenario(movies = 4_000, shows = 150, recordQueries = false, reportName = "scale-audit.md")

    /** Every SQL statement Room runs, on a smaller library, checked with EXPLAIN QUERY PLAN. */
    @Test fun queryPlanAudit() = scenario(movies = 1_000, shows = 40, recordQueries = true, reportName = "query-plan-audit.md")

    private fun scenario(movies: Int, shows: Int, recordQueries: Boolean, reportName: String): Unit = runBlocking {
        p = Pipeline(ApplicationProvider.getApplicationContext(), recordQueries)
        val rnd = Random(42)
        val episodesPerShow = 12
        p.chat(1, "Movies"); p.chat(2, "Series"); p.chat(3, "Mixed")
        var mid = 1L
        fun file(id: Long) = TelegramFile(id.toInt(), "u$id", "r$id", 700_000_000L + id, 700_000_000L + id, null, true, false, false, 0, 0, 0)
        val movieTitles = mutableListOf<Pair<String, Int>>()
        repeat(movies) { i ->
            val title = List(rnd.nextInt(1, 4)) { words[rnd.nextInt(words.size)] }.joinToString(".") { it.replaceFirstChar(Char::uppercase) }
            val year = rnd.nextInt(1980, 2026)
            movieTitles += title.replace('.', ' ') to year
            val chat = if (i % 3 == 0) 3L else 1L
            p.telegram.add(message(chat, mid, "$title.$year.${langs[rnd.nextInt(langs.size)]}.${quals[rnd.nextInt(quals.size)]}.mkv", file(mid), TelegramMediaContentType.VIDEO, 7200))
            mid++
        }
        repeat(shows) { s ->
            val show = List(2) { words[rnd.nextInt(words.size)] }.joinToString(".") { it.replaceFirstChar(Char::uppercase) } + ".Show$s"
            for (e in 1..episodesPerShow) {
                p.telegram.add(message(2, mid, "$show.S0${1 + e / 7}E%02d.${langs[rnd.nextInt(langs.size)]}.720p.mkv".format(e), file(mid), TelegramMediaContentType.VIDEO, 1320))
                mid++
            }
        }
        val total = mid - 1
        // Metadata for a sample of titles.
        movieTitles.shuffled(rnd).take(300).forEach { (t, y) -> p.tmdb.movie(t, y, "ta", "Action", "Drama") }

        timed("index ${"%,d".format(total)} messages (3 chats)", "${p.telegram.searchCalls.get()} page requests before") {
            for (chat in 1L..3L) p.engine.run(chat, dev.yourstream.media.api.model.IndexMode.INCREMENTAL, kotlin.time.Duration.parse("10m"))
        }
        lines += "| (page requests to Telegram) | ${p.telegram.searchCalls.get()} | ${total} messages / ${p.telegram.searchCalls.get()} requests |"
        val synced = timed("library sync") { p.sync.sync() }
        val items = p.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM library_item").use { it.moveToFirst(); it.getInt(0) }
        lines += "| (library items) | $items | ${synced} |"
        assertTrue(items > movies * 0.7, "items $items")
        timed("search index build") { p.searchIndexer.flush() }
        timed("metadata match run (1 000 items max)", "${p.tmdb.calls.get()} provider calls before") { p.matchMetadata() }
        lines += "| (provider calls) | ${p.tmdb.calls.get()} | for up to 1 000 items: cache + one search per title |"

        // Re-index with nothing new: must be cheap (incremental) and queue nothing downstream.
        p.sync.sync(); p.searchIndexer.flush() // drain what metadata matching queued
        val before = p.telegram.searchCalls.get()
        timed("incremental re-index, nothing new") { for (chat in 1L..3L) p.engine.run(chat, dev.yourstream.media.api.model.IndexMode.INCREMENTAL, kotlin.time.Duration.parse("10m")) }
        lines += "| (page requests, nothing new) | ${p.telegram.searchCalls.get() - before} | |"
        timed("verify run (re-reads every message)") { for (chat in 1L..3L) p.engine.run(chat, dev.yourstream.media.api.model.IndexMode.VERIFY, kotlin.time.Duration.parse("10m")) }
        val queued = p.db.openHelper.readableDatabase.query("SELECT (SELECT COUNT(*) FROM library_pending) + (SELECT COUNT(*) FROM search_dirty)").use { it.moveToFirst(); it.getInt(0) }
        lines += "| (rows queued for re-sync after unchanged re-index + verify) | $queued | must be 0 |"
        assertEquals(0, queued, "unchanged rows must not re-queue the library/search")
        timed("library sync, nothing new") { p.sync.sync() }

        // Reads.
        for ((name, q) in listOf("search exact" to "raja rani", "search typo" to "thalaivn", "search filters" to "tamil 1080p", "search series" to "show12 s01")) {
            p.search.search(SearchRequest(q)) // warm
            timed(name) { p.search.search(SearchRequest(q + " ")) }
        }
        timed("library list via Paging (initial load 150 + count)") { p.media.search(LibraryQuery(), 50).asSnapshot() }
        val listSql = dev.yourstream.library.data.LibraryQueryBuilder.list(LibraryQuery())
        timed("library list raw SQL page (LIMIT 50)") { p.db.openHelper.readableDatabase.query(listSql.sql + " LIMIT 50", listSql.args.toTypedArray()).use { c -> while (c.moveToNext()) Unit } }
        val countSql = dev.yourstream.library.data.LibraryQueryBuilder.count(LibraryQuery())
        timed("library count raw SQL") { p.db.openHelper.readableDatabase.query(countSql.sql, countSql.args.toTypedArray()).use { it.moveToFirst() } }
        val plan = p.db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN " + listSql.sql, listSql.args.toTypedArray()).use { c -> buildList { while (c.moveToNext()) add(c.getString(3)) } }
        lines += "| (library list plan) | | ${plan.joinToString("; ")} |"
        timed("assistant: unwatched tamil movies") { p.assistant.ask("show unwatched tamil movies") }
        // Watch activity on 300 items / 40 series.
        val ids = p.db.openHelper.readableDatabase.query("SELECT id, kind FROM library_item ORDER BY id LIMIT 2000").use { c -> buildList { while (c.moveToNext()) add(c.getLong(0) to c.getString(1)) } }
        timed("record 300 progress updates") {
            ids.filter { it.second == "MOVIE" }.take(260).forEachIndexed { i, (id, _) -> p.clock.now += 60_000; p.history.updateProgress(PlaybackTarget(id), null, (i % 10 + 1) * 600_000L, 7_200_000) }
            val series = p.db.openHelper.readableDatabase.query("SELECT series_id, id FROM episode WHERE number = 1 LIMIT 40").use { c -> buildList { while (c.moveToNext()) add(c.getLong(0) to c.getLong(1)) } }
            series.forEach { (s, e) -> p.clock.now += 60_000; p.history.updateProgress(PlaybackTarget(s, e), null, 1_300_000, 1_320_000) }
        }
        val cw = timed("continue watching (300 tracked)") { p.watch.continueNow(20) }
        assertEquals(20, cw.size)
        timed("recently watched") { p.watch.recentlyWatchedNow(30) }
        timed("recently added") { p.watch.recentlyAddedNow(30) }
        timed("what should I watch next?") { p.assistant.ask("what should I watch next?") }
        timed("catalog presence (2 items)") { p.catalogs.withLibraryPresence(p.catalog.items) }

        // Query-plan audit.
        val big = setOf("media", "media_parsed", "library_item", "episode", "watch_state", "watch_history", "search_doc", "media_link", "item_metadata", "metadata_match", "season")
        val scans = mutableListOf<String>()
        val sql = p.db.openHelper.readableDatabase
        Thread.sleep(300) // query callback executor
        for ((q, n) in p.queries.entries.sortedByDescending { it.value.get() }) {
            val t = q.trim()
            if (!t.startsWith("SELECT", ignoreCase = true) && !t.startsWith("WITH", ignoreCase = true)) continue
            val plan = runCatching { sql.query("EXPLAIN QUERY PLAN $t", arrayOfNulls<Any?>(t.count { it == '?' })).use { c -> buildList { while (c.moveToNext()) add(c.getString(3)) } } }.getOrNull() ?: continue
            val bad = plan.filter { d -> d.startsWith("SCAN ") && !d.contains("USING") && big.any { b -> Regex("^SCAN $b\\b|^SCAN \\w+ ?\\(?$b").containsMatchIn(d) || d == "SCAN $b" || d.startsWith("SCAN $b ") } }
            if (bad.isNotEmpty()) scans += "- ×${n.get()} `${t.replace(Regex("\\s+"), " ").take(220)}` → ${bad.joinToString("; ")}"
        }
        val report = buildString {
            appendLine("# Scale audit: ${"%,d".format(Locale.US, total)} media messages → $items library items\n")
            appendLine("Robolectric (JVM), in-memory SQLite. Times in ms.\n")
            appendLine("| Stage | ms / value | notes |"); appendLine("|---|---|---|")
            lines.forEach { appendLine(it) }
            if (recordQueries) appendLine("\n## Full scans of large tables (${scans.size} distinct queries, ${p.queries.size} distinct statements recorded)\n")
            if (recordQueries) scans.forEach { appendLine(it) }
        }
        File("build/reports").mkdirs(); File("build/reports/$reportName").writeText(report)
        println(report)
    }
}
