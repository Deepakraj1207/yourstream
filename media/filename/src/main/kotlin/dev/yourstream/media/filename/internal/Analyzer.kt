package dev.yourstream.media.filename.internal

import dev.yourstream.media.filename.AudioInfo
import dev.yourstream.media.filename.Field
import dev.yourstream.media.filename.FieldSource
import dev.yourstream.media.filename.FileExtensions
import dev.yourstream.media.filename.Language
import dev.yourstream.media.filename.ReleaseFlag
import dev.yourstream.media.filename.SubtitleInfo

/** What one piece of text (file name, caption line) yielded, before merging sources. */
internal data class Analysis(
    val prepared: Prepared,
    val title: Field<String>?,
    val alternativeTitle: Field<String>?,
    val year: Field<Int>?,
    val season: Field<Int>?,
    val seasonEnd: Int?,
    val episode: Field<Int>?,
    val episodeEnd: Int?,
    val absoluteEpisode: Field<Int>?,
    val airDate: Field<String>?,
    val resolution: Field<Int>?,
    val quality: Field<String>?,
    val qualityTags: List<String>,
    val service: Field<String>?,
    val videoCodec: Field<String>?,
    val bitDepth: Int?,
    val hdr: Field<String>?,
    val audio: AudioInfo?,
    val languages: List<Field<Language>>,
    val dubbed: Boolean,
    val subtitles: SubtitleInfo?,
    val edition: Field<String>?,
    val group: Field<String>?,
    val part: Field<Int>?,
    val flags: Set<ReleaseFlag>,
    val warnings: List<String>,
) {
    val hasIdentity: Boolean get() = year != null || season != null || episode != null || absoluteEpisode != null || airDate != null
    val hasTechnical: Boolean get() = resolution != null || quality != null || videoCodec != null || audio != null
}

/**
 * Runs the detectors over one text and resolves what they found into fields: which number is
 * the year, where the title ends, whether a trailing number is an episode.
 */
internal object Analyzer {

    private val SPACE = Regex("\\s+")
    private val NUMBER_TOKEN = Regex("^(\\d{1,4})(?:v\\d)?$", RegexOption.IGNORE_CASE)
    private val SUBTITLE_FILE_FLAGS = setOf("forced", "sdh", "cc", "hi", "full", "default")
    private val MULTI_LANGUAGE = Language("mul", "Multiple")
    private val COMMON_RESOLUTIONS = setOf(360, 480, 576, 720, 1080, 1440, 2160)

