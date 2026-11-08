package dev.yourstream.playback.tracks

/**
 * Decides the language of each track from all available evidence, because container language
 * tags are often missing ("und"), copied defaults ("eng" on every track of a Tamil dual-audio
 * release) or plain wrong.
 *
 * Evidence, strongest first (per track):
 *  1. CONTENT  – subtitle text in an unambiguous script (Tamil, Telugu, Hangul …) or clearly English
 *  2. LABEL    – a language named in the track title ("Tamil 5.1", "Hindi [Dubbed]")
 *  3. METADATA – the container tag, *unless* it looks like a copied default (see below)
 *  4. FILE_NAME – external subtitle file name ("movie.ta.srt", "Movie Tamil.srt")
 *  5. FILE_NAME_ORDER – languages in the video name ("Tam+Tel+Hin") given to the remaining
 *     untagged audio tracks in order, when the counts line up
 *  6. ORIGINAL_LANGUAGE – a single untagged audio track in a file whose name names no language
 *
 * A tag is distrusted when every track of the type has the same tag while labels or the file
 * name prove there are several languages, or when it contradicts the track's own label.
 */
object LanguageDetector {

    fun analyze(tracks: List<TrackCandidate>, context: FileContext = FileContext()): List<AnalyzedTrack> {
        val audio = analyzeType(tracks.filter { it.type == TrackType.AUDIO }, context.fileNameAudioLanguages.mapNotNull(LanguageCatalog::normalizeCode), context)
        val subs = analyzeType(tracks.filter { it.type == TrackType.SUBTITLE }, context.fileNameSubtitleLanguages.mapNotNull(LanguageCatalog::normalizeCode), context)
        val byId = (audio + subs).associateBy { it.id }
        return tracks.map { byId.getValue(it.id) }
    }

    private fun analyzeType(tracks: List<TrackCandidate>, nameLanguages: List<String>, context: FileContext): List<AnalyzedTrack> {
        if (tracks.isEmpty()) return emptyList()
        val type = tracks.first().type
        val declared = tracks.map { LanguageCatalog.normalizeCode(it.declaredLanguage) }
        val labels = tracks.map { labelLanguage(it.label) }
        // Several languages are present according to labels or the file name…
        val distinctEvidence = (labels.filterNotNull() + if (type == TrackType.AUDIO) nameLanguages else emptyList()).toSet()
        // …but every track carries the same tag: the tag is a muxer default, not information.
        val copiedDefault = tracks.size > 1 && declared.all { it != null && it == declared.first() } &&
            (distinctEvidence.size > 1 || (distinctEvidence.isNotEmpty() && declared.first() !in distinctEvidence))

        val result = tracks.mapIndexed { i, t ->
            val content = t.contentLanguage?.takeIf { it.confidence >= 0.6 }?.let { DetectedLanguage(it.code, it.confidence, LanguageSource.CONTENT) }
            val label = labels[i]?.let { DetectedLanguage(it, 0.9, LanguageSource.LABEL, "label \"${t.label}\"") }
            val tag = declared[i]
            val metadata = tag?.let {
                when {
                    copiedDefault -> DetectedLanguage(it, 0.25, LanguageSource.METADATA, "same tag on all tracks; probably a default")
                    label != null && label.code != it -> DetectedLanguage(it, 0.3, LanguageSource.METADATA, "contradicts label")
                    else -> DetectedLanguage(it, 0.8, LanguageSource.METADATA)
                }
            }
            val fileName = if (t.origin != TrackOrigin.EMBEDDED) subtitleFileLanguage(t.fileName)?.let { DetectedLanguage(it, 0.7, LanguageSource.FILE_NAME, "file \"${t.fileName}\"") } else null
            val best = listOfNotNull(content, label, metadata, fileName).maxByOrNull { it.confidence }
            // Content contradicting a declared language wins clearly ("eng"-tagged file full of Tamil script).
            val decided = if (content != null && best != null && best.source != LanguageSource.CONTENT && content.code != best.code && content.confidence >= 0.8) content else best
            AnalyzedTrack(t, decided ?: DetectedLanguage.UNKNOWN)
        }.toMutableList()

        if (type == TrackType.AUDIO) {
            // Untrusted / unknown tracks get the file name's languages, in order, when the counts fit.
            val weak = result.indices.filter { result[it].language.confidence < 0.5 }
            val taken = result.filter { it.language.confidence >= 0.5 }.mapNotNull { it.language.code }.toSet()
            val remaining = nameLanguages.filter { it !in taken }
            if (weak.isNotEmpty() && remaining.size == weak.size) {
                weak.forEachIndexed { k, idx ->
                    result[idx] = result[idx].copy(language = DetectedLanguage(remaining[k], if (weak.size == 1) 0.6 else 0.5, LanguageSource.FILE_NAME_ORDER, "file name lists ${nameLanguages.joinToString("+")}"))
                }
            } else if (weak.size == 1 && tracks.size == 1 && nameLanguages.isEmpty() && result[0].language.code == null) {
                LanguageCatalog.normalizeCode(context.originalLanguage)?.let { orig ->
                    result[0] = result[0].copy(language = DetectedLanguage(orig, 0.4, LanguageSource.ORIGINAL_LANGUAGE, "title's original language"))
                }
            }
        }
        return result
    }

    /** One language named in a label; null if none or several ("Tamil + Hindi"). */
    fun labelLanguage(label: String?): String? = LanguageCatalog.findInText(label).singleOrNull()

    /**
     * Language in an external subtitle file name: the suffix before the extension ("x.ta.srt",
     * "x.eng.forced.srt") or a name anywhere ("Movie Tamil Subtitles.srt"). Null when absent or
     * ambiguous.
     */
    fun subtitleFileLanguage(fileName: String?): String? {
        if (fileName.isNullOrBlank()) return null
        val base = fileName.substringBeforeLast('.')
        val parts = base.split('.', '_', '-', ' ').filter { it.isNotBlank() }
        // Suffix tokens, skipping flags: name.ta.forced → ta
        for (token in parts.asReversed().take(3)) {
            if (token.lowercase() in SUFFIX_FLAGS) continue
            LanguageCatalog.findInText(token, allowTwoLetterCodes = true).singleOrNull()?.let { return it }
            break
        }
        return LanguageCatalog.findInText(base).singleOrNull()
    }

    private val SUFFIX_FLAGS = setOf("forced", "sdh", "cc", "hi", "full", "default", "sub", "subs", "subtitle", "subtitles")
}
