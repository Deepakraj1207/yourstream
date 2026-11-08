package dev.yourstream.playback.media3

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.yourstream.core.common.Logger
import dev.yourstream.library.api.WatchHistoryRepository
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
import javax.inject.Singleton

/** Creates one controller per player screen; everything shared (streamer, cache) is a singleton. */
class TelegramPlaybackControllerFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val streamer: TelegramFileStreamer,
    private val resolver: MediaSourceResolver,
    private val progress: PlaybackProgressSink,
    private val telegram: TelegramManager,
) {
    fun create(config: PlaybackConfig = PlaybackConfig()) = TelegramPlaybackController(
        context = context,
        streamer = streamer,
        resolver = resolver,
        config = config,
        progressSink = progress,
        networkAvailable = telegram.connectionState.map { it == TelegramConnectionState.READY },
    )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PlaybackModule {

    @Binds abstract fun resolver(impl: LibraryMediaSourceResolver): MediaSourceResolver

    companion object {
        @Provides @Singleton
        fun streamCache(files: TelegramFileRepository) = StreamCache(files)

        @Provides @Singleton
        fun streamer(files: TelegramFileRepository, cache: StreamCache, logger: Logger): TelegramFileStreamer = TelegramFileStreamer(
            repo = files,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, t -> logger.e("Stream", "uncaught", t) }),
            config = StreamingConfig(),
            logger = logger,
            cache = cache,
        )

        @Provides
        fun progressSink(history: WatchHistoryRepository) = PlaybackProgressSink { target, mediaId, position, duration ->
            history.updateProgress(target, mediaId, position, duration)
        }
    }
}
