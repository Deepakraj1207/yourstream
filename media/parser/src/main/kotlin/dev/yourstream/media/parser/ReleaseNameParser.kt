package dev.yourstream.media.parser

import dev.yourstream.media.api.model.ParsedMedia

/**
 * Understands release-style file names as they appear in Telegram channels, e.g.
 *
 *  - `Vikram.2022.1080p.WEB-DL.Tamil.DDP5.1.x264.ESub.mkv`
 *  - `www.1TamilMV.xyz - Leo (2023) Tamil HQ HDRip - 720p - x264 - (DD+5.1 - 192Kbps & AAC) - 1.4GB - ESub.mkv`
 *  - `[SubsPlease] Frieren - 12 (1080p) [ABCD1234].mkv`
 *  - `The.Office.US.S02E05.720p.BluRay.x265-GROUP.mkv`
 *
 * The title is everything before the first technical marker (year, episode, resolution…).
 * Pure and deterministic; bump [VERSION] whenever results change so stored rows can be re-parsed.
 */
object ReleaseNameParser {

    const val VERSION: Int = 1

    private val IGNORE = RegexOption.IGNORE_CASE

    // ---- noise: channel handles, links, site prefixes ----
    private val NOISE = listOf(
        Regex("https?://\\S+", IGNORE),
        Regex("t\\.me/\\S+", IGNORE),
        Regex("@\\w{3,}"),
        Regex("\\bwww\\.[\\w-]+(\\.[\\w-]+)+", IGNORE),
        Regex("\\b[\\w-]+\\.(com|net|org|xyz|in|me|to|cc|ws|pw|life|biz|lol|world|bar|wtf|win|art|club|site|online|live|tv|vip|fun|link|pics|bid|mx|co)\\b", IGNORE),
    )
    private val LEADING_GROUP = Regex("^\\s*\\[([^\\]]{2,40})]\\s*")
    private val TRAILING_HASH = Regex("\\[[0-9A-Fa-f]{8}]")
    private val SEPARATORS = Regex("[._\\[\\](){}|~]+")
    private val SPACES = Regex("\\s+")
    private val EMOJI_ETC = Regex("[^\\p{L}\\p{N}\\p{M}\\s.,'&!?:+\\-()\\[\\]_]")

    // ---- markers (applied to the cleaned, space-separated name) ----
    private val SXXEYY = Regex("\\bS(\\d{1,2})\\s?E(\\d{1,4})(?:\\s?-?\\s?E?(\\d{1,4}))?\\b", IGNORE)
    private val NXNN = Regex("\\b(\\d{1,2})x(\\d{2,3})\\b", IGNORE)
    private val SEASON_EPISODE_WORDS = Regex("\\bSeason\\s?(\\d{1,2})\\s?(?:Episode|Ep|E)\\s?(\\d{1,4})\\b", IGNORE)
    private val SEASON_ONLY = Regex("\\b(?:S|Season\\s?)(\\d{1,2})\\b(?!\\s?E\\d)", IGNORE)
    private val EPISODE_ONLY = Regex("\\b(?:EP|Episode|E)\\s?(\\d{1,4})\\b", IGNORE)
    private val ANIME_EPISODE = Regex("\\s-\\s(\\d{1,4})(?:v\\d)?(?=\\s|$)")
    private val YEAR = Regex("\\b(19[2-9]\\d|20[0-4]\\d)\\b")
    private val RESOLUTION = Regex("\\b(4320|2160|1440|1080|720|576|540|480|360)[pi]\\b", IGNORE)
    private val RESOLUTION_WORD = Regex("\\b(4K|UHD|FHD)\\b", IGNORE)

