package dev.yourstream.core.language

import java.text.Normalizer
import java.util.Locale

/** A language as the app uses it: ISO 639-1 where one exists ("ta"), else 639-2/3 ("bho"). */
data class Lang(val code: String, val name: String, val nativeName: String? = null)

/**
 * Every spelling of a language that shows up in container metadata (ISO 639-1/2B/2T/3, BCP-47
 * with region/script), track labels ("Tamil", "TAM", "தமிழ்", "Hindi Dubbed") and file names
 * ("movie.ta.srt", "Tam+Tel+Hin").
 */
object LanguageCatalog {

    // Declared first: the maps below are built with fold() during initialization.
    private val LATIN_MARKS = Regex("(?<=[a-z])\\p{Mn}+")
    private val TOKEN = Regex("[^\\p{L}\\p{M}]+")
    /** Abbreviations that are not ordinary words in common languages. */
    private val SAFE_ABBREVIATIONS = setOf("tam", "tel", "hin", "kan", "kann", "guj", "eng", "jpn", "kor", "tml", "tlg", "hnd", "mlm", "beng", "bho", "urd", "asm", "ori", "pun", "chi", "spa", "fre", "ger", "deu", "ita", "rus", "ara", "tha")

    private class Entry(
        val lang: Lang,
        /** Codes accepted in metadata: 639-1, 639-2 B/T, 639-3 and old/legacy codes. */
        val codes: List<String>,
        /** Full names (any language) and native names: trusted anywhere in free text. */
        val names: List<String>,
        /** Abbreviations ("tam", "hin"): trusted in labels and file-name tokens. */
        val short: List<String> = emptyList(),
    )

