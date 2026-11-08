package dev.yourstream.catalog.sync

import dev.yourstream.catalog.api.CatalogItem
import dev.yourstream.catalog.api.CatalogItemRef
import dev.yourstream.catalog.api.CatalogMetadata
import dev.yourstream.catalog.api.CatalogProviderId
import dev.yourstream.catalog.api.CatalogSource
import dev.yourstream.catalog.api.CatalogType
import dev.yourstream.catalog.api.ExternalMetadataFallback
import dev.yourstream.catalog.api.Sourced
import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import dev.yourstream.metadata.api.MetadataCandidate
import dev.yourstream.metadata.api.MetadataQuery
import dev.yourstream.metadata.api.MetadataRef
import dev.yourstream.metadata.api.MetadataType
import dev.yourstream.metadata.api.ProviderId
import dev.yourstream.metadata.core.MetadataProviderManager
import dev.yourstream.metadata.core.match.TitleSimilarity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The metadata providers of Phase 5 (TMDB, Cinemeta, … in their configured order, with their
 * own cache, health and fallback) as the catalog's metadata/search fallback.
 *
 * Lookup by id first (the catalog's TMDB id, then its IMDb id, which the metadata manager can
 * translate between providers). A catalog item without ids is only resolved by title search
 * when exactly one candidate has a near-identical title and the same year: a guess is never
 * presented as the title's metadata.
 */
@Singleton
class MetadataCatalogFallback @Inject constructor(private val manager: MetadataProviderManager) : ExternalMetadataFallback {

    override suspend fun metadata(item: CatalogItem): AppResult<Sourced<CatalogMetadata>> {
        val type = item.ref.type.toMetadata()
        var lastError: AppError = AppError.NotFound("no id to look up ${item.title}")
        for (ref in refsFor(item, type)) {
            when (val r = details(ref, item.ref)) {
                is AppResult.Ok -> return r
                is AppResult.Err -> lastError = r.error
            }
        }
        if (item.ref.imdbId != null || item.ref.tmdbId != null) return AppResult.Err(lastError)

        val year = item.year ?: return AppResult.Err(lastError)
        val candidates = when (val r = manager.search(MetadataQuery(title = item.title, year = year, type = type))) {
            is AppResult.Ok -> r.value.value
            is AppResult.Err -> return r
        }
        val sure = candidates.filter { it.year == year && TitleSimilarity.similarity(it.title, item.title) >= SAME_TITLE }
        val only = sure.singleOrNull() ?: return AppResult.Err(AppError.NotFound("no unambiguous match for ${item.title} ($year)"))
        return details(only.ref, item.ref)
    }

    override suspend fun search(query: String, type: CatalogType?): AppResult<Sourced<List<CatalogItem>>> =
        when (val r = manager.search(MetadataQuery(title = query, type = type?.toMetadata()))) {
            is AppResult.Err -> r
            is AppResult.Ok -> AppResult.Ok(
                Sourced(r.value.value.map { it.toItem() }, CatalogSource.ExternalMetadata(r.value.provider.value), r.value.fromCache),
            )
        }

    private fun refsFor(item: CatalogItem, type: MetadataType): List<MetadataRef> = buildList {
        val ref = item.ref
        // An item that came from a metadata search already is a metadata provider ref.
        if (manager.providers.any { it.id.value == ref.provider.value }) add(MetadataRef(ProviderId(ref.provider.value), type, ref.id, ref.imdbId))
        ref.tmdbId?.let { add(MetadataRef(TMDB, type, it, ref.imdbId)) }
        // Cinemeta's ids are IMDb ids; when it is not configured the manager translates the IMDb id.
        ref.imdbId?.let { add(MetadataRef(CINEMETA, type, it, it)) }
    }.distinct()

    private suspend fun details(ref: MetadataRef, catalogRef: CatalogItemRef): AppResult<Sourced<CatalogMetadata>> =
        when (ref.type) {
            MetadataType.MOVIE -> when (val r = manager.movie(ref)) {
                is AppResult.Err -> r
                is AppResult.Ok -> r.value.value.let { m ->
                    AppResult.Ok(
                        Sourced(
                            CatalogMetadata(
                                ref = catalogRef.copy(imdbId = catalogRef.imdbId ?: m.ref.imdbId),
                                title = m.title, year = m.year, description = m.overview, posterUrl = m.posterUrl,
                                backdropUrl = m.backdropUrl, genres = m.genres, cast = m.cast.map { it.name }, directors = m.directors,
                                runtimeMinutes = m.runtimeMinutes, rating = m.rating, releaseDate = m.releaseDate,
                                originalLanguage = m.originalLanguage,
                            ),
                            CatalogSource.ExternalMetadata(r.value.provider.value), r.value.fromCache,
                        ),
                    )
                }
            }
            MetadataType.SERIES -> when (val r = manager.series(ref)) {
                is AppResult.Err -> r
                is AppResult.Ok -> r.value.value.let { s ->
                    AppResult.Ok(
                        Sourced(
                            CatalogMetadata(
                                ref = catalogRef.copy(imdbId = catalogRef.imdbId ?: s.ref.imdbId),
                                title = s.title, year = s.year, description = s.overview, posterUrl = s.posterUrl,
                                backdropUrl = s.backdropUrl, genres = s.genres, cast = s.cast.map { it.name },
                                rating = s.rating, releaseDate = s.firstAirDate, originalLanguage = s.originalLanguage,
                            ),
                            CatalogSource.ExternalMetadata(r.value.provider.value), r.value.fromCache,
                        ),
                    )
                }
            }
        }

    private fun MetadataCandidate.toItem() = CatalogItem(
        ref = CatalogItemRef(
            provider = CatalogProviderId(ref.provider.value),
            type = if (ref.type == MetadataType.SERIES) CatalogType.SERIES else CatalogType.MOVIE,
            id = ref.id,
            imdbId = ref.imdbId,
            tmdbId = ref.id.takeIf { ref.provider == TMDB },
        ),
        title = title, year = year, posterUrl = posterUrl, backdropUrl = backdropUrl, description = overview, rating = rating,
        languages = listOfNotNull(originalLanguage),
    )

    private fun CatalogType.toMetadata() = if (this == CatalogType.SERIES) MetadataType.SERIES else MetadataType.MOVIE

    private companion object {
        val TMDB = ProviderId("tmdb")
        val CINEMETA = ProviderId("cinemeta")
        const val SAME_TITLE = 0.95
    }
}
