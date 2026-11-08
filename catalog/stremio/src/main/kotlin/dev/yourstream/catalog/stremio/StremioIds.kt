package dev.yourstream.catalog.stremio

import java.net.URLDecoder

/** Cross-ids and inline fields some add-ons pack into their item ids. */
data class ParsedStremioId(
    val imdbId: String?,
    val tmdbId: String?,
    /** Composite ids may carry these (Indian Regional Catalog: "tmdb:<id>||Title||Year||type||poster||description"). */
    val title: String? = null,
    val year: Int? = null,
    val posterUrl: String? = null,
    val description: String? = null,
)

object StremioIds {
    private val IMDB = Regex("^tt\\d{5,}$")

    fun parse(id: String): ParsedStremioId {
        if (IMDB.matches(id)) return ParsedStremioId(imdbId = id, tmdbId = null)
        val parts = id.split("||")
        val head = parts[0]
        val tmdb = head.removePrefix("tmdb:").takeIf { head.startsWith("tmdb:") && it.isNotBlank() }?.substringBefore(':')
        val imdb = head.takeIf { IMDB.matches(it.substringBefore(':')) }?.substringBefore(':')
        if (parts.size < 2) return ParsedStremioId(imdb, tmdb)
        return ParsedStremioId(
            imdbId = imdb,
            tmdbId = tmdb,
            title = parts.getOrNull(1)?.takeIf { it.isNotBlank() },
            year = parts.getOrNull(2)?.toIntOrNull(),
            posterUrl = parts.getOrNull(4)?.takeIf { it.startsWith("http") },
            description = parts.getOrNull(5)?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }?.takeIf { it.isNotBlank() },
        )
    }
}
