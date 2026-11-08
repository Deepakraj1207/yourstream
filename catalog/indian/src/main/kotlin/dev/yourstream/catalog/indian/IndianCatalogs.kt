package dev.yourstream.catalog.indian

import dev.yourstream.catalog.api.CatalogLanguages
import dev.yourstream.catalog.api.CatalogProviderId
import dev.yourstream.catalog.stremio.StremioAddonDefinition
import dev.yourstream.catalog.stremio.StremioCatalogEntry
import dev.yourstream.catalog.stremio.StremioCatalogProvider
import dev.yourstream.core.common.Clock
import io.ktor.client.HttpClient

/**
 * Optional Indian-focused catalog providers, described as Stremio add-on definitions. They are
 * enabled by configuration (`catalog.providers`); nothing in the catalog core refers to them.
 *
 * Both are third-party community add-ons consumed through their public manifests (no
 * scraping). Only their catalog/meta resources are used; Indian Regional Catalog also offers a
 * stream resource, which this app never calls.
 */
object IndianCatalogs {

    /**
     * IndiaStreams (indiastreams.rdata.in): curated "trending / popular on Netflix & Prime /
     * Hotstar, SonyLiv, Zee5" lists, mixed languages, refreshed every 4 h by the add-on.
     * Manifest (v1.0.2): resources = [catalog] only, no search, no paging, IMDb ids.
     */
    val INDIA_STREAMS = StremioAddonDefinition(
        id = CatalogProviderId("indiastreams"),
        displayName = "IndiaStreams",
        attribution = "Catalog by IndiaStreams (indiastreams.rdata.in)",
        manifestUrls = listOf("https://indiastreams.rdata.in/manifest.json"),
        catalogLanguages = { emptyList() }, // lists mix Hindi, English and regional titles
        catalogTags = { entry ->
            buildList {
                if (entry.id.startsWith("trending")) add("trending")
                if (entry.id in setOf("nfxprm", "hstzee", "nfxprmtv", "hstzeetv")) add("ott")
                if (entry.id in setOf("recmov", "atpmub")) add("recommended")
            }
        },
    )

    /**
     * Indian Regional Catalog: one catalog per language for movies ("ta") and series
     * ("ta_series"), plus Hindi Dubbed and OTT Releases. Manifest (v1.0.6): catalog + meta
     * (+ stream, unused); paging with skip (20 per page); ids are IMDb or composite
     * "tmdb:<id>||Title||Year||type||poster||description". Two public instances with the same
     * manifest: the second is a mirror.
     */
    val INDIAN_REGIONAL = StremioAddonDefinition(
        id = CatalogProviderId("indian_regional"),
        displayName = "Indian Regional Catalog",
        attribution = "Catalog by Indian Regional Catalog (Stremio community add-on)",
        manifestUrls = listOf(
            "https://indian-regional-catalog.vercel.app/manifest.json",
            "https://83e20802dcf1-indian-streams.baby-beamup.club/manifest.json",
        ),
        catalogLanguages = ::regionalLanguages,
        // The add-on injects announcement entries ("ir:promo-…") into its lists.
        includeItem = { !it.ref.id.startsWith("ir:promo") },
        catalogTags = { entry ->
            when {
                entry.id == "hindi_dubbed" -> listOf("dubbed")
                entry.id == "ott_releases" -> listOf("ott", "new")
                else -> emptyList()
            }
        },
    )

    /** "ta" / "ta_series" → [ta]; "hindi_dubbed" → [hi]; "ott_releases" → mixed. */
    internal fun regionalLanguages(entry: StremioCatalogEntry): List<String> {
        val code = entry.id.removeSuffix("_series")
        return when {
            code in CatalogLanguages.INDIAN_CODES -> listOf(code)
            entry.id == "hindi_dubbed" -> listOf("hi")
            else -> emptyList()
        }
    }

    /** Definitions by config id. */
    val KNOWN: Map<String, StremioAddonDefinition> = listOf(INDIA_STREAMS, INDIAN_REGIONAL).associateBy { it.id.value }

    /** Builds the configured providers, in order; unknown ids are ignored. */
    fun create(ids: List<String>, clientFactory: () -> HttpClient, clock: Clock = Clock.System): List<StremioCatalogProvider> =
        ids.mapNotNull { KNOWN[it.trim().lowercase()] }.map { StremioCatalogProvider(clientFactory(), it, clock) }

}
