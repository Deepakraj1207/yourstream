package dev.yourstream.assistant.engine

import dev.yourstream.assistant.core.Entities
import dev.yourstream.core.language.LanguageCatalog
import dev.yourstream.search.core.ItemKind
import dev.yourstream.search.core.WatchState

/** Deterministic wording of what was asked ("unwatched Tamil comedy movies from 2005"). */
internal object Describe {
    fun watchAdjective(w: WatchState) = when (w) {
        WatchState.ANY -> null
        WatchState.UNWATCHED -> "unwatched"
        WatchState.IN_PROGRESS -> "in-progress"
        WatchState.WATCHED -> "watched"
    }

    fun entities(e: Entities): String = buildString {
        watchAdjective(e.watch)?.let { append(it).append(' ') }
        e.minResolution?.let { append(if (it >= 2160) "4K " else "${it}p+ ") }
        e.languages.joinToString("/") { LanguageCatalog.displayName(it) }.takeIf { it.isNotEmpty() }?.let { append(it).append(' ') }
        e.genres.joinToString("/").takeIf { it.isNotEmpty() }?.let { append(it).append(' ') }
        append(
            when (e.kinds.singleOrNull()) {
                ItemKind.MOVIE -> "movies"
                ItemKind.SERIES -> "series"
                ItemKind.VIDEO -> "videos"
                ItemKind.AUDIO -> "audio items"
                null -> "titles"
            },
        )
        e.title?.let { append(" matching \"").append(it).append('"') }
        when {
            e.yearFrom != null && e.yearFrom == e.yearTo -> append(" from ${e.yearFrom}")
            e.yearFrom != null && e.yearTo != null -> append(" from ${e.yearFrom}–${e.yearTo}")
            e.yearFrom != null -> append(" from ${e.yearFrom} on")
            e.yearTo != null -> append(" up to ${e.yearTo}")
        }
        e.season?.let { append(" with a season $it") }
    }
}
