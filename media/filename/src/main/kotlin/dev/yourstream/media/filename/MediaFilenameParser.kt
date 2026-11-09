package dev.yourstream.media.filename

import dev.yourstream.media.filename.internal.RandomNames

import dev.yourstream.media.filename.internal.Analysis
import dev.yourstream.media.filename.internal.Analyzer
import dev.yourstream.media.filename.internal.TitleNormalizer
import dev.yourstream.media.filename.internal.Vocabulary

/**
 * Turns a raw file name (plus optional caption / chat title) into [FilenameMetadata].
 *
 * Pipeline:
 *  1. Preprocess: split extension and split-part suffix, remove links/handles/sites/sizes,
 *     take bracketed release groups, turn separators into spaces.
 *  2. Detect: ordered rules (episodes, resolution, source, codecs, audio, subtitles,
 *     languages, editions, flags, parts, year candidates, group) claim the characters they
 *     match, so no text is read twice.
 *  3. Resolve: choose the year among candidates, find where the title ends, decide whether a
 *     trailing number is an episode, keep or drop language words inside the title.
 *  4. Normalise the title (display + search key) and score every field.
 *  5. Fall back to the caption, then the chat title, when the file name is generic.
 *
 * Never throws: on any internal error it returns a low-confidence result with a warning.
 * Thread-safe and stateless.
 */
public object MediaFilenameParser {

    /** Bump when results change, so stored metadata can be recomputed. */
    /** 3: random file names (hashes, UUIDs, Telegram ids) count as generic; their tokens never become titles. */
    /** 4: language tags ("Tel+Hin") and channel brands ("TR Movies") are not titles; the post above the file is. */
    public const val VERSION: Int = 4

    private val GENERIC_NAME = Regex(
        "^(video|vid|file|movie|document|doc|telegram|tg|img|mov|clip|media|untitled|new|downloaded|download|v|output|record(ing)?|screen\\s?recording)?" +
            "[\\s_-]*(\\d[\\d\\s_-]*)?(wa\\d+)?$",
        RegexOption.IGNORE_CASE,
    )

    public fun parse(fileName: String, hints: ParseHints = ParseHints()): FilenameMetadata = try {
        parseInternal(fileName, hints)
    } catch (t: Throwable) {
        failed(fileName, t)
    }

    /**
     * A name made by a machine ("7744f81a5b3063b9.mp4", "AgADBQADq7Ex.mkv", "VID_20230505.mp4"): it
     * says nothing about the video, so it is never shown as a title.
     */
    public fun isMachineName(name: String): Boolean {
        val base = name.trim().let { if (Regex("\\.[A-Za-z0-9]{2,4}$").containsMatchIn(it)) it.substringBeforeLast('.') else it }
        val spaced = base.replace(Regex("[._\\-]+"), " ").trim()
        if (spaced.isEmpty()) return true
        if (GENERIC_NAME.matches(spaced)) return true
        return RandomNames.isJunkTitle(spaced) || spaced.split(' ').all { RandomNames.isRandomToken(it) }
    }

    /** Parses free text that is not a file name (a caption line, a search query). */
    public fun parseText(text: String): FilenameMetadata = try {
        build(text, Analyzer.analyze(text, isFileName = false, FieldSource.CAPTION), emptyList())
    } catch (t: Throwable) {
        failed(text, t)
    }

    private fun parseInternal(fileName: String, hints: ParseHints): FilenameMetadata {
        val fromName = stripRandomTokens(Analyzer.analyze(fileName, isFileName = true, FieldSource.FILE_NAME))
        val warnings = mutableListOf<String>()
        var result = fromName

        if (isGeneric(fromName)) {
            val line = captionLine(hints.caption)
            if (line != null) {
                val fromCaption = Analyzer.analyze(line, isFileName = false, FieldSource.CAPTION)
                if (fromCaption.title != null && !isGeneric(fromCaption)) {
                    warnings += "file name is generic; title taken from the caption"
                    result = merge(primary = fromCaption, fallback = fromName)
                } else if (fromCaption.title != null) {
                    // "Tel+Hin 450MB": no title, but its languages and size still describe the file.
                    result = merge(primary = fromName, fallback = fromCaption.copy(title = null))
                }
            }
        }
        if (result.title == null || isGeneric(result)) {
            val line = captionLine(hints.context)
            val fromContext = line?.let { Analyzer.analyze(it, isFileName = false, FieldSource.CAPTION) }
            if (fromContext?.title != null && !isGeneric(fromContext)) {
                warnings += CONTEXT_WARNING
                val own = result.copy(title = null)
                result = merge(primary = fromContext, fallback = own).let { m ->
                    // The post names the title (and maybe the season); the file says which episode.
                    m.copy(title = m.title?.let { it.copy(confidence = it.confidence * 0.85f) }, episode = own.episode ?: m.episode,
                        episodeEnd = own.episodeEnd ?: m.episodeEnd, season = own.season ?: m.season, part = own.part ?: m.part)
                }
            }
        }
        if (result.title == null || isGeneric(result)) {
            chatTitle(hints.chatTitle)?.let { t ->
                warnings += CHAT_TITLE_WARNING
                result = result.copy(title = Field(t, 0.35f, hints.chatTitle.orEmpty(), FieldSource.CHAT_TITLE))
            }
        }
        // A language list or channel name with nothing better found is no title ("Tel", "TR Movies").
        result.title?.let { t ->
            val words = t.value.split(Regex("[^\\p{L}\\p{N}]+")).count { it.isNotEmpty() }
            val hasLanguage = t.value.split(Regex("[^\\p{L}\\p{N}]+")).any { Vocabulary.languageOf(it) != null }
            if (isTagsOnly(t.value, t.source == FieldSource.CAPTION, result.languages.size) && (hasLanguage || words >= 2)) result = result.copy(title = null)
        }
        if (result.title == null) warnings += NO_TITLE_WARNING
        else if (isGeneric(result)) warnings += GENERIC_TITLE_WARNING
        return build(fileName, result, warnings)
    }

