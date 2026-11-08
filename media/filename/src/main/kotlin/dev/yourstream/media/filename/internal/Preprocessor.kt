package dev.yourstream.media.filename.internal

import dev.yourstream.media.filename.FileExtensions

/** The input made scannable: extension split off, noise removed, separators turned into spaces. */
internal data class Prepared(
    val original: String,
    val extension: String?,
    val splitPart: Int?,
    /** Space-separated text the detectors scan. Never contains the extension. */
    val text: String,
    val leadingGroup: String?,
    val trailingBracketGroup: String?,
    /** Years written in parentheses, e.g. "(2024)": strong year evidence. */
    val parenthesizedYears: Set<Int>,
    /** The name used '-' as its only separator, so '-' is not a release-group marker. */
    val hyphenSeparated: Boolean,
    /** Trailing words before a subtitle extension ("movie.en.forced.srt" → ["en", "forced"]). */
    val subtitleSuffixWords: List<String>,
    val removedNoise: List<String>,
)

internal object Preprocessor {

    const val MAX_LENGTH = 512

    private val IGNORE = RegexOption.IGNORE_CASE
    private val CONTROL = Regex("[\\p{Cntrl}\\u200B-\\u200F\\uFEFF]")
    private val LEADING_BRACKET = Regex("^\\s*[\\[【]([^\\]】]{1,60})[\\]】]\\s*")
    private val TRAILING_BRACKET = Regex("\\s*\\[([^\\]]{1,40})]\\s*$")
    private val CRC = Regex("\\[[0-9A-Fa-f]{8}]")
    private val PAREN_YEAR = Regex("\\((19[2-9]\\d|20\\d{2})\\)")
    private const val TLDS = "com|net|org|xyz|cc|ws|pw|biz|lol|wtf|club|site|online|vip|mx|bar|icu|top|io|ru|tk|pics|skin|autos|boats|homes|mov|dad|prof|wiki|lat|cfd|sbs|cyou|quest|beauty|hair|makeup|monster|rest|shop|art|link|click|fun|space|website|in\\.net|co\\.in"
    /** Pattern → replacement. Only the @handle pattern keeps its preceding separator. */
    private val NOISE: List<Pair<Regex, String>> = listOf(
        Regex("https?://\\S+", IGNORE) to " ",
        Regex("\\bt\\.me/\\S+", IGNORE) to " ",
        Regex("(^|\\s|[._(\\[-])@[A-Za-z0-9_]{3,}") to "$1 ",
        Regex("\\bwww\\.[\\w-]+(\\.[\\w-]+)+", IGNORE) to " ",
        Regex("\\b[\\w-]{2,}\\.($TLDS)\\b", IGNORE) to " ",
        Regex("\\b\\d+(?:[.,]\\d+)?\\s?(?:GB|MB|GiB|MiB|KB)\\b", IGNORE) to " ",
        Regex("\\b(join|subscribe|follow)\\s+(us|now|channel)\\b.*$", IGNORE) to " ",
    )
    private val SYMBOLS = Regex("[^\\p{L}\\p{N}\\p{M}\\s._\\-+&'()\\[\\]{}|~,;:!?]")
    private val SEPARATORS = Regex("[._\\[\\](){}|~,;:【】]+")
    private val SPACES = Regex("\\s+")

    fun prepare(input: String, isFileName: Boolean): Prepared {
        val original = input
        var s = CONTROL.replace(input.take(MAX_LENGTH), " ").trim()

        var extension: String? = null
        var splitPart: Int? = null
        if (isFileName) {
            val parts = FileExtensions.split(s)
            s = parts.baseName
            extension = parts.extension
            splitPart = parts.splitPart
        }

        val removed = mutableListOf<String>()
        // Links/handles first: a leading "[www.site.com]" is noise, not a release group.
        for ((regex, replacement) in NOISE) {
            regex.findAll(s).forEach { removed += it.value.trim() }
            s = regex.replace(s, replacement)
        }

        var leadingGroup: String? = null
        LEADING_BRACKET.find(s)?.let { m ->
            val inside = m.groupValues[1].trim()
            if (inside.isNotEmpty() && looksLikeGroup(inside)) leadingGroup = inside
            // "[1080p]" or "[Tamil]" at the start still carries information: move it behind
            // the title, where the detectors treat it as technical text.
            s = s.removeRange(m.range)
            if (leadingGroup == null && inside.isNotBlank()) s = "$s $inside"
        }
        s = CRC.replace(s, " ")

        var trailingGroup: String? = null
        TRAILING_BRACKET.find(s)?.let { m ->
            val inside = m.groupValues[1].trim()
            if (looksLikeGroup(inside)) {
                trailingGroup = inside
                s = s.removeRange(m.range)
            }
        }

        val parenYears = PAREN_YEAR.findAll(s).map { it.groupValues[1].toInt() }.toSet()

        s = SYMBOLS.replace(s, " ")
        val hyphenSeparated = s.none { it == ' ' || it == '.' || it == '_' } && s.contains('-')
        if (hyphenSeparated) s = s.replace('-', ' ')

        var suffixWords = emptyList<String>()
        if (extension != null && extension in FileExtensions.SUBTITLE) {
            suffixWords = s.split('.', '_', ' ', '-').filter { it.isNotBlank() }.takeLast(3)
        }

        val text = SPACES.replace(SEPARATORS.replace(s, " "), " ").trim()
        return Prepared(original, extension, splitPart, text, leadingGroup, trailingGroup, parenYears, hyphenSeparated, suffixWords, removed)
    }

    private val TECHNICAL = Regex(
        "^(\\d{3,4}[pi]|4k|x26[45]|h\\.?26[45]|hevc|avc|web-?dl|webrip|bluray|hdrip|aac|dts|ddp?5?\\.?1|hdr|dual|multi|esubs?|10bit|\\d+)$",
        IGNORE,
    )

    private fun looksLikeGroup(inside: String): Boolean {
        if (inside.length < 2 || inside.length > 30) return false
        if (inside.contains(' ') && inside.split(' ').size > 3) return false
        if (TECHNICAL.matches(inside.trim())) return false
        if (Vocabulary.languageOf(inside) != null) return false
        if (inside.split(Regex("[\\s+&/,-]+")).count { Vocabulary.languageOf(it) != null } >= 2) return false
        return inside.any(Char::isLetter)
    }
}