    private val catalog = listOf(
        Entry(Lang("ta", "Tamil", "தமிழ்"), listOf("ta", "tam"), listOf("tamil", "தமிழ்"), listOf("tam", "tml")),
        Entry(Lang("te", "Telugu", "తెలుగు"), listOf("te", "tel"), listOf("telugu", "తెలుగు"), listOf("tel", "tlg")),
        Entry(Lang("hi", "Hindi", "हिन्दी"), listOf("hi", "hin"), listOf("hindi", "हिन्दी", "हिंदी"), listOf("hin", "hnd")),
        Entry(Lang("ml", "Malayalam", "മലയാളം"), listOf("ml", "mal"), listOf("malayalam", "മലയാളം"), listOf("mal", "mlm")),
        Entry(Lang("kn", "Kannada", "ಕನ್ನಡ"), listOf("kn", "kan"), listOf("kannada", "ಕನ್ನಡ"), listOf("kan", "kann")),
        Entry(Lang("bn", "Bengali", "বাংলা"), listOf("bn", "ben"), listOf("bengali", "bangla", "বাংলা"), listOf("ben", "beng")),
        Entry(Lang("mr", "Marathi", "मराठी"), listOf("mr", "mar"), listOf("marathi", "मराठी"), listOf("mar")),
        Entry(Lang("gu", "Gujarati", "ગુજરાતી"), listOf("gu", "guj"), listOf("gujarati", "ગુજરાતી"), listOf("guj")),
        Entry(Lang("pa", "Punjabi", "ਪੰਜਾਬੀ"), listOf("pa", "pan"), listOf("punjabi", "panjabi", "ਪੰਜਾਬੀ"), listOf("pun", "pan")),
        Entry(Lang("or", "Odia", "ଓଡ଼ିଆ"), listOf("or", "ori", "ory"), listOf("odia", "oriya", "ଓଡ଼ିଆ"), listOf("ori")),
        Entry(Lang("as", "Assamese", "অসমীয়া"), listOf("as", "asm"), listOf("assamese", "অসমীয়া"), listOf("asm")),
        Entry(Lang("ur", "Urdu", "اردو"), listOf("ur", "urd"), listOf("urdu", "اردو"), listOf("urd")),
        Entry(Lang("si", "Sinhala", "සිංහල"), listOf("si", "sin"), listOf("sinhala", "sinhalese", "සිංහල"), listOf("sin")),
        Entry(Lang("ne", "Nepali", "नेपाली"), listOf("ne", "nep"), listOf("nepali", "नेपाली"), listOf("nep")),
        Entry(Lang("bho", "Bhojpuri", "भोजपुरी"), listOf("bho"), listOf("bhojpuri", "भोजपुरी"), listOf("bho")),
        Entry(Lang("sa", "Sanskrit", "संस्कृतम्"), listOf("sa", "san"), listOf("sanskrit"), emptyList()),
        Entry(Lang("en", "English"), listOf("en", "eng"), listOf("english", "inglés", "anglais", "englisch"), listOf("eng")),
        Entry(Lang("ko", "Korean", "한국어"), listOf("ko", "kor"), listOf("korean", "한국어"), listOf("kor")),
        Entry(Lang("ja", "Japanese", "日本語"), listOf("ja", "jpn"), listOf("japanese", "日本語"), listOf("jpn", "jap")),
        Entry(Lang("zh", "Chinese", "中文"), listOf("zh", "zho", "chi", "cmn", "yue", "zh-hans", "zh-hant"), listOf("chinese", "mandarin", "cantonese", "中文", "國語", "粵語"), listOf("chi", "chn")),
        Entry(Lang("es", "Spanish", "Español"), listOf("es", "spa"), listOf("spanish", "español", "espanol", "castellano", "latino"), listOf("spa", "esp")),
        Entry(Lang("fr", "French", "Français"), listOf("fr", "fre", "fra"), listOf("french", "français", "francais"), listOf("fre", "fra")),
        Entry(Lang("de", "German", "Deutsch"), listOf("de", "ger", "deu"), listOf("german", "deutsch"), listOf("ger", "deu")),
        Entry(Lang("it", "Italian", "Italiano"), listOf("it", "ita"), listOf("italian", "italiano"), listOf("ita")),
        Entry(Lang("pt", "Portuguese", "Português"), listOf("pt", "por"), listOf("portuguese", "português", "portugues"), listOf("por")),
        Entry(Lang("ru", "Russian", "Русский"), listOf("ru", "rus"), listOf("russian", "русский"), listOf("rus")),
        Entry(Lang("ar", "Arabic", "العربية"), listOf("ar", "ara"), listOf("arabic", "العربية"), listOf("ara")),
        Entry(Lang("tr", "Turkish", "Türkçe"), listOf("tr", "tur"), listOf("turkish", "türkçe"), listOf("tur")),
        Entry(Lang("th", "Thai", "ไทย"), listOf("th", "tha"), listOf("thai", "ไทย"), listOf("tha")),
        Entry(Lang("id", "Indonesian"), listOf("id", "ind"), listOf("indonesian", "bahasa indonesia"), listOf("ind")),
        Entry(Lang("ms", "Malay"), listOf("ms", "may", "msa", "zsm"), listOf("malay", "bahasa melayu"), listOf("may", "msa")),
        Entry(Lang("vi", "Vietnamese"), listOf("vi", "vie"), listOf("vietnamese", "tiếng việt"), listOf("vie")),
        Entry(Lang("tl", "Filipino"), listOf("tl", "fil", "tgl"), listOf("filipino", "tagalog"), listOf("fil", "tgl")),
        Entry(Lang("fa", "Persian", "فارسی"), listOf("fa", "per", "fas"), listOf("persian", "farsi", "فارسی"), listOf("per", "fas")),
        Entry(Lang("he", "Hebrew", "עברית"), listOf("he", "heb", "iw"), listOf("hebrew", "עברית"), listOf("heb")),
        Entry(Lang("pl", "Polish"), listOf("pl", "pol"), listOf("polish", "polski"), listOf("pol")),
        Entry(Lang("nl", "Dutch"), listOf("nl", "dut", "nld"), listOf("dutch", "nederlands"), listOf("dut", "nld")),
        Entry(Lang("uk", "Ukrainian"), listOf("uk", "ukr"), listOf("ukrainian", "українська"), listOf("ukr")),
        Entry(Lang("sv", "Swedish"), listOf("sv", "swe"), listOf("swedish", "svenska"), listOf("swe")),
        Entry(Lang("no", "Norwegian"), listOf("no", "nor", "nb", "nob", "nn", "nno"), listOf("norwegian", "norsk"), listOf("nor")),
        Entry(Lang("da", "Danish"), listOf("da", "dan"), listOf("danish", "dansk"), listOf("dan")),
        Entry(Lang("fi", "Finnish"), listOf("fi", "fin"), listOf("finnish", "suomi"), listOf("fin")),
        Entry(Lang("el", "Greek"), listOf("el", "gre", "ell"), listOf("greek", "ελληνικά"), listOf("gre", "ell")),
        Entry(Lang("cs", "Czech"), listOf("cs", "cze", "ces"), listOf("czech", "čeština"), listOf("cze", "ces")),
        Entry(Lang("hu", "Hungarian"), listOf("hu", "hun"), listOf("hungarian", "magyar"), listOf("hun")),
        Entry(Lang("ro", "Romanian"), listOf("ro", "rum", "ron"), listOf("romanian", "română"), listOf("rum", "ron")),
    )

