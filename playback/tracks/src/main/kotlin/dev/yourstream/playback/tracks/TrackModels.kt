package dev.yourstream.playback.tracks

enum class TrackType { AUDIO, SUBTITLE }

enum class TrackOrigin {
    /** Inside the video file. */
    EMBEDDED,
    /** A separate subtitle file from the user's Telegram (indexed and linked to the title). */
    EXTERNAL,
    /** A file the user added on the device. */
    LOCAL,
}

/**
 * One selectable track, as the player sees it, before any interpretation. [id] is opaque and only
 * valid for the current file (Media3 group/track index, or "ext:<id>" for external subtitles).
 */
data class TrackCandidate(
    val id: String,
    val type: TrackType,
    /** Position among tracks of its type in the file (0-based). */
    val index: Int,
    /** Language tag from container metadata (may be missing, "und", or wrong). */
    val declaredLanguage: String? = null,
    /** Human label / title ("Tamil 5.1", "English [SDH]", "Director's commentary"). */
    val label: String? = null,
    val codec: String? = null,
    val channels: Int? = null,
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
    val isHearingImpaired: Boolean = false,
    val isCommentary: Boolean = false,
    /** Audio description for the visually impaired. */
    val isDescriptive: Boolean = false,
    val origin: TrackOrigin = TrackOrigin.EMBEDDED,
    /** External subtitle file name, when known (its own language hints). */
    val fileName: String? = null,
    /** Language guessed from subtitle text, when the content was available. */
    val contentLanguage: ContentLanguage? = null,
    /** The player can actually decode/render it. */
    val supported: Boolean = true,
)

enum class LanguageSource {
    /** A choice the user made for this track before. */
    USER,
    /** Subtitle text written in a script/words of one language. */
    CONTENT,
    /** Language named in the track label. */
    LABEL,
    /** Container language tag, consistent with the other evidence. */
    METADATA,
    /** External subtitle file name ("movie.ta.srt"). */
    FILE_NAME,
    /** Languages in the video file name, matched to untagged tracks by order. */
    FILE_NAME_ORDER,
    /** The title's original language (metadata provider), single-track files only. */
    ORIGINAL_LANGUAGE,
    NONE,
}

/** Language decided for a track, with how sure we are and why. */
data class DetectedLanguage(val code: String?, val confidence: Double, val source: LanguageSource, val note: String? = null) {
    companion object { val UNKNOWN = DetectedLanguage(null, 0.0, LanguageSource.NONE) }
}

/** A track plus its detected language. */
data class AnalyzedTrack(val track: TrackCandidate, val language: DetectedLanguage) {
    val id: String get() = track.id
    /** Commentary / audio description / forced-only: not a normal "main" track. */
    val isSpecial: Boolean get() = track.isCommentary || track.isDescriptive || (track.type == TrackType.SUBTITLE && track.isForced)
}

/** What the file around the tracks tells us. */
data class FileContext(
    /** The video's file name (Telegram document name). */
    val fileName: String? = null,
    /** Audio languages the filename parser found, in file-name order. */
    val fileNameAudioLanguages: List<String> = emptyList(),
    /** Subtitle languages the parser found in the name ("ESub" → en). */
    val fileNameSubtitleLanguages: List<String> = emptyList(),
    /** Original language of the title from TMDB/Cinemeta, if matched. */
    val originalLanguage: String? = null,
)

/**
 * Flags from the label, because container flags are often missing: "Commentary", "Audio
 * Description", "Forced", "SDH"/"CC"/"Hearing Impaired". Container flags are kept (OR).
 */
fun TrackCandidate.withLabelHints(): TrackCandidate {
    val l = label?.lowercase() ?: return this
    fun has(vararg words: String) = words.any { w -> Regex("(^|[^a-z])${Regex.escape(w)}([^a-z]|$)").containsMatchIn(l) }
    return copy(
        isCommentary = isCommentary || has("commentary", "commentaire", "kommentar"),
        isDescriptive = isDescriptive || has("audio description", "described", "descriptive"),
        isForced = isForced || (type == TrackType.SUBTITLE && has("forced", "signs", "signs & songs")),
        isHearingImpaired = isHearingImpaired || (type == TrackType.SUBTITLE && has("sdh", "cc", "hearing impaired")),
    )
}