    private fun isGeneric(a: Analysis): Boolean {
        val title = a.title?.value ?: return true
        if (GENERIC_NAME.matches(title)) return true
        if (title.count(Char::isLetter) < 2) return true
        if (RandomNames.isJunkTitle(title)) return true
        if (isTagsOnly(title, a.title.source == FieldSource.CAPTION, a.languages.size)) return true
        return false
    }

    /** Warnings that say the title is a guess, or missing: the file may take its title from elsewhere. */
    public const val CONTEXT_WARNING: String = "title taken from the post above the file"
    public const val CHAT_TITLE_WARNING: String = "title guessed from the chat title"
    public const val NO_TITLE_WARNING: String = "no title found"
    /** The title is only the file's machine-made or generic name ("VID 20240101"); shown as untitled. */
    public const val GENERIC_TITLE_WARNING: String = "title is only the file's generic name"

    /**
     * Language tags and channel words, not a title: "Tel Hin", "Tamil Dubbed", "TR Movies",
     * "Dual Audio". At least one word must be a language or channel word, so short real titles
     * ("Us", "Ran", "Leo") stay titles. Short language codes are ambiguous ("Ben 10", "Ben"), so they
     * count only next to another language, or as a caption's or a language list's only word
     * ("Tel" under a file, "Tel" of "Tel+Hin") (pure; tested).
     */
    public fun isTagsOnly(title: String, fromCaption: Boolean = false, otherLanguages: Int = 0): Boolean {
        val words = title.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        val languageWords = words.count { Vocabulary.languageOf(it) != null }
        val shortTrusted = languageWords >= 2 || (words.size == 1 && languageWords == 1 && (fromCaption || otherLanguages > 0))
        fun tag(w: String): Boolean {
            val language = Vocabulary.languageOf(w) != null
            return (language && (w.length > 3 || shortTrusted)) || w in Vocabulary.CHANNEL_WORDS
        }
        return words.any(::tag) && words.all { w -> tag(w) || (w.length <= 2 && w.all(Char::isLetter)) || w.all(Char::isDigit) }
    }

    /** "Vikram a7F3k9Qz2LmP8xY1" → "Vikram": random tokens are dropped when real words remain. */
    private fun stripRandomTokens(a: Analysis): Analysis {
        val t = a.title ?: return a
        val words = t.value.split(' ')
        if (words.none(RandomNames::isRandomToken)) return a
        val kept = words.filterNot(RandomNames::isRandomToken)
        if (kept.joinToString("").count(Char::isLetter) < 2) return a // all junk: isGeneric handles it
        return a.copy(title = t.copy(value = kept.joinToString(" ")))
    }

    private fun captionLine(caption: String?): String? = caption
        ?.lineSequence()
        ?.map { it.trim() }
        ?.firstOrNull { line -> line.count(Char::isLetter) >= 2 && !line.startsWith("#") && !line.startsWith("http") && !line.startsWith("@") }

    private fun chatTitle(chatTitle: String?): String? {
        if (chatTitle.isNullOrBlank()) return null
        // "Speedy Links", "TR Movies Tamil", "Dada Linkz": a channel of many titles, not a title.
        val words = chatTitle.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
        if (words.any { it in Vocabulary.CHANNEL_BRAND_WORDS }) return null
        var t = Analyzer.analyze(chatTitle, isFileName = false, FieldSource.CHAT_TITLE).title?.value ?: return null
        t = Vocabulary.CHAT_TITLE_NOISE.replace(t, " ")
        t = t.split(Regex("\\s+")).filter { it.isNotBlank() && Vocabulary.languageOf(it) == null }.joinToString(" ")
        return TitleNormalizer.display(t)?.takeIf { it.count(Char::isLetter) >= 2 }
    }

