package dev.yourstream.media.filename.internal

import java.text.Normalizer
import java.util.Locale

/** Turns the raw title region into a display title and a matching key. */
internal object TitleNormalizer {

    private val I = RegexOption.IGNORE_CASE
    private val SPACES = Regex("\\s+")
    private val EDGE_JUNK = listOf(
        Regex("^(watch|download|new|latest)\\s+", I),
        Regex("\\s+(full\\s+(hd\\s+)?(movie|film)|movie\\s+download|watch\\s+online|official(\\s+(video|movie|trailer))?|(new|latest)\\s+movie|full\\s+episode)$", I),
    )
    private val SMALL_WORDS = setOf("a", "an", "and", "as", "at", "but", "by", "for", "in", "of", "on", "or", "the", "to", "vs", "with", "from")
    private val ROMAN = Regex("^(ii|iii|iv|vi|vii|viii|ix|xi|xii)$", I)
    private val LATIN_MARKS = Regex("(?<=\\p{IsLatin})\\p{Mn}+")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}\\p{M}]+")

    fun display(raw: String): String? {
        var s = SPACES.replace(raw, " ").trim().trim('-', '+', '&', ',', '"', ' ', '\'')
        for (junk in EDGE_JUNK) s = junk.replace(s, "").trim()
        s = s.trim('-', '+', '&', ',', ' ')
        if (s.none(Char::isLetterOrDigit)) return null
        return fixCase(s)
    }

    /** "Ocean's Eleven & Co." → "oceans eleven and co"; "Amélie" → "amelie". */
    fun key(title: String): String {
        val folded = Normalizer.normalize(LATIN_MARKS.replace(Normalizer.normalize(title, Normalizer.Form.NFKD), ""), Normalizer.Form.NFC)
        return NON_WORD.replace(folded.lowercase(Locale.ROOT).replace("&", " and ").replace("'", "").replace("’", ""), " ").trim()
    }

    private fun fixCase(s: String): String {
        val letters = s.filter(Char::isLetter)
        val cased = letters.filter { it.isUpperCase() || it.isLowerCase() }
        if (cased.isEmpty()) return s // scripts without case (Tamil, Devanagari …)
        val allUpper = cased.all(Char::isUpperCase)
        val allLower = cased.all(Char::isLowerCase)
        if (allUpper && !s.contains(' ') && s.length <= 5) return s // acronyms: RRR, KGF, PK
        if (!allUpper && !allLower) return s // the uploader chose a casing; keep it
        val words = s.split(' ')
        return words.mapIndexed { i, word ->
            val lower = word.lowercase(Locale.ROOT)
            when {
                ROMAN.matches(word) -> word.uppercase(Locale.ROOT)
                i > 0 && i < words.lastIndex && lower in SMALL_WORDS -> lower
                else -> lower.replaceFirstChar { it.titlecase(Locale.ROOT) }
            }
        }.joinToString(" ")
    }
}
