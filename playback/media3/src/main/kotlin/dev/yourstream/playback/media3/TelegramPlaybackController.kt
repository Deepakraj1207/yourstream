package dev.yourstream.playback.media3

import android.content.Context
import android.os.Debug
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaSource
import dev.yourstream.core.common.AppResult
import dev.yourstream.library.api.PlaybackTarget
import dev.yourstream.playback.adaptive.AdaptiveStreaming
import dev.yourstream.playback.tracks.AnalyzedTrack
import dev.yourstream.playback.tracks.FileContext
import dev.yourstream.playback.tracks.PreferenceScope
import dev.yourstream.playback.tracks.TrackScope
import dev.yourstream.playback.tracks.TrackSelection
import dev.yourstream.playback.tracks.TrackSession
import dev.yourstream.playback.tracks.TrackType
import dev.yourstream.playback.adaptive.PlaybackPhase
import dev.yourstream.playback.adaptive.PlaybackSample
import dev.yourstream.playback.adaptive.SeekManager
import dev.yourstream.playback.adaptive.SeekTarget
import dev.yourstream.playback.stream.TelegramFileStreamer
import dev.yourstream.playback.stream.TelegramStreamException
import dev.yourstream.playback.stream.Ticker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Creates the ExoPlayer (tests substitute a Robolectric-friendly one). */
@OptIn(UnstableApi::class)
fun interface PlayerFactory {
    fun create(context: Context, mediaSourceFactory: MediaSource.Factory, loadControl: LoadControl): ExoPlayer
}

@OptIn(UnstableApi::class)
object DefaultPlayerFactory : PlayerFactory {
    override fun create(context: Context, mediaSourceFactory: MediaSource.Factory, loadControl: LoadControl): ExoPlayer =
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .setHandleAudioBecomingNoisy(true)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .build()
}

/**
 * Plays Telegram media with ExoPlayer and turns its callbacks into one observable
 * [PlaybackState] plus [PlaybackMetrics].
 *
 *   resolve (library → Telegram file) → MediaItem(tgfile://…) → TelegramMediaSourceFactory
 *   → ProgressiveMediaSource → TelegramMediaDataSource → TelegramFileStreamer → Telegram
 *
 * Must be used from the main thread (ExoPlayer's application looper). [release] when done:
 * it stops the Telegram download of the file and lets the stream cache trim.
 */
