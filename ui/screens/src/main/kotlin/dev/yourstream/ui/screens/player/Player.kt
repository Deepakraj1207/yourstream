package dev.yourstream.ui.screens.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.navigation.toRoute
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yourstream.core.common.AppResult
import dev.yourstream.core.diagnostics.PlaybackSession
import dev.yourstream.core.diagnostics.PlaybackSessionStore
import dev.yourstream.core.language.LanguageCatalog
import dev.yourstream.library.api.Episode
import dev.yourstream.library.api.EpisodeRepository
import dev.yourstream.library.api.PlaybackTarget
import dev.yourstream.library.api.SeriesRepository
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
import dev.yourstream.ui.screens.episodes.EpisodeItem
import dev.yourstream.ui.screens.episodes.EpisodesViewModel
import dev.yourstream.ui.screens.episodes.SeasonTab
import dev.yourstream.ui.screens.navigation.OpenTarget
import dev.yourstream.ui.screens.navigation.PlayerRoute
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

data class TrackOption(val id: String, val label: String)

/** A neighbouring episode: "S01E07" + its title, and how to play it. */
data class EpisodeLink(val label: String, val title: String?, val play: OpenTarget.Play)

/** What is playing, for the quality badge and the info panel. Values are display-ready. */
data class QualityInfo(
    /** "4K", "1080p", "720p", "480p". */
    val resolution: String? = null,
    /** "1920 × 1080". */
    val dimensions: String? = null,
    /** "HEVC", "H.264", "AV1". */
    val videoCodec: String? = null,
    /** "23.976 fps". */
    val frameRate: String? = null,
    /** "8.4 Mbps". */
    val bitrate: String? = null,
    /** "Dolby Digital Plus · 5.1". */
    val audio: String? = null,
    val fileName: String? = null,
    val sizeBytes: Long? = null,
) {
    /** The short badge on the player: "1080p · HEVC". */
    val badge: String? get() = listOfNotNull(resolution, videoCodec).joinToString(" · ").ifEmpty { null }
}

/** The series' episodes, for the in-player episode list (null for movies). */
data class EpisodesPanel(val seasons: List<SeasonTab>, val selectedSeasonId: Long?, val currentEpisodeId: Long)

data class PlayerUiState(
    val title: String = "",
    /** "S01E06 · Washington B.C." for episodes. */
    val subtitle: String? = null,
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val bufferedMs: Long = 0,
    val speed: Float = 1f,
    val quality: QualityInfo? = null,
    val error: UiError? = null,
    /** Automatic recovery in progress, from the controller ("waiting for network"). */
    val recovery: String? = null,
    val audio: List<TrackOption> = emptyList(),
    val subtitles: List<TrackOption> = emptyList(),
    val selectedAudio: String? = null,
    /** Null = subtitles off. */
    val selectedSubtitle: String? = null,
    val previous: EpisodeLink? = null,
    val next: EpisodeLink? = null,
    /** Where playback resumed (offers "Start over"); null when it started from the beginning. */
    val resumedFromMs: Long? = null,
    val episodes: EpisodesPanel? = null,
) {
    val loading: Boolean get() = status == PlaybackStatus.PREPARING || status == PlaybackStatus.BUFFERING
    val ended: Boolean get() = status == PlaybackStatus.ENDED
}

sealed interface PlayerAction {
    data object TogglePlay : PlayerAction
    data object Pause : PlayerAction
    data class SeekTo(val positionMs: Long) : PlayerAction
    data class SeekBy(val deltaMs: Long) : PlayerAction
    /** Timeline drag / key scrub: fast key-frame previews, then one exact seek. */
    data object ScrubStart : PlayerAction
    data class ScrubEnd(val positionMs: Long) : PlayerAction
    data class SetSpeed(val speed: Float) : PlayerAction
    data class SelectAudio(val id: String) : PlayerAction
    data class SelectSubtitle(val id: String?) : PlayerAction
    data class SelectSeason(val seasonId: Long) : PlayerAction
    data object StartOver : PlayerAction
    data object DismissResume : PlayerAction
    data object Retry : PlayerAction
}

/** Everything around playback that the controller does not know: neighbours, resume, episodes. */
data class PlayerExtras(
    val subtitle: String? = null,
    val previous: EpisodeLink? = null,
    val next: EpisodeLink? = null,
    val resumedFromMs: Long? = null,
    val openError: UiError? = null,
)

