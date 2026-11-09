package dev.yourstream.ui.screens

import dev.yourstream.library.api.MediaRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posters for lists whose domain models do not carry one (Continue Watching, search hits): looked
 * up through the existing [MediaRepository.getItem] (the item's matched metadata), cached in
 * memory. "No poster yet" expires after [missTtlMs]: metadata is often matched after a title
 * first appears. Lists that already carry posters (library pages) never come here.
 */
@Singleton
class ArtworkLookup(private val media: MediaRepository, private val now: () -> Long, private val missTtlMs: Long) {
    @Inject constructor(media: MediaRepository) : this(media, System::currentTimeMillis, 5 * 60_000L)

    private data class Entry(val url: String?, val at: Long)

    private val cache = object : LinkedHashMap<Long, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Entry>?) = size > MAX
    }

    suspend fun poster(itemId: Long): String? {
        synchronized(cache) {
            cache[itemId]?.let { e -> if (e.url != null || now() - e.at < missTtlMs) return e.url }
        }
        val url = runCatching { media.getItem(itemId)?.summary?.let(Presentation::artwork) }.getOrNull()
        synchronized(cache) { cache[itemId] = Entry(url, now()) }
        return url
    }

    private companion object { const val MAX = 300 }
}
