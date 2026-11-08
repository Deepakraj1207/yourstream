package dev.yourstream.search.engine

import androidx.test.core.app.ApplicationProvider
import dev.yourstream.core.database.YourStreamDatabase
import dev.yourstream.search.core.ItemKind
import dev.yourstream.search.core.SearchFilters
import dev.yourstream.search.core.SearchRequest
import dev.yourstream.search.core.SearchSort
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Locale
import kotlin.random.Random
import kotlin.test.assertTrue

/**
 * Search performance on synthetic libraries. Default: 2 000 items (quick check, part of the test
 * suite). Full: 20 000 and 50 000 items with `-Psearch.benchmark`. Report:
 * search/engine/build/reports/search-benchmark.md.
 */
@RunWith(RobolectricTestRunner::class)
class SearchBenchmarkTest {
    private val full = System.getProperty("search.benchmark") != null
    private val sizes = if (full) listOf(20_000, 50_000) else listOf(2_000)

    private val words = ("love story night city king queen war road last first dark light blood fire river mountain secret house " +
        "family brother sister father mother son daughter friend enemy game hunter killer police doctor school college village " +
        "kingdom empire legend journey return revenge escape island ocean storm thunder shadow ghost dream heart soul power " +
        "time space star planet robot dragon tiger lion eagle wolf snake magic spell sword shield gold silver diamond money " +
        "raja rani veera singam thalaivan kadhal nanban amma appa anna thambi kalyanam pongal deepavali vettai pandian").split(' ')
    private val seeded = listOf(
        "Doraemon" to "SERIES", "Tom and Jerry" to "SERIES", "Ben 10" to "SERIES", "Chhota Bheem" to "SERIES",
        "Shinchan" to "SERIES", "Motu Patlu" to "SERIES", "Spider-Man: Homecoming" to "MOVIE", "Vikram" to "MOVIE",
    )
    private val genres = listOf("Action", "Comedy", "Drama", "Animation", "Family", "Thriller", "Romance", "Horror", "Science Fiction", "Crime")
    private val languages = listOf("ta", "te", "hi", "ml", "kn", "en", "bn")
    private val qualities = listOf("WEB-DL", "BluRay", "HDRip", "WEBRip", "HDTV", "DVDRip")

    private data class Measure(val name: String, val p50: Double, val p95: Double, val max: Double, val results: Int)

    @Test
    fun benchmark() {
        val report = StringBuilder("# Search benchmark\n\nRobolectric (JVM, native SQLite), in-memory database. Times in ms.\n\n")
        for (n in sizes) report.append(run(n))
        File("build/reports").mkdirs()
        File("build/reports/search-benchmark.md").writeText(report.toString())
        println(report)
    }

