package dev.yourstream.ui.screens

import dev.yourstream.library.api.Episode
import dev.yourstream.library.api.LibraryItemSummary
import dev.yourstream.library.api.LibraryKind
import dev.yourstream.library.api.PlaybackTarget
import dev.yourstream.search.core.ItemKind
import dev.yourstream.search.core.SearchHit
import dev.yourstream.ui.core.MediaCardModel
import dev.yourstream.ui.screens.navigation.OpenTarget
import dev.yourstream.watch.core.NextAction
import dev.yourstream.watch.core.NextReason
import dev.yourstream.watch.engine.AddedEntry
import dev.yourstream.watch.engine.ContinueEntry
import dev.yourstream.watch.engine.WatchSummary

/** The line under a Continue Watching card, chosen from the domain's decision (localised by the screen). */
sealed interface TileCaption {
    data class MinutesLeft(val minutes: Int) : TileCaption
    data object NextEpisode : TileCaption
    data object NewEpisode : TileCaption
    data object Start : TileCaption
    /** A reason the domain gives as text ("Tamil · action · ★ 7.6"). */
    data class Reason(val text: String) : TileCaption
    data object Resume : TileCaption
    /** A catalog title that is also in the user's Telegram library. */
    data object InLibrary : TileCaption
}

/**
 * A card plus what it opens and which actions apply. Mapping domain models to tiles is
 * presentation only: no decisions are made here that the domain does not already make.
 */
data class Tile(
    val card: MediaCardModel,
    val open: OpenTarget,
    /** Library item behind the card (null: no item actions). */
    val itemId: Long? = null,
    val isFavorite: Boolean? = null,
    /** In "My library"; null = no add/remove action. */
    val inLibrary: Boolean? = null,
    val watched: Boolean? = null,
    /** Continue Watching entries can be removed from the list. */
    val dismissible: Boolean = false,
    val caption: TileCaption? = null,
)

/** Presentation mappers, domain → UI models (pure; tested). Neutral, language-free formats. */
object Presentation {
    fun tile(s: LibraryItemSummary): Tile {
        val seriesWatched = s.kind == LibraryKind.SERIES && s.episodeCount > 0 && s.watchedEpisodeCount >= s.episodeCount
        val watched = s.progress?.completed == true || seriesWatched
        return Tile(
            MediaCardModel(
                key = "item:${s.id}",
                title = s.metadataTitle ?: s.title,
                subtitle = subtitle(s.year, s.episodeCount.takeIf { s.kind == LibraryKind.SERIES && it > 0 }),
                progress = s.progress?.takeIf { !it.completed && it.positionMs > 0 }?.fraction
                    ?: if (s.kind == LibraryKind.SERIES && !seriesWatched && s.watchedEpisodeCount > 0) s.watchedEpisodeCount.toFloat() / s.episodeCount else null,
                badge = resolution(s.maxResolution),
                posterUrl = s.posterUrl,
                watched = watched,
            ),
            OpenTarget.Details(s.id), itemId = s.id, isFavorite = s.isFavorite, watched = watched, inLibrary = s.inLibrary,
        )
    }

    fun tile(e: ContinueEntry, posterUrl: String? = null): Tile = Tile(
        MediaCardModel(
            key = "cw:${e.target.itemId}:${e.target.episodeKey}",
            title = e.title,
            subtitle = e.episode?.let { listOfNotNull(it.label, it.title).joinToString(" · ") } ?: e.year?.toString(),
            progress = (e.percent / 100f).takeIf { e.percent > 0 },
            posterUrl = posterUrl,
        ),
        OpenTarget.Play(e.target, e.lastMediaId), itemId = e.target.itemId, dismissible = true,
        caption = when {
            e.action == NextAction.RESUME && e.durationMs > 0 -> TileCaption.MinutesLeft(((e.durationMs - e.positionMs) / 60_000).toInt().coerceAtLeast(1))
            e.reason == NextReason.NEW_EPISODE -> TileCaption.NewEpisode
            e.episode != null -> TileCaption.NextEpisode
            else -> TileCaption.Start
        },
    )

    fun tile(a: AddedEntry, posterUrl: String? = null): Tile = Tile(
        MediaCardModel("added:${a.itemId}", a.title, subtitle(a.year, null), badge = if (a.newEpisodes > 0) "+${a.newEpisodes}" else null, posterUrl = posterUrl),
        OpenTarget.Details(a.itemId), itemId = a.itemId, isFavorite = a.favorite,
    )

