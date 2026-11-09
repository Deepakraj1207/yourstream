package dev.yourstream.ui.screens

import dev.yourstream.library.api.Episode
import dev.yourstream.library.api.LibraryItemSummary
import dev.yourstream.library.api.LibraryKind
import dev.yourstream.search.core.ItemKind
import dev.yourstream.search.core.SearchHit
import dev.yourstream.ui.core.MediaCardModel
import dev.yourstream.ui.screens.navigation.OpenTarget
import dev.yourstream.watch.engine.AddedEntry
import dev.yourstream.watch.engine.ContinueEntry
import dev.yourstream.watch.engine.WatchSummary

/**
 * A card plus what it opens. Mapping domain models to tiles is presentation only: no decisions
 * are made here that the domain does not already make (what is "next", what is "in progress").
 */
data class Tile(val card: MediaCardModel, val open: OpenTarget)

/** Presentation mappers, domain → UI models (pure; tested). Neutral, language-free formats. */
object Presentation {
    fun tile(s: LibraryItemSummary): Tile = Tile(
        MediaCardModel(
            key = "item:${s.id}",
            title = s.metadataTitle ?: s.title,
            subtitle = subtitle(s.year, s.kind, s.episodeCount.takeIf { s.kind == LibraryKind.SERIES }),
            progress = s.progress?.takeIf { !it.completed && it.positionMs > 0 }?.fraction
                ?: if (s.kind == LibraryKind.SERIES && s.episodeCount > 0 && s.watchedEpisodeCount > 0) s.watchedEpisodeCount.toFloat() / s.episodeCount else null,
            badge = resolution(s.maxResolution),
            posterUrl = s.posterUrl,
        ),
        open(s.id, s.kind),
    )

    fun tile(e: ContinueEntry): Tile = Tile(
        MediaCardModel(
            key = "cw:${e.target.itemId}:${e.target.episodeKey}",
            title = e.title,
            subtitle = e.episode?.label ?: e.year?.toString(),
            progress = (e.percent / 100f).takeIf { e.percent > 0 },
        ),
        OpenTarget.Play(e.target, e.lastMediaId),
    )

    fun tile(a: AddedEntry): Tile = Tile(
        MediaCardModel(
            key = "added:${a.itemId}",
            title = a.title,
            subtitle = subtitle(a.year, a.kind, null),
            badge = if (a.newEpisodes > 0) "+${a.newEpisodes}" else null,
        ),
        open(a.itemId, a.kind),
    )

    fun tile(w: WatchSummary): Tile = Tile(
        MediaCardModel(
            key = "fav:${w.itemId}",
            title = w.title,
            subtitle = subtitle(w.year, w.kind, w.totalEpisodes.takeIf { w.kind == LibraryKind.SERIES && it > 0 }),
            progress = (w.percent / 100f).takeIf { w.percent in 1..99 },
        ),
        open(w.itemId, w.kind),
    )

    fun tile(h: SearchHit): Tile {
        val kind = when (h.kind) { ItemKind.SERIES -> LibraryKind.SERIES; ItemKind.MOVIE -> LibraryKind.MOVIE; else -> LibraryKind.VIDEO }
        // "ben 10 s2e5" resolved to an episode: open it directly.
        val open = h.episodeId?.let { OpenTarget.Play(dev.yourstream.library.api.PlaybackTarget(h.itemId, it)) }
            ?: h.season?.let { OpenTarget.Details(h.itemId) }
            ?: open(h.itemId, kind)
        val label = when {
            h.season != null && h.episodeNumber != null -> "S%02dE%02d".format(h.season, h.episodeNumber)
            h.season != null -> "S%02d".format(h.season)
            else -> null
        }
        return Tile(MediaCardModel("hit:${h.itemId}:${h.episodeId ?: 0}", h.title, listOfNotNull(h.year?.toString(), label).joinToString(" · ").ifEmpty { null }), open)
    }

    fun episodeTitle(e: Episode): String = listOfNotNull(e.label, e.title).joinToString(" · ")

    /** Series open their episodes' overview (details with seasons); movies/videos open details. */
    private fun open(itemId: Long, kind: LibraryKind): OpenTarget = OpenTarget.Details(itemId)

    private fun subtitle(year: Int?, kind: LibraryKind, episodes: Int?): String? =
        listOfNotNull(year?.toString(), episodes?.let { "$it ep" }).joinToString(" · ").ifEmpty { null }

    fun resolution(height: Int?): String? = when {
        height == null -> null
        height >= 2000 -> "4K"
        height >= 1000 -> "1080p"
        height >= 700 -> "720p"
        else -> null
    }
}
