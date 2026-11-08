package dev.yourstream.playback.adaptive.bench

import dev.yourstream.playback.adaptive.BufferPolicy
import dev.yourstream.playback.adaptive.PlaybackPhase
import dev.yourstream.playback.adaptive.PlaybackSample
import dev.yourstream.playback.stream.RangeSet
import dev.yourstream.playback.stream.StreamHints
import dev.yourstream.playback.stream.TelegramFileStream
import dev.yourstream.playback.stream.TelegramFileStreamer
import dev.yourstream.playback.stream.TelegramStreamException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A player model that consumes a constant-bitrate file in (scaled) real time, with ExoPlayer's
 * buffering rules: load while below minBuffer, stop at maxBuffer / byte cap, start when
 * startPlaybackMs is buffered, resume after a stall at resumeAfterRebufferMs, retry load errors
 * with backoff (1, 2, 4, 8, 8 s; then a "player error" the user sees, followed by a retry).
 *
 * All durations here are media time; [clock] is the scaled clock (real time × K).
 */
class VirtualPlayer(
    private val streamer: TelegramFileStreamer,
    val fileId: Int,
    private val sizeBytes: Long,
    val durationMs: Long,
    private val clock: TimeScale,
    private val scope: CoroutineScope,
    private val policy: () -> BufferPolicy,
    private val onSample: (PlaybackSample) -> Unit = {},
    private val played: RangeSet,
) {
    val bytesPerSecond = sizeBytes * 1000 / durationMs
    private val lock = Any()
    var phase = PlaybackPhase.IDLE; private set
    private var position = 0.0
    private var bufferedEnd = 0L
    private var loading = true
    private var required = 0L
    private var phaseStart = 0L
    private var paused = false
    private var loader: Job? = null
    private var ticker: Job? = null
    private var pendingSeekStart: Long? = null

    // metrics (media ms)
    var startupMs: Long? = null; private set
    val seekLatencies = mutableListOf<Long>()
    var rebuffers = 0; private set
    var rebufferMs = 0L; private set
    var loadErrors = 0; private set
    var playerErrors = 0; private set
    var peakBufferBytes = 0L; private set
    var playedMs = 0L; private set

    val positionMs: Long get() = synchronized(lock) { position.toLong() }
    val isEnded: Boolean get() = phase == PlaybackPhase.ENDED

    fun start(atMs: Long = 0) {
        synchronized(lock) {
            position = atMs.toDouble(); bufferedEnd = atMs
            phase = PlaybackPhase.STARTING; phaseStart = clock.nowMs(); required = policy().startPlaybackMs
        }
        startLoader()
        ticker = scope.launch { tickLoop() }
    }

    fun seek(toMs: Long) {
        loader?.cancel()
        synchronized(lock) {
            position = toMs.coerceIn(0, durationMs - 1).toDouble(); bufferedEnd = position.toLong()
            if (phase == PlaybackPhase.BUFFERING) rebufferMs += clock.nowMs() - phaseStart
            phase = PlaybackPhase.SEEKING; phaseStart = clock.nowMs(); pendingSeekStart = phaseStart
            required = policy().startPlaybackMs; loading = true
        }
        startLoader()
    }

    fun pause() = synchronized(lock) { paused = true }
    fun resume() = synchronized(lock) { paused = false }

    fun stop() {
        loader?.cancel(); ticker?.cancel()
    }

    private fun startLoader() {
        val from = synchronized(lock) { bufferedEnd }
        loader = scope.launch { loadLoop(from) }
    }

    private fun byteOf(ms: Long) = (ms * bytesPerSecond / 1000).coerceAtMost(sizeBytes)

    private suspend fun loadLoop(fromMs: Long) {
        var stream: TelegramFileStream? = null
        var failures = 0
        val buf = ByteArray(64 * 1024)
        try {
            while (scope.isActive) {
                val p = policy()
                val (shouldLoad, end) = synchronized(lock) {
                    val ahead = bufferedEnd - position.toLong()
                    val aheadBytes = ahead * bytesPerSecond / 1000
                    if (ahead >= p.maxBufferMs || aheadBytes >= p.targetBufferBytes || bufferedEnd >= durationMs) loading = false
                    else if (ahead < p.minBufferMs) loading = true
                    loading to bufferedEnd
                }
                if (!shouldLoad) { delay(clock.realMs(50)); continue }
                try {
                    val s = stream ?: streamer.open(fileId, byteOf(end), StreamHints(sizeBytes, durationMs)).also { stream = it }
                    val n = s.read(buf, 0, buf.size)
                    if (n < 0) { synchronized(lock) { bufferedEnd = durationMs }; continue }
                    failures = 0
                    synchronized(lock) {
                        bufferedEnd = minOf(durationMs, bufferedEnd + n * 1000 / bytesPerSecond)
                        peakBufferBytes = maxOf(peakBufferBytes, (bufferedEnd - position.toLong()) * bytesPerSecond / 1000)
                    }
                } catch (e: TelegramStreamException) {
                    stream?.close(); stream = null
                    loadErrors++; failures++
                    if (failures > 5) { playerErrors++; failures = 0 } // error shown, user / auto retry
                    delay(clock.realMs(minOf(8_000L, 1_000L shl (failures - 1).coerceAtLeast(0))))
                }
            }
        } catch (_: CancellationException) {
        } finally { stream?.close() }
    }

    private suspend fun tickLoop() {
        var last = clock.nowMs()
        var lastSample = 0L
        while (scope.isActive) {
            delay(clock.realMs(100).coerceAtLeast(2))
            val now = clock.nowMs()
            val dt = now - last
            last = now
            val p = policy()
            synchronized(lock) {
                val ahead = bufferedEnd - position.toLong()
                when (phase) {
                    PlaybackPhase.STARTING, PlaybackPhase.SEEKING, PlaybackPhase.BUFFERING ->
                        // Like LoadControl.shouldStartPlayback: evaluated against the policy in force now.
                        if (ahead >= (if (phase == PlaybackPhase.BUFFERING) p.resumeAfterRebufferMs else p.startPlaybackMs) || bufferedEnd >= durationMs) {
                            when (phase) {
                                PlaybackPhase.STARTING -> startupMs = now - phaseStart
                                PlaybackPhase.SEEKING -> { seekLatencies += now - phaseStart; pendingSeekStart = null }
                                else -> rebufferMs += now - phaseStart
                            }
                            phase = PlaybackPhase.PLAYING
                        }
                    PlaybackPhase.PLAYING -> if (!paused) {
                        val step = minOf(dt.toDouble(), ahead.toDouble().coerceAtLeast(0.0))
                        val before = position
                        position += step
                        playedMs += step.toLong()
                        played.add(byteOf(before.toLong()), byteOf(position.toLong()) - byteOf(before.toLong()))
                        if (position >= durationMs - 1) phase = PlaybackPhase.ENDED
                        else if (step < dt) {
                            phase = PlaybackPhase.BUFFERING; phaseStart = now; rebuffers++
                            required = p.resumeAfterRebufferMs
                        }
                    }
                    else -> Unit
                }
            }
            if (now - lastSample >= 500) {
                lastSample = now
                val s = synchronized(lock) {
                    val ph = if (phase == PlaybackPhase.PLAYING && paused) PlaybackPhase.PAUSED else phase
                    PlaybackSample(now, ph, position.toLong(), bufferedEnd, durationMs, bytesPerSecond, loading = loading)
                }
                onSample(s)
            }
        }
    }
}

/** Real time × [factor]: benchmarks run [factor] times faster than the media they model. */
class TimeScale(val factor: Int) {
    private val origin = System.nanoTime()
    fun nowMs(): Long = (System.nanoTime() - origin) / 1_000_000 * factor
    fun nanos(): Long = (System.nanoTime() - origin) * factor
    /** Real milliseconds for [mediaMs] of media time. */
    fun realMs(mediaMs: Long): Long = (mediaMs / factor).coerceAtLeast(1)
}