    fun tile(w: WatchSummary, posterUrl: String? = null): Tile = Tile(
        MediaCardModel(
            key = "fav:${w.itemId}", title = w.title,
            subtitle = subtitle(w.year, w.totalEpisodes.takeIf { w.kind == LibraryKind.SERIES && it > 0 }),
            progress = (w.percent / 100f).takeIf { w.percent in 1..99 }, posterUrl = posterUrl,
        ),
        OpenTarget.Details(w.itemId), itemId = w.itemId, isFavorite = w.favorite,
    )

    /**
     * A Media Brain or recommendation result. Episodes and "continue" picks open the player
     * directly (one click); titles open their details. The reason becomes the caption.
     */
    fun tile(a: dev.yourstream.assistant.engine.AssistantItem, posterUrl: String? = null): Tile {
        val episode = a.episodeId?.let { OpenTarget.Play(PlaybackTarget(a.itemId, it)) }
        val open = episode ?: if (a.reason == "continue") OpenTarget.Play(PlaybackTarget(a.itemId)) else OpenTarget.Details(a.itemId)
        val label = when {
            a.season != null && a.episode != null -> "S%02dE%02d".format(a.season, a.episode)
            a.episode != null -> "E%02d".format(a.episode)
            else -> null
        }
        val caption = when (a.reason) {
            null, "" -> null
            "continue" -> TileCaption.Resume
            "next episode" -> TileCaption.NextEpisode
            else -> TileCaption.Reason(a.reason!!)
        }
        return Tile(
            MediaCardModel("brain:${a.itemId}:${a.episodeId ?: 0}", a.title,
                listOfNotNull(a.year?.toString(), label, a.episodeTitle).joinToString(" · ").ifEmpty { null }, progress = a.progress, posterUrl = posterUrl),
            open, itemId = a.itemId, caption = caption,
        )
    }

    fun tile(h: SearchHit, posterUrl: String? = null): Tile {
        // "ben 10 s2e5" resolved to an episode: open it directly.
        val open = h.episodeId?.let { OpenTarget.Play(PlaybackTarget(h.itemId, it)) } ?: OpenTarget.Details(h.itemId)
        val label = when {
            h.season != null && h.episodeNumber != null -> "S%02dE%02d".format(h.season, h.episodeNumber)
            h.season != null -> "S%02d".format(h.season)
            else -> null
        }
        return Tile(
            MediaCardModel("hit:${h.itemId}:${h.episodeId ?: 0}", h.title, listOfNotNull(h.year?.toString(), label).joinToString(" · ").ifEmpty { null }, posterUrl = posterUrl),
            open, itemId = h.itemId,
        )
    }

    /**
     * A title from a catalog add-on. In the library: opens its details. Otherwise it searches the
     * library for it (catalogs are information only; what plays is always the user's own media).
     */
    fun tile(item: dev.yourstream.catalog.api.CatalogItem, presence: dev.yourstream.catalog.api.LibraryPresence?): Tile = Tile(
        MediaCardModel("cat:${item.ref.provider.value}:${item.ref.id}", item.title, item.year?.toString(), posterUrl = item.posterUrl),
        presence?.let { OpenTarget.Details(it.itemId) } ?: OpenTarget.CatalogTitle(
            dev.yourstream.ui.screens.navigation.CatalogTitleRoute(
                item.ref.provider.value, item.ref.type.name, item.ref.id, item.title, item.year, item.posterUrl, item.ref.imdbId,
            ),
        ),
        itemId = presence?.itemId,
        caption = if (presence != null) TileCaption.InLibrary else null,
    )

    fun isSeries(h: SearchHit) = h.kind == ItemKind.SERIES

    fun episodeTitle(e: Episode): String = listOfNotNull(e.label, e.title).joinToString(" · ")

    private fun subtitle(year: Int?, episodes: Int?): String? =
        listOfNotNull(year?.toString(), episodes?.let { "$it ep" }).joinToString(" · ").ifEmpty { null }

    fun resolution(height: Int?): String? = when {
        height == null -> null
        height >= 2000 -> "4K"
        height >= 1000 -> "1080p"
        height >= 700 -> "720p"
        else -> null
    }
}
