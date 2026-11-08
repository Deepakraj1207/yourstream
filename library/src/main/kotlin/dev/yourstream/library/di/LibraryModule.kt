package dev.yourstream.library.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.yourstream.core.common.Logger
import dev.yourstream.library.api.AudioTrackRepository
import dev.yourstream.library.api.EpisodeRepository
import dev.yourstream.library.api.MediaRepository
import dev.yourstream.library.api.MovieRepository
import dev.yourstream.library.api.SeriesRepository
import dev.yourstream.library.api.SubtitleRepository
import dev.yourstream.library.api.WatchHistoryRepository
import dev.yourstream.library.data.LibraryScope
import dev.yourstream.library.data.LibrarySyncTrigger
import dev.yourstream.library.data.RoomAudioTrackRepository
import dev.yourstream.library.data.RoomEpisodeRepository
import dev.yourstream.library.data.RoomMediaRepository
import dev.yourstream.library.data.RoomMovieRepository
import dev.yourstream.library.data.RoomSeriesRepository
import dev.yourstream.library.data.RoomSubtitleRepository
import dev.yourstream.library.data.RoomWatchHistoryRepository
import dev.yourstream.media.api.MediaIndexListener
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class LibraryModule {
    @Binds abstract fun media(impl: RoomMediaRepository): MediaRepository
    @Binds abstract fun movies(impl: RoomMovieRepository): MovieRepository
    @Binds abstract fun series(impl: RoomSeriesRepository): SeriesRepository
    @Binds abstract fun episodes(impl: RoomEpisodeRepository): EpisodeRepository
    @Binds abstract fun watch(impl: RoomWatchHistoryRepository): WatchHistoryRepository
    @Binds abstract fun subtitles(impl: RoomSubtitleRepository): SubtitleRepository
    @Binds abstract fun audio(impl: RoomAudioTrackRepository): AudioTrackRepository

    /** The library listens to the media index. */
    @Binds @IntoSet abstract fun indexListener(impl: LibrarySyncTrigger): MediaIndexListener

    companion object {
        @Provides @Singleton @LibraryScope
        fun scope(logger: Logger): CoroutineScope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, t -> logger.e("Library", "uncaught", t) },
        )
    }
}