@OptIn(UnstableApi::class)
class TelegramPlaybackController(
    context: Context,
    private val streamer: TelegramFileStreamer,
    private val resolver: MediaSourceResolver? = null,
    private val config: PlaybackConfig = PlaybackConfig(),
    private val progressSink: PlaybackProgressSink? = null,
    networkAvailable: Flow<Boolean>? = null,
    private val ticker: Ticker = Ticker.System,
    playerFactory: PlayerFactory = DefaultPlayerFactory,
    /** For work that must outlive the controller (cancelling the download, saving progress). */
    private val backgroundScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /**
     * Phase 8 adaptive streaming (buffer manager, network monitor, seek manager, prefetch), built
     * with the controller's scope. Null = Phase 7 fixed buffering.
     */
    adaptive: ((CoroutineScope) -> AdaptivePlayback)? = null,
    /** Phase 9: preference-based audio/subtitle selection, remembered choices, external subtitles. */
    private val trackSupport: TrackSupport? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** The adaptive parts in use, if any (for the test player's diagnostics). */
    val adaptive: AdaptivePlayback? = adaptive?.invoke(scope)

    val mediaSourceFactory = TelegramMediaSourceFactory.withDefaults(
        streamer, context, TelegramLoadErrorHandlingPolicy(config.loadRetries, config.retryBaseDelayMs, config.retryMaxDelayMs),
    )
    val loadControl: LoadControl = this.adaptive?.let { a -> AdaptiveLoadControl({ a.streaming.policy }) } ?: DefaultLoadControl.Builder()
        .setBufferDurationsMs(config.minBufferMs, config.maxBufferMs, config.bufferForPlaybackMs, config.bufferForPlaybackAfterRebufferMs)
        .setTargetBufferBytes(config.targetBufferBytes)
        .setPrioritizeTimeOverSizeThresholds(false)
        .build()

    /** The underlying player, for a PlayerView in the test player. */
    val player: ExoPlayer = playerFactory.create(context, mediaSourceFactory, loadControl)

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _metrics = MutableStateFlow(PlaybackMetrics())
    val metrics: StateFlow<PlaybackMetrics> = _metrics.asStateFlow()

    private var media: PlayableMedia? = null
    private var prepareStartNanos: Long? = null
    private var everReady = false
    private var pendingSeekStartNanos: Long? = null
    private var rebufferStartNanos: Long? = null
    private var autoRetries = 0
    private var recoveryRetries = 0
    private var versionSwitches = 0
    private val failedMediaIds = mutableSetOf<Long>()
    private var recovery: String? = null
    private var recoveryJob: Job? = null
    private var networkUp: Boolean? = null
    /** Errors and the recovery chosen for them, newest last (diagnostics). */
    private val _recoveryLog = MutableStateFlow<List<String>>(emptyList())
    val recoveryLog: StateFlow<List<String>> = _recoveryLog.asStateFlow()
    private var lastProgressSaveNanos = 0L
    private var released = false
    private var samplerJob: Job? = null

    private val seekManager: SeekManager? = this.adaptive?.let { a ->
        SeekManager(
            target = object : SeekTarget {
                override fun applySeek(positionMs: Long) = rawSeek(positionMs)
                override fun setFastSeek(enabled: Boolean) = setFastSeeking(enabled)
            },
            scope = scope,
            clock = { System.nanoTime() / 1_000_000 },
            governor = a.streaming.governor,
            onFocus = { media?.let { streamer.focus(it.fileId).size } ?: 0 },
        )
    }
    private var trackSession: TrackSession? = null
    private var tracksKey: String? = null
    private var mappedTracks: Media3Tracks.Mapped? = null
    private val _tracks = MutableStateFlow(TrackState())
    /** Detected tracks and the current choice with its reason (for a track menu / diagnostics). */
    val tracks: StateFlow<TrackState> = _tracks.asStateFlow()

    private var droppedFrames = 0L
    private var droppedAtLastSample = 0L
    private var lastSampleNanos = 0L
    private var fastSeekByPolicy = false
    private var scrubbing = false

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val now = ticker.nanos()
            when (playbackState) {
                Player.STATE_READY -> {
                    if (!everReady) {
                        everReady = true
                        prepareStartNanos?.let { start -> updateMetrics { it.copy(startupMs = ms(now - start)) } }
                    }
                    completeSeek(now)
                    rebufferStartNanos?.let { start ->
                        rebufferStartNanos = null
                        updateMetrics { it.copy(rebufferMs = it.rebufferMs + ms(now - start)) }
                    }
                    autoRetries = 0
                    recoveryRetries = 0
                    recovery = null
                }
                Player.STATE_BUFFERING -> if (everReady && pendingSeekStartNanos == null && rebufferStartNanos == null) {
                    rebufferStartNanos = now
                    updateMetrics { it.copy(rebufferCount = it.rebufferCount + 1) }
                }
                Player.STATE_ENDED -> saveProgress(force = true)
                Player.STATE_IDLE -> Unit
            }
            publish()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) saveProgress(force = true)
            publish()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = publish()

        override fun onPlayerError(error: PlaybackException) {
            pendingSeekStartNanos = null
            rebufferStartNanos = null
            recover(error.toPlaybackError())
            publish()
        }

        override fun onRenderedFirstFrame() {
            val start = prepareStartNanos ?: return
            if (_metrics.value.firstFrameMs == null) updateMetrics { it.copy(firstFrameMs = ms(ticker.nanos() - start)) }
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            // Seeks issued through the player directly (e.g. PlayerView controls) are measured too.
            if (reason == Player.DISCONTINUITY_REASON_SEEK && pendingSeekStartNanos == null) startSeek()
            publish()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) = publish()

        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) = handleTracks(tracks)
    }

    init {
        player.addListener(listener)
        player.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onDroppedVideoFrames(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, count: Int, elapsedMs: Long) {
                droppedFrames += count
            }
        })
        networkAvailable?.let { flow ->
            scope.launch {
                flow.distinctUntilChanged().collect { available ->
                    networkUp = available
                    val error = _state.value.error
                    if (available && config.autoRetryOnReconnect && error?.retryable == true && autoRetries < config.maxAutoRetries) {
                        autoRetries++
                        retry()
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ commands

    /** Resolves a library title and starts it. Availability problems come back as errors, without touching the player. */
    suspend fun open(target: PlaybackTarget, mediaId: Long? = null, startPositionMs: Long? = null, playWhenReady: Boolean = true): AppResult<PlayableMedia> {
        resetRecovery()
        val r = (resolver ?: error("no resolver")).resolve(target, mediaId)
        when (r) {
            is AppResult.Ok -> play(prepareTracks(r.value), startPositionMs, playWhenReady)
            is AppResult.Err -> _state.value = _state.value.copy(
                status = PlaybackStatus.ERROR,
                error = PlaybackError(PlaybackErrorKind.FILE_UNAVAILABLE, r.error.message, retryable = r.error !is dev.yourstream.core.common.AppError.NotFound),
            )
        }
        return r
    }

    fun play(media: PlayableMedia, startPositionMs: Long? = null, playWhenReady: Boolean = true) {
        check(!released) { "released" }
        val previous = this.media
        if (previous != null && previous.fileId != media.fileId) releaseFile(previous)
        this.media = media
        trackSession = trackSupport?.let { TrackSession(it.store, trackScopeOf(media), media.trackContext ?: FileContext(fileName = media.fileName)) }
        tracksKey = null; mappedTracks = null; _tracks.value = TrackState()
        this.adaptive?.let { a ->
            a.prefetch?.onPlaying(media.fileId)
            a.prefetch?.reset()
            a.next?.current = media
        }
        everReady = false
        pendingSeekStartNanos = null
        rebufferStartNanos = null
        _metrics.value = PlaybackMetrics()
        prepareStartNanos = ticker.nanos()
        player.setMediaItem(media.mediaItem(), startPositionMs ?: 0L)
        player.playWhenReady = playWhenReady
        player.prepare()
        startSampler()
        publish()
    }

    // ------------------------------------------------------------------ tracks

    /**
     * Before play: external subtitle files (the user's Telegram subtitle files for this title),
     * file context for language detection, and ExoPlayer's initial language preferences.
     */
    private suspend fun prepareTracks(media: PlayableMedia): PlayableMedia {
        val t = trackSupport ?: return media
        Media3Tracks.presetLanguages(player, t.store.preferences())
        val context = runCatching { t.context?.context(media) }.getOrNull()
        val subtitles = media.target?.let { target -> runCatching { t.subtitles?.load(target, media.mediaId) }.getOrNull() }.orEmpty()
        return media.copy(subtitles = media.subtitles + subtitles, trackContext = context ?: media.trackContext)
    }

    private fun trackScopeOf(media: PlayableMedia): TrackScope? = media.target?.let {
        if (it.episodeId != null) TrackScope(it.itemId, PreferenceScope.SERIES) else TrackScope(it.itemId, PreferenceScope.MOVIE)
    }

    /** New track list from the player: detect languages, select by preference / memory, apply. Once per distinct list. */
    private fun handleTracks(t: androidx.media3.common.Tracks) {
        val session = trackSession ?: return
        val m = media ?: return
        val mapped = Media3Tracks.candidates(t, m.subtitles)
        if (mapped.candidates.isEmpty()) return
        mappedTracks = mapped
        val key = mapped.candidates.joinToString { it.id + "/" + it.supported }
        if (key == tracksKey) return
        tracksKey = key
        scope.launch {
            val selection = session.onTracks(mapped.candidates)
            if (released || media !== m) return@launch
            Media3Tracks.apply(player, mapped, selection)
            publishTracks(session)
            trackSupport?.onAnalyzed?.let { cb -> backgroundScope.launch { runCatching { cb(m, session.tracks) } } }
        }
    }

    /** User picks an audio track: applied now and remembered for the movie / the whole series. */
    fun selectAudio(trackId: String) {
        val session = trackSession ?: return
        val mapped = mappedTracks ?: return
        scope.launch {
            session.userSelectedAudio(trackId) ?: return@launch
            Media3Tracks.apply(player, mapped, session.selection!!)
            publishTracks(session)
        }
    }

    /** User picks a subtitle track, or null for off: applied and remembered. */
    fun selectSubtitle(trackId: String?) {
        val session = trackSession ?: return
        val mapped = mappedTracks ?: return
        scope.launch {
            if (trackId != null && session.userSelectedSubtitle(trackId) == null) return@launch
            if (trackId == null) session.userSelectedSubtitle(null)
            Media3Tracks.apply(player, mapped, session.selection!!)
            publishTracks(session)
        }
    }

    private fun publishTracks(session: TrackSession) {
        _tracks.value = TrackState(
            audio = session.tracks.filter { it.track.type == TrackType.AUDIO },
            subtitles = session.tracks.filter { it.track.type == TrackType.SUBTITLE },
            selection = session.selection,
        )
    }

    fun resume() { player.play() }
    fun pause() { player.pause() }
    fun togglePlayPause() { if (player.playWhenReady) pause() else resume() }

    /** User seek. With adaptive streaming, bursts are coalesced and background transfers make way (SeekManager). */
    fun seekTo(positionMs: Long) {
        seekManager?.seekTo(positionMs) ?: rawSeek(positionMs)
    }

    /** Seek-bar drag start / end: key-frame previews at a bounded rate, then one exact seek. */
    fun beginScrub() { scrubbing = true; seekManager?.beginScrub() ?: setFastSeeking(true) }

    fun endScrub(positionMs: Long) {
        scrubbing = false
        seekManager?.endScrub(positionMs) ?: run { setFastSeeking(false); rawSeek(positionMs) }
    }

    private fun rawSeek(positionMs: Long) {
        startSeek()
        player.seekTo(positionMs.coerceAtLeast(0))
        publish()
    }

    fun seekBy(deltaMs: Long) = seekTo(player.currentPosition + deltaMs)

    /**
     * Fast seeking jumps to the nearest key frame instead of decoding up to the exact position:
     * less data and decoding per seek (scrubbing); exact seeking is the default.
     */
    fun setFastSeeking(enabled: Boolean) = player.setSeekParameters(if (enabled) SeekParameters.CLOSEST_SYNC else SeekParameters.EXACT)

    private fun resetRecovery() {
        recoveryJob?.cancel(); recoveryJob = null
        recoveryRetries = 0; versionSwitches = 0; failedMediaIds.clear(); recovery = null
    }

    /** Applies [PlaybackConfig.recovery] to an error: retry later, wait for the network, or switch to another version. */
    private fun recover(error: PlaybackError) {
        val policy = config.recovery ?: return
        val m = media ?: return
        val canSwitch = resolver != null && m.target != null && m.mediaId != null
        val action = policy.decide(error, recoveryRetries, versionSwitches, networkUp, canSwitch)
        _recoveryLog.value = (_recoveryLog.value + "${error.kind}: ${error.message.take(120)} → $action").takeLast(20)
        recoveryJob?.cancel()
        when (action) {
            is RecoveryAction.Retry -> {
                recoveryRetries = action.attempt
                recovery = "retry ${action.attempt} in ${action.delayMs / 1000} s"
                recoveryJob = scope.launch(Dispatchers.Main) { delay(action.delayMs); if (!released) retry() }
            }
            RecoveryAction.WaitForNetwork -> recovery = "waiting for network"
            RecoveryAction.SwitchVersion -> {
                versionSwitches++
                m.mediaId?.let { failedMediaIds += it }
                recovery = "switching to another version"
                val position = player.currentPosition.coerceAtLeast(0)
                val playWhenReady = player.playWhenReady
                // Posted, never run inside the player's own error callback.
                recoveryJob = scope.launch(Dispatchers.Main) {
                    when (val r = resolver!!.resolveExcluding(m.target!!, failedMediaIds)) {
                        is AppResult.Ok -> if (!released) {
                            updateMetrics { it.copy(retries = it.retries + 1) }
                            val switches = versionSwitches; val failed = failedMediaIds.toSet()
                            play(prepareTracks(r.value), position, playWhenReady)
                            versionSwitches = switches; failedMediaIds += failed
                            recovery = "switched to ${r.value.fileName}"
                            publish()
                        }
                        is AppResult.Err -> { recovery = null; _recoveryLog.value = _recoveryLog.value + "no other version: ${r.error.message}"; publish() }
                    }
                }
            }
            is RecoveryAction.GiveUp -> recovery = null
        }
    }

    /** Re-prepares after an error; playback continues from the current position. */
    fun retry() {
        if (player.playerError == null && _state.value.status != PlaybackStatus.ERROR) return
        val m = media ?: return
        updateMetrics { it.copy(retries = it.retries + 1) }
        if (player.currentMediaItem == null) play(m) else player.prepare()
        publish()
    }

    /** Stops playback and the Telegram download of the current file. */
    fun stop() {
        saveProgress(force = true)
        player.stop()
        media?.let(::releaseFile)
        samplerJob?.cancel()
        publish()
    }

    fun release() {
        if (released) return
        this.adaptive?.prefetch?.cancel()
        saveProgress(force = true)
        released = true
        media?.let(::releaseFile)
        player.removeListener(listener)
        player.release()
        scope.cancel()
    }

    // ------------------------------------------------------------------ state

    /** Current state straight from the player (the flow is updated on events and every sample). */
    fun snapshot(): PlaybackState {
        val error = player.playerError?.toPlaybackError() ?: _state.value.error.takeIf { player.playerError == null && _state.value.status == PlaybackStatus.ERROR && player.currentMediaItem == null }
        val status = when {
            error != null -> PlaybackStatus.ERROR
            else -> when (player.playbackState) {
                Player.STATE_IDLE -> PlaybackStatus.IDLE
                Player.STATE_BUFFERING -> if (everReady) PlaybackStatus.BUFFERING else PlaybackStatus.PREPARING
                Player.STATE_READY -> PlaybackStatus.READY
                else -> PlaybackStatus.ENDED
            }
        }
        val duration = player.duration.takeIf { it != C.TIME_UNSET }
        val video = player.videoSize
        val format = player.videoFormat
        return PlaybackState(
            status = status,
            playWhenReady = player.playWhenReady,
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition,
            durationMs = duration,
            bufferedPositionMs = player.bufferedPosition,
            media = media,
            error = error,
            // The stream's format is known at READY; the rendered size only after the first frame.
            videoWidth = video.width.takeIf { it > 0 } ?: format?.width?.takeIf { it > 0 },
            videoHeight = video.height.takeIf { it > 0 } ?: format?.height?.takeIf { it > 0 },
            seeking = pendingSeekStartNanos != null,
            recovery = recovery,
        )
    }

    private fun publish() {
        if (!released) _state.value = snapshot()
    }

    private fun startSampler() {
        samplerJob?.cancel()
        samplerJob = scope.launch {
            while (isActive) {
                sample()
                delay(config.metricsSampleIntervalMs)
            }
        }
    }

    /** Refreshes position/buffer, memory peaks, stream counters; saves progress periodically. */
    fun sample() {
        if (released) return
        publish()
        if (pendingSeekStartNanos != null && player.playbackState == Player.STATE_READY) completeSeek(ticker.nanos())
        val runtime = Runtime.getRuntime()
        val java = runtime.totalMemory() - runtime.freeMemory()
        val native = Debug.getNativeHeapAllocatedSize()
        val stats = media?.let { streamer.stats(it.fileId) }
        updateMetrics {
            it.copy(
                peakJavaHeapBytes = maxOf(it.peakJavaHeapBytes, java),
                peakNativeHeapBytes = maxOf(it.peakNativeHeapBytes, native),
                bytesServed = stats?.bytesServed ?: it.bytesServed,
                downloadedBytes = stats?.downloadedBytes ?: it.downloadedBytes,
                downloadRequests = stats?.downloadRequests?.size ?: it.downloadRequests,
                dataWaitMs = stats?.dataWaitMs ?: it.dataWaitMs,
            )
        }
        if (player.isPlaying) saveProgress(force = false)
        adaptiveSample()
    }

    /** Feeds the adaptive engine; its policy drives the load control (read live) and the download window. */
    private fun adaptiveSample() {
        val a = this.adaptive ?: return
        val m = media ?: return
        val now = ticker.nanos()
        val elapsedSec = if (lastSampleNanos == 0L) 0.0 else (now - lastSampleNanos) / 1e9
        val dropRate = if (elapsedSec > 0) (droppedFrames - droppedAtLastSample) / elapsedSec else 0.0
        lastSampleNanos = now; droppedAtLastSample = droppedFrames
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        val bitrate = player.videoFormat?.bitrate?.takeIf { it > 0 }?.let { it / 8L }
            ?: duration?.let { m.sizeBytes * 1000 / it } ?: m.durationMs?.takeIf { it > 0 }?.let { m.sizeBytes * 1000 / it }
        val phase = when {
            player.playerError != null || player.playbackState == Player.STATE_IDLE -> PlaybackPhase.IDLE
            player.playbackState == Player.STATE_ENDED -> PlaybackPhase.ENDED
            pendingSeekStartNanos != null -> PlaybackPhase.SEEKING
            player.playbackState == Player.STATE_BUFFERING -> if (everReady) PlaybackPhase.BUFFERING else PlaybackPhase.STARTING
            player.playWhenReady -> PlaybackPhase.PLAYING
            else -> PlaybackPhase.PAUSED
        }
        val policy = a.streaming.onSample(
            PlaybackSample(now / 1_000_000, phase, player.currentPosition, player.bufferedPosition, duration, bitrate, dropRate, player.isLoading),
            m.fileId,
        )
        if (!scrubbing && policy.preferFastSeek != fastSeekByPolicy) {
            fastSeekByPolicy = policy.preferFastSeek
            setFastSeeking(policy.preferFastSeek)
        }
    }

    private fun startSeek() {
        pendingSeekStartNanos = ticker.nanos()
        rebufferStartNanos = null
        updateMetrics { it.copy(seekCount = it.seekCount + 1) }
    }

    private fun completeSeek(now: Long) {
        val start = pendingSeekStartNanos ?: return
        pendingSeekStartNanos = null
        seekManager?.onSeekCompleted()
        updateMetrics { it.copy(seekLatenciesMs = it.seekLatenciesMs + ms(now - start)) }
    }

    private fun saveProgress(force: Boolean) {
        val sink = progressSink ?: return
        val m = media ?: return
        val target = m.target ?: return
        val now = ticker.nanos()
        if (!force && now - lastProgressSaveNanos < config.progressSaveIntervalMs * 1_000_000) return
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        val position = if (player.playbackState == Player.STATE_ENDED) duration else player.currentPosition
        lastProgressSaveNanos = now
        backgroundScope.launch { sink.save(target, m.mediaId, position, duration) }
    }

    private fun releaseFile(m: PlayableMedia) {
        backgroundScope.launch { streamer.release(m.fileId, cancelDownload = true) }
    }

    private inline fun updateMetrics(block: (PlaybackMetrics) -> PlaybackMetrics) { _metrics.value = block(_metrics.value) }

    private fun ms(nanos: Long) = nanos / 1_000_000
}

/** The tracks of the current file as analysed, and what is selected and why. */
data class TrackState(
    val audio: List<AnalyzedTrack> = emptyList(),
    val subtitles: List<AnalyzedTrack> = emptyList(),
    val selection: TrackSelection? = null,
)

/** Media3 error → what the app shows and whether a retry makes sense. */
fun PlaybackException.toPlaybackError(): PlaybackError {
    telegramCause()?.let { tg ->
        return when (tg) {
            is TelegramStreamException.Unavailable, is TelegramStreamException.OutOfRange ->
                PlaybackError(PlaybackErrorKind.FILE_UNAVAILABLE, tg.message ?: "File unavailable", retryable = false, code = errorCode)
            else -> PlaybackError(PlaybackErrorKind.NETWORK, tg.message ?: "Network error", retryable = tg.retryable, code = errorCode)
        }
    }
    return when (errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
            PlaybackError(PlaybackErrorKind.FILE_UNAVAILABLE, message ?: errorCodeName, false, errorCode)
        in 2000..2999 -> PlaybackError(PlaybackErrorKind.NETWORK, message ?: errorCodeName, true, errorCode)
        in 3000..3999 -> PlaybackError(PlaybackErrorKind.UNSUPPORTED_FORMAT, message ?: errorCodeName, false, errorCode)
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
            PlaybackError(PlaybackErrorKind.UNSUPPORTED_FORMAT, message ?: errorCodeName, false, errorCode)
        in 4000..4999 -> PlaybackError(PlaybackErrorKind.DECODER, message ?: errorCodeName, false, errorCode)
        else -> PlaybackError(PlaybackErrorKind.UNKNOWN, message ?: errorCodeName, false, errorCode)
    }
}

/** The adaptive parts used by one controller. */
class AdaptivePlayback(
    val streaming: AdaptiveStreaming,
    /** Tells the prefetcher what plays now (it looks up the next episode from that). */
    val next: NextEpisodeProvider? = null,
) {
    val prefetch get() = streaming.prefetch
}
