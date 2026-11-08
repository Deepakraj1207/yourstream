package dev.yourstream.assistant.core

import dev.yourstream.core.language.LanguageCatalog
import dev.yourstream.search.core.ItemKind
import dev.yourstream.search.core.QueryParser
import dev.yourstream.search.core.SearchSort
import dev.yourstream.search.core.WatchState
import java.text.Normalizer
import java.time.Year

/**
 * Deterministic interpretation of a natural-language request: no model, no network.
 *
 * The sentence is normalized (lower case, contractions expanded: "haven't" → "have not", number
 * words next to season/episode → digits), then recognised phrases are *consumed* in a fixed
 * order, longest patterns first, so one word never feeds two entities:
 *
 *   intent cue → season/episode → years → watched state → sort / limit / resolution →
 *   media type → language → genre → filler words
 *
 * Whatever is left is the title ("continue watching **ben 10**", "find **doraemon** episodes").
 */
class IntentParser(
    genres: Set<String> = QueryParser.DEFAULT_GENRES,
    private val currentYear: () -> Int = { Year.now().value },
) {
    private val genreNames = genres + QueryParser.DEFAULT_GENRES

    fun parse(input: String): Interpretation {
        val notes = mutableListOf<String>()
        val s = Sentence(normalize(input))
        // Leading command verbs ("show me …", "find …") so "show" is not read as "TV show".
        s.take(LEADING_COMMAND)

        // 1. Intent cues (removed from the text).
        var intent: Intent? = null
        var cue = false
        fun cue(i: Intent, vararg phrases: String): Boolean {
            if (intent != null) return false
            val p = phrases.firstOrNull { s.has(it) } ?: return false
            s.take(p); intent = i; cue = true; notes += "\"$p\" → ${i.name.lowercase().replace('_', ' ')}"; return true
        }
        cue(Intent.RECOMMEND, "what should i watch next", "what should i watch", "what do i watch", "what to watch", "what can i watch",
            "recommend me", "recommend", "suggest me", "suggest", "something to watch", "anything to watch", "i am bored", "surprise me",
            "what is good", "anything good")
        cue(Intent.CONTINUE_WATCHING, "continue watching", "keep watching", "resume watching", "carry on watching", "carry on with",
            "where i left off", "where did i stop", "pick up", "continue", "resume")
        if (intent == null && (s.startsWith("play ") || s.startsWith("put on ") || s.startsWith("start watching "))) {
            listOf("start watching", "put on", "play").first { s.startsWith("$it ") }.let { s.take(it) }
            intent = Intent.PLAY; cue = true; notes += "\"play\" → play"
        }

        // 2. Season / episode.
        var season: Int? = null
        var episode: Int? = null
        s.take(Regex("\\bs(\\d{1,2}) ?e(\\d{1,3})\\b"))?.let { season = it[1].toInt(); episode = it[2].toInt() }
        if (season == null) s.take(Regex("\\b(\\d{1,2})x(\\d{1,3})\\b"))?.let { season = it[1].toInt(); episode = it[2].toInt() }
        s.take(Regex("\\bseason (\\d{1,2})\\b"))?.let { season = it[1].toInt() }
        if (season == null) s.take(Regex("\\bs(\\d{1,2})\\b"))?.let { season = it[1].toInt() }
        s.take(Regex("\\b(?:episode|ep) (\\d{1,4})\\b"))?.let { episode = it[1].toInt() }
        if (episode == null) s.take(Regex("\\be(\\d{1,4})\\b"))?.let { episode = it[1].toInt() }
        season?.let { notes += "season $it" }
        episode?.let { notes += "episode $it" }
        s.take(Regex("\\bof season\\b|\\bof episode\\b"))

        // 3. Years and ranges.
        var yearFrom: Int? = null
        var yearTo: Int? = null
        val year = "((?:19|20)\\d\\d)"
        fun range(from: Int?, to: Int?, what: String) { yearFrom = from; yearTo = to; notes += what }
        when {
            s.take(Regex("\\b(?:between|from) $year (?:and|to|-) $year\\b"))?.let { range(it[1].toInt(), it[2].toInt(), "years ${it[1]}–${it[2]}") } != null -> Unit
            s.take(Regex("\\b$year to $year\\b"))?.let { range(it[1].toInt(), it[2].toInt(), "years ${it[1]}–${it[2]}") } != null -> Unit
            s.take(Regex("\\b(?:after|since|newer than) $year\\b"))?.let { range(it[1].toInt() + if (it[0].startsWith("after")) 1 else 0, null, "from ${it[1]}") } != null -> Unit
            s.take(Regex("\\b(?:before|older than) $year\\b"))?.let { range(null, it[1].toInt() - 1, "before ${it[1]}") } != null -> Unit
            s.take(Regex("\\b(?:the )?((?:19|20)?\\d0)s\\b"))?.let { m ->
                val d = m[1].toInt().let { if (it < 100) (if (it >= 30) 1900 + it else 2000 + it) else it }
                range(d, d + 9, "the ${m[1]}s")
            } != null -> Unit
            s.take(Regex("\\bthis year\\b")) != null -> currentYear().let { range(it, it, "this year ($it)") }
            s.take(Regex("\\blast year\\b")) != null -> (currentYear() - 1).let { range(it, it, "last year ($it)") }
            s.take(Regex("\\b(?:from |in |of )?$year\\b"))?.let { range(it[1].toInt(), it[1].toInt(), "year ${it[1]}") } != null -> Unit
        }

        // 4. Watched state.
        var watch = WatchState.ANY
        val watchPhrases = listOf(
            WatchState.IN_PROGRESS to listOf("did not finish", "have not finished", "not finished", "half watched", "partially watched", "in progress", "unfinished", "started"),
            WatchState.UNWATCHED to listOf("i have not watched", "i have not seen", "i never watched", "i never saw", "have not watched", "have not seen",
                "not watched yet", "not watched", "not seen", "never watched", "never seen", "new to me", "unwatched", "unseen", "yet to watch"),
            WatchState.WATCHED to listOf("i have watched", "i have seen", "i already watched", "i watched", "already watched", "already seen", "watched before",
                "watched", "seen", "finished", "completed"),
        )
        loop@ for ((state, phrases) in watchPhrases) for (p in phrases) if (s.has(p)) { s.take(p); watch = state; notes += "\"$p\" → ${state.name.lowercase().replace('_', ' ')}"; break@loop }

        // 5. Sort, limit, resolution.
        var sort: SearchSort? = null
        var reversed = false
        val sorts = listOf(
            "recently added" to SearchSort.RECENTLY_ADDED, "latest" to SearchSort.RECENTLY_ADDED, "newest" to SearchSort.RECENTLY_ADDED, "new releases" to SearchSort.RECENTLY_ADDED,
            "top rated" to SearchSort.RATING, "highest rated" to SearchSort.RATING, "best rated" to SearchSort.RATING, "best" to SearchSort.RATING, "popular" to SearchSort.RATING,
            "oldest" to SearchSort.YEAR, "alphabetical order" to SearchSort.TITLE, "alphabetically" to SearchSort.TITLE, "a to z" to SearchSort.TITLE,
            "biggest" to SearchSort.SIZE, "largest" to SearchSort.SIZE, "recently watched" to SearchSort.RECENTLY_WATCHED,
        )
        sorts.firstOrNull { s.has(it.first) }?.let { (p, so) -> s.take(p); sort = so; reversed = p == "oldest"; notes += "\"$p\" → sort by ${so.name.lowercase().replace('_', ' ')}" }
        var limit: Int? = null
        s.take(Regex("\\b(?:top|first|only) (\\d{1,3})\\b"))?.let { limit = it[1].toInt() }
        if (limit == null) Regex("\\b(\\d{1,3}) (?=movies|films|shows|series|episodes|cartoons|videos)").find(s.text)?.let { m -> limit = m.groupValues[1].toInt(); s.text = s.text.replaceRange(m.range, " ") }
        limit?.let { notes += "limit $it" }
        var minResolution: Int? = null
        listOf("4k" to 2160, "uhd" to 2160, "2160p" to 2160, "full hd" to 1080, "1080p" to 1080, "720p" to 720, "in hd" to 720, "hd" to 720)
            .firstOrNull { s.has(it.first) }?.let { (p, r) -> s.take(p); minResolution = r; notes += "≥ ${r}p" }

        // 6. Media type.
        val kinds = mutableSetOf<ItemKind>()
        var episodesWord = false
        if (s.has("episodes") || s.has("episode")) { s.take("episodes"); s.take("episode"); episodesWord = true; kinds += ItemKind.SERIES }
        for ((p, k) in KIND_WORDS) if (s.has(p)) { s.take(p); kinds += k }
        if (kinds.isNotEmpty()) notes += "type ${kinds.joinToString { it.name.lowercase() }}"

        // 7. Languages: full names in any script ("tamil", "हिन्दी"), also "dubbed in X" / "in X".
        val languages = mutableSetOf<String>()
        s.take(Regex("\\b(?:dubbed|dub|audio)\\b"))
        for (word in s.words()) {
            if (word.length < 4 && word.none { it.code > 0x2FF }) continue
            LanguageCatalog.findInText(word).singleOrNull()?.let { code -> languages += code; s.take(word); notes += "$word → language ${LanguageCatalog.displayName(code)}" }
        }

        // 8. Genres (lexicon + synonyms, two-word genres first).
        val genres = mutableSetOf<String>()
        for (g in genreNames.filter { ' ' in it && it != "sci fi" }) if (s.has(g)) { s.take(g); genres += g; notes += "$g → genre" }
        if (s.has("sci fi")) { s.take("sci fi"); genres += "science fiction"; notes += "sci fi → genre science fiction" }
        for (word in s.words()) {
            val g = QueryParser.GENRE_SYNONYMS[word] ?: word.removeSuffix("s").takeIf { it in genreNames } ?: word.takeIf { it in genreNames } ?: continue
            genres += g; s.take(word); notes += "$word → genre $g"
        }

        // 9. Filler.
        s.removeWords(FILLER)
        // Connectors stay inside a title ("tom and jerry") but not at its ends ("tamil and telugu").
        val title = s.words().dropWhile { it in CONNECTORS }.dropLastWhile { it in CONNECTORS }.joinToString(" ").ifEmpty { null }
        title?.let { notes += "title \"$it\"" }

        // Intent from the entities when no cue was given.
        val finalIntent = intent ?: when {
            episodesWord || (season != null || episode != null) && kinds.none { it == ItemKind.MOVIE } -> Intent.EPISODES
            else -> Intent.SEARCH
        }
        val entities = Entities(
            title = title, kinds = kinds, languages = languages, genres = genres, yearFrom = yearFrom, yearTo = yearTo,
            season = season, episode = episode, watch = watch, sort = sort, reversed = reversed, limit = limit, minResolution = minResolution,
        )
        val recognised = listOf(languages.isNotEmpty(), genres.isNotEmpty(), yearFrom != null || yearTo != null, season != null, episode != null,
            watch != WatchState.ANY, kinds.isNotEmpty(), sort != null).count { it }
        val confidence = when {
            cue -> 0.9
            recognised >= 2 -> 0.85
            recognised == 1 -> 0.75
            title != null -> 0.6 // bare words: a plain title search
            else -> 0.3
        }
        return Interpretation(input, finalIntent, entities, confidence, notes = notes)
    }

    /** A sentence being consumed: matched phrases are blanked out. */
    private class Sentence(initial: String) {
        var text = " $initial "
        fun has(phrase: String) = text.contains(" $phrase ")
        fun startsWith(prefix: String) = text.trimStart().startsWith(prefix)
        fun take(phrase: String) { text = text.replaceFirst(" $phrase ", "  ") }
        fun take(regex: Regex): List<String>? {
            val m = regex.find(text) ?: return null
            text = text.replaceRange(m.range, " ")
            return m.groupValues
        }
        fun words() = text.split(' ').filter { it.isNotEmpty() }
        fun removeWords(set: Set<String>) { text = " " + words().filter { it !in set }.joinToString(" ") + " " }
    }

    companion object {
        private val CONTRACTIONS = listOf(
            "haven't" to "have not", "hasn't" to "has not", "didn't" to "did not", "don't" to "do not", "doesn't" to "does not",
            "can't" to "can not", "won't" to "will not", "i've" to "i have", "i'm" to "i am", "what's" to "what is", "let's" to "let us",
            "isn't" to "is not", "aren't" to "are not", "wasn't" to "was not", "i'd" to "i would",
        )
        private val NUMBER_WORDS = listOf(
            "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen",
            "sixteen", "seventeen", "eighteen", "nineteen", "twenty",
        ).mapIndexed { i, w -> w to i + 1 }.toMap()
        private val ORDINALS = listOf("first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth").mapIndexed { i, w -> w to i + 1 }.toMap()

        private val KIND_WORDS = listOf(
            "tv shows" to ItemKind.SERIES, "tv series" to ItemKind.SERIES, "web series" to ItemKind.SERIES,
            "movies" to ItemKind.MOVIE, "movie" to ItemKind.MOVIE, "films" to ItemKind.MOVIE, "film" to ItemKind.MOVIE, "cinema" to ItemKind.MOVIE,
            "series" to ItemKind.SERIES, "shows" to ItemKind.SERIES, "show" to ItemKind.SERIES, "serials" to ItemKind.SERIES, "serial" to ItemKind.SERIES,
            "videos" to ItemKind.VIDEO, "clips" to ItemKind.VIDEO,
        )

        /** Command and filler words; whatever else remains is the title. */
        private val FILLER = setOf(
            "show", "me", "find", "list", "search", "look", "for", "all", "the", "some", "any", "please", "can", "could", "would", "you", "i", "want", "wanna",
            "to", "watch", "get", "give", "my", "a", "an", "from", "in", "with", "that", "which", "are", "is", "there", "do", "have", "what", "good",
            "available", "library", "collection", "telegram", "something", "ones", "those", "these", "again", "like", "about", "ok", "hey", "now", "next",
            "where", "on", "lets", "let", "us", "see", "browse", "open", "need", "would", "like", "am", "it", "be", "will", "not", "yet",
        )

        private val LEADING_COMMAND = Regex("^ +(?:(?:can|could|would) you )?(?:please )?(?:show|list|find|search for|search|look for|get|give|browse|i want to see|i want|i wanna see)(?: me)? ")

        private val CONNECTORS = setOf("and", "or", "of", "the", "by", "for")

        fun normalize(input: String): String {
            var t = input.lowercase().replace('’', '\'').replace('‘', '\'')
            for ((a, b) in CONTRACTIONS) t = t.replace(a, b)
            t = t.replace(Regex("[?!.,;:\"()\\[\\]]"), " ").replace("'s ", " ").replace("'", "")
            // "season two", "second season", "episode ten", "tenth episode" → digits.
            t = Regex("\\b(season|episode|ep|part) (${NUMBER_WORDS.keys.joinToString("|")})\\b").replace(t) { "${it.groupValues[1]} ${NUMBER_WORDS[it.groupValues[2]]}" }
            t = Regex("\\b(${ORDINALS.keys.joinToString("|")}) (season|episode)\\b").replace(t) { "${it.groupValues[2]} ${ORDINALS[it.groupValues[1]]}" }
            t = Regex("((?:19|20)\\d\\d) ?- ?((?:19|20)\\d\\d)").replace(t, "$1 to $2")
            // Like SearchText.normalize, but letters and digits stay together ("s2e10", "1080p", "90s").
            val folded = Normalizer.normalize(Regex("(?<=[a-z])\\p{Mn}+").replace(Normalizer.normalize(t, Normalizer.Form.NFD), ""), Normalizer.Form.NFC)
            return Regex("[^\\p{L}\\p{N}\\p{M}]+").replace(folded.replace("&", " and "), " ").trim()
        }
    }
}
