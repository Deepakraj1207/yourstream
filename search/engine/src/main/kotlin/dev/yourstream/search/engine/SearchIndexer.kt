package dev.yourstream.search.engine

import androidx.room.withTransaction
import dev.yourstream.core.database.YourStreamDatabase
import dev.yourstream.core.database.dao.SearchItemSource
import dev.yourstream.core.database.entity.SearchDocEntity
import dev.yourstream.core.language.LanguageCatalog
import dev.yourstream.search.core.SearchText
import dev.yourstream.search.core.TermIndex
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the search rows. Triggers queue changed items in search_dirty; [flush] rebuilds exactly
 * those (in batches, one transaction each) and adds their words to the in-memory vocabulary used
 * for fuzzy matching. Runs before every search, so results never lag behind the library.
 */
@Singleton
class SearchIndexer @Inject constructor(private val db: YourStreamDatabase) {
    private val dao = db.searchDao()
    private val lock = Mutex()
    private var vocabulary: TermIndex? = null
    private var genreNames: Set<String>? = null

    /** Bumped whenever the index changes (invalidates cached result lists). */
    val version = AtomicLong()

    /** Rebuilds dirty rows. Returns how many items were processed. */
    suspend fun flush(batch: Int = 400): Int = lock.withLock {
        var total = 0
        while (true) {
            val ids = dao.dirty(batch)
            if (ids.isEmpty()) break
            val docs = build(ids)
            db.withTransaction {
                val present = docs.map { it.itemId }.toSet()
                dao.deleteDocs(ids.filter { it !in present })
                dao.upsertDocs(docs)
                dao.clearDirty(ids)
            }
            vocabulary?.let { v -> docs.forEach { d -> v.addAll(words(d)) } }
            if (docs.any { it.genres.length > 2 }) genreNames = null
            total += ids.size
        }
        if (total > 0) version.incrementAndGet()
        total
    }

    /** Rebuilds everything (e.g. after a normalization change). */
    suspend fun rebuildAll(): Int {
        dao.queueAll()
        lock.withLock { vocabulary = null }
        return flush()
    }

    /** The library's vocabulary (loaded on first use, then kept up to date by [flush]). */
    suspend fun terms(): TermIndex = lock.withLock {
        vocabulary ?: TermIndex().also { index ->
            var offset = 0
            while (true) {
                val rows = dao.vocabulary(offset, 2_000)
                if (rows.isEmpty()) break
                rows.forEach { r -> index.addAll(split(r.textTitle) + split(r.textAlt) + split(r.textChats) + split(r.textExtra)) }
                offset += rows.size
            }
            vocabulary = index
        }
    }

    /** Genres present in the library (normalized), for reading genre words in queries. */
    suspend fun genres(): Set<String> = genreNames ?: dao.genreSets().flatMap { it.value.split(',') }.filter { it.isNotBlank() }.toSet().also { genreNames = it }

    private fun words(d: SearchDocEntity) = split(d.textTitle) + split(d.textAlt) + split(d.textChats) + split(d.textExtra)

    private fun split(s: String) = s.split(' ').filter { it.isNotEmpty() }

    private suspend fun build(ids: List<Long>): List<SearchDocEntity> {
        val items = dao.itemSources(ids)
        if (items.isEmpty()) return emptyList()
        val languages = dao.languages(ids).groupBy({ it.itemId }, { it.value })
        val seasons = dao.seasons(ids).groupBy({ it.itemId }, { it.value })
        val files = dao.files(ids).groupBy { it.itemId }
        val chats = dao.chatTitles(files.values.flatten().map { it.chatId }.distinct()).associate { it.chatId to it.title }
        val now = System.currentTimeMillis()
        return items.map { item -> doc(item, languages[item.id].orEmpty(), seasons[item.id].orEmpty(), files[item.id].orEmpty(), chats, now) }
    }

    private fun doc(
        item: SearchItemSource,
        languages: List<String>,
        seasons: List<String>,
        files: List<dev.yourstream.core.database.dao.SearchFileSource>,
        chats: Map<Long, String>,
        now: Long,
    ): SearchDocEntity {
        val titleTokens = (SearchText.tokens(item.title) + SearchText.tokens(item.normalizedTitle)).distinct()
        val metaTokens = item.metaTitle?.let(SearchText::tokens).orEmpty() + item.originalTitle?.let(SearchText::tokens).orEmpty()
        val alt = (metaTokens + SearchText.tokens(item.searchText) + SearchText.compounds(SearchText.tokens(item.title)) +
            item.metaTitle?.let { SearchText.compounds(SearchText.tokens(it)) }.orEmpty()).distinct().filter { it !in titleTokens }
        val fileWords = files.asSequence().flatMap { SearchText.tokens(it.fileName.substringBeforeLast('.')) }.distinct().take(MAX_FILE_WORDS).toList()
        val chatIds = files.map { it.chatId }.distinct()
        val chatWords = chatIds.mapNotNull(chats::get).flatMap(SearchText::tokens).distinct()
        val langs = (languages + listOfNotNull(item.originalLanguage)).mapNotNull(LanguageCatalog::normalizeCode).distinct()
        val genres = item.genres?.split(',')?.map { SearchText.normalize(it) }?.filter { it.isNotEmpty() }?.distinct().orEmpty()
        val extra = (genres.flatMap { it.split(' ') } + langs.flatMap { code ->
            val l = LanguageCatalog.of(code)
            SearchText.tokens(listOfNotNull(l?.name, l?.nativeName).joinToString(" "))
        } + listOfNotNull(item.provider)).distinct()
        val qualities = files.mapNotNull { it.quality?.let(SearchText::normalize)?.takeIf(String::isNotEmpty) }.distinct()
        val maxRes = (files.mapNotNull { it.resolution } + listOfNotNull(item.maxResolution)).maxOrNull()
        return SearchDocEntity(
            itemId = item.id, kind = item.kind, title = item.metaTitle ?: item.title, sortTitle = item.sortTitle, year = item.year,
            languages = set(langs), genres = set(genres), seasons = set(seasons), maxResolution = maxRes, qualities = set(qualities),
            chatIds = set(chatIds.map(Long::toString)), providers = set(listOfNotNull(item.provider)), rating = item.rating,
            addedAt = item.addedAt, latestAddedAt = item.latestAddedAt, availableCount = item.availableCount, totalSize = item.totalSize,
            hidden = item.hidden, textTitle = (titleTokens + (if (item.metaTitle != null) SearchText.tokens(item.metaTitle!!) else emptyList())).distinct().joinToString(" "),
            textAlt = alt.joinToString(" "), textFiles = fileWords.joinToString(" "), textChats = chatWords.joinToString(" "),
            textExtra = extra.joinToString(" "), indexedAt = now,
        )
    }

    /** ",a,b," — and "," when empty (so `= ','` finds "none"). */
    private fun set(values: List<String>) = if (values.isEmpty()) "," else values.joinToString(",", prefix = ",", postfix = ",")

    private companion object { const val MAX_FILE_WORDS = 300 }
}
