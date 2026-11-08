package dev.yourstream.playback.tracks

import dev.yourstream.core.language.LanguageCatalog

import java.util.Locale

enum class SubtitleMode {
    /** Never show subtitles automatically. */
    OFF,
    /**
     * Only when the audio is in a language the user does not list as an audio preference
     * ("foreign" audio); with understood audio, only *forced* subtitles in that language (signs,
     * foreign-language lines).
     */
    FOREIGN_ONLY,
    /** Always, in the preferred subtitle languages. */
    ALWAYS,
}

/** The user's language settings (codes are normalized on construction). */
data class LanguagePreferences(
    /** Audio languages in order of preference, e.g. Tamil, English, Hindi. */
    val audio: List<String> = listOf("en"),
    /** Subtitle languages in order of preference. */
    val subtitles: List<String> = listOf("en"),
    /** Tried after the lists (for audio and subtitles). */
    val fallbackLanguage: String? = "en",
    val subtitleMode: SubtitleMode = SubtitleMode.FOREIGN_ONLY,
    /** Skip commentary / audio-description tracks unless nothing else exists. */
    val avoidCommentary: Boolean = true,
    /** Prefer subtitles for the deaf and hard of hearing (SDH/CC) over plain ones. */
    val preferSdh: Boolean = false,
) {
    fun normalized() = copy(
        audio = audio.mapNotNull(LanguageCatalog::normalizeCode).distinct(),
        subtitles = subtitles.mapNotNull(LanguageCatalog::normalizeCode).distinct(),
        fallbackLanguage = LanguageCatalog.normalizeCode(fallbackLanguage),
    )
}

/** Where a remembered choice lives: the movie itself, or the whole series (all its episodes). */
enum class PreferenceScope { MOVIE, SERIES }

/**
 * A remembered track, described so it can be found again in *another* file (the next episode,
 * a different version): track ids and indexes change between files, language and label do not.
 */
data class TrackSignature(
    val language: String?,
    val label: String?,
    val channels: Int? = null,
    val commentary: Boolean = false,
    val descriptive: Boolean = false,
    val forced: Boolean = false,
    val sdh: Boolean = false,
) {
    fun encode(): String = listOf(language.orEmpty(), (label ?: "").replace("|", "/"), channels?.toString().orEmpty(), flag(commentary), flag(descriptive), flag(forced), flag(sdh)).joinToString("|")

    companion object {
        fun of(t: AnalyzedTrack) = TrackSignature(
            t.language.code, normalizeLabel(t.track.label), t.track.channels, t.track.isCommentary, t.track.isDescriptive, t.track.isForced, t.track.isHearingImpaired,
        )

        fun decode(s: String?): TrackSignature? {
            if (s.isNullOrBlank()) return null
            val p = s.split("|")
            if (p.size < 7) return null
            return TrackSignature(p[0].ifEmpty { null }, p[1].ifEmpty { null }, p[2].toIntOrNull(), p[3] == "1", p[4] == "1", p[5] == "1", p[6] == "1")
        }

        private fun flag(b: Boolean) = if (b) "1" else "0"

        fun normalizeLabel(label: String?): String? =
            label?.lowercase(Locale.ROOT)?.replace(Regex("[^\\p{L}\\p{N}]+"), " ")?.trim()?.ifEmpty { null }
    }
}

sealed interface SubtitleChoice {
    /** The user switched subtitles off for this movie/series. */
    data object Off : SubtitleChoice
    data class Track(val signature: TrackSignature) : SubtitleChoice
}

/** What the user picked last time for a movie / series. */
data class RememberedChoice(
    val scope: PreferenceScope,
    val itemId: Long,
    val audio: TrackSignature? = null,
    val subtitle: SubtitleChoice? = null,
)

enum class SelectionRule {
    REMEMBERED, PREFERRED_LANGUAGE, FALLBACK_LANGUAGE, FILE_DEFAULT, FIRST_AVAILABLE,
    REMEMBERED_OFF, OFF_BY_PREFERENCE, AUDIO_UNDERSTOOD, FORCED_FOR_AUDIO, NO_PREFERRED_MATCH, NONE_AVAILABLE,
}

data class Choice(val track: AnalyzedTrack?, val rule: SelectionRule, val detail: String)

data class TrackSelection(val audio: Choice, val subtitle: Choice)

/**
 * Picks audio and subtitles. Pure: same inputs, same answer.
 *
 * Audio: remembered pick for this movie/series → preferred languages in order → fallback
 * language → the file's default track → the first track. Commentary / audio description are
 * skipped while anything else exists. Among several tracks of the chosen language: confidently
 * detected first, then the file's default, then file order.
 *
 * Subtitles: remembered pick (including "off") → mode OFF: none → FOREIGN_ONLY with understood
 * audio: forced subtitles in the audio language only → otherwise preferred subtitle languages →
 * fallback language → none (a subtitle in a language the user did not ask for is not shown).
 */
object TrackSelector {

    fun select(tracks: List<AnalyzedTrack>, preferences: LanguagePreferences, remembered: RememberedChoice? = null): TrackSelection {
        val prefs = preferences.normalized()
        val audio = selectAudio(tracks.filter { it.track.type == TrackType.AUDIO && it.track.supported }, prefs, remembered)
        val subtitle = selectSubtitle(tracks.filter { it.track.type == TrackType.SUBTITLE && it.track.supported }, prefs, remembered, audio.track)
        return TrackSelection(audio, subtitle)
    }

