package dev.yourstream.metadata.core.match

import java.text.Normalizer
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * How alike two titles are, 0..1. Robust to the usual differences between a release name and a
 * catalogue title: case, accents, punctuation, "&"/"and", dotted acronyms (K.G.F = KGF),
 * number words and Roman numerals (Chapter Two = Chapter 2 = Chapter II), a leading article,
 * and one title containing the other ("Dune" vs "Dune: Part One").
 */
object TitleSimilarity {

    private val LATIN_MARKS = Regex("(?<=\\p{IsLatin})\\p{Mn}+")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val NUMBERS = mapOf(
        "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5", "six" to "6", "seven" to "7",
        "eight" to "8", "nine" to "9", "ten" to "10", "ii" to "2", "iii" to "3", "iv" to "4", "vi" to "6",
        "vii" to "7", "viii" to "8", "ix" to "9",
    )
    private val ARTICLES = setOf("the", "a", "an")

    fun tokens(title: String): List<String> {
        val folded = Normalizer.normalize(LATIN_MARKS.replace(Normalizer.normalize(title, Normalizer.Form.NFKD), ""), Normalizer.Form.NFC)
        val words = NON_WORD.replace(folded.lowercase(Locale.ROOT).replace("&", " and ").replace("'", "").replace("’", ""), " ")
            .trim().split(' ').filter { it.isNotEmpty() }.map { NUMBERS[it] ?: it }
        return if (words.size > 1 && words.first() in ARTICLES) words.drop(1) else words
    }

    fun normalize(title: String): String = tokens(title).joinToString(" ")

    fun similarity(a: String, b: String): Double {
        val ta = tokens(a)
        val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        if (ta == tb) return 1.0

        // Spacing-insensitive comparison catches "K.G.F" vs "KGF", "Spider Man" vs "Spiderman".
        val ca = ta.joinToString("")
        val cb = tb.joinToString("")
        if (ca == cb) return 0.98
        // Squared: one typo in a long title stays high, but short different words that share
        // letters ("Doraemon"/"Pokemon") drop well below the bar.
        val edit = (1.0 - levenshtein(ca, cb).toDouble() / max(ca.length, cb.length)).let { it * it }

        val sa = ta.toSet()
        val sb = tb.toSet()
        val jaccard = sa.intersect(sb).size.toDouble() / sa.union(sb).size

        // One title fully inside the other: good but not equal, weaker the more extra words there
        // are. A leading match ("Dune" / "Dune Part One", "Pushpa 2" / "Pushpa 2 The Rule") is
        // the usual title + subtitle shape and counts more than words found anywhere.
        val (smallList, largeList) = if (ta.size <= tb.size) ta to tb else tb to ta
        val small = smallList.toSet()
        val large = largeList.toSet()
        val ratio = small.size.toDouble() / large.size
        val containment = when {
            largeList.take(smallList.size) == smallList -> 0.56 + 0.4 * ratio
            large.containsAll(small) -> 0.45 + 0.4 * ratio
            else -> 0.0
        }

        return max(edit, max(jaccard * 0.95, containment)).coerceIn(0.0, 1.0)
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}
