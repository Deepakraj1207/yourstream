package dev.yourstream.assistant.core

import dev.yourstream.core.language.LanguageCatalog
import dev.yourstream.search.core.ItemKind
import dev.yourstream.search.core.QueryParser
import dev.yourstream.search.core.SearchSort
import dev.yourstream.search.core.WatchState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Optional on-device language model (none ships with the app). It gets the request and the
 * deterministic reading as hints and answers with JSON in the [ModelAnswer] shape, or null.
 *
 * The assistant never *needs* it: when it is absent, slow, throws, or answers nonsense, the
 * deterministic interpretation is used unchanged.
 */
fun interface IntentModel {
    suspend fun interpret(text: String, deterministic: Interpretation): String?
}

/** The JSON a model must produce. Unknown keys are ignored; every field is optional. */
@Serializable
data class ModelAnswer(
    val intent: String? = null,
    val title: String? = null,
    val mediaType: String? = null,
    val languages: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val watched: Boolean? = null,
    val inProgress: Boolean? = null,
    val sort: String? = null,
    val limit: Int? = null,
)

/**
 * Deterministic parser first; then, only when a model is installed and the parser was unsure
 * ([confidence] below [modelBelow]), the model's answer is validated and merged: entities the
 * parser recognised win, the model fills gaps (typically the intent and the title).
 */
class AssistantInterpreter(
    private val parser: IntentParser = IntentParser(),
    private val model: IntentModel? = null,
    private val timeoutMs: Long = 1_500,
    private val modelBelow: Double = 0.8,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    suspend fun interpret(text: String): Interpretation {
        val base = parser.parse(text)
        val m = model ?: return base
        if (base.confidence >= modelBelow) return base
        val raw = try {
            withTimeoutOrNull(timeoutMs) { m.interpret(text, base) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // a broken model never breaks the assistant
        } ?: return base.copy(notes = base.notes + "model: no answer, deterministic reading kept")
        val answer = parseAnswer(raw) ?: return base.copy(notes = base.notes + "model: invalid answer, deterministic reading kept")
        return merge(base, answer)
    }

    /** Extracts and decodes the first JSON object in [raw] (models like to wrap it in prose). */
    fun parseAnswer(raw: String): ModelAnswer? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { json.decodeFromString(ModelAnswer.serializer(), raw.substring(start, end + 1)) }.getOrNull()
    }

    fun merge(base: Interpretation, a: ModelAnswer): Interpretation {
        val e = base.entities
        val intent = a.intent?.let { v -> Intent.entries.firstOrNull { it.name.equals(v.replace(' ', '_'), ignoreCase = true) } }
        val kind = a.mediaType?.let { v -> ItemKind.entries.firstOrNull { it.name.equals(v, ignoreCase = true) } ?: KIND_ALIASES[v.lowercase()] }
        val languages = a.languages.mapNotNull { LanguageCatalog.normalizeCode(it) ?: LanguageCatalog.findInText(it).firstOrNull() }.toSet()
        val genres = a.genres.map { it.lowercase().trim() }.map { QueryParser.GENRE_SYNONYMS[it] ?: it }.filter { it in QueryParser.DEFAULT_GENRES }.toSet()
        val watch = when {
            a.inProgress == true -> WatchState.IN_PROGRESS
            a.watched == true -> WatchState.WATCHED
            a.watched == false -> WatchState.UNWATCHED
            else -> WatchState.ANY
        }
        val modelTitle = a.title?.trim()?.lowercase()?.ifEmpty { null }
        fun year(y: Int?) = y?.takeIf { it in 1880..2100 }
        fun small(n: Int?, max: Int) = n?.takeIf { it in 1..max }
        val merged = e.copy(
            // Leftover words are only a guess at a title when nothing else was recognised: the model may correct it.
            title = if (base.confidence <= TITLE_GUESS) modelTitle else e.title ?: modelTitle,
            kinds = e.kinds.ifEmpty { setOfNotNull(kind) },
            languages = e.languages.ifEmpty { languages },
            genres = e.genres.ifEmpty { genres },
            yearFrom = e.yearFrom ?: year(a.yearFrom),
            yearTo = e.yearTo ?: year(a.yearTo),
            season = e.season ?: small(a.season, 99),
            episode = e.episode ?: small(a.episode, 9999),
            watch = if (e.watch != WatchState.ANY) e.watch else watch,
            sort = e.sort ?: a.sort?.let { v -> SearchSort.entries.firstOrNull { it.name.equals(v.replace(' ', '_'), ignoreCase = true) } },
            limit = e.limit ?: small(a.limit, 500),
        )
        return base.copy(
            intent = intent ?: base.intent,
            entities = merged,
            confidence = maxOf(base.confidence, 0.7),
            source = InterpretationSource.MODEL,
            notes = base.notes + "model: merged (${intent?.name?.lowercase() ?: "intent kept"})",
        )
    }

    private companion object {
        /** IntentParser's confidence for "only unrecognised words" (a plain title search). */
        const val TITLE_GUESS = 0.6
        val KIND_ALIASES = mapOf("film" to ItemKind.MOVIE, "tv" to ItemKind.SERIES, "show" to ItemKind.SERIES, "episode" to ItemKind.SERIES, "clip" to ItemKind.VIDEO)
    }
}
