package dev.yourstream.desktop.data

/** One movie or series in the library: every indexed file that belongs to it. */
data class LibraryTitle(
    val key: String,
    val title: String,
    val year: Int?,
    val isSeries: Boolean,
    val files: List<MediaRecord>,
    /** Newest message date of its files (epoch seconds), for "recently added". */
    val addedAt: Long,
) {
    /** Best resolution among the files (2160 = 4K). */
    val bestResolution: Int? get() = files.mapNotNull { it.resolution }.maxOrNull()

    /** Seasons → episodes → the versions of each episode. Season 0 holds season-less episodes. */
    val seasons: Map<Int, Map<Int, List<MediaRecord>>>
        get() = files.groupBy { it.season ?: if (it.absoluteEpisode != null) 0 else 1 }
            .toSortedMap()
            .mapValues { (_, eps) -> eps.groupBy { it.episode ?: it.absoluteEpisode ?: 0 }.toSortedMap() }
}

/**
 * Groups indexed files into titles, like the Android library: a movie's versions (qualities,
 * languages, re-uploads) become one title; a series' episodes become one title with seasons.
 *
 * Files without a title, non-video files and later pieces of split files (".002" …; playing
 * split files is not in the desktop app yet) are left out.
 */
object LibraryBuilder {
    fun build(records: List<MediaRecord>): List<LibraryTitle> {
        val usable = records.filter { it.video && !it.deleted && !it.title.isNullOrBlank() && (it.partNumber ?: 1) <= 1 }
        val groups = LinkedHashMap<String, MutableList<MediaRecord>>()
        for (r in usable) groups.getOrPut(keyOf(r)) { ArrayList() } += r
        return groups.map { (key, files) ->
            val series = files.any { it.isEpisode }
            // The most common spelling of the title among the files.
            val title = files.groupingBy { it.title!!.trim() }.eachCount().maxByOrNull { it.value }!!.key
            val year = files.mapNotNull { it.year }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            LibraryTitle(key, title, year, series, files.sortedWith(versionOrder), files.maxOf { it.messageDate })
        }.sortedByDescending { it.addedAt }
    }

    /** Series ignore the year (episodes rarely carry it); movies keep it apart (remakes). */
    fun keyOf(r: MediaRecord): String {
        val name = (r.titleKey ?: r.title.orEmpty()).lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        return if (r.isEpisode) "s:$name" else "m:$name:${r.year ?: ""}"
    }

    /** Best version first: higher resolution, then larger file. */
    val versionOrder: Comparator<MediaRecord> = compareByDescending<MediaRecord> { it.resolution ?: 0 }.thenByDescending { it.sizeBytes }

    /** Titles whose name contains every word of [query] (case- and accent-insensitive). */
    fun search(titles: List<LibraryTitle>, query: String): List<LibraryTitle> {
        val words = fold(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return titles
        return titles.filter { t -> val name = fold(t.title + " " + (t.year ?: "")); words.all { it in name } }
    }

    private fun fold(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ")
}

/** "4K", "1080p" … for a resolution in lines. */
fun resolutionLabel(lines: Int?): String? = when {
    lines == null -> null
    lines >= 2000 -> "4K"
    lines >= 1400 -> "1440p"
    lines >= 1000 -> "1080p"
    lines >= 700 -> "720p"
    else -> "${lines}p"
}

/** "1.4 GB" */
fun sizeLabel(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(java.util.Locale.ROOT, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "${bytes shr 20} MB"
    else -> "${bytes shr 10} KB"
}
