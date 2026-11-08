package dev.yourstream.catalog.api

/** Display data for catalog languages. Not provider-specific: providers map their catalogs onto these codes. */
data class CatalogLanguage(val code: String, val name: String, val nativeName: String?, val isIndian: Boolean)

object CatalogLanguages {
    val ALL: List<CatalogLanguage> = listOf(
        CatalogLanguage("hi", "Hindi", "हिन्दी", true),
        CatalogLanguage("ta", "Tamil", "தமிழ்", true),
        CatalogLanguage("te", "Telugu", "తెలుగు", true),
        CatalogLanguage("ml", "Malayalam", "മലയാളം", true),
        CatalogLanguage("kn", "Kannada", "ಕನ್ನಡ", true),
        CatalogLanguage("bn", "Bengali", "বাংলা", true),
        CatalogLanguage("mr", "Marathi", "मराठी", true),
        CatalogLanguage("gu", "Gujarati", "ગુજરાતી", true),
        CatalogLanguage("pa", "Punjabi", "ਪੰਜਾਬੀ", true),
        CatalogLanguage("or", "Odia", "ଓଡ଼ିଆ", true),
        CatalogLanguage("as", "Assamese", "অসমীয়া", true),
        CatalogLanguage("ur", "Urdu", "اردو", true),
        CatalogLanguage("bho", "Bhojpuri", "भोजपुरी", true),
        CatalogLanguage("en", "English", null, false),
    )
    private val byCode = ALL.associateBy { it.code }

    fun of(code: String): CatalogLanguage = byCode[code] ?: CatalogLanguage(code, code, null, false)
    val INDIAN_CODES: Set<String> = ALL.filter { it.isIndian }.map { it.code }.toSet()
}