/**
 * Owns one playback controller for the screen's lifetime (survives rotation; released when the
 * screen leaves). All playback decisions (version, tracks, recovery) stay in the playback layer;
 * neighbours and the episode list come from the library. This only maps their state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    saved: SavedStateHandle,
    factory: TelegramPlaybackControllerFactory,
    private val history: WatchHistoryRepository,
    private val session: PlaybackSessionStore,
    private val episodes: EpisodeRepository,
    series: SeriesRepository,
    private val active: dev.yourstream.playback.media3.ActivePlayback,
) : ViewModel() {
    private val route = saved.toRoute<PlayerRoute>()
    private val target: PlaybackTarget = route.target
    private val controller: TelegramPlaybackController = factory.create()
    private val extras = MutableStateFlow(PlayerExtras())
    private val selectedSeason = MutableStateFlow<Long?>(null)

    /** For the video surface only. */
    val player: Player get() = controller.player

    private val panel: Flow<EpisodesPanel?> = target.episodeId?.let { episodeId ->
        combine(
            series.seasons(target.itemId).map { list -> list.map { SeasonTab(it.id, it.displayName) } }.catch { emit(emptyList()) },
            selectedSeason,
        ) { seasons, selected -> EpisodesPanel(seasons, selected ?: seasons.firstOrNull()?.seasonId, episodeId) }
    } ?: flowOf(null)

    val state: StateFlow<PlayerUiState> = combine(controller.state, controller.tracks, extras, panel, ::map)
        .stateIn(viewModelScope, SharingStarted.Eagerly, PlayerUiState())

    /** Episodes of the season shown in the episode list. */
    val episodeItems: Flow<PagingData<EpisodeItem>> = selectedSeason.filterNotNull()
        .flatMapLatest { seasonId -> episodes.episodes(target.itemId, seasonId) }
        .map { page -> page.map(EpisodesViewModel::item) }
        .cachedIn(viewModelScope)

    init {
        viewModelScope.launch {
            // Resume: the session saved before a process death is newer than the history.
            val saved = session.read()?.takeIf { it.itemId == target.itemId && it.episodeId == target.episodeId }
            val resume = saved?.positionMs ?: runCatching { history.progress(target).first() }.getOrNull()?.takeIf { !it.completed }?.positionMs
            when (val r = controller.open(target, route.mediaId ?: saved?.mediaId, resume)) {
                is AppResult.Err -> extras.update { it.copy(openError = r.error.toUiError()) }
                is AppResult.Ok -> if (resume != null && resume >= RESUME_MIN_MS) extras.update { it.copy(resumedFromMs = resume) }
            }
        }
        target.episodeId?.let { episodeId -> viewModelScope.launch { loadNeighbours(episodeId) } }
        viewModelScope.launch { trackSession() }
        // The media notification and lock screen show what plays, and control this player.
        viewModelScope.launch {
            state.map { it.title to it.subtitle }.filter { it.first.isNotBlank() }.distinctUntilChanged()
                .collect { (title, subtitle) -> active.attach(controller.player, title, subtitle) }
        }
    }

    fun onAction(action: PlayerAction) {
        when (action) {
            PlayerAction.TogglePlay -> controller.togglePlayPause()
            PlayerAction.Pause -> controller.pause()
            is PlayerAction.SeekTo -> controller.seekTo(action.positionMs)
            is PlayerAction.SeekBy -> controller.seekBy(action.deltaMs)
            PlayerAction.ScrubStart -> controller.beginScrub()
            is PlayerAction.ScrubEnd -> controller.endScrub(action.positionMs)
            is PlayerAction.SetSpeed -> controller.setSpeed(action.speed)
            is PlayerAction.SelectAudio -> controller.selectAudio(action.id)
            is PlayerAction.SelectSubtitle -> controller.selectSubtitle(action.id)
            is PlayerAction.SelectSeason -> selectedSeason.value = action.seasonId
            PlayerAction.StartOver -> { controller.seekTo(0); extras.update { it.copy(resumedFromMs = null) } }
            PlayerAction.DismissResume -> extras.update { it.copy(resumedFromMs = null) }
            PlayerAction.Retry -> controller.retry()
        }
    }

    private suspend fun loadNeighbours(episodeId: Long) {
        val current = runCatching { episodes.episode(episodeId) }.getOrNull()
        val next = runCatching { episodes.nextEpisode(episodeId) }.getOrNull()
        val previous = runCatching { episodes.previousEpisode(episodeId) }.getOrNull()
        extras.update { it.copy(subtitle = current?.let(::episodeSubtitle), next = next?.let(::link), previous = previous?.let(::link)) }
        if (selectedSeason.value == null) selectedSeason.value = current?.seasonId
    }

    /** Saves the session every 5 s while playing, so a process death can resume. */
    private suspend fun trackSession() {
        var lastSave = 0L
        controller.state.collect { s ->
            val now = System.currentTimeMillis()
            if (s.status == PlaybackStatus.ENDED) {
                session.clear()
            } else if (s.isPlaying && now - lastSave >= 5_000) {
                lastSave = now
                session.save(PlaybackSession(target.itemId, target.episodeId, s.media?.mediaId, s.media?.title.orEmpty(), s.positionMs, s.durationMs ?: 0, now))
            }
        }
    }

    override fun onCleared() {
        session.clear() // left normally (not killed): nothing to recover
        active.detach(controller.player) // before release: the media session lets go of it first
        controller.release()
    }

    companion object {
        /** Below this, "resume" is just the start: no "Start over" offer. */
        const val RESUME_MIN_MS = 10_000L

        val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

        /** Pure mapping (tested). */
        fun map(s: PlaybackState, t: TrackState, x: PlayerExtras, episodes: EpisodesPanel?): PlayerUiState = PlayerUiState(
            title = s.media?.title.orEmpty(),
            subtitle = x.subtitle,
            status = s.status, isPlaying = s.isPlaying, positionMs = s.positionMs, durationMs = s.durationMs, bufferedMs = s.bufferedPositionMs,
            speed = s.speed,
            quality = quality(s),
            error = x.openError ?: s.error?.let { e ->
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
            previous = x.previous, next = x.next,
            resumedFromMs = x.resumedFromMs,
            episodes = episodes,
        )

        fun link(e: Episode) = EpisodeLink(e.label, e.title, OpenTarget.Play(e.target))

        fun episodeSubtitle(e: Episode): String = listOfNotNull(e.label, e.title).joinToString(" · ")

        /** Display-ready quality from the stream's format (falls back to the file's declared size). */
        fun quality(s: PlaybackState): QualityInfo? {
            val m = s.media
            val f = s.format
            val w = s.videoWidth ?: m?.width
            val h = s.videoHeight ?: m?.height
            if (m == null && f == null && h == null) return null
            return QualityInfo(
                resolution = resolution(w, h),
                dimensions = if (w != null && h != null) "$w × $h" else null,
                videoCodec = f?.videoMimeType?.let(::codecName),
                frameRate = f?.frameRate?.let { if (it % 1f == 0f) "${it.toInt()} fps" else String.format(Locale.ROOT, "%.3f", it).trimEnd('0') + " fps" },
                bitrate = f?.videoBitrate?.let(::bitrateLabel),
                audio = listOfNotNull(f?.audioMimeType?.let(::codecName), f?.audioChannels?.let(::channelsLabel)).joinToString(" · ").ifEmpty { null },
                fileName = m?.fileName,
                sizeBytes = m?.sizeBytes?.takeIf { it > 0 },
            )
        }

        /** Height-based, but wide (cinemascope) frames count by width: 1920 × 800 is 1080p. */
        fun resolution(width: Int?, height: Int?): String? {
            val k = maxOf(height ?: 0, (width ?: 0) * 9 / 16)
            return when {
                k <= 0 -> null
                k >= 2000 -> "4K"
                k >= 1400 -> "1440p"
                k >= 1000 -> "1080p"
                k >= 700 -> "720p"
                k >= 560 -> "576p"
                k >= 440 -> "480p"
                else -> "${k}p"
            }
        }

        fun codecName(mime: String): String = when (mime.lowercase()) {
            "video/hevc" -> "HEVC"
            "video/avc" -> "H.264"
            "video/av01" -> "AV1"
            "video/x-vnd.on2.vp9" -> "VP9"
            "video/x-vnd.on2.vp8" -> "VP8"
            "video/mp4v-es" -> "MPEG-4"
            "video/mpeg2" -> "MPEG-2"
            "video/dolby-vision" -> "Dolby Vision"
            "audio/mp4a-latm" -> "AAC"
            "audio/ac3" -> "Dolby Digital"
            "audio/eac3" -> "Dolby Digital Plus"
            "audio/eac3-joc" -> "Dolby Atmos"
            "audio/ac4" -> "Dolby AC-4"
            "audio/true-hd" -> "Dolby TrueHD"
            "audio/vnd.dts", "audio/vnd.dts.hd", "audio/vnd.dts.uhd;profile=p2" -> "DTS"
            "audio/opus" -> "Opus"
            "audio/vorbis" -> "Vorbis"
            "audio/flac" -> "FLAC"
            "audio/mpeg" -> "MP3"
            "audio/raw" -> "PCM"
            else -> mime.substringAfter('/').uppercase()
        }

        fun channelsLabel(channels: Int): String = when (channels) {
            1 -> "Mono"
            2 -> "Stereo"
            6 -> "5.1"
            8 -> "7.1"
            else -> "$channels ch"
        }

        fun bitrateLabel(bps: Int): String =
            if (bps >= 1_000_000) String.format(Locale.ROOT, "%.1f Mbps", bps / 1_000_000.0) else "${bps / 1000} kbps"

        fun speedLabel(speed: Float): String =
            (if (speed % 1f == 0f) speed.toInt().toString() else speed.toString().trimEnd('0')) + "×"

        private fun label(a: AnalyzedTrack, index: Int): String {
            val language = a.language.code?.let(LanguageCatalog::displayName)
            return listOfNotNull(language, a.track.label?.takeIf { it != language }).joinToString(" · ").ifEmpty { "#${index + 1}" }
        }
    }
}