    private val SOURCES = listOf(
        "WEB-DL" to Regex("\\bWEB[\\s-]?DL\\b", IGNORE),
        "WEBRip" to Regex("\\bWEB[\\s-]?Rip\\b", IGNORE),
        "BluRay" to Regex("\\bBlu[\\s-]?Ray\\b|\\bBD[\\s-]?Rip\\b|\\bBR[\\s-]?Rip\\b", IGNORE),
        "HDRip" to Regex("\\bHD[\\s-]?Rip\\b", IGNORE),
        "DVDRip" to Regex("\\bDVD[\\s-]?Rip\\b", IGNORE),
        "PreDVD" to Regex("\\bPre[\\s-]?DVD(?:Rip)?\\b", IGNORE),
        "DVDScr" to Regex("\\bDVD[\\s-]?Scr\\b", IGNORE),
        "HDTV" to Regex("\\bHDTV\\b", IGNORE),
        "HDCAM" to Regex("\\bHD[\\s-]?CAM\\b", IGNORE),
        "CAM" to Regex("\\bCAM[\\s-]?Rip\\b|\\bCAM\\b", IGNORE),
        "HDTS" to Regex("\\bHD[\\s-]?TS\\b|\\bTELESYNC\\b", IGNORE),
        "DVD" to Regex("\\bDVD\\b", IGNORE),
        "WEB" to Regex("\\bWEB\\b", IGNORE),
    )
    private val VIDEO_CODECS = listOf(
        "H.265" to Regex("\\b(x265|h[\\s.]?265|HEVC)\\b", IGNORE),
        "H.264" to Regex("\\b(x264|h[\\s.]?264|AVC)\\b", IGNORE),
        "AV1" to Regex("\\bAV1\\b", IGNORE),
        "VP9" to Regex("\\bVP9\\b", IGNORE),
        "XviD" to Regex("\\bXviD\\b", IGNORE),
        "DivX" to Regex("\\bDivX\\b", IGNORE),
    )
    private val AUDIO_CODECS = listOf(
        "DD+" to Regex("\\b(DDP|DD\\+|E[\\s-]?AC[\\s-]?3)(?:\\s?[257]\\s?[01])?", IGNORE),
        "DD" to Regex("\\bDD(?!P|\\+)(?:\\s?[257]\\s?[01])?\\b|\\bAC[\\s-]?3\\b", IGNORE),
        "TrueHD" to Regex("\\bTrueHD\\b", IGNORE),
        "Atmos" to Regex("\\bAtmos\\b", IGNORE),
        "DTS" to Regex("\\bDTS(?:[\\s-]?HD)?(?:[\\s-]?MA)?\\b", IGNORE),
        "AAC" to Regex("\\bAAC(?:\\s?[257]\\s?[01])?\\b", IGNORE),
        "FLAC" to Regex("\\bFLAC\\b", IGNORE),
        "Opus" to Regex("\\bOpus\\b", IGNORE),
        "MP3" to Regex("\\bMP3\\b", IGNORE),
    )
    private val HDR = listOf(
        "DV" to Regex("\\b(DV|DoVi|Dolby\\s?Vision)\\b", IGNORE),
        "HDR10+" to Regex("\\bHDR10(\\+|Plus)", IGNORE),
        "HDR10" to Regex("\\bHDR10\\b", IGNORE),
        "HDR" to Regex("\\bHDR\\b", IGNORE),
    )
    private val TEN_BIT = Regex("\\b10[\\s-]?bit\\b", IGNORE)
    private val EDITIONS = listOf(
        "Extended" to Regex("\\bExtended(\\s?(Cut|Edition))?\\b", IGNORE),
        "Director's Cut" to Regex("\\bDirector'?s?\\s?Cut\\b", IGNORE),
        "Uncut" to Regex("\\bUncut\\b", IGNORE),
        "Unrated" to Regex("\\bUnrated\\b", IGNORE),
        "Remastered" to Regex("\\bRemaster(ed)?\\b", IGNORE),
        "IMAX" to Regex("\\bIMAX\\b", IGNORE),
    )
    private val QUALITY_WORDS = Regex("\\b(HQ|Proper|Repack|Original|Org|Clean|Line|Hybrid|Multi\\s?Audio|Dual\\s?Audio|Audio|Untouched|Rip|Complete|Esubs?|Msubs?|Subs?|\\d+(\\.\\d+)?\\s?(GB|MB|Kbps))\\b", IGNORE)

