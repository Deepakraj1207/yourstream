package dev.yourstream.search.core

import java.util.concurrent.ConcurrentHashMap

enum class TermMatchKind { EXACT, PREFIX, FUZZY, PHONETIC }

data class TermMatch(val term: String, val kind: TermMatchKind, val distance: Int = 0)

/**
 * The vocabulary of the library (every distinct word of titles, alternative titles, chat names,
 * genres, languages) with a trigram index and transliteration keys, so a misspelled or
 * differently romanized query word can be mapped to words that really exist in the library.
 * Expansion is per word: cost depends on the vocabulary size, not on the number of items.
 */
class TermIndex {
    private val counts = ConcurrentHashMap<String, Int>()
    private val trigrams = ConcurrentHashMap<String, MutableSet<String>>()
    private val phonetic = ConcurrentHashMap<String, MutableSet<String>>()

    val size: Int get() = counts.size

    fun contains(term: String) = counts.containsKey(term)

    @Synchronized fun add(term: String) {
        if (term.isEmpty()) return
        val before = counts.merge(term, 1, Int::plus)
        if (before != 1) return
        for (g in grams(term)) trigrams.getOrPut(g) { ConcurrentHashMap.newKeySet() }.add(term)
        Phonetic.key(term)?.let { k -> phonetic.getOrPut(k) { ConcurrentHashMap.newKeySet() }.add(term) }
    }

    fun addAll(terms: Iterable<String>) = terms.forEach(::add)

    /**
     * Library words that a query word may mean, best first: itself, typo neighbours (edit distance
     * by length) and transliteration twins. Prefixes are not listed: the full-text index matches
     * them directly (`word*`).
     */
    fun expand(word: String, max: Int = 8): List<TermMatch> {
        val out = LinkedHashMap<String, TermMatch>()
        if (counts.containsKey(word)) out[word] = TermMatch(word, TermMatchKind.EXACT)
        val allowed = EditDistance.allowedFor(word.length)
        if (allowed > 0) {
            // Candidates share trigrams with the word: enough to be within the allowed edits.
            val shared = HashMap<String, Int>()
            for (g in grams(word)) trigrams[g]?.forEach { t -> shared.merge(t, 1, Int::plus) }
            val need = (grams(word).size - 3 * allowed).coerceAtLeast(1)
            shared.entries.asSequence().filter { it.value >= need && it.key != word }
                .mapNotNull { (t, _) -> EditDistance.within(word, t, allowed)?.let { d -> TermMatch(t, TermMatchKind.FUZZY, d) } }
                .sortedWith(compareBy<TermMatch> { it.distance }.thenByDescending { counts[it.term] ?: 0 })
                .forEach { out.putIfAbsent(it.term, it) }
        }
        Phonetic.key(word)?.let { k -> phonetic[k]?.filter { it != word }?.forEach { out.putIfAbsent(it, TermMatch(it, TermMatchKind.PHONETIC, 1)) } }
        return out.values.take(max)
    }

    /** Library words starting with [prefix] (suggestions), most frequent first. */
    fun completions(prefix: String, max: Int = 10): List<String> =
        counts.entries.asSequence().filter { it.key.startsWith(prefix) }.sortedByDescending { it.value }.take(max).map { it.key }.toList()

    companion object {
        /** Padded trigrams ("^do", "dor", …, "on$"): short words still have several. */
        fun grams(word: String): Set<String> {
            val w = "^$word$"
            return (0..w.length - 3).map { w.substring(it, it + 3) }.toSet()
        }
    }
}
