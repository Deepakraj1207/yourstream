package dev.yourstream.media.filename.internal

internal enum class Kind {
    SEASON, EPISODE, AIR_DATE, YEAR_CANDIDATE, RESOLUTION, QUALITY, QUALITY_TAG, SERVICE, VIDEO_CODEC, BIT_DEPTH, HDR,
    AUDIO_CODEC, AUDIO_CHANNELS, AUDIO_BITRATE, DUAL_AUDIO, MULTI_AUDIO, LANGUAGE, DUBBED, SUBTITLE, EDITION, FLAG,
    PART, GROUP, NOISE,
}

/** Something a detector recognised, at [range] of the scanned text. */
internal data class Finding(
    val kind: Kind,
    val value: Any,
    val range: IntRange,
    val confidence: Float,
    val raw: String,
    /** Extra values (episode/season range ends, subtitle flags…). */
    val extra: Map<String, Any> = emptyMap(),
)

/**
 * The text being scanned plus which characters detectors already consumed. A character
 * belongs to at most one finding, so "2160p" can never also be read as a year, and "DD 5 1"
 * once claimed as audio is not read as an episode range.
 */
internal class Scan(val text: String) {
    private val claimed = BooleanArray(text.length)
    val findings = mutableListOf<Finding>()

    fun isFree(range: IntRange): Boolean = range.all { it in claimed.indices && !claimed[it] }

    fun claim(range: IntRange) {
        for (i in range) if (i in claimed.indices) claimed[i] = true
    }

    fun firstFree(regex: Regex): MatchResult? = regex.findAll(text).firstOrNull { isFree(it.range) }

    fun allFree(regex: Regex): List<MatchResult> = regex.findAll(text).filter { isFree(it.range) }.toList()

    fun add(kind: Kind, value: Any, match: MatchResult, confidence: Float, extra: Map<String, Any> = emptyMap(), claim: Boolean = true) =
        add(kind, value, match.range, confidence, match.value, extra, claim)

    fun add(kind: Kind, value: Any, range: IntRange, confidence: Float, raw: String, extra: Map<String, Any> = emptyMap(), claim: Boolean = true) {
        if (claim) claim(range)
        findings += Finding(kind, value, range, confidence.coerceIn(0f, 1f), raw.trim(), extra)
    }

    fun first(kind: Kind): Finding? = findings.filter { it.kind == kind }.minByOrNull { it.range.first }
    fun all(kind: Kind): List<Finding> = findings.filter { it.kind == kind }.sortedBy { it.range.first }
    fun has(kind: Kind): Boolean = findings.any { it.kind == kind }
    fun remove(finding: Finding) { findings.remove(finding) }

    /** Unclaimed text between [from] and [to], collapsed. */
    fun freeText(from: Int, to: Int): String {
        val sb = StringBuilder()
        for (i in from until to.coerceAtMost(text.length)) sb.append(if (claimed[i]) ' ' else text[i])
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    fun isClaimed(index: Int): Boolean = index in claimed.indices && claimed[index]
}
