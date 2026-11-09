package dev.yourstream.media.filename.internal

/**
 * Recognises machine-made file names: hashes ("ee4e5c5b2f7c4a9e"), UUIDs, Telegram file ids
 * ("AgADBQADq7ExGwbKjVQ"), random keys ("x7k2p9"). Such names say nothing about the video, so
 * the caption (or chat title) names it instead. Real titles with digits stay titles: "M3GAN",
 * "Se7en", "K3G", "Kaithi2019", "TheDarkKnight", "Ben 10".
 */
internal object RandomNames {
    private val HEX = Regex("^[0-9a-fA-F]+$")
    private val TRAILING_YEAR = Regex("(19|20)\\d{2}$")
    /** Run-together words: "TheDarkKnight", "BreakingBad", "MCU", "IronMan2". */
    private val CAMEL = Regex("^(?:[A-Z][a-z]{2,}|[a-z]{3,}|[A-Z]{2,4}|\\d{1,3})+$")
    /** Words that alone never make a title. */
    private val NOISE = setOf(
        "hd", "sd", "fhd", "uhd", "hq", "telegram", "tg", "video", "vid", "videos", "file", "files", "movie", "movies",
        "clip", "media", "new", "full", "download", "downloaded", "upload", "watch", "online", "mp4", "mkv", "part",
    )

    fun isRandomToken(word: String): Boolean {
        val t = word.filter(Char::isLetterOrDigit)
        if (t.length < 5) return false // "K3G", "R2D2", "c3a9": too short to tell (see isHexFragment)
        val core = TRAILING_YEAR.replace(t, "").ifEmpty { return false }
        val letters = core.count(Char::isLetter)
        val digits = core.count(Char::isDigit)
        if (letters == 0) return false // numbers alone are handled elsewhere ("1917", "300")
        // Hashes: hexadecimal with letters and digits, 8+ characters.
        if (core.length >= 8 && digits > 0 && HEX.matches(core)) return true
        // Keys that keep switching between letters and digits: "x7k2p9", "a7F3k9Qz".
        if (core.length >= 6 && transitions(core) >= 4) return true
        // Long single tokens that are not run-together words: ids such as "AgADBQADq7ExGwbKjVQ".
        if (core.length >= 12 && !CAMEL.matches(core) && (digits > 0 || caseFlips(core) >= 4)) return true
        return false
    }

    /** Nothing in [title] names anything: only random tokens, hex pieces and noise words ("hd", "video"). */
    fun isJunkTitle(title: String): Boolean {
        val words = title.split(' ', '.', '_', '-').filter { it.isNotBlank() }
        val meaningful = words.filterNot { isRandomToken(it) || isHexFragment(it) || it.lowercase() in NOISE }
        // Something was filtered out and nothing that names a title is left ("Hd", "Telegram C3a9").
        return meaningful.size < words.size && meaningful.joinToString("").count(Char::isLetter) < 2
    }

    /** UUID parts and short hash pieces: "4b5d", "c3a9" (hex with both letters and digits). */
    private fun isHexFragment(word: String): Boolean = word.length in 4..7 && isShortHex(word) && word.any(Char::isDigit) && word.any(Char::isLetter)

    private fun isShortHex(w: String) = w.isNotEmpty() && HEX.matches(w)

    private fun transitions(s: String): Int = s.zipWithNext().count { (a, b) -> a.isDigit() != b.isDigit() }

    private fun caseFlips(s: String): Int = s.filter(Char::isLetter).zipWithNext().count { (a, b) -> a.isUpperCase() != b.isUpperCase() }
}
