package dev.yourstream.search.core

import dev.yourstream.core.language.LanguageCatalog

/**
 * Turns free text into words to match plus soft filters, without a fixed syntax:
 *  "ben 10 s2"        → words [ben, 10], Season(2)
 *  "s02e05", "2x05"   → Season(2, episode 5)
 *  "leo 2023"         → words [leo], Year(2023)
 *  "tamil comedy"     → Language(ta), Genre(comedy)
 *  "1080p bluray"     → Resolution(1080), Quality(bluray)
 *  "unwatched movies" → Watched(UNWATCHED), Kind(MOVIE)
 * Explicit operators are hard filters: `lang:ta`, `genre:comedy`, `year:2020`, `year:2018-2023`,
 * `season:2`, `res:720`, `quality:web-dl`, `kind:series`, `provider:tmdb`, `watched:no`, `in:<chat>`.
 *
 * [genres] are the genres present in the library (normalized), so only real genres are read as one.
 */
class QueryParser(private val genres: Set<String> = DEFAULT_GENRES) {

    fun parse(raw: String): ParsedQuery {
        var filters = SearchFilters()
        val chatWords = mutableListOf<String>()
        // 1. Operators (on the raw text: values may contain '-').
        val rest = StringBuilder()
        for (part in raw.trim().split(Regex("\\s+"))) {
            val op = OPERATOR.matchEntire(part)
            if (op == null) { rest.append(part).append(' '); continue }
            val (key, value) = op.destructured
            filters = applyOperator(filters, key.lowercase(), value, chatWords)
        }
        // 2. Structured patterns on lower-case words before normalization splits them.
        val words = rest.toString().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }.toMutableList()
        val soft = mutableListOf<SoftFilter>()
        val consumed = BooleanArray(words.size)
        for (i in words.indices) {
            if (consumed[i]) continue
            val w = words[i].trim('.', ',', '(', ')', '[', ']')
            val next = words.getOrNull(i + 1)?.trim('.', ',')
            SEASON_EPISODE.matchEntire(w)?.let { m ->
                val g = m.groupValues.drop(1).filter { it.isNotEmpty() }
                soft += SoftFilter.Season(g[0].toInt(), g[1].toInt(), listOf(w)); consumed[i] = true
            }
            if (consumed[i]) continue
            SEASON.matchEntire(w)?.let { m -> soft += SoftFilter.Season(m.groupValues.drop(1).first { it.isNotEmpty() }.toInt(), null, listOf(w)); consumed[i] = true }
            if (consumed[i]) continue
            if (w in setOf("season", "s") && next?.toIntOrNull() != null) {
                soft += SoftFilter.Season(next.toInt(), null, listOf(w, next)); consumed[i] = true; consumed[i + 1] = true; continue
            }
            if (w in setOf("episode", "ep", "e") && next?.toIntOrNull() != null) {
                soft += SoftFilter.Episode(next.toInt(), listOf(w, next)); consumed[i] = true; consumed[i + 1] = true; continue
            }
            EPISODE.matchEntire(w)?.let { m -> soft += SoftFilter.Episode(m.groupValues.drop(1).first { it.isNotEmpty() }.toInt(), listOf(w)); consumed[i] = true }
            if (consumed[i]) continue
            RESOLUTIONS[w]?.let { soft += SoftFilter.Resolution(it, listOf(w)); consumed[i] = true }
            if (consumed[i]) continue
            QUALITIES[w.replace("-", "").replace(".", "")]?.let { soft += SoftFilter.Quality(it, listOf(w)); consumed[i] = true }
            if (consumed[i]) continue
            w.toIntOrNull()?.takeIf { it in 1900..2099 }?.let { soft += SoftFilter.Year(it, listOf(w)) } // stays a title word too
            KINDS[w]?.let { soft += SoftFilter.Kind(it, listOf(w)); consumed[i] = true }
            if (consumed[i]) continue
            WATCH[w]?.let { soft += SoftFilter.Watched(it, listOf(w)); consumed[i] = true }
            if (consumed[i]) continue
            if (w in PROVIDERS) { soft += SoftFilter.Provider(w, listOf(w)); consumed[i] = true }
        }
        // 3. Languages and genres: words that remain title words as well (a film may be called "Tamil Padam").
        val remaining = words.filterIndexed { i, _ -> !consumed[i] }
        val tokens = SearchText.tokens(remaining.joinToString(" "))
        for (t in tokens) {
            // Full language names only ("tamil", "hindi", "தமிழ்"); 3-letter codes collide with title words ("ben", "tom").
            if ((t.length >= 4 || t.any { it.code > 0x2FF }) && t !in NOT_LANGUAGES) LanguageCatalog.findInText(t).singleOrNull()?.let { soft += SoftFilter.Language(it, listOf(t)) }
            genreOf(t)?.let { soft += SoftFilter.Genre(it, listOf(t)) }
        }
        // Language/genre/year words are only soft: they stay as optional title words, not required ones.
        val softOnly = soft.filter { it is SoftFilter.Language || it is SoftFilter.Genre || it is SoftFilter.Year }.flatMap { it.words }.toSet()
        val terms = SearchText.significant(tokens.filter { it !in softOnly })
            .let { if (it.size == 1 && it[0] in SearchText.STOPWORDS && tokens.size > 1) emptyList() else it }
        return ParsedQuery(terms, soft.distinct(), filters, chatWords, SearchText.normalize(remaining.joinToString(" ")))
    }

    private fun genreOf(word: String): String? = GENRE_SYNONYMS[word]?.takeIf { it in genres } ?: word.takeIf { it in genres }

    private fun applyOperator(f: SearchFilters, key: String, value: String, chatWords: MutableList<String>): SearchFilters = when (key) {
        "lang", "language", "audio" -> LanguageCatalog.normalizeCode(value)?.let { f.copy(languages = f.languages + it) } ?: f
        "genre" -> f.copy(genres = f.genres + (GENRE_SYNONYMS[SearchText.normalize(value)] ?: SearchText.normalize(value)))
        "year" -> {
            val range = value.split('-').mapNotNull { it.toIntOrNull() }
            if (range.size == 2) f.copy(yearFrom = range[0], yearTo = range[1]) else range.firstOrNull()?.let { f.copy(yearFrom = it, yearTo = it) } ?: f
        }
        "season", "s" -> value.toIntOrNull()?.let { f.copy(season = it) } ?: f
        "res", "resolution" -> (RESOLUTIONS[value.lowercase()] ?: value.removeSuffix("p").toIntOrNull())?.let { f.copy(minResolution = it) } ?: f
        "quality" -> QUALITIES[value.lowercase().replace("-", "").replace(".", "")]?.let { f.copy(qualities = f.qualities + it) } ?: f
        "kind", "type" -> KINDS[value.lowercase()]?.let { f.copy(kinds = f.kinds + it) } ?: f
        "provider" -> f.copy(providers = f.providers + value.lowercase())
        "watched" -> when (value.lowercase()) {
            "yes", "true", "1" -> f.copy(watch = WatchState.WATCHED)
            "no", "false", "0" -> f.copy(watch = WatchState.UNWATCHED)
            "partial", "progress" -> f.copy(watch = WatchState.IN_PROGRESS)
            else -> f
        }
        "in", "chat", "channel" -> { chatWords += SearchText.tokens(value.replace('_', ' ')); f }
        else -> f
    }

    companion object {
        private val OPERATOR = Regex("(lang|language|audio|genre|year|season|s|res|resolution|quality|kind|type|provider|watched|in|chat|channel):(.+)", RegexOption.IGNORE_CASE)
        /** "s02e05", "s2e5", "s02.e05", "2x05". */
        private val SEASON_EPISODE = Regex("s(\\d{1,2})[._-]?e(\\d{1,3})|(\\d{1,2})x(\\d{2,3})")
        private val SEASON = Regex("s(\\d{1,2})|season(\\d{1,2})")
        private val EPISODE = Regex("e(\\d{1,3})|ep(\\d{1,3})|episode(\\d{1,3})")
        private val RESOLUTIONS = mapOf(
            "2160p" to 2160, "4k" to 2160, "uhd" to 2160, "1440p" to 1440, "1080p" to 1080, "fhd" to 1080, "1080" to 1080,
            "720p" to 720, "hd" to 720, "480p" to 480, "sd" to 480, "360p" to 360,
        )
        /** Normalized quality names, as the parser stores them lower-cased. */
        private val QUALITIES = mapOf(
            "webdl" to "web dl", "web" to "web dl", "webrip" to "webrip", "bluray" to "bluray", "bdrip" to "bluray", "brrip" to "bluray",
            "hdrip" to "hdrip", "dvdrip" to "dvdrip", "dvd" to "dvdrip", "hdtv" to "hdtv", "cam" to "cam", "hdcam" to "cam", "predvd" to "cam",
            "hdts" to "telesync", "ts" to "telesync", "remux" to "remux",
        )
        private val KINDS = mapOf(
            "movie" to ItemKind.MOVIE, "movies" to ItemKind.MOVIE, "film" to ItemKind.MOVIE, "films" to ItemKind.MOVIE,
            "series" to ItemKind.SERIES, "show" to ItemKind.SERIES, "shows" to ItemKind.SERIES, "tv" to ItemKind.SERIES, "episodes" to ItemKind.SERIES,
        )
        private val WATCH = mapOf(
            "unwatched" to WatchState.UNWATCHED, "unseen" to WatchState.UNWATCHED, "new" to WatchState.UNWATCHED,
            "watched" to WatchState.WATCHED, "seen" to WatchState.WATCHED, "finished" to WatchState.WATCHED,
            "continue" to WatchState.IN_PROGRESS, "resume" to WatchState.IN_PROGRESS, "unfinished" to WatchState.IN_PROGRESS,
        )
        private val PROVIDERS = setOf("tmdb", "cinemeta", "unmatched")
        /** Words that look like language codes but are ordinary words in titles. */
        private val NOT_LANGUAGES = setOf("man", "son", "can", "bar", "tom", "sun", "the", "and", "per", "mal", "pan", "dan", "bel", "ice")
        val DEFAULT_GENRES = setOf(
            "action", "adventure", "animation", "comedy", "crime", "documentary", "drama", "family", "fantasy", "history", "horror",
            "music", "mystery", "romance", "science fiction", "thriller", "war", "western", "kids", "reality", "sci fi", "talk", "news",
        )
        val GENRE_SYNONYMS: Map<String, String> = mapOf(
            "comedies" to "comedy", "funny" to "comedy", "cartoon" to "animation", "cartoons" to "animation", "anime" to "animation", "animated" to "animation",
            "romantic" to "romance", "scifi" to "science fiction", "horror" to "horror", "scary" to "horror",
            "thrillers" to "thriller", "documentaries" to "documentary", "docu" to "documentary", "kids" to "family", "children" to "family",
        )
    }
}
