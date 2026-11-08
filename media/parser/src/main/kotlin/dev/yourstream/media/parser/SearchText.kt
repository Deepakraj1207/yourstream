package dev.yourstream.media.parser

import java.text.Normalizer
import java.util.Locale

/** Text normalisation shared by indexing and searching, so both sides agree. */
object SearchText {

    private val LATIN_MARKS = Regex("(?<=\\p{IsLatin})\\p{Mn}+")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}\\p{M}]+")

    /**
     * Lower-case, Latin accents removed ("Amélie" → "amelie"), punctuation and separators
     * collapsed to single spaces. Marks on non-Latin scripts (Tamil, Devanagari vowel signs)
     * are kept because they change the word.
     */
    fun normalize(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD)
        val noAccents = LATIN_MARKS.replace(decomposed, "")
        val recomposed = Normalizer.normalize(noAccents, Normalizer.Form.NFC)
        return NON_WORD.replace(recomposed.lowercase(Locale.ROOT), " ").trim()
    }

    /**
     * FTS4 MATCH expression for user input: every word must match, each as a prefix
     * ("aven endg" finds "Avengers Endgame"). Only letters/digits survive, so the result
     * can never contain FTS operators. Null when nothing searchable is left.
     */
    fun ftsQuery(input: String, maxTerms: Int = 8): String? {
        val terms = normalize(input).split(' ').filter { it.isNotEmpty() }.take(maxTerms)
        if (terms.isEmpty()) return null
        return terms.joinToString(" ") { "$it*" }
    }
}