    private val byCode: Map<String, Entry> = buildMap { catalog.forEach { e -> e.codes.forEach { put(it, e) } } }
    private val byName: Map<String, Entry> = buildMap { catalog.forEach { e -> e.names.forEach { put(fold(it), e) } } }
    private val byShort: Map<String, Entry> = buildMap { catalog.forEach { e -> e.short.forEach { put(it, e) } } }

    /** Tags that mean "no / unknown / several languages". */
    private val unknown = setOf("", "und", "mul", "zxx", "mis", "unk", "unknown", "null", "none", "qaa", "xx")

    val all: List<Lang> get() = catalog.map { it.lang }

    fun of(code: String): Lang? = normalizeCode(code)?.let { c -> byCode[c]?.lang ?: Lang(c, c) }

    /**
     * Canonical code for a metadata tag ("tam", "ta-IN", "TA", "zh-Hant", "Tamil"), or null for
     * unknown / "und" / garbage. Unrecognized but well-formed codes are kept as they are.
     */
    fun normalizeCode(tag: String?): String? {
        val t = tag?.trim()?.lowercase(Locale.ROOT)?.replace('_', '-') ?: return null
        if (t in unknown) return null
        byCode[t]?.let { return it.lang.code }
        val primary = t.substringBefore('-')
        if (primary in unknown) return null
        byCode[primary]?.let { return it.lang.code }
        byName[fold(t)]?.let { return it.lang.code }
        return primary.takeIf { it.length in 2..3 && it.all { c -> c in 'a'..'z' } }
    }

    /** Region of a BCP-47 tag ("en-US" → "US"), for tie-breaking only. */
    fun regionOf(tag: String?): String? = tag?.replace('_', '-')?.split('-')?.drop(1)?.firstOrNull { it.length == 2 }?.uppercase(Locale.ROOT)

    /**
     * Languages named in free text (track label, file name), in the order they appear.
     * Full and native names anywhere; abbreviations only as separate tokens; 2-letter codes only
     * when [allowTwoLetterCodes] (file-name suffixes like "movie.ta.srt").
     */
    fun findInText(text: String?, allowTwoLetterCodes: Boolean = false): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        val found = mutableListOf<Pair<Int, String>>()
        val folded = fold(text)
        // Names (also native script), as whole words.
        for ((name, e) in byName) {
            var from = 0
            while (true) {
                val i = folded.indexOf(name, from)
                if (i < 0) break
                val before = folded.getOrNull(i - 1)
                val after = folded.getOrNull(i + name.length)
                if ((before == null || !before.isLetter()) && (after == null || !after.isLetter())) found += i to e.lang.code
                from = i + name.length
            }
        }
        // Tokens: abbreviations and codes ("Tam+Tel+Hin", "[ENG]", "movie.ta.forced.srt"). Three-letter
        // tokens that are also ordinary words ("may", "per", "dan") count only in short texts, in
        // upper case, or when they are unambiguous release-name abbreviations.
        val original = TOKEN.split(Normalizer.normalize(text, Normalizer.Form.NFC)).filter { it.isNotEmpty() }
        val tokens = TOKEN.split(folded).filter { it.isNotEmpty() }
        val short = tokens.size <= 4
        var pos = 0
        tokens.forEachIndexed { idx, token ->
            val at = folded.indexOf(token, pos).also { if (it >= 0) pos = it + token.length }
            val upper = original.getOrNull(idx)?.let { o -> o.length > 1 && o.all { !it.isLetter() || it.isUpperCase() } } == true
            val e = when {
                token.length == 2 -> if (allowTwoLetterCodes) byCode[token] else null
                token.length == 3 && (short || upper || token in SAFE_ABBREVIATIONS) -> byShort[token] ?: byCode[token]
                token.length > 3 -> byShort[token]
                else -> null
            }
            if (e != null) found += at to e.lang.code
        }
        return found.sortedBy { it.first }.map { it.second }.distinct()
    }

    fun displayName(code: String?): String = code?.let { byCode[it]?.lang?.name ?: it } ?: "Unknown"



    /** Lowercase, strip Latin accents (keeps Indic and other scripts intact). */
    private fun fold(s: String): String {
        val d = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        return Normalizer.normalize(d.replace(LATIN_MARKS, ""), Normalizer.Form.NFC)
    }

}