    /** Identity (title, year, episodes) from [primary]; technical fields from whichever has them. */
    private fun merge(primary: Analysis, fallback: Analysis): Analysis = primary.copy(
        prepared = fallback.prepared,
        title = primary.title?.let { it.copy(confidence = it.confidence * 0.9f) },
        year = primary.year ?: fallback.year,
        season = primary.season ?: fallback.season,
        seasonEnd = primary.seasonEnd ?: fallback.seasonEnd,
        episode = primary.episode ?: fallback.episode,
        episodeEnd = primary.episodeEnd ?: fallback.episodeEnd,
        absoluteEpisode = primary.absoluteEpisode ?: fallback.absoluteEpisode,
        airDate = primary.airDate ?: fallback.airDate,
        resolution = fallback.resolution ?: primary.resolution,
        quality = fallback.quality ?: primary.quality,
        qualityTags = (fallback.qualityTags + primary.qualityTags).distinct(),
        service = fallback.service ?: primary.service,
        videoCodec = fallback.videoCodec ?: primary.videoCodec,
        bitDepth = fallback.bitDepth ?: primary.bitDepth,
        hdr = fallback.hdr ?: primary.hdr,
        audio = fallback.audio ?: primary.audio,
        languages = (fallback.languages + primary.languages).distinctBy { it.value.code },
        dubbed = fallback.dubbed || primary.dubbed,
        subtitles = fallback.subtitles ?: primary.subtitles,
        edition = primary.edition ?: fallback.edition,
        group = fallback.group ?: primary.group,
        part = fallback.part ?: primary.part,
        flags = fallback.flags + primary.flags,
        warnings = fallback.warnings + primary.warnings,
    )

    private fun build(original: String, a: Analysis, extraWarnings: List<String>): FilenameMetadata {
        val kind = kindOf(a)
        val identity = listOfNotNull(a.year, a.season, a.episode, a.absoluteEpisode, a.airDate).maxOfOrNull { it.confidence } ?: 0f
        val technical = listOfNotNull(a.resolution?.confidence, a.quality?.confidence, a.videoCodec?.confidence, a.audio?.confidence).maxOrNull() ?: 0f
        val overall = 0.4f * (a.title?.confidence ?: 0f) + 0.3f * identity + 0.2f * technical +
            0.1f * (if (a.prepared.extension != null) 1f else 0.5f)
        return FilenameMetadata(
            original = original,
            cleanedName = a.prepared.text,
            extension = a.prepared.extension,
            title = a.title,
            normalizedTitle = a.title?.value?.let(TitleNormalizer::key)?.ifBlank { null },
            alternativeTitle = a.alternativeTitle,
            year = a.year,
            season = a.season,
            seasonEnd = a.seasonEnd,
            episode = a.episode,
            episodeEnd = a.episodeEnd,
            absoluteEpisode = a.absoluteEpisode,
            airDate = a.airDate,
            resolution = a.resolution,
            quality = a.quality,
            qualityTags = a.qualityTags,
            streamingService = a.service,
            videoCodec = a.videoCodec,
            bitDepth = a.bitDepth,
            hdr = a.hdr,
            audio = a.audio,
            languages = a.languages,
            isDubbed = a.dubbed,
            subtitles = a.subtitles,
            edition = a.edition,
            releaseGroup = a.group,
            partNumber = a.part,
            flags = a.flags,
            kind = kind,
            confidence = overall.coerceIn(0f, 1f),
            warnings = a.warnings + extraWarnings,
            parserVersion = VERSION,
        )
    }

    private fun kindOf(a: Analysis): Field<MediaKindHint> {
        val episodic = listOfNotNull(a.episode, a.absoluteEpisode, a.airDate).maxByOrNull { it.confidence }
        return when {
            episodic != null -> Field(MediaKindHint.EPISODE, episodic.confidence, episodic.raw)
            a.season != null -> Field(MediaKindHint.SEASON_PACK, 0.8f, a.season.raw)
            ReleaseFlag.COMPLETE in a.flags -> Field(MediaKindHint.SEASON_PACK, 0.5f, "complete")
            a.year != null -> Field(MediaKindHint.MOVIE, if (a.hasTechnical) 0.8f else 0.7f, a.year.raw)
            a.hasTechnical && a.title != null -> Field(MediaKindHint.MOVIE, 0.45f, "")
            else -> Field(MediaKindHint.UNKNOWN, 0.3f, "")
        }
    }

    private fun failed(input: String, t: Throwable) = FilenameMetadata(
        original = input, cleanedName = input, extension = null, title = null, normalizedTitle = null, alternativeTitle = null,
        year = null, season = null, seasonEnd = null, episode = null, episodeEnd = null, absoluteEpisode = null, airDate = null,
        resolution = null, quality = null, qualityTags = emptyList(), streamingService = null, videoCodec = null, bitDepth = null,
        hdr = null, audio = null, languages = emptyList(), isDubbed = false, subtitles = null, edition = null, releaseGroup = null,
        partNumber = null, flags = emptySet(), kind = Field(MediaKindHint.UNKNOWN, 0f, ""), confidence = 0f,
        warnings = listOf("parser error: ${t::class.simpleName}: ${t.message}"), parserVersion = VERSION,
    )
}
