package dev.yourstream.desktop

import dev.yourstream.core.common.Clock
import dev.yourstream.core.common.Logger
import dev.yourstream.core.network.HttpClients
import dev.yourstream.desktop.data.DesktopIndexStore
import dev.yourstream.desktop.data.SettingsStore
import dev.yourstream.desktop.library.IndexRunner
import dev.yourstream.desktop.library.PosterService
import dev.yourstream.desktop.playback.ByteSource
import dev.yourstream.desktop.playback.StreamServer
import dev.yourstream.media.indexer.engine.EngineConfig
import dev.yourstream.media.indexer.engine.IndexProgressTracker
import dev.yourstream.media.indexer.engine.IndexingEngine
import dev.yourstream.media.parser.MediaNormalizer
import dev.yourstream.metadata.cinemeta.CinemetaProvider
import dev.yourstream.metadata.core.InMemoryMetadataCache
import dev.yourstream.metadata.core.MetadataProviderManager
import dev.yourstream.metadata.core.match.MetadataMatcher
import dev.yourstream.metadata.tmdb.TmdbConfig
import dev.yourstream.metadata.tmdb.TmdbProvider
import dev.yourstream.playback.stream.StreamCache
import dev.yourstream.playback.stream.TelegramFileStreamer
import dev.yourstream.telegram.api.TelegramAuthManager
import dev.yourstream.telegram.api.TelegramChatRepository
import dev.yourstream.telegram.api.TelegramFileRepository
import dev.yourstream.telegram.api.TelegramManager
import dev.yourstream.telegram.api.TelegramMessageRepository
import dev.yourstream.telegram.api.model.TelegramConfig
import dev.yourstream.telegram.core.auth.DefaultTelegramAuthManager
import dev.yourstream.telegram.core.chat.DefaultTelegramChatRepository
import dev.yourstream.telegram.core.file.DefaultTelegramFileRepository
import dev.yourstream.telegram.core.manager.DefaultTelegramManager
import dev.yourstream.telegram.core.message.DefaultTelegramMessageRepository
import dev.yourstream.telegram.tdlib.TdlibTelegramClient
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File

/**
 * Everything the desktop app is made of, wired by hand (Hilt does this on Android). The same
 * classes as the Android app wherever they are plain Kotlin.
 */
class AppGraph(val build: BuildInfo = BuildInfo.load(), val dataDir: File = AppPaths.dataDir()) : AutoCloseable {
    val logger: Logger = FileLogger(File(dataDir, "logs"))
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, t -> logger.e("App", "uncaught error", t) },
    )

    val settings = SettingsStore(File(dataDir, "settings.json"))

    // ---- Telegram (TDLib's JVM build: the Windows libraries are inside the jar) ----
    val telegramConfig = TelegramConfig(
        apiId = build.apiId,
        apiHash = build.apiHash,
        databaseDirectory = File(dataDir, "telegram/db").absolutePath,
        filesDirectory = File(dataDir, "telegram/files").absolutePath,
        systemLanguageCode = java.util.Locale.getDefault().language.ifEmpty { "en" },
        deviceModel = if (AppPaths.isWindows) "Windows PC" else "Desktop",
        systemVersion = "${System.getProperty("os.name")} ${System.getProperty("os.version")}",
        applicationVersion = build.version,
        tdlibLogFile = File(dataDir, "logs/tdlib.log").absolutePath,
    )
    private val client = TdlibTelegramClient(scope, logger)
    val telegram: TelegramManager = DefaultTelegramManager(client, telegramConfig, scope, logger)
    val auth: TelegramAuthManager = DefaultTelegramAuthManager(client, telegramConfig, scope, logger)
    val chats: TelegramChatRepository = DefaultTelegramChatRepository(client, scope, logger)
    val files: TelegramFileRepository = DefaultTelegramFileRepository(client)
    val messages: TelegramMessageRepository = DefaultTelegramMessageRepository(client)

    // ---- Index and library ----
    val index = DesktopIndexStore(File(dataDir, "index.jsonl"), logger)
    val progress = IndexProgressTracker()
    private val engine = IndexingEngine(
        messages = messages, store = index, normalizer = MediaNormalizer(), tracker = progress,
        clock = Clock.System, logger = logger, config = EngineConfig(), listeners = emptySet(),
    )
    val indexer = IndexRunner(engine, index, progress, telegram, scope, logger)

    // ---- Posters ----
    private val http = HttpClients.create(userAgent = "YourStream-Desktop/${build.version}")
    private val tmdb = TmdbProvider(http, { settings.settings.value.tmdbKey.let { k -> if (k.length > 40) TmdbConfig(readToken = k) else TmdbConfig(apiKey = k) } })
    private val metadata = MetadataProviderManager(listOf(tmdb, CinemetaProvider(http)), InMemoryMetadataCache(), Clock.System, logger)
    val posters = PosterService(MetadataMatcher(metadata), http, File(dataDir, "cache/posters"), logger)

    // ---- Playback: Telegram files streamed while downloading, served to VLC on 127.0.0.1 ----
    val streamer = TelegramFileStreamer(files, scope, logger = logger, cache = StreamCache(files))
    val streamServer = StreamServer(object : ByteSource {
        override suspend fun size(fileId: Int): Long = index.mediaByFileId(fileId)?.sizeBytes?.takeIf { it > 0 } ?: -1

        override suspend fun open(fileId: Int, position: Long): ByteSource.Reader {
            val stream = streamer.open(fileId, position)
            return object : ByteSource.Reader {
                override suspend fun read(buffer: ByteArray, offset: Int, length: Int) = stream.read(buffer, offset, length)
                override fun close() = stream.close()
            }
        }
    }, logger)

    override fun close() {
        streamServer.close()
        index.close()
        scope.cancel()
    }
}
