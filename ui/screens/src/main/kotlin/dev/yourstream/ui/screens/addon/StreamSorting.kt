package dev.yourstream.ui.screens.addon

import dev.yourstream.catalog.api.AddonStream

/** How a title's streams are ordered inside each source (Settings is not needed: the sheet on the title page). */
enum class StreamSort { BEST, LARGEST, SMALLEST, SOURCE }

/** Quality buckets for the filter. */
enum class StreamQuality(val minHeight: Int) { UHD(1800), FHD(1000), HD(650), SD(0) }

/**
 * What the Sort & filter sheet keeps (empty sets: no limit). Remembered in the settings, so every
 * title opens with the same choice.
 */
data class StreamFilter(
    val sort: StreamSort = StreamSort.BEST,
    val qualities: Set<StreamQuality> = emptySet(),
    /** Show the user's Telegram files. */
    val telegram: Boolean = true,
    /** Show add-on streams. */
    val addons: Boolean = true,
    val direct: Boolean = true,
    val torrents: Boolean = true,
    /** Audio languages (ISO codes); a stream matches when it lists or mentions one. */
    val languages: Set<String> = emptySet(),
) {
    val isDefault: Boolean get() = this == StreamFilter()
    /** How many limits are set (for the button's "Filters · 2"). */
    val active: Int get() = listOf(qualities.isNotEmpty(), !telegram || !addons, !direct || !torrents, languages.isNotEmpty()).count { it }

    /** [groups] filtered and sorted; groups left empty are dropped (still-loading groups stay). Pure; tested. */
    fun apply(groups: List<StreamGroup>, languageNames: (String) -> String = { it }): List<StreamGroup> = groups.mapNotNull { g ->
        val telegramGroup = g.id == StreamGroup.TELEGRAM
        if (telegramGroup && !telegram || !telegramGroup && !addons) return@mapNotNull null
        val kept = g.streams.filter { s -> keeps(s, languageNames) }
        val sorted = when (sort) {
            StreamSort.SOURCE -> kept
            StreamSort.BEST -> kept.sortedWith(compareByDescending<AddonStream> { heightOf(it) ?: 0 }.thenByDescending { it.sizeBytes ?: 0L })
            StreamSort.LARGEST -> kept.sortedByDescending { it.sizeBytes ?: -1L }
            StreamSort.SMALLEST -> kept.sortedBy { it.sizeBytes ?: Long.MAX_VALUE }
        }
        if (sorted.isEmpty() && !g.loading && g.streams.isNotEmpty()) null else g.copy(streams = sorted)
    }

    private fun keeps(s: AddonStream, languageNames: (String) -> String): Boolean {
        if (s.isTorrent && !torrents) return false
        if (!s.isTorrent && !s.isLibrary && !direct) return false
        if (qualities.isNotEmpty()) {
            val q = qualityOf(s) ?: return false
            if (q !in qualities) return false
        }
        if (languages.isNotEmpty()) {
            val text = listOfNotNull(s.name, s.description, s.fileName).joinToString(" ").lowercase()
            val matches = languages.any { code ->
                code in s.languages || languageNames(code).lowercase().let { it.isNotBlank() && it in text } ||
                    Regex("(?<![a-z])${Regex.escape(code.lowercase())}(?![a-z])").containsMatchIn(text)
            }
            if (!matches) return false
        }
        return true
    }

    companion object {
        private val HEIGHT = Regex("(?i)(?<![0-9])(4320|2160|1440|1080|720|576|480|360)p")
        private val UHD_WORDS = Regex("(?i)(?<![a-z])(4k|uhd)(?![a-z])")

        /** The video height a stream says ("1080p", "4K"; the user's files know theirs). */
        fun heightOf(s: AddonStream): Int? = s.library?.resolution
            ?: listOfNotNull(s.name, s.description, s.fileName).joinToString(" ").let { text ->
                HEIGHT.find(text)?.groupValues?.get(1)?.toInt() ?: if (UHD_WORDS.containsMatchIn(text)) 2160 else null
            }

        fun qualityOf(s: AddonStream): StreamQuality? = heightOf(s)?.let { h -> StreamQuality.entries.firstOrNull { h >= it.minHeight } }
    }
}
