package dev.yourstream.e2e

import android.content.Context
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.FakeClock
import androidx.room.Room
import dev.yourstream.assistant.engine.LocalMediaAssistant
import dev.yourstream.catalog.core.CatalogManager
import dev.yourstream.catalog.sync.MetadataCatalogFallback
import dev.yourstream.catalog.sync.RoomCatalogCache
import dev.yourstream.catalog.sync.RoomLibraryAvailability
import dev.yourstream.catalog.sync.RoomLocalCatalogSource
import dev.yourstream.core.common.AppResult
import dev.yourstream.core.common.NoOpLogger
import dev.yourstream.core.database.YourStreamDatabase
import dev.yourstream.core.database.entity.IndexedChatEntity
import dev.yourstream.library.api.PlaybackTarget
import dev.yourstream.library.data.LibrarySynchronizer
import dev.yourstream.library.data.RoomEpisodeRepository
import dev.yourstream.library.data.RoomMediaRepository
import dev.yourstream.library.data.RoomSeriesRepository
import dev.yourstream.library.data.RoomSubtitleRepository
import dev.yourstream.library.data.RoomWatchHistoryRepository
import dev.yourstream.library.data.TrackPreferenceStore
import dev.yourstream.media.api.model.IndexMode
import dev.yourstream.media.indexer.engine.EngineConfig
import dev.yourstream.media.indexer.engine.IndexProgressTracker
import dev.yourstream.media.indexer.engine.IndexingEngine
import dev.yourstream.media.indexer.store.RoomIndexStore
import dev.yourstream.media.parser.MediaNormalizer
import dev.yourstream.metadata.core.MetadataProviderManager
import dev.yourstream.metadata.core.match.MetadataMatcher
import dev.yourstream.metadata.sync.LibraryMetadataService
import dev.yourstream.metadata.sync.MatchLibraryMetadata
import dev.yourstream.metadata.sync.MetadataSettings
import dev.yourstream.metadata.sync.RoomMetadataCache
import dev.yourstream.playback.media3.ExternalSubtitleLoader
import dev.yourstream.playback.media3.LibraryMediaSourceResolver
import dev.yourstream.playback.media3.PlaybackConfig
import dev.yourstream.playback.media3.PlaybackProgressSink
import dev.yourstream.playback.media3.RoomTrackPreferenceStore
import dev.yourstream.playback.media3.TelegramPlaybackController
import dev.yourstream.playback.media3.TrackContextProvider
import dev.yourstream.playback.media3.TrackSupport
import dev.yourstream.playback.stream.TelegramFileStreamer
import dev.yourstream.playback.testing.SimulatedTelegramFiles
import dev.yourstream.playback.testing.TestMedia
import dev.yourstream.search.engine.LibrarySearchEngine
import dev.yourstream.search.engine.SearchIndexer
import dev.yourstream.telegram.api.model.TelegramMediaContentType
import dev.yourstream.watch.engine.RoomWatchIntelligence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.nio.file.Files
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * The app's object graph without Hilt and without TDLib: every real repository, engine and
 * policy; Telegram is [FakeTelegramMessages] + [SimulatedTelegramFiles] (real bytes of real
 * media files, simulated transfer). Every SQL statement Room runs is recorded for the query-plan audit.
 */
class Pipeline(val context: Context, recordQueries: Boolean = false) : AutoCloseable {
    val clock = TestClock(System.currentTimeMillis())
    val dir: File = Files.createTempDirectory("e2e").toFile()
    val queries = ConcurrentHashMap<String, AtomicInteger>()
    private val queryExecutor = Executors.newSingleThreadExecutor()
    val db: YourStreamDatabase = Room.inMemoryDatabaseBuilder(context, YourStreamDatabase::class.java)
        .addCallback(YourStreamDatabase.CALLBACK)
        .apply { if (recordQueries) setQueryCallback({ sql, _ -> queries.getOrPut(sql) { AtomicInteger() }.incrementAndGet() }, queryExecutor) }
        .build()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Telegram
    val telegram = FakeTelegramMessages()
    val files = SimulatedTelegramFiles(File(dir, "tdlib"))
    private var nextFileId = 1

    // Indexing + parsing
    val store = RoomIndexStore(db, clock)
    val normalizer = MediaNormalizer()
    val tracker = IndexProgressTracker()
    val engine = IndexingEngine(telegram, store, normalizer, tracker, clock, NoOpLogger,
        EngineConfig(pageDelay = 0.milliseconds, retryBaseDelay = 10.milliseconds, maxAttempts = 2), emptySet())

