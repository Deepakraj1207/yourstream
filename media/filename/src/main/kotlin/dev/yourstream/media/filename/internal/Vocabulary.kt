package dev.yourstream.media.filename.internal

import dev.yourstream.media.filename.Language
import java.util.Locale

/** One language and every way release names spell it. */
internal data class LanguageEntry(
    val language: Language,
    /** Unambiguous full names, matched anywhere after the title. */
    val names: List<String>,
    /** Short forms ("tam", "hin"): only trusted in groups ("Tam+Tel") or next to dub/audio/sub. */
    val short: List<String>,
    /** Native-script names, matched anywhere. */
    val native: List<String> = emptyList(),
    /** ISO 639-1 code, only trusted as a subtitle-file suffix ("movie.ta.srt"). */
    val iso1: String? = null,
)

internal object Vocabulary {

    val LANGUAGES: List<LanguageEntry> = listOf(
        LanguageEntry(Language("ta", "Tamil"), listOf("tamil"), listOf("tam"), listOf("தமிழ்"), "ta"),
        LanguageEntry(Language("te", "Telugu"), listOf("telugu"), listOf("tel"), listOf("తెలుగు"), "te"),
        LanguageEntry(Language("hi", "Hindi"), listOf("hindi"), listOf("hin"), listOf("हिंदी", "हिन्दी"), "hi"),
        LanguageEntry(Language("ml", "Malayalam"), listOf("malayalam"), listOf("mal"), listOf("മലയാളം"), "ml"),
        LanguageEntry(Language("kn", "Kannada"), listOf("kannada"), listOf("kan", "kann"), listOf("ಕನ್ನಡ"), "kn"),
        LanguageEntry(Language("bn", "Bengali"), listOf("bengali", "bangla"), listOf("ben", "beng"), listOf("বাংলা"), "bn"),
        LanguageEntry(Language("mr", "Marathi"), listOf("marathi"), listOf("mar"), listOf("मराठी"), "mr"),
        LanguageEntry(Language("pa", "Punjabi"), listOf("punjabi"), listOf("pun", "pan"), listOf("ਪੰਜਾਬੀ"), "pa"),
        LanguageEntry(Language("gu", "Gujarati"), listOf("gujarati"), listOf("guj"), listOf("ગુજરાતી"), "gu"),
        LanguageEntry(Language("or", "Odia"), listOf("odia", "oriya"), listOf("ori"), emptyList(), "or"),
        LanguageEntry(Language("as", "Assamese"), listOf("assamese"), listOf("asm"), emptyList(), "as"),
        LanguageEntry(Language("ur", "Urdu"), listOf("urdu"), listOf("urd"), emptyList(), "ur"),
        LanguageEntry(Language("si", "Sinhala"), listOf("sinhala", "sinhalese"), listOf("sin"), emptyList(), "si"),
        LanguageEntry(Language("ne", "Nepali"), listOf("nepali"), listOf("nep"), emptyList(), "ne"),
        LanguageEntry(Language("bho", "Bhojpuri"), listOf("bhojpuri"), listOf("bho"), emptyList()),
        LanguageEntry(Language("en", "English"), listOf("english"), listOf("eng"), emptyList(), "en"),
        LanguageEntry(Language("ko", "Korean"), listOf("korean"), listOf("kor"), listOf("한국어"), "ko"),
        LanguageEntry(Language("ja", "Japanese"), listOf("japanese"), listOf("jap", "jpn"), listOf("日本語"), "ja"),
        LanguageEntry(Language("zh", "Chinese"), listOf("chinese", "mandarin", "cantonese"), listOf("chi", "zho", "chn"), listOf("中文"), "zh"),
        LanguageEntry(Language("es", "Spanish"), listOf("spanish", "castellano", "latino"), listOf("spa", "esp"), emptyList(), "es"),
        LanguageEntry(Language("fr", "French"), listOf("french", "francais"), listOf("fre", "fra"), emptyList(), "fr"),
        LanguageEntry(Language("de", "German"), listOf("german", "deutsch"), listOf("ger", "deu"), emptyList(), "de"),
        LanguageEntry(Language("it", "Italian"), listOf("italian"), listOf("ita"), emptyList(), "it"),
        LanguageEntry(Language("pt", "Portuguese"), listOf("portuguese"), listOf("por"), emptyList(), "pt"),
        LanguageEntry(Language("ru", "Russian"), listOf("russian"), listOf("rus"), emptyList(), "ru"),
        LanguageEntry(Language("ar", "Arabic"), listOf("arabic"), listOf("ara"), emptyList(), "ar"),
        LanguageEntry(Language("tr", "Turkish"), listOf("turkish"), listOf("tur"), emptyList(), "tr"),
        LanguageEntry(Language("th", "Thai"), listOf("thai"), emptyList(), emptyList(), "th"),
        LanguageEntry(Language("id", "Indonesian"), listOf("indonesian"), listOf("ind", "indo"), emptyList(), "id"),
        LanguageEntry(Language("ms", "Malay"), listOf("malay"), listOf("msa", "may"), emptyList(), "ms"),
        LanguageEntry(Language("vi", "Vietnamese"), listOf("vietnamese"), listOf("vie"), emptyList(), "vi"),
        LanguageEntry(Language("tl", "Filipino"), listOf("filipino", "tagalog"), listOf("fil", "tgl"), emptyList(), "tl"),
        LanguageEntry(Language("fa", "Persian"), listOf("persian", "farsi"), listOf("per", "fas"), emptyList(), "fa"),
        LanguageEntry(Language("he", "Hebrew"), listOf("hebrew"), listOf("heb"), emptyList(), "he"),
        LanguageEntry(Language("pl", "Polish"), listOf("polish"), listOf("pol"), emptyList(), "pl"),
        LanguageEntry(Language("nl", "Dutch"), listOf("dutch"), listOf("dut", "nld"), emptyList(), "nl"),
    )