    private fun run(n: Int): String = runBlocking {
        val db = YourStreamDatabase.inMemory(ApplicationProvider.getApplicationContext())
        val f = SearchFixture(db)
        val rnd = Random(n)
        val t0 = System.nanoTime()
        var files = 0
        f.transaction {
            (1..20L).forEach { chat(it, listOf("Tamil Movies", "Kids Cartoons", "Hindi Dubbed", "Telugu Hits", "Web Series")[(it % 5).toInt()] + " $it") }
            for (id in 1L..n) {
                val (title, kind) = seeded.getOrNull((id - 1).toInt())
                    ?: (List(rnd.nextInt(1, 4)) { words[rnd.nextInt(words.size)] }.joinToString(" ").replaceFirstChar { it.uppercase() } to
                        listOf("MOVIE", "MOVIE", "MOVIE", "SERIES", "SERIES", "VIDEO")[rnd.nextInt(6)])
                val year = rnd.nextInt(1980, 2026)
                item(id, title, kind, year = if (kind == "MOVIE") year else null, addedAt = rnd.nextLong(1, 1_000_000), episodes = if (kind == "SERIES") 10 else 0)
                if (rnd.nextInt(10) < 6) metadata(id, title, List(rnd.nextInt(1, 4)) { genres[rnd.nextInt(genres.size)] }.distinct(), rating = rnd.nextDouble(4.0, 9.5), year = year)
                language(id, *List(rnd.nextInt(1, 3)) { languages[rnd.nextInt(languages.size)] }.distinct().toTypedArray())
                if (kind == "SERIES") repeat(rnd.nextInt(1, 6)) { season(id, it + 1) }
                repeat(if (kind == "SERIES") rnd.nextInt(2, 6) else rnd.nextInt(1, 3)) { k ->
                    val name = title.replace(Regex("[^A-Za-z0-9]+"), ".") + (if (kind == "SERIES") ".S01E%02d".format(k + 1) else ".$year") +
                        ".${listOf("Tamil", "Hindi", "Telugu", "Tam+Hin", "English")[rnd.nextInt(5)]}.${listOf(480, 720, 1080, 2160)[rnd.nextInt(4)]}p.${qualities[rnd.nextInt(qualities.size)]}.mkv"
                    file(id, name, chatId = rnd.nextLong(1, 21), resolution = listOf(480, 720, 1080, 2160)[rnd.nextInt(4)], quality = qualities[rnd.nextInt(qualities.size)])
                    files++
                }
                if (rnd.nextInt(10) == 0) watch(id, rnd.nextLong(1, 100_000), completed = rnd.nextBoolean())
            }
        }
        val insertMs = ms(t0)
        val indexer = SearchIndexer(db)
        val engine = LibrarySearchEngine(db, indexer)
        val t1 = System.nanoTime()
        val indexed = indexer.flush()
        val indexMs = ms(t1)
        val rt = Runtime.getRuntime()
        System.gc(); val heapBefore = rt.totalMemory() - rt.freeMemory()
        val t2 = System.nanoTime()
        val vocabulary = indexer.terms()
        val vocabMs = ms(t2)
        System.gc(); val heapAfter = rt.totalMemory() - rt.freeMemory()

        val queries = listOf(
            "exact title" to SearchRequest("doraemon"),
            "prefix (typing)" to SearchRequest("dora"),
            "typo" to SearchRequest("doremon"),
            "transliteration" to SearchRequest("chota beem"),
            "two words" to SearchRequest("tom jerry"),
            "title + season" to SearchRequest("ben 10 s2"),
            "common words" to SearchRequest("love story"),
            "file-name words" to SearchRequest("tam hin 1080p"),
            "filters only (soft)" to SearchRequest("tamil comedy"),
            "hard filters + sort" to SearchRequest("", SearchFilters(languages = setOf("ta"), genres = setOf("action"), kinds = setOf(ItemKind.MOVIE)), SearchSort.YEAR),
            "operators" to SearchRequest("lang:ta year:2010-2020 action"),
            "no match" to SearchRequest("xqzzv"),
        )
        val measures = queries.map { (name, req) ->
            repeat(3) { indexer.version.incrementAndGet(); engine.search(req) } // warm up
            val times = (1..25).map {
                indexer.version.incrementAndGet() // defeat the result cache: measure a full search
                val s = System.nanoTime(); val p = engine.search(req); (System.nanoTime() - s) / 1e6 to p.total
            }
            val sorted = times.map { it.first }.sorted()
            Measure(name, sorted[sorted.size / 2], sorted[(sorted.size * 95) / 100 - 1].coerceAtLeast(sorted.last().takeIf { sorted.size < 20 } ?: 0.0), sorted.last(), times.last().second)
        }
        // Pages 2..5 of a ranked query come from the cache.
        engine.search(SearchRequest("love", limit = 30))
        val pageTimes = (1..4).map { p -> val s = System.nanoTime(); engine.search(SearchRequest("love", offset = p * 30, limit = 30)); (System.nanoTime() - s) / 1e6 }.sorted()

        // Relevance sanity at scale: the seeded titles are found first.
        assertTrue(engine.search(SearchRequest("doremon")).hits.first().title == "Doraemon")
        assertTrue(engine.search(SearchRequest("tom jerry")).hits.first().title == "Tom and Jerry")
        assertTrue(engine.search(SearchRequest("ben 10 s2")).hits.take(3).any { it.title == "Ben 10" })
        assertTrue(engine.search(SearchRequest("chota beem")).hits.first().title == "Chhota Bheem")
        db.close()

        buildString {
            appendLine("## ${"%,d".format(Locale.US, n)} items, ${"%,d".format(Locale.US, files)} files")
            appendLine()
            appendLine("- insert (fixture): ${insertMs.toInt()} ms; **index build: ${indexMs.toInt()} ms** for $indexed items (${(indexed / (indexMs / 1000)).toInt()} items/s)")
            appendLine("- vocabulary: ${"%,d".format(Locale.US, vocabulary.size)} words, loaded in ${vocabMs.toInt()} ms, ~${(heapAfter - heapBefore).coerceAtLeast(0) / (1024 * 1024)} MB heap")
            appendLine("- result pages 2–5 of a cached query: median ${"%.2f".format(Locale.US, pageTimes[pageTimes.size / 2])} ms")
            appendLine()
            appendLine("| Query | p50 | p95 | max | results |")
            appendLine("|---|---|---|---|---|")
            measures.forEach { appendLine("| ${it.name} | ${"%.1f".format(Locale.US, it.p50)} | ${"%.1f".format(Locale.US, it.p95)} | ${"%.1f".format(Locale.US, it.max)} | ${it.results} |") }
            appendLine()
        }
    }

    private fun ms(start: Long) = (System.nanoTime() - start) / 1e6
}
