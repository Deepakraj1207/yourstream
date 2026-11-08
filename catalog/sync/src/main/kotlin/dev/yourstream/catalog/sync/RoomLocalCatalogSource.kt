package dev.yourstream.catalog.sync

import dev.yourstream.catalog.api.CatalogItem
import dev.yourstream.catalog.api.CatalogItemRef
import dev.yourstream.catalog.api.CatalogMetadata
import dev.yourstream.catalog.api.CatalogProviderId
import dev.yourstream.catalog.api.CatalogType
import dev.yourstream.catalog.api.LocalCatalogSource
import dev.yourstream.core.database.YourStreamDatabase
import dev.yourstream.core.database.dao.LocalCatalogRow
import dev.yourstream.library.data.LibraryQueryBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user's own library (Telegram files + applied metadata) as the last step of the catalog
 * fallback chain. Works offline and without any provider. Only titles with available files are
 * listed, so everything here is actually watchable.
 */
@Singleton
class RoomLocalCatalogSource @Inject constructor(db: YourStreamDatabase) : LocalCatalogSource {
    private val dao = db.catalogDao()

    override suspend fun discover(language: String?, type: CatalogType?, skip: Int, limit: Int): List<CatalogItem> =
        dao.localItems(kinds(type), language?.lowercase(), skip, limit).map { it.toItem() }

    override suspend fun search(query: String, type: CatalogType?, limit: Int): List<CatalogItem> {
        val match = LibraryQueryBuilder.ftsMatch(query) ?: return emptyList()
        return dao.searchLocal(match, kinds(type), limit).map { it.toItem() }
    }

    /** Applied metadata of the library item the catalog item is (or refers to); null if none. */
    override suspend fun metadata(item: CatalogItem): CatalogMetadata? {
        val row = localId(item.ref)?.let { dao.localItem(it) }
            ?: item.ref.imdbId?.let { imdb -> dao.presenceByImdb(listOf(imdb)).firstOrNull()?.let { dao.localItem(it.id) } }
            ?: return null
        if (row.metaTitle == null) return null
        return CatalogMetadata(
            ref = item.ref.copy(imdbId = item.ref.imdbId ?: row.imdbId),
            title = row.metaTitle ?: row.title,
            year = row.year,
            description = row.overview,
            posterUrl = row.posterUrl,
            backdropUrl = row.backdropUrl,
            genres = row.genres.csv(),
            cast = castNames(row.cast),
            directors = row.directors.csv(),
            runtimeMinutes = row.runtimeMinutes,
            rating = row.rating,
            releaseDate = row.releaseDate,
            originalLanguage = row.originalLanguage,
        )
    }

    private fun LocalCatalogRow.toItem() = CatalogItem(
        ref = CatalogItemRef(
            provider = CatalogProviderId.LOCAL,
            type = if (kind == "SERIES") CatalogType.SERIES else CatalogType.MOVIE,
            id = "$ID_PREFIX$id",
            imdbId = imdbId,
            tmdbId = externalId.takeIf { metaProvider == "tmdb" },
        ),
        title = metaTitle ?: title,
        year = year,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        description = overview,
        genres = genres.csv(),
        rating = rating,
        runtimeMinutes = runtimeMinutes,
        directors = directors.csv(),
        languages = (languages.csv() + listOfNotNull(originalLanguage)).distinct(),
    )

    private fun kinds(type: CatalogType?) = when (type) {
        CatalogType.MOVIE -> listOf("MOVIE")
        CatalogType.SERIES -> listOf("SERIES")
        null -> listOf("MOVIE", "SERIES")
    }

    private fun String?.csv() = this?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    private fun castNames(encoded: String?): List<String> = runCatching {
        (json.parseToJsonElement(encoded ?: return emptyList()) as JsonArray)
            .mapNotNull { ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.content }
    }.getOrDefault(emptyList())

    companion object {
        const val ID_PREFIX = "library:"
        private val json = Json { ignoreUnknownKeys = true }

        /** The library item id behind a local catalog ref. */
        fun localId(ref: CatalogItemRef): Long? =
            ref.id.takeIf { ref.provider == CatalogProviderId.LOCAL && it.startsWith(ID_PREFIX) }?.removePrefix(ID_PREFIX)?.toLongOrNull()
    }
}
