package dev.yourstream.catalog.sync

import dev.yourstream.catalog.api.CatalogItem
import dev.yourstream.catalog.api.CatalogItemRef
import dev.yourstream.catalog.api.CatalogType
import dev.yourstream.catalog.api.LibraryAvailability
import dev.yourstream.catalog.api.LibraryPresence
import dev.yourstream.catalog.api.PresenceMatch
import dev.yourstream.core.database.YourStreamDatabase
import dev.yourstream.core.database.dao.PresenceRow
import dev.yourstream.library.data.LibraryQueryBuilder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Answers "do I already have this catalog title on Telegram?". This is the only bridge from a
 * catalog title to something playable, and it goes through the library, never the provider.
 *
 * Order: IMDb id → TMDB id → normalized title + kind + year. A title match needs the same kind,
 * and the same year when both sides know it; if several library items fit, none is reported.
 */
@Singleton
class RoomLibraryAvailability @Inject constructor(db: YourStreamDatabase) : LibraryAvailability {
    private val dao = db.catalogDao()

    override suspend fun presence(items: List<CatalogItem>): Map<CatalogItemRef, LibraryPresence> {
        if (items.isEmpty()) return emptyMap()
        val result = HashMap<CatalogItemRef, LibraryPresence>()

        items.forEach { item -> RoomLocalCatalogSource.localId(item.ref)?.let { id -> dao.localItem(id)?.let { row ->
            result[item.ref] = LibraryPresence(row.id, row.title, row.availableCount, PresenceMatch.IMDB_ID.takeIf { row.imdbId != null } ?: PresenceMatch.TITLE_YEAR)
        } } }

        val byImdb = items.mapNotNull { it.ref.imdbId }.distinct().chunked(CHUNK)
            .flatMap { dao.presenceByImdb(it) }.groupBy { it.key }
        val byTmdb = items.mapNotNull { it.ref.tmdbId }.distinct().chunked(CHUNK)
            .flatMap { dao.presenceByExternalId("tmdb", it) }.groupBy { it.key }
        val keysOf = items.associate { it.ref to LibraryQueryBuilder.normalize(it.title) }
        val byTitle = keysOf.values.filter { it.isNotBlank() }.distinct().chunked(CHUNK)
            .flatMap { dao.presenceByTitle(it) }.groupBy { it.key }

        for (item in items) {
            if (item.ref in result) continue
            val kind = if (item.ref.type == CatalogType.SERIES) "SERIES" else "MOVIE"
            val hit = item.ref.imdbId?.let { byImdb[it] }?.best(kind)?.let { it to PresenceMatch.IMDB_ID }
                ?: item.ref.tmdbId?.let { byTmdb[it] }?.best(kind)?.let { it to PresenceMatch.TMDB_ID }
                ?: byTitle[keysOf[item.ref]]?.filter { it.kind == kind && yearFits(it.year, item.year) }
                    ?.singleOrNull()?.let { it to PresenceMatch.TITLE_YEAR }
                ?: continue
            result[item.ref] = LibraryPresence(hit.first.id, hit.first.title, hit.first.availableCount, hit.second)
        }
        return result
    }

    /** An id match is exact; prefer the same kind, then the item with most files. */
    private fun List<PresenceRow>.best(kind: String) =
        sortedWith(compareByDescending<PresenceRow> { it.kind == kind }.thenByDescending { it.availableCount }).firstOrNull()

    /** Series rows usually have no year; movies must agree when both years are known. */
    private fun yearFits(library: Int?, catalog: Int?) = library == null || catalog == null || library == catalog

    private companion object { const val CHUNK = 500 }
}