    fun analyze(input: String, isFileName: Boolean, source: FieldSource): Analysis {
        val prepared = Preprocessor.prepare(input, isFileName)
        val scan = Scan(prepared.text)
        val warnings = mutableListOf<String>()
        for ((name, detector) in Detectors.ALL) {
            runCatching { detector(scan, prepared) }.onFailure { warnings += "rule '$name' failed: ${it.message}" }
        }
        fun <T> field(value: T, confidence: Float, raw: String) = Field(value, confidence.coerceIn(0f, 1f), raw, source)

        // ---- year: the last candidate that is not the first word ("1917", "2012" are titles) ----
        val candidates = scan.all(Kind.YEAR_CANDIDATE).filter { scan.isFree(it.range) }
        val eligible = candidates.filter { it.range.first > 0 }
        val chosenYear = eligible.lastOrNull()
        var year: Field<Int>? = null
        if (chosenYear != null) {
            val y = chosenYear.value as Int
            val followed = scan.findings.any { it.kind != Kind.YEAR_CANDIDATE && it.range.first > chosenYear.range.last }
            val confidence = when {
                y in prepared.parenthesizedYears -> 0.95f
                eligible.size > 1 -> 0.8f
                followed -> 0.9f
                else -> 0.75f
            }
            scan.claim(chosenYear.range)
            scan.add(Kind.NOISE, y, chosenYear.range, 1f, chosenYear.raw, claim = false) // acts as a title terminator
            year = field(y, confidence, chosenYear.raw)
        }

        // ---- languages that could be part of the title ("English Vinglish", "The English Patient") ----
        val hardEnd = scan.findings
            .filter { it.kind != Kind.YEAR_CANDIDATE && it.kind != Kind.LANGUAGE && it.range.first >= 0 }
            .minOfOrNull { it.range.first } ?: prepared.text.length
        for (lang in scan.all(Kind.LANGUAGE)) {
            if (lang.range.first >= hardEnd) continue
            val after = prepared.text.substring(lang.range.last + 1, hardEnd).trim()
            // Languages (and dub words) may follow, plus one trailing episode number ("Shinchan Tamil 125").
            val tokens = if (after.isEmpty()) emptyList() else after.split(SPACE)
            val onlyLanguagesFollow = tokens.withIndex().all { (i, t) ->
                Vocabulary.languageOf(t) != null || t.equals("dub", true) || t.equals("dubbed", true) ||
                    (i == tokens.lastIndex && NUMBER_TOKEN.matches(t))
            }
            if (lang.range.first == 0 || !onlyLanguagesFollow) scan.remove(lang)
        }

        // ---- title region ----
        val titleEnd = scan.findings
            .filter { it.kind != Kind.YEAR_CANDIDATE && it.range.first >= 0 }
            .minOfOrNull { it.range.first } ?: prepared.text.length
        var titleRaw = prepared.text.substring(0, titleEnd).trim()
        var titlePenalty = 0f
        if (titleRaw.isBlank()) {
            // Markers first ("S01E05 Doraemon Tamil"): use the first free run of words.
            titleRaw = firstFreeSegment(scan)
            titlePenalty += 0.2f
            if (titleRaw.isNotEmpty()) warnings += "title not at the start of the name"
        }
        if (prepared.extension in FileExtensions.SUBTITLE) titleRaw = stripSubtitleSuffix(titleRaw)

        // ---- absolute episode: "Shinchan 125 Tamil", "[Group] Title - 12 (1080p)" ----
        var absoluteEpisode: Field<Int>? = null
        val hasEpisodeInfo = scan.has(Kind.EPISODE) || scan.has(Kind.SEASON) || scan.has(Kind.AIR_DATE)
        if (!hasEpisodeInfo && year == null) {
            val tokens = titleRaw.split(SPACE).filter { it.isNotEmpty() }
            val last = tokens.lastOrNull()
            val number = last?.let { NUMBER_TOKEN.matchEntire(it) }?.groupValues?.get(1)?.toInt()
            val wordsBefore = tokens.dropLast(1).filter { it != "-" }
            if (number != null && number > 0 && wordsBefore.any { t -> t.any(Char::isLetter) }) {
                val animeStyle = tokens.size >= 3 && tokens[tokens.size - 2] == "-"
                val followedByMarkers = titleEnd < prepared.text.length
                val confidence = when {
                    animeStyle -> 0.8f
                    followedByMarkers && number >= 10 -> 0.65f
                    followedByMarkers -> 0.5f
                    else -> 0.4f
                }
                if (confidence < 0.6f) warnings += "trailing number $number may be part of the title"
                absoluteEpisode = field(number, confidence, last)
                titleRaw = wordsBefore.joinToString(" ")
            }
        }

        // A number right after the title's language words: "Shinchan Tamil 125".
        if (absoluteEpisode == null && !hasEpisodeInfo && year == null && titleEnd < prepared.text.length) {
            val next = scan.freeText(titleEnd, prepared.text.length).split(SPACE).firstOrNull()
            val number = next?.let { NUMBER_TOKEN.matchEntire(it) }?.groupValues?.get(1)?.toInt()
            if (number != null && number > 0 && number !in COMMON_RESOLUTIONS && scan.has(Kind.LANGUAGE)) {
                absoluteEpisode = field(number, 0.6f, next)
            }
        }

        // ---- title + alternative title ----
        val akaParts = titleRaw.split(Regex("\\s+(?:aka|a\\s?k\\s?a)\\s+", RegexOption.IGNORE_CASE), limit = 2)
        val display = TitleNormalizer.display(akaParts[0])
        val alternative = akaParts.getOrNull(1)?.let(TitleNormalizer::display)
        val title = display?.let {
            val words = it.split(' ').size
            var c = 0.55f
            if (titleEnd < prepared.text.length || year != null) c += 0.25f
            if (year != null || scan.has(Kind.SEASON) || scan.has(Kind.EPISODE)) c += 0.1f
            if (words >= 2) c += 0.05f
            if (it.all { ch -> ch.isDigit() || ch == ' ' }) c -= 0.2f
            if (it.length < 3) c -= 0.15f
            if (absoluteEpisode != null && absoluteEpisode.confidence < 0.6f) c -= 0.1f
            field(it, minOf(0.97f, c - titlePenalty), titleRaw)
        }

        // ---- the rest ----
        val seasonFinding = scan.first(Kind.SEASON)
        val episodeFinding = scan.first(Kind.EPISODE)
        val audioCodecs = scan.all(Kind.AUDIO_CODEC)
        val channels = audioCodecs.firstNotNullOfOrNull { it.extra["channels"] as? String } ?: scan.first(Kind.AUDIO_CHANNELS)?.value as? String
        val dual = scan.first(Kind.DUAL_AUDIO)
        val multi = scan.first(Kind.MULTI_AUDIO)
        val bitrate = scan.first(Kind.AUDIO_BITRATE)
        val audio = if (audioCodecs.isEmpty() && channels == null && dual == null && multi == null && bitrate == null) null else AudioInfo(
            codecs = audioCodecs.map { it.value as String }.distinct(),
            channels = channels,
            bitrateKbps = bitrate?.value as? Int,
            isDualAudio = dual != null,
            isMultiAudio = multi != null,
            confidence = (audioCodecs.map { it.confidence } + listOfNotNull(dual?.confidence, multi?.confidence, bitrate?.confidence)).maxOrNull() ?: 0.6f,
        )

        val languages = scan.all(Kind.LANGUAGE)
            .groupBy { (it.value as Language).code }
            .map { (_, list) -> list.maxBy { it.confidence }.let { field(it.value as Language, it.confidence, it.raw) } }

        val subtitles = subtitleInfo(scan, prepared)
        val flags = buildSet {
            scan.all(Kind.FLAG).forEach { add(ReleaseFlag.valueOf(it.value as String)) }
            scan.all(Kind.QUALITY_TAG).forEach { tag ->
                if (tag.value == "PROPER") add(ReleaseFlag.PROPER)
                if (tag.value == "REPACK") add(ReleaseFlag.REPACK)
            }
        }
        val part = prepared.splitPart?.let { field(it, 0.98f, ".%03d".format(it)) }
            ?: scan.first(Kind.PART)?.let { field(it.value as Int, it.confidence, it.raw) }

        return Analysis(
            prepared = prepared,
            title = title,
            alternativeTitle = alternative?.let { field(it, (title?.confidence ?: 0.5f) - 0.1f, akaParts[1]) },
            year = year,
            season = seasonFinding?.let { field(it.value as Int, it.confidence, it.raw) },
            seasonEnd = seasonFinding?.extra?.get("end") as? Int,
            episode = episodeFinding?.let { field(it.value as Int, it.confidence, it.raw) },
            episodeEnd = episodeFinding?.extra?.get("end") as? Int,
            absoluteEpisode = absoluteEpisode,
            airDate = scan.first(Kind.AIR_DATE)?.let { field(it.value as String, it.confidence, it.raw) },
            resolution = scan.first(Kind.RESOLUTION)?.let { field(it.value as Int, it.confidence, it.raw) },
            quality = scan.first(Kind.QUALITY)?.let { field(it.value as String, it.confidence, it.raw) },
            qualityTags = scan.all(Kind.QUALITY_TAG).map { it.value as String }.distinct(),
            service = scan.first(Kind.SERVICE)?.let { field(it.value as String, it.confidence, it.raw) },
            videoCodec = scan.first(Kind.VIDEO_CODEC)?.let { field(it.value as String, it.confidence, it.raw) },
            bitDepth = scan.first(Kind.BIT_DEPTH)?.value as? Int,
            hdr = scan.first(Kind.HDR)?.let { field(it.value as String, it.confidence, it.raw) },
            audio = audio,
            languages = languages,
            dubbed = scan.has(Kind.DUBBED),
            subtitles = subtitles,
            edition = scan.first(Kind.EDITION)?.let { field(it.value as String, it.confidence, it.raw) },
            group = scan.first(Kind.GROUP)?.let { field(it.value as String, it.confidence, it.raw) },
            part = part,
            flags = flags,
            warnings = warnings,
        )
    }