    // Library
    val sync = LibrarySynchronizer(db, clock, NoOpLogger)
    val history = RoomWatchHistoryRepository(db, clock)
    val media = RoomMediaRepository(db, sync, history, clock)
    val series = RoomSeriesRepository(db, media)
    val episodes = RoomEpisodeRepository(db)
    val subtitles = RoomSubtitleRepository(db, TrackPreferenceStore(db, clock), clock)

    // Metadata
    val tmdb = FakeMetadataProvider()
    val metadata = MetadataProviderManager(listOf(tmdb), RoomMetadataCache(db), clock, NoOpLogger)
    val metadataService = LibraryMetadataService(db, metadata, MetadataMatcher(metadata), MetadataSettings(), clock, NoOpLogger)
    val matchMetadata = MatchLibraryMetadata(metadataService)

    // Search, assistant, watch intelligence, catalogs
    val searchIndexer = SearchIndexer(db)
    val search = LibrarySearchEngine(db, searchIndexer)
    val assistant = LocalMediaAssistant(db, search, searchIndexer, Optional.empty())
    val watch = RoomWatchIntelligence(db, clock)
    val catalog = FakeCatalog()
    val catalogs = CatalogManager(listOf(catalog), RoomCatalogCache(db), clock, NoOpLogger, MetadataCatalogFallback(metadata), RoomLocalCatalogSource(db), RoomLibraryAvailability(db))

    // Playback
    val streamer = TelegramFileStreamer(files, scope)
    val resolver = LibraryMediaSourceResolver(media, episodes, db, telegram, files)
    val trackStore = RoomTrackPreferenceStore(db)
    val trackSupport = TrackSupport(trackStore, ExternalSubtitleLoader(subtitles, db, telegram, files, context), TrackContextProvider(db))
    private val controllers = mutableListOf<TelegramPlaybackController>()

    suspend fun chat(chatId: Long, title: String, enabled: Boolean = true) {
        db.indexedChatDao().upsert(IndexedChatEntity(chatId, title, "CHANNEL", enabled, "IDLE", null, null, null, 0, clock.now, clock.now))
    }

    /** Posts a real media file (or text) into a chat, as Telegram would hold it. Returns the Telegram file id. */
    suspend fun post(chatId: Long, messageId: Long, name: String, source: File, type: TelegramMediaContentType = TelegramMediaContentType.VIDEO,
                     durationSeconds: Int? = 3, downloaded: Boolean = false): Int {
        val id = nextFileId++
        files.addFile(id, source, alreadyDownloaded = downloaded)
        val f = (files.getFile(id) as AppResult.Ok).value
        telegram.add(message(chatId, messageId, name, f, type, durationSeconds))
        return id
    }

    fun clip(name: String): File = TestMedia.file(name, dir)

    /** What the app does after Telegram changes: index enabled chats → library sync → metadata → search index. */
    suspend fun indexAll(mode: IndexMode = IndexMode.INCREMENTAL) {
        for (chatId in db.indexedChatDao().enabledChatIds()) engine.run(chatId, mode, 5.minutes)
        sync.sync()
        matchMetadata()
        searchIndexer.flush()
    }

    fun controller(audio: Boolean = true, config: PlaybackConfig = PlaybackConfig()): TelegramPlaybackController = TelegramPlaybackController(
        context, streamer, resolver, config,
        progressSink = PlaybackProgressSink { t, m, p, d -> history.updateProgress(t, m, p, d) },
        playerFactory = { ctx, sources, load ->
            ExoPlayer.Builder(ctx).setClock(FakeClock(true)).setMediaSourceFactory(sources).setLoadControl(load)
                .setStuckBufferingDetectionTimeoutMs(Int.MAX_VALUE).setStuckPlayingDetectionTimeoutMs(Int.MAX_VALUE)
                .setStuckPlayingNotEndingTimeoutMs(Int.MAX_VALUE).setStuckSuppressedDetectionTimeoutMs(Int.MAX_VALUE).build()
                .also { p -> if (!audio) p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build() }
        },
        trackSupport = trackSupport,
    ).also { controllers += it }

    fun itemOf(title: String): Long = run {
        db.openHelper.readableDatabase.query("SELECT id FROM library_item WHERE title = ?", arrayOf(title)).use { c -> check(c.moveToFirst()) { "no item $title" }; c.getLong(0) }
    }

    fun target(itemId: Long, episodeId: Long? = null) = PlaybackTarget(itemId, episodeId)

    override fun close() {
        controllers.forEach { runCatching { it.release() } }
        files.close()
        scope.cancel()
        db.close()
        queryExecutor.shutdownNow()
        dir.deleteRecursively()
    }
}
