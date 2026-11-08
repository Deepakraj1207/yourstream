package dev.yourstream.playback.tracks

/** Persists language settings and remembered choices (Room in the app, in-memory in tests). */
interface TrackPreferenceStore {
    suspend fun preferences(): LanguagePreferences
    suspend fun setPreferences(preferences: LanguagePreferences)
    /** The remembered choice of a movie or a series (its item id), if any. */
    suspend fun remembered(itemId: Long): RememberedChoice?
    suspend fun rememberAudio(itemId: Long, scope: PreferenceScope, signature: TrackSignature)
    suspend fun rememberSubtitle(itemId: Long, scope: PreferenceScope, choice: SubtitleChoice)
    suspend fun forget(itemId: Long)
}

class InMemoryTrackPreferenceStore(private var prefs: LanguagePreferences = LanguagePreferences()) : TrackPreferenceStore {
    private val choices = HashMap<Long, RememberedChoice>()
    override suspend fun preferences() = prefs
    override suspend fun setPreferences(preferences: LanguagePreferences) { prefs = preferences.normalized() }
    override suspend fun remembered(itemId: Long) = choices[itemId]
    override suspend fun rememberAudio(itemId: Long, scope: PreferenceScope, signature: TrackSignature) {
        choices[itemId] = (choices[itemId] ?: RememberedChoice(scope, itemId)).copy(scope = scope, audio = signature)
    }
    override suspend fun rememberSubtitle(itemId: Long, scope: PreferenceScope, choice: SubtitleChoice) {
        choices[itemId] = (choices[itemId] ?: RememberedChoice(scope, itemId)).copy(scope = scope, subtitle = choice)
    }
    override suspend fun forget(itemId: Long) { choices.remove(itemId) }
}

/** Which movie / series a file belongs to (per-movie vs per-series memory). */
data class TrackScope(val itemId: Long, val scope: PreferenceScope)

/**
 * The tracks of the file being played and the decisions about them, independent of any player
 * or UI: give it the player's tracks ([onTracks]), apply [selection] to the player; when the user
 * picks something, call [userSelectedAudio] / [userSelectedSubtitle] and it is remembered for
 * the movie, or for the whole series.
 */
class TrackSession(
    private val store: TrackPreferenceStore,
    private val scope: TrackScope?,
    private val context: FileContext = FileContext(),
) {
    var tracks: List<AnalyzedTrack> = emptyList(); private set
    var selection: TrackSelection? = null; private set

    /** New track list from the player (first time or after external subtitles were added). */
    suspend fun onTracks(candidates: List<TrackCandidate>): TrackSelection {
        tracks = LanguageDetector.analyze(candidates, context)
        val remembered = scope?.let { store.remembered(it.itemId) }
        return TrackSelector.select(tracks, store.preferences(), remembered).also { selection = it }
    }

    suspend fun userSelectedAudio(trackId: String): AnalyzedTrack? {
        val t = tracks.firstOrNull { it.id == trackId && it.track.type == TrackType.AUDIO } ?: return null
        scope?.let { store.rememberAudio(it.itemId, it.scope, TrackSignature.of(t)) }
        selection = selection?.copy(audio = Choice(t, SelectionRule.REMEMBERED, "chosen by the user"))
        return t
    }

    /** [trackId] null = subtitles off. */
    suspend fun userSelectedSubtitle(trackId: String?): AnalyzedTrack? {
        val t = trackId?.let { id -> tracks.firstOrNull { it.id == id && it.track.type == TrackType.SUBTITLE } ?: return null }
        scope?.let { store.rememberSubtitle(it.itemId, it.scope, t?.let { s -> SubtitleChoice.Track(TrackSignature.of(s)) } ?: SubtitleChoice.Off) }
        selection = selection?.copy(subtitle = Choice(t, if (t == null) SelectionRule.REMEMBERED_OFF else SelectionRule.REMEMBERED, "chosen by the user"))
        return t
    }
}
