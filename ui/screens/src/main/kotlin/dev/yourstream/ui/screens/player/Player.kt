package dev.yourstream.ui.screens.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yourstream.core.common.AppResult
import dev.yourstream.core.diagnostics.PlaybackSession
import dev.yourstream.core.diagnostics.PlaybackSessionStore
import dev.yourstream.core.language.LanguageCatalog
import dev.yourstream.library.api.PlaybackTarget
import dev.yourstream.library.api.WatchHistoryRepository
import dev.yourstream.playback.media3.PlaybackErrorKind
import dev.yourstream.playback.media3.PlaybackState
import dev.yourstream.playback.media3.PlaybackStatus
import dev.yourstream.playback.media3.TelegramPlaybackController
import dev.yourstream.playback.media3.TelegramPlaybackControllerFactory
import dev.yourstream.playback.media3.TrackState
import dev.yourstream.playback.tracks.AnalyzedTrack
import dev.yourstream.ui.core.ErrorKind
import dev.yourstream.ui.core.UiError
import dev.yourstream.ui.core.toUiError
import dev.yourstream.ui.screens.navigation.OpenTarget
import dev.yourstream.ui.screens.navigation.PlayerRoute
import dev.yourstream.watch.core.NextAction
import dev.yourstream.watch.engine.WatchIntelligence
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TrackOption(val id: String, val label: String)

data class PlayerUiState(
    val title: String = "",
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val bufferedMs: Long = 0,
    val error: UiError? = null,
    /** Automatic recovery in progress, from the controller ("waiting for network"). */
    val recovery: String? = null,
    val audio: List<TrackOption> = emptyList(),
    val subtitles: List<TrackOption> = emptyList(),
    val selectedAudio: String? = null,
    /** Null = subtitles off. */
    val selectedSubtitle: String? = null,
    /** Offered when an episode ends (the domain's next-episode decision). */
    val next: Pair<String, OpenTarget.Play>? = null,
) {
    val loading: Boolean get() = status == PlaybackStatus.PREPARING || status == PlaybackStatus.BUFFERING
}

sealed interface PlayerAction {
    data object TogglePlay : PlayerAction
    data object Pause : PlayerAction
    data class SeekTo(val positionMs: Long) : PlayerAction
    data class SeekBy(val deltaMs: Long) : PlayerAction
    data class SelectAudio(val id: String) : PlayerAction
    data class SelectSubtitle(val id: String?) : PlayerAction
    data object Retry : PlayerAction
}

/**
 * Owns one playback controller for the screen's lifetime (survives rotation; released when the
 * screen leaves). All playback decisions — version, resume point, tracks, recovery, next
 * episode — stay in the domain/playback layer; this only maps their state.
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    saved: SavedStateHandle,
    factory: TelegramPlaybackControllerFactory,
    private val history: WatchHistoryRepository,
    private val watch: WatchIntelligence,
    private val session: PlaybackSessionStore,
) : ViewModel() {
    private val route = saved.toRoute<PlayerRoute>()
    private val target: PlaybackTarget = route.target
    private val controller: TelegramPlaybackController = factory.create()
    private val next = MutableStateFlow<Pair<String, OpenTarget.Play>?>(null)
    private val openError = MutableStateFlow<UiError?>(null)

    /** For the video surface only. */
    val player: Player get() = controller.player

    val state: StateFlow<PlayerUiState> = combine(controller.state, controller.tracks, next, openError) { s, t, n, e -> map(s, t, n, e) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PlayerUiState())

    init {
        viewModelScope.launch {
            // Resume: the session saved before a process death is newer than the history.
            val saved = session.read()?.takeIf { it.itemId == target.itemId && it.episodeId == target.episodeId }
            val resume = saved?.positionMs ?: history.progress(target).first()?.takeIf { !it.completed }?.positionMs
            val r = controller.open(target, route.mediaId ?: saved?.mediaId, resume)
            if (r is AppResult.Err) openError.value = r.error.toUiError()
        }
        viewModelScope.launch { trackSession() }
    }

    fun onAction(action: PlayerAction) {
        when (action) {
            PlayerAction.TogglePlay -> controller.togglePlayPause()
            PlayerAction.Pause -> controller.pause()
            is PlayerAction.SeekTo -> controller.seekTo(action.positionMs)
            is PlayerAction.SeekBy -> controller.seekBy(action.deltaMs)
            is PlayerAction.SelectAudio -> controller.selectAudio(action.id)
            is PlayerAction.SelectSubtitle -> controller.selectSubtitle(action.id)
            PlayerAction.Retry -> controller.retry()
        }
    }

    /** Saves the session every 5 s while playing; asks the domain for the next episode at the end. */
    private suspend fun trackSession() {
        var lastSave = 0L
        controller.state.collect { s ->
            val now = System.currentTimeMillis()
            if (s.status == PlaybackStatus.ENDED) {
                session.clear()
                if (target.episodeId != null && next.value == null) {
                    next.value = runCatching { watch.nextEpisode(target.itemId) }.getOrNull()?.takeIf { it.action != NextAction.NONE }?.episode
                        ?.takeIf { it.episodeId != target.episodeId }
                        ?.let { e -> e.label to OpenTarget.Play(PlaybackTarget(target.itemId, e.episodeId), e.lastMediaId) }
                }
            } else if (s.isPlaying && now - lastSave >= 5_000) {
                lastSave = now
                session.save(PlaybackSession(target.itemId, target.episodeId, s.media?.mediaId, s.media?.title.orEmpty(), s.positionMs, s.durationMs ?: 0, now))
            }
        }
    }

    override fun onCleared() {
        session.clear() // left normally (not killed): nothing to recover
        controller.release()
    }

    companion object {
        /** Pure mapping (tested). */
        fun map(s: PlaybackState, t: TrackState, next: Pair<String, OpenTarget.Play>?, openError: UiError?): PlayerUiState = PlayerUiState(
            title = s.media?.title.orEmpty(),
            status = s.status, isPlaying = s.isPlaying, positionMs = s.positionMs, durationMs = s.durationMs, bufferedMs = s.bufferedPositionMs,
            error = openError ?: s.error?.let { e ->
                val kind = when (e.kind) {
                    PlaybackErrorKind.NETWORK -> ErrorKind.OFFLINE
                    PlaybackErrorKind.FILE_UNAVAILABLE -> ErrorKind.NOT_FOUND
                    else -> ErrorKind.UNKNOWN
                }
                UiError(kind, e.message, e.retryable)
            },
            recovery = s.recovery,
            audio = t.audio.mapIndexed { i, a -> TrackOption(a.id, label(a, i)) },
            subtitles = t.subtitles.mapIndexed { i, a -> TrackOption(a.id, label(a, i)) },
            selectedAudio = t.selection?.audio?.track?.id,
            selectedSubtitle = t.selection?.subtitle?.track?.id,
            next = next,
        )

        private fun label(a: AnalyzedTrack, index: Int): String {
            val language = a.language.code?.let(LanguageCatalog::displayName)
            return listOfNotNull(language, a.track.label?.takeIf { it != language }).joinToString(" · ").ifEmpty { "#${index + 1}" }
        }
    }
}
