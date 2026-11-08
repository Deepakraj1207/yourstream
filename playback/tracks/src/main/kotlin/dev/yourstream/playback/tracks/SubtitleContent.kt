package dev.yourstream.playback.tracks

import java.lang.Character.UnicodeScript

data class ContentLanguage(val code: String, val confidence: Double)

/**
 * Guesses the language of subtitle *text* (external files are small and readable before
 * playback). Unambiguous scripts identify the language directly (Tamil, Telugu, Kannada,
 * Malayalam, Gujarati, Gurmukhi, Odia, Hangul, Kana, Thai …). Devanagari, Bengali, Arabic and
 * Cyrillic are shared by several languages and give a weaker guess. Latin text is only called
 * English when common English function words are frequent; romanized Tamil/Hindi ("Tanglish")
 * stays unknown.
 */
object SubtitleContent {

    fun guess(text: String): ContentLanguage? {
        val lines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !TIMING.matches(it) && !it.all { c -> c.isDigit() } }
            // ASS/SSA: the text is the 10th field of a Dialogue line.
            .map { if (it.startsWith("Dialogue:")) it.split(",", limit = 10).getOrElse(9) { "" } else it }
            .map { TAGS.replace(it, " ") }
            .take(400)
            .joinToString(" ")
        val counts = HashMap<UnicodeScript, Int>()
        var letters = 0
        lines.codePoints().forEach { cp ->
            if (Character.isLetter(cp)) {
                letters++
                val script = UnicodeScript.of(cp)
                counts[script] = (counts[script] ?: 0) + 1
            }
        }
        if (letters < 20) return null
        val (script, n) = counts.maxByOrNull { it.value } ?: return null
        val share = n.toDouble() / letters
        if (share < 0.6) return null
        SCRIPT_LANGUAGE[script]?.let { (code, conf) -> return ContentLanguage(code, conf * share) }
        if (script == UnicodeScript.HAN) {
            val kana = (counts[UnicodeScript.HIRAGANA] ?: 0) + (counts[UnicodeScript.KATAKANA] ?: 0)
            return if (kana > 0) ContentLanguage("ja", 0.85) else ContentLanguage("zh", 0.8 * share)
        }
        if (script == UnicodeScript.LATIN) {
            val words = WORD.findAll(lines.lowercase()).map { it.value }.toList()
            if (words.size < 15) return null
            val english = words.count { it in ENGLISH }.toDouble() / words.size
            if (english >= 0.18) return ContentLanguage("en", (0.6 + english).coerceAtMost(0.9))
        }
        return null
    }

    private val TIMING = Regex("""^\d{1,2}:\d{2}:\d{2}[,.]\d{1,3}\s*-->.*$|^WEBVTT.*$|^\[.*]$|^(Style|Format|Title|ScriptType|PlayRes[XY]):.*$""")
    private val TAGS = Regex("""<[^>]+>|\{[^}]*}|\\[Nnh]""")
    private val WORD = Regex("[a-z']+")
    private val ENGLISH = setOf(
        "the", "you", "i", "to", "a", "and", "is", "it", "of", "that", "what", "in", "me", "we", "this", "my",
        "your", "don't", "for", "be", "have", "no", "not", "are", "do", "was", "he", "she", "on", "with", "i'm",
        "it's", "can", "know", "just", "all", "so", "they", "here", "there", "will", "get", "go", "come", "yes",
    )
    private val SCRIPT_LANGUAGE: Map<UnicodeScript, Pair<String, Double>> = mapOf(
        UnicodeScript.TAMIL to ("ta" to 0.98),
        UnicodeScript.TELUGU to ("te" to 0.98),
        UnicodeScript.KANNADA to ("kn" to 0.98),
        UnicodeScript.MALAYALAM to ("ml" to 0.98),
        UnicodeScript.GUJARATI to ("gu" to 0.98),
        UnicodeScript.GURMUKHI to ("pa" to 0.98),
        UnicodeScript.ORIYA to ("or" to 0.98),
        UnicodeScript.SINHALA to ("si" to 0.98),
        UnicodeScript.HANGUL to ("ko" to 0.98),
        UnicodeScript.HIRAGANA to ("ja" to 0.95),
        UnicodeScript.KATAKANA to ("ja" to 0.9),
        UnicodeScript.THAI to ("th" to 0.98),
        UnicodeScript.HEBREW to ("he" to 0.9),
        UnicodeScript.GREEK to ("el" to 0.95),
        // Shared scripts: the most common language, with a weaker confidence.
        UnicodeScript.DEVANAGARI to ("hi" to 0.65), // also Marathi, Nepali, Sanskrit, Bhojpuri
        UnicodeScript.BENGALI to ("bn" to 0.7),     // also Assamese
        UnicodeScript.ARABIC to ("ar" to 0.55),     // also Urdu, Persian
        UnicodeScript.CYRILLIC to ("ru" to 0.55),   // also Ukrainian, Bulgarian …
    )
}
