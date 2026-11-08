package dev.yourstream.playback.media3

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.yourstream.core.common.Logger
import dev.yourstream.library.api.EpisodeRepository
import dev.yourstream.library.api.WatchHistoryRepository
import dev.yourstream.playback.adaptive.AdaptiveStreaming
import dev.yourstream.playback.adaptive.BufferConfig
import dev.yourstream.playback.adaptive.BufferManager
import dev.yourstream.playback.adaptive.DefaultNetworkMonitor
import dev.yourstream.playback.adaptive.DownloadGovernor
import dev.yourstream.playback.adaptive.PrefetchConfig
import dev.yourstream.playback.adaptive.PrefetchManager
import dev.yourstream.playback.adaptive.TransferLimits
import dev.yourstream.playback.stream.StreamCache
import dev.yourstream.playback.stream.StreamingConfig
import dev.yourstream.playback.stream.TelegramFileStreamer
import dev.yourstream.telegram.api.TelegramFileRepository
import dev.yourstream.telegram.api.TelegramManager
import dev.yourstream.telegram.api.model.TelegramConnectionState
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PlaybackScope

/**
 * Playback configuration (app module provides it from build configuration):
 * download limits, buffering tiers, prefetch behaviour, and whether adaptive streaming is on.
 */
data class PlaybackSettings(
    val adaptive: Boolean = true,
    val limits: TransferLimits = TransferLimits(),
    val buffer: BufferConfig = BufferConfig(),
    val prefetch: PrefetchConfig = PrefetchConfig(),
)

/** Creates one controller per player screen; everything shared (streamer, governor, monitor, cache) is a singleton. */
class TelegramPlaybackControllerFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val streamer: TelegramFileStreamer,
    private val resolver: MediaSourceResolver,
    private val progress: PlaybackProgressSink,
    private val telegram: TelegramManager,
    private val governor: DownloadGovernor,
    private val monitor: DefaultNetworkMonitor,
    private val episodes: EpisodeRepository,
    private val settings: PlaybackSettings,
) {
    fun create(config: PlaybackConfig = PlaybackConfig()) = TelegramPlaybackController(
        context = context,
        streamer = streamer,
        resolver = resolver,
        config = config,
        progressSink = progress,
        networkAvailable = telegram.connectionState.map { it == TelegramConnectionState.READY },
        adaptive = if (!settings.adaptive) null else { scope ->
            val next = NextEpisodeProvider(episodes, resolver)
            val prefetch = PrefetchManager(governor, next, scope, settings.prefetch)
            AdaptivePlayback(AdaptiveStreaming(streamer, governor, monitor, BufferManager(deviceProfile(context), settings.buffer), scope, prefetch), next)
        },
    )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PlaybackModule {

    @Binds abstract fun resolver(impl: LibraryMediaSourceResolver): MediaSourceResolver

    companion object {
        @Provides @Singleton
        fun streamCache(files: TelegramFileRepository) = StreamCache(files)

        @Provides @Singleton @PlaybackScope
        fun playbackScope(logger: Logger): CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, t -> logger.e("Playback", "uncaught", t) })

        /** Every playback download goes through the governor: bounded concurrency, playback first. */
        @Provides @Singleton
        fun governor(files: TelegramFileRepository, @PlaybackScope scope: CoroutineScope, settings: PlaybackSettings) =
            DownloadGovernor(files, scope, settings.limits)

        @Provides @Singleton
        fun networkMonitor(@ApplicationContext context: Context, telegram: TelegramManager, @PlaybackScope scope: CoroutineScope) =
            DefaultNetworkMonitor(
                connectivity = AndroidConnectivity(context).state,
                telegramConnected = telegram.connectionState.map { it == TelegramConnectionState.READY },
                scope = scope,
                clock = System::currentTimeMillis,
            )

        @Provides @Singleton
        fun streamer(governor: DownloadGovernor, cache: StreamCache, logger: Logger, monitor: DefaultNetworkMonitor, @PlaybackScope scope: CoroutineScope): TelegramFileStreamer =
            TelegramFileStreamer(repo = governor, scope = scope, config = StreamingConfig(), logger = logger, cache = cache)
                .also { AdaptiveStreaming.connect(it, monitor) }

        @Provides
        fun progressSink(history: WatchHistoryRepository) = PlaybackProgressSink { target, mediaId, position, duration ->
            history.updateProgress(target, mediaId, position, duration)
        }
    }
}
