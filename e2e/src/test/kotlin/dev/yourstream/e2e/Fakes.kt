package dev.yourstream.e2e

import dev.yourstream.catalog.api.CatalogDescriptor
import dev.yourstream.catalog.api.CatalogExtra
import dev.yourstream.catalog.api.CatalogItem
import dev.yourstream.catalog.api.CatalogItemRef
import dev.yourstream.catalog.api.CatalogMetadata
import dev.yourstream.catalog.api.CatalogPage
import dev.yourstream.catalog.api.CatalogProvider
import dev.yourstream.catalog.api.CatalogProviderId
import dev.yourstream.catalog.api.CatalogProviderInfo
import dev.yourstream.catalog.api.CatalogType
import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import dev.yourstream.core.common.Clock
import dev.yourstream.metadata.api.CastMember
import dev.yourstream.metadata.api.EpisodeMetadata
import dev.yourstream.metadata.api.MetadataCandidate
import dev.yourstream.metadata.api.MetadataProvider
import dev.yourstream.metadata.api.MetadataQuery
import dev.yourstream.metadata.api.MetadataRef
import dev.yourstream.metadata.api.MetadataType
import dev.yourstream.metadata.api.MovieMetadata
import dev.yourstream.metadata.api.ProviderCapability
import dev.yourstream.metadata.api.ProviderId
import dev.yourstream.metadata.api.SeasonMetadata
import dev.yourstream.metadata.api.SeriesMetadata
import dev.yourstream.telegram.api.TelegramMessageRepository
import dev.yourstream.telegram.api.model.MediaMessagePage
import dev.yourstream.telegram.api.model.MediaSearchFilter
import dev.yourstream.telegram.api.model.MessageLookup
import dev.yourstream.telegram.api.model.TelegramFile
import dev.yourstream.telegram.api.model.TelegramMediaContentType
import dev.yourstream.telegram.api.model.TelegramMediaFile
import dev.yourstream.telegram.api.model.TelegramMediaMessage
import dev.yourstream.telegram.api.model.TelegramMessageEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class TestClock(@Volatile var now: Long = 1_700_000_000_000L) : Clock { override fun nowMillis() = now }

/** Chats holding media messages, paged like TDLib; [online] = false makes every call fail as when Telegram is unreachable. */
class FakeTelegramMessages : TelegramMessageRepository {
    val chats = ConcurrentHashMap<Long, MutableMap<Long, TelegramMediaMessage>>()
    @Volatile var online = true
    val searchCalls = AtomicInteger()
    val lookupCalls = AtomicInteger()
    override val events = MutableSharedFlow<TelegramMessageEvent>(extraBufferCapacity = 64)

    fun add(m: TelegramMediaMessage) { chats.getOrPut(m.chatId) { java.util.Collections.synchronizedMap(mutableMapOf()) }[m.messageId] = m }
    fun delete(chatId: Long, messageId: Long) { chats[chatId]?.remove(messageId) }

    override suspend fun searchMedia(chatId: Long, filter: MediaSearchFilter, fromMessageId: Long, limit: Int): AppResult<MediaMessagePage> {
        searchCalls.incrementAndGet()
        if (!online) return AppResult.Err(AppError.Network("Telegram unreachable"))
        val all = chats[chatId].orEmpty().values.filter { it.contentType.name == filter.name }
        val matching = all.filter { fromMessageId == 0L || it.messageId < fromMessageId }.sortedByDescending { it.messageId }
        val page = matching.take(limit)
        return AppResult.Ok(MediaMessagePage(page, if (matching.size > limit) page.last().messageId else 0L, all.size))
    }

    override suspend fun lookupMessages(chatId: Long, messageIds: List<Long>): AppResult<List<MessageLookup>> {
        lookupCalls.incrementAndGet()
        if (!online) return AppResult.Err(AppError.Network("Telegram unreachable"))
        return AppResult.Ok(messageIds.map { id -> chats[chatId]?.get(id)?.let { MessageLookup.Found(it) } ?: MessageLookup.Missing(id) })
    }
}

fun message(chatId: Long, messageId: Long, name: String, file: TelegramFile, type: TelegramMediaContentType, durationSeconds: Int? = null, caption: String = "") =
    TelegramMediaMessage(
        chatId = chatId, messageId = messageId, date = 1_600_000_000L + messageId, editDate = 0, albumId = 0, caption = caption, contentType = type,
        media = TelegramMediaFile(file, name, if (name.endsWith(".srt")) "application/x-subrip" else "video/mp4", durationSeconds, null, null, type == TelegramMediaContentType.VIDEO, null, null, null, null),
    )

/** A TMDB-like provider: movies and series with seasons; can be switched off. */
class FakeMetadataProvider(private val name: String = "tmdb") : MetadataProvider {
    override val id = ProviderId(name)
    override val displayName = name
    override val capabilities = ProviderCapability.entries.toSet()
    override var isConfigured = true
    @Volatile var down = false
    val calls = AtomicInteger()
    private val items = mutableListOf<MetadataCandidate>()
    private val genres = mutableMapOf<String, List<String>>()