    /** Full names and their usual abbreviations. Abbreviations count only inside "Tam+Tel" style groups. */
    private val LANGUAGES = mapOf(
        "tamil" to listOf("tamil", "tam"), "telugu" to listOf("telugu", "tel"), "hindi" to listOf("hindi", "hin"),
        "malayalam" to listOf("malayalam", "mal"), "kannada" to listOf("kannada", "kan"), "english" to listOf("english", "eng"),
        "bengali" to listOf("bengali", "bangla", "ben"), "marathi" to listOf("marathi", "mar"), "punjabi" to listOf("punjabi", "pun"),
        "gujarati" to listOf("gujarati", "guj"), "urdu" to listOf("urdu"), "odia" to listOf("odia", "oriya"),
        "korean" to listOf("korean", "kor"), "japanese" to listOf("japanese", "jap", "jpn"), "chinese" to listOf("chinese", "chi"),
        "spanish" to listOf("spanish", "spa"), "french" to listOf("french", "fre"), "german" to listOf("german", "ger"),
        "italian" to listOf("italian", "ita"), "russian" to listOf("russian", "rus"), "arabic" to listOf("arabic", "ara"),
        "thai" to listOf("thai"), "turkish" to listOf("turkish", "tur"), "indonesian" to listOf("indonesian", "indo"),
    )
    private val LANGUAGE_GROUP = Regex("\\b([A-Za-z]{3,10})(\\s?[+&/,]\\s?[A-Za-z]{3,10})+\\b")
    private val MULTI_AUDIO = Regex("\\b(Dual|Multi)\\s?Audio\\b", IGNORE)
    private val ENGLISH_SUBS = Regex("\\b(E[\\s-]?Subs?|Eng[\\s-]?Subs?|English\\s?Subs?)\\b", IGNORE)
    private val MULTI_SUBS = Regex("\\bM[\\s-]?Subs?\\b", IGNORE)
    private val PART_WORD = Regex("\\b(?:CD|Disc|Disk)\\s?(\\d{1,2})\\b", IGNORE)
    private val PART_AT_END = Regex("\\bPart\\s?(\\d{1,2})\\s*$", IGNORE)
    private val RELEASE_GROUP = Regex("-\\s?([A-Za-z0-9]{2,20})\\s*$")
    private val GENERIC_NAME = Regex("^(video|vid|file|movie|document|doc|telegram|img|mov|clip)?[\\s_-]*[\\d\\s_:-]*$", IGNORE)

    fun parse(fileName: String, caption: String? = null): ParsedMedia {
        val fromName = parseText(fileName, isFileName = true)
        val captionLine = caption?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
        if (captionLine == null) return fromName

        // Channels often upload "video_2024.mp4" with the real name in the caption.
        val nameTitle = fromName.title
        val nameIsGeneric = nameTitle == null || GENERIC_NAME.matches(nameTitle)
        if (!nameIsGeneric) return fromName
        val fromCaption = parseText(captionLine, isFileName = false)
        return merge(primary = fromCaption, secondary = fromName)
    }

