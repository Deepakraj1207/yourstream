package dev.yourstream.search.core

import java.text.Normalizer
import java.util.Locale

/**
 * One normalization for indexing and for queries, so both sides always agree:
 * lowercase, Latin accents folded, "&" → "and", apostrophes dropped, letters and digits split
 * ("ben10" → "ben 10", "007spectre" → "007 spectre"), everything else a space.
 */
object SearchText {
    private val LATIN_MARKS = Regex("(?<=[a-z])\\p{Mn}+")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}\\p{M}]+")
    private val LETTER_DIGIT = Regex("(?<=\\p{L})(?=\\p{N})|(?<=\\p{N})(?=\\p{L})")

    /** Words that carry no meaning for matching ("tom and jerry" = "tom jerry"). */
    val STOPWORDS = setOf("the", "a", "an", "and", "of", "in", "on", "to", "le", "la", "el", "de")

    fun normalize(text: String): String {
        val lower = text.lowercase(Locale.ROOT).replace("&", " and ").replace("'", "").replace("’", "")
        val folded = Normalizer.normalize(LATIN_MARKS.replace(Normalizer.normalize(lower, Normalizer.Form.NFD), ""), Normalizer.Form.NFC)
        return NON_WORD.replace(LETTER_DIGIT.replace(folded, " "), " ").trim()
    }

    fun tokens(text: String): List<String> = normalize(text).split(' ').filter { it.isNotEmpty() }

    /** Tokens without stopwords (unless nothing else is left). */
    fun significant(tokens: List<String>): List<String> = tokens.filter { it !in STOPWORDS }.ifEmpty { tokens }

    /**
     * Adjacent tokens joined ("spider man" → "spiderman", "ben 10" → "ben10"), indexed next to the
     * title so a query written either way finds it.
     */
    fun compounds(tokens: List<String>): List<String> =
        tokens.zipWithNext { a, b -> a + b }.filter { it.length in 4..24 }
}

/**
 * Transliteration skeleton for romanized titles, which are spelled many ways
 * ("Doraemon"/"Doremon", "Shinchan"/"Sinchan", "Chhota Bheem"/"Chota Bheem",
 * "Kaththi"/"Kathi", "Tamizh"/"Tamil"): common digraphs merged, doubled letters collapsed,
 * vowels after the first letter dropped. Only used for words of ≥ 5 letters (short skeletons
 * collide too easily).
 */
object Phonetic {
    private val RULES = listOf(
        "chh" to "c", "ph" to "f", "sh" to "s", "ch" to "c", "th" to "t", "dh" to "d", "kh" to "k", "gh" to "g",
        "bh" to "b", "jh" to "j", "zh" to "l", "ck" to "k", "q" to "k", "x" to "ks", "w" to "v", "z" to "j",
    )

    fun key(term: String): String? {
        if (term.length < 5 || !term.all { it in 'a'..'z' }) return null
        var s = term
        for ((from, to) in RULES) s = s.replace(from, to)
        val out = StringBuilder()
        for ((i, c) in s.withIndex()) {
            if (i > 0 && c in "aeiouyh") continue
            if (out.isNotEmpty() && out.last() == c) continue
            out.append(c)
        }
        return out.toString().takeIf { it.length >= 3 }
    }
}

/** Optimal-string-alignment distance (Levenshtein + adjacent transposition), cut off above [max]. */
object EditDistance {
    fun within(a: String, b: String, max: Int): Int? {
        if (kotlin.math.abs(a.length - b.length) > max) return null
        if (a == b) return 0
        val n = a.length
        val m = b.length
        var prev2 = IntArray(m + 1)
        var prev = IntArray(m + 1) { it }
        var cur = IntArray(m + 1)
        for (i in 1..n) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, prev2[j - 2] + 1)
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > max) return null
            val t = prev2; prev2 = prev; prev = cur; cur = t
        }
        return prev[m].takeIf { it <= max }
    }

    /** Typos allowed for a word of this length: none up to 3 letters, 1 up to 6, 2 from 7. */
    fun allowedFor(length: Int) = when {
        length <= 3 -> 0
        length <= 6 -> 1
        else -> 2
    }
}