    fun movie(title: String, year: Int, language: String, vararg genre: String, imdb: String? = null) {
        items += MetadataCandidate(MetadataRef(id, MetadataType.MOVIE, "m-$title", imdb), title, year = year, originalLanguage = language, popularity = 50.0)
        genres["m-$title"] = genre.toList()
    }

    fun series(title: String, year: Int, language: String, vararg genre: String) {
        items += MetadataCandidate(MetadataRef(id, MetadataType.SERIES, "s-$title"), title, year = year, originalLanguage = language, popularity = 50.0)
        genres["s-$title"] = genre.toList()
    }

    private fun check(): AppError? { calls.incrementAndGet(); return if (down) AppError.Network("provider unreachable") else null }

    override suspend fun search(query: MetadataQuery): AppResult<List<MetadataCandidate>> {
        check()?.let { return AppResult.Err(it) }
        val q = query.title.lowercase()
        return AppResult.Ok(items.filter { (query.type == null || it.ref.type == query.type) && it.title.lowercase().let { t -> t == q || t.startsWith(q) || q.startsWith(t) } })
    }

    override suspend fun movie(id: String, language: String?): AppResult<MovieMetadata> {
        check()?.let { return AppResult.Err(it) }
        val c = items.firstOrNull { it.ref.id == id } ?: return AppResult.Err(AppError.NotFound(id))
        return AppResult.Ok(MovieMetadata(c.ref, c.title, overview = "About ${c.title}", genres = genres[id].orEmpty(), year = c.year,
            releaseDate = "${c.year}-06-01", originalLanguage = c.originalLanguage, runtimeMinutes = 150, rating = 7.8, cast = listOf(CastMember("Star"))))
    }

    override suspend fun series(id: String, language: String?): AppResult<SeriesMetadata> {
        check()?.let { return AppResult.Err(it) }
        val c = items.firstOrNull { it.ref.id == id } ?: return AppResult.Err(AppError.NotFound(id))
        return AppResult.Ok(SeriesMetadata(c.ref, c.title, overview = "Series ${c.title}", genres = genres[id].orEmpty(), year = c.year, originalLanguage = c.originalLanguage))
    }

    override suspend fun season(seriesId: String, seasonNumber: Int, language: String?): AppResult<SeasonMetadata> {
        check()?.let { return AppResult.Err(it) }
        val c = items.firstOrNull { it.ref.id == seriesId } ?: return AppResult.Err(AppError.NotFound(seriesId))
        return AppResult.Ok(SeasonMetadata(c.ref, seasonNumber, "Season $seasonNumber", episodes = (1..20).map { EpisodeMetadata(c.ref, seasonNumber, it, "Episode $it title") }))
    }

    override suspend fun episode(seriesId: String, seasonNumber: Int, episodeNumber: Int, language: String?) = AppResult.Err(AppError.NotFound(seriesId))

    override suspend fun findByImdbId(imdbId: String, type: MetadataType): AppResult<MetadataRef> =
        items.firstOrNull { it.ref.imdbId == imdbId }?.let { AppResult.Ok(it.ref) } ?: AppResult.Err(AppError.NotFound(imdbId))
}

/** A Stremio-like Tamil movie catalog. */
class FakeCatalog : CatalogProvider {
    override val id = CatalogProviderId("tamil")
    override val displayName = "Tamil catalog"
    override val attribution = "test"
    @Volatile var down = false
    val calls = AtomicInteger()
    private val descriptor = CatalogDescriptor(id, "top", CatalogType.MOVIE, "Top Tamil", languages = listOf("ta"), extras = listOf(CatalogExtra("skip")))
    val items = listOf(
        CatalogItem(CatalogItemRef(id, CatalogType.MOVIE, "tt9179430", "tt9179430"), "Vikram", 2022, catalogId = "top", languages = listOf("ta")),
        CatalogItem(CatalogItemRef(id, CatalogType.MOVIE, "tt15654328", "tt15654328"), "Jailer", 2023, catalogId = "top", languages = listOf("ta")),
    )

    private fun err() = AppResult.Err(AppError.Network("catalog unreachable"))
    override suspend fun describe(): AppResult<CatalogProviderInfo> { calls.incrementAndGet(); return if (down) err() else AppResult.Ok(CatalogProviderInfo(id, "Tamil", "1", null, listOf(descriptor), listOf("catalog"), emptyList(), "https://fake")) }
    override suspend fun page(catalog: CatalogDescriptor, skip: Int, genre: String?): AppResult<CatalogPage> { calls.incrementAndGet(); return if (down) err() else AppResult.Ok(CatalogPage(items.drop(skip), null)) }
    override suspend fun search(query: String, type: CatalogType?) = AppResult.Err(AppError.InvalidState("no search"))
    override suspend fun metadata(ref: CatalogItemRef): AppResult<CatalogMetadata> = AppResult.Err(AppError.InvalidState("no meta"))
}