    private fun parseText(input: String, isFileName: Boolean): ParsedMedia {
        var raw = input.trim()
        var partNumber: Int? = null
        var extensionKnown = false
        if (isFileName) {
            val parts = MediaClassifier.splitName(raw)
            raw = parts.baseName
            partNumber = parts.splitPart
            extensionKnown = parts.extension != null
        }

        var releaseGroup: String? = null
        LEADING_GROUP.find(raw)?.let { match ->
            val inside = match.groupValues[1]
            if (NOISE.none { it.containsMatchIn(inside) } && !YEAR.matches(inside) && !RESOLUTION.containsMatchIn(inside)) {
                releaseGroup = inside.trim()
            }
            raw = raw.removeRange(match.range)
        }
        raw = TRAILING_HASH.replace(raw, " ")
        NOISE.forEach { raw = it.replace(raw, " ") }
        raw = EMOJI_ETC.replace(raw, " ")

        val text = SPACES.replace(SEPARATORS.replace(raw, " "), " ").trim()
        val markers = mutableListOf<Int>() // start offsets of technical markers

        // --- episodes ---
        var season: Int? = null
        var episode: Int? = null
        var episodeEnd: Int? = null
        (SXXEYY.find(text))?.let { m ->
            season = m.groupValues[1].toInt(); episode = m.groupValues[2].toInt()
            episodeEnd = m.groupValues[3].toIntOrNull()?.takeIf { it > episode!! }
            markers += m.range.first
        } ?: SEASON_EPISODE_WORDS.find(text)?.let { m ->
            season = m.groupValues[1].toInt(); episode = m.groupValues[2].toInt(); markers += m.range.first
        } ?: NXNN.find(text)?.let { m ->
            season = m.groupValues[1].toInt(); episode = m.groupValues[2].toInt(); markers += m.range.first
        }
        if (episode == null) {
            SEASON_ONLY.find(text)?.let { m -> season = m.groupValues[1].toInt(); markers += m.range.first }
            EPISODE_ONLY.find(text)?.let { m -> episode = m.groupValues[1].toInt(); markers += m.range.first }
        }

        // --- year: the last one wins, because titles can contain years too
        // ("1917 (2019)", "Blade Runner 2049 (2017)") ---
        val years = YEAR.findAll(text).toList()
        var year: Int? = null
        if (years.isNotEmpty()) {
            val chosen = years.last()
            if (chosen.range.first > 0 || years.size == 1 && text.length > 4) {
                year = chosen.value.toInt()
                if (chosen.range.first > 0) markers += chosen.range.first
            }
        }

        // --- technical tokens ---
        var resolution: Int? = null
        RESOLUTION.find(text)?.let { resolution = it.groupValues[1].toInt(); markers += it.range.first }
        if (resolution == null) RESOLUTION_WORD.find(text)?.let {
            resolution = if (it.value.equals("FHD", true)) 1080 else 2160; markers += it.range.first
        }
        val source = firstOf(SOURCES, text, markers)
        val videoCodec = firstOf(VIDEO_CODECS, text, markers)
        val audioCodecs = allOf(AUDIO_CODECS, text, markers)
        val hdr = firstOf(HDR, text, markers)
        TEN_BIT.find(text)?.let { markers += it.range.first }
        val edition = firstOf(EDITIONS, text, markers)

        val languages = linkedSetOf<String>()
        val lower = text.lowercase()
        for ((name, words) in LANGUAGES) {
            // A language word at the very start is part of the title ("English Vinglish").
            val m = Regex("\\b${words[0]}\\b").findAll(lower).firstOrNull { it.range.first > 0 } ?: continue
            languages += name
            markers += m.range.first
        }
        LANGUAGE_GROUP.findAll(text).forEach { group ->
            val found = group.value.split('+', '&', '/', ',').map { it.trim().lowercase() }
                .mapNotNull { token -> LANGUAGES.entries.firstOrNull { token in it.value }?.key }
            if (found.size >= 2) { languages += found; markers += group.range.first }
        }
        MULTI_AUDIO.find(text)?.let { languages += "multi"; markers += it.range.first }

        val subtitles = linkedSetOf<String>()
        ENGLISH_SUBS.find(text)?.let { subtitles += "english"; markers += it.range.first }
        MULTI_SUBS.find(text)?.let { subtitles += "multi"; markers += it.range.first }
        QUALITY_WORDS.find(text)?.let { markers += it.range.first }

        if (partNumber == null) {
            PART_WORD.find(text)?.let { partNumber = it.groupValues[1].toInt(); markers += it.range.first }
        }
        if (partNumber == null && isFileName) PART_AT_END.find(text)?.let { partNumber = it.groupValues[1].toInt() }

        // Anime style "Title - 12 (1080p)": only when nothing technical comes before it, so a
        // "- 1.4GB -" size fragment late in the name is not taken for an episode.
        if (episode == null) {
            ANIME_EPISODE.find(text)?.let { m ->
                if (!YEAR.matches(m.groupValues[1]) && m.range.first < (markers.minOrNull() ?: Int.MAX_VALUE)) {
                    episode = m.groupValues[1].toInt()
                    markers += m.range.first
                }
            }
        }

        val hasMarkers = markers.isNotEmpty()
        if (releaseGroup == null && hasMarkers) {
            RELEASE_GROUP.find(text)?.let { m ->
                val candidate = m.groupValues[1]
                val looksTechnical = (SOURCES + VIDEO_CODECS + AUDIO_CODECS).any { it.second.matches(candidate) } ||
                    RESOLUTION.matches(candidate) || candidate.all(Char::isDigit) || LANGUAGES.values.flatten().contains(candidate.lowercase())
                if (!looksTechnical) releaseGroup = candidate
            }
        }

        val titleEnd = markers.minOrNull() ?: text.length
        val title = cleanTitle(text.substring(0, titleEnd))

        val score = (if (title != null) 0.4f else 0f) +
            (if (year != null || episode != null || season != null) 0.3f else 0f) +
            (if (resolution != null || source != null || videoCodec != null) 0.2f else 0f) +
            (if (extensionKnown || !isFileName) 0.1f else 0f)

        return ParsedMedia(
            title = title,
            year = year,
            season = season,
            episode = episode,
            episodeEnd = episodeEnd,
            resolution = resolution,
            source = source,
            videoCodec = videoCodec,
            audioCodecs = audioCodecs,
            hdr = hdr,
            languages = languages.toList(),
            subtitleLanguages = subtitles.toList(),
            edition = edition,
            releaseGroup = releaseGroup,
            partNumber = partNumber,
            confidence = score,
            parserVersion = VERSION,
        )
    }