    fun selectAudio(audio: List<AnalyzedTrack>, prefs: LanguagePreferences, remembered: RememberedChoice?): Choice {
        if (audio.isEmpty()) return Choice(null, SelectionRule.NONE_AVAILABLE, "no audio track")
        val pool = if (prefs.avoidCommentary) audio.filterNot { it.isSpecial }.ifEmpty { audio } else audio
        remembered?.audio?.let { sig -> matchSignature(audio, sig)?.let { return Choice(it, SelectionRule.REMEMBERED, "same as last time (${describe(sig)})") } }
        for (lang in prefs.audio) {
            best(pool.filter { it.language.code == lang })?.let { return Choice(it, SelectionRule.PREFERRED_LANGUAGE, "${LanguageCatalog.displayName(lang)} is preference #${prefs.audio.indexOf(lang) + 1}") }
        }
        prefs.fallbackLanguage?.let { lang ->
            best(pool.filter { it.language.code == lang })?.let { return Choice(it, SelectionRule.FALLBACK_LANGUAGE, "fallback ${LanguageCatalog.displayName(lang)}") }
        }
        pool.firstOrNull { it.track.isDefault }?.let { return Choice(it, SelectionRule.FILE_DEFAULT, "no preferred language; the file's default track") }
        return Choice(pool.minBy { it.track.index }, SelectionRule.FIRST_AVAILABLE, "no preferred language; first track")
    }

    fun selectSubtitle(subs: List<AnalyzedTrack>, prefs: LanguagePreferences, remembered: RememberedChoice?, audio: AnalyzedTrack?): Choice {
        when (val r = remembered?.subtitle) {
            SubtitleChoice.Off -> return Choice(null, SelectionRule.REMEMBERED_OFF, "turned off last time")
            is SubtitleChoice.Track -> matchSignature(subs, r.signature)?.let { return Choice(it, SelectionRule.REMEMBERED, "same as last time (${describe(r.signature)})") }
            null -> Unit
        }
        if (subs.isEmpty()) return Choice(null, SelectionRule.NONE_AVAILABLE, "no subtitles")
        if (prefs.subtitleMode == SubtitleMode.OFF) return Choice(null, SelectionRule.OFF_BY_PREFERENCE, "subtitles off in settings")
        val audioLang = audio?.language?.code
        val understood = audioLang != null && (audioLang in prefs.audio)
        if (prefs.subtitleMode == SubtitleMode.FOREIGN_ONLY && understood) {
            val forced = subs.filter { it.track.isForced && it.language.code == audioLang }
            return best(forced)?.let { Choice(it, SelectionRule.FORCED_FOR_AUDIO, "forced subtitles for ${LanguageCatalog.displayName(audioLang)} audio") }
                ?: Choice(null, SelectionRule.AUDIO_UNDERSTOOD, "${LanguageCatalog.displayName(audioLang)} audio is understood")
        }
        val full = subs.filterNot { it.track.isForced }.ifEmpty { subs }
        for (lang in prefs.subtitles) {
            bestSubtitle(full.filter { it.language.code == lang }, prefs)?.let {
                return Choice(it, SelectionRule.PREFERRED_LANGUAGE, "${LanguageCatalog.displayName(lang)} is subtitle preference #${prefs.subtitles.indexOf(lang) + 1}")
            }
        }
        prefs.fallbackLanguage?.let { lang ->
            bestSubtitle(full.filter { it.language.code == lang }, prefs)?.let { return Choice(it, SelectionRule.FALLBACK_LANGUAGE, "fallback ${LanguageCatalog.displayName(lang)}") }
        }
        return Choice(null, SelectionRule.NO_PREFERRED_MATCH, "no subtitles in a preferred language")
    }

    /** Best of several tracks in the same language. */
    private fun best(tracks: List<AnalyzedTrack>): AnalyzedTrack? = tracks.sortedWith(
        compareByDescending<AnalyzedTrack> { it.language.confidence >= 0.5 }
            .thenByDescending { it.track.isDefault }
            .thenBy { it.track.index },
    ).firstOrNull()

    private fun bestSubtitle(tracks: List<AnalyzedTrack>, prefs: LanguagePreferences): AnalyzedTrack? = tracks.sortedWith(
        compareByDescending<AnalyzedTrack> { it.language.confidence >= 0.5 }
            .thenByDescending { it.track.isHearingImpaired == prefs.preferSdh }
            .thenByDescending { it.track.isDefault }
            .thenBy { it.track.origin.ordinal } // embedded, then the user's Telegram files, then local
            .thenBy { it.track.index },
    ).firstOrNull()

    /**
     * Finds the remembered track in a (possibly different) file: same language required when it was
     * known; label, channel layout and flags break ties. An unknown-language pick is found by label.
     */
    fun matchSignature(tracks: List<AnalyzedTrack>, sig: TrackSignature): AnalyzedTrack? {
        fun score(t: AnalyzedTrack): Int {
            val label = TrackSignature.normalizeLabel(t.track.label)
            if (sig.language != null && t.language.code != sig.language) return 0
            if (sig.language == null && (sig.label == null || label != sig.label)) return 0
            var s = 10
            if (sig.label != null && label == sig.label) s += 4
            if (sig.channels != null && t.track.channels == sig.channels) s += 1
            if (t.track.isCommentary == sig.commentary) s += 3
            if (t.track.isDescriptive == sig.descriptive) s += 3
            if (t.track.isForced == sig.forced) s += 3
            if (t.track.isHearingImpaired == sig.sdh) s += 1
            return s
        }
        return tracks.map { it to score(it) }.filter { it.second > 0 }.maxWithOrNull(compareBy<Pair<AnalyzedTrack, Int>> { it.second }.thenByDescending { -it.first.track.index })?.first
    }

    private fun describe(sig: TrackSignature) = listOfNotNull(sig.language?.let(LanguageCatalog::displayName), sig.label).joinToString(", ").ifEmpty { "track" }
}