    private val byWord: Map<String, LanguageEntry> = buildMap {
        for (entry in LANGUAGES) {
            entry.names.forEach { put(it, entry) }
            entry.short.forEach { put(it, entry) }
            entry.native.forEach { put(it.lowercase(Locale.ROOT), entry) }
        }
    }

    private val byIso1: Map<String, LanguageEntry> = LANGUAGES.mapNotNull { e -> e.iso1?.let { it to e } }.toMap()

    /** Full name, short form or native name → language. */
    fun languageOf(word: String): LanguageEntry? = byWord[word.lowercase(Locale.ROOT)]

    /** Full name, short form, native name or ISO 639-1 code → language (subtitle-file suffixes). */
    fun languageOfCode(word: String): LanguageEntry? {
        val w = word.lowercase(Locale.ROOT)
        return byIso1[w] ?: byWord[w]
    }

    val FULL_NAMES: List<String> = LANGUAGES.flatMap { it.names }
    val SHORT_NAMES: List<String> = LANGUAGES.flatMap { it.short }
    val NATIVE_NAMES: List<String> = LANGUAGES.flatMap { it.native }

    /** Streaming services: unambiguous names (anywhere after the title). */
    val SERVICES_LONG: List<Pair<String, Regex>> = listOf(
        "Netflix" to "netflix",
        "Amazon" to "amazon|prime\\s?video|amzn",
        "Disney+ Hotstar" to "hotstar|disney\\s?\\+?\\s?hotstar",
        "Disney+" to "dsnp|disney\\s?plus",
        "HBO Max" to "hmax|hbo\\s?max",
        "Apple TV+" to "atvp|apple\\s?tv",
        "Hulu" to "hulu",
        "ZEE5" to "zee\\s?5",
        "SonyLIV" to "sony\\s?liv",
        "JioCinema" to "jio\\s?cinema|jiohotstar",
        "SunNXT" to "sun\\s?nxt",
        "Aha" to "aha\\s?video",
        "MX Player" to "mx\\s?player",
        "Voot" to "voot",
        "ALTBalaji" to "alt\\s?balaji",
        "Hoichoi" to "hoichoi",
        "Crunchyroll" to "crunchyroll|cr\\s?web",
        "YouTube" to "youtube|yt\\s?web",
    ).map { (name, pattern) -> name to Regex("\\b($pattern)\\b", RegexOption.IGNORE_CASE) }

    /** Two-letter service codes, only trusted right before WEB-DL/WEBRip ("NF WEB-DL"). */
    val SERVICES_SHORT: Map<String, String> = mapOf(
        "nf" to "Netflix", "hs" to "Disney+ Hotstar", "jc" to "JioCinema", "it" to "iTunes", "pcok" to "Peacock",
        "pmtp" to "Paramount+", "stan" to "Stan", "zee5" to "ZEE5", "sonyliv" to "SonyLIV",
    )

    /** Words a chat title adds that are not part of a show title. */
    val CHAT_TITLE_NOISE: Regex = Regex(
        "\\b(official|channel|movies?|films?|series|episodes?|hd|full|all|new|latest|collection|group|tv|cartoons?|anime|dubbed|dub|in|kids|world|hub|zone)\\b",
        RegexOption.IGNORE_CASE,
    )
}