    private fun cleanTitle(raw: String): String? {
        val trimmed = raw.trim().trim('-', '+', '&', ',', ':', ' ', '\'').let { SPACES.replace(it, " ") }
        if (trimmed.isEmpty() || trimmed.none(Char::isLetterOrDigit)) return null
        return trimmed
    }

    private fun firstOf(table: List<Pair<String, Regex>>, text: String, markers: MutableList<Int>): String? {
        var best: Pair<String, Int>? = null
        for ((name, regex) in table) {
            val m = regex.find(text) ?: continue
            if (best == null || m.range.first < best.second) best = name to m.range.first
        }
        best?.let { markers += it.second }
        return best?.first
    }

    private fun allOf(table: List<Pair<String, Regex>>, text: String, markers: MutableList<Int>): List<String> =
        table.mapNotNull { (name, regex) -> regex.find(text)?.let { markers += it.range.first; it.range.first to name } }
            .sortedBy { it.first }.map { it.second }.distinct()

    private fun merge(primary: ParsedMedia, secondary: ParsedMedia): ParsedMedia = primary.copy(
        title = primary.title ?: secondary.title,
        year = primary.year ?: secondary.year,
        season = primary.season ?: secondary.season,
        episode = primary.episode ?: secondary.episode,
        episodeEnd = primary.episodeEnd ?: secondary.episodeEnd,
        resolution = primary.resolution ?: secondary.resolution,
        source = primary.source ?: secondary.source,
        videoCodec = primary.videoCodec ?: secondary.videoCodec,
        audioCodecs = (primary.audioCodecs + secondary.audioCodecs).distinct(),
        hdr = primary.hdr ?: secondary.hdr,
        languages = (primary.languages + secondary.languages).distinct(),
        subtitleLanguages = (primary.subtitleLanguages + secondary.subtitleLanguages).distinct(),
        edition = primary.edition ?: secondary.edition,
        releaseGroup = primary.releaseGroup ?: secondary.releaseGroup,
        partNumber = secondary.partNumber ?: primary.partNumber,
        confidence = maxOf(primary.confidence, secondary.confidence),
    )
}