    private fun subtitleInfo(scan: Scan, prepared: Prepared): SubtitleInfo? {
        if (prepared.extension in FileExtensions.SUBTITLE) {
            val words = prepared.subtitleSuffixWords.map { it.lowercase() }
            val langs = words.mapNotNull { w -> if (w in SUBTITLE_FILE_FLAGS) null else Vocabulary.languageOfCode(w)?.language }.distinct()
            return SubtitleInfo(
                languages = langs,
                hardcoded = false,
                forced = "forced" in words,
                sdh = "sdh" in words || "cc" in words || "hi" in words,
                isSubtitleFile = true,
                confidence = if (langs.isNotEmpty()) 0.75f else 0.5f,
            )
        }
        val found = scan.all(Kind.SUBTITLE)
        if (found.isEmpty()) return null
        val languages = found.mapNotNull { f ->
            when (val v = f.value) {
                is Language -> v
                "multi" -> MULTI_LANGUAGE
                else -> null
            }
        }.distinct()
        val hardcoded = when {
            found.any { it.value == "hardcoded" } -> true
            found.any { it.value == "softsub" } -> false
            else -> null
        }
        if (found.all { it.value == "none" }) return SubtitleInfo(emptyList(), null, false, false, false, 0.8f)
        return SubtitleInfo(languages, hardcoded, forced = false, sdh = false, isSubtitleFile = false, confidence = found.maxOf { it.confidence })
    }

    /** "Movie en forced" → "Movie" for "Movie.en.forced.srt". */
    private fun stripSubtitleSuffix(title: String): String {
        val tokens = title.split(SPACE).toMutableList()
        while (tokens.size > 1) {
            val last = tokens.last().lowercase()
            if (last in SUBTITLE_FILE_FLAGS || (last.length <= 3 && Vocabulary.languageOfCode(last) != null) || Vocabulary.languageOf(last) != null) {
                tokens.removeAt(tokens.lastIndex)
            } else break
        }
        return tokens.joinToString(" ")
    }

    private fun firstFreeSegment(scan: Scan): String {
        val segments = mutableListOf<String>()
        val sb = StringBuilder()
        for (i in scan.text.indices) {
            if (scan.isClaimed(i)) {
                if (sb.isNotBlank()) segments += sb.toString()
                sb.clear()
            } else sb.append(scan.text[i])
        }
        if (sb.isNotBlank()) segments += sb.toString()
        return segments.map { it.trim() }.firstOrNull { seg -> seg.count(Char::isLetter) >= 2 } ?: ""
    }
}
