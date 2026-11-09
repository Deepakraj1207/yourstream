package dev.yourstream.di

import android.content.Context
import android.os.Build
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.yourstream.BuildConfig
import dev.yourstream.catalog.indian.IndianCatalogs
import dev.yourstream.catalog.stremio.StremioAddonDefinition
import dev.yourstream.catalog.stremio.StremioCatalogProvider
import dev.yourstream.catalog.api.CatalogProvider
import dev.yourstream.catalog.sync.CatalogProviderFactory
import dev.yourstream.catalog.sync.CatalogAddons
import dev.yourstream.core.common.Clock
import dev.yourstream.core.common.Logger
import dev.yourstream.core.network.HttpClients
import dev.yourstream.log.AppLogger
import dev.yourstream.metadata.sync.MetadataProviderOrder
import dev.yourstream.metadata.sync.MetadataSettings
import dev.yourstream.metadata.tmdb.TmdbConfig
import dev.yourstream.telegram.api.model.TelegramConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.util.Locale
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(diagnostics: dev.yourstream.robust.AppDiagnostics, logger: AppLogger): CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + kotlinx.coroutines.CoroutineExceptionHandler { _, error ->
            // A failure in background work is recorded, never a crash of the whole app.
            logger.e("AppScope", "Uncaught failure in background work", error)
        },
    )

    @Provides
    @Singleton
    fun logger(appLogger: AppLogger): Logger = appLogger

    /** TMDB credentials from BuildConfig (local/secrets properties or env); never in source. */
    @Provides
    @Singleton
    fun tmdbConfig(): TmdbConfig = TmdbConfig(apiKey = BuildConfig.TMDB_API_KEY, readToken = BuildConfig.TMDB_READ_TOKEN)

    @Provides
    @Singleton
    fun metadataOrder(): MetadataProviderOrder = MetadataProviderOrder(BuildConfig.METADATA_PROVIDERS.split(',').map { it.trim() }.filter { it.isNotEmpty() })

    @Provides
    @Singleton
    fun metadataSettings(): MetadataSettings = MetadataSettings(language = BuildConfig.METADATA_LANGUAGE.ifBlank { null })

    /**
     * Catalog providers from configuration, in fallback order. The catalog core knows none of
     * them; this is the only place that names concrete add-ons.
     */
    @Provides
    @Singleton
    fun catalogProviders(clock: Clock): CatalogProviderFactory = object : CatalogProviderFactory {
        override fun builtIns(): List<CatalogProvider> {
            val ids = BuildConfig.CATALOG_PROVIDERS.split(',').map { it.trim() }.filter { it.isNotEmpty() && it != "none" }
            val known = IndianCatalogs.create(ids, { HttpClients.create() }, clock)
            val custom = BuildConfig.CATALOG_CUSTOM_MANIFESTS.split(',').map { it.trim() }.filter { it.startsWith("https://") || it.startsWith("stremio://") }
                .map { StremioCatalogProvider(HttpClients.create(), StremioAddonDefinition.custom(it), clock) }
            // Cinemeta: Stremio's own catalog of popular movies and series (on by default, switchable).
            val cinemeta = StremioCatalogProvider(HttpClients.create(), StremioAddonDefinition.custom(CatalogAddons.CINEMETA, "Cinemeta").copy(id = dev.yourstream.catalog.api.CatalogProviderId("cinemeta")), clock)
            return listOf(cinemeta) + known + custom
        }

        override fun custom(manifestUrl: String, name: String): CatalogProvider =
            StremioCatalogProvider(HttpClients.create(), StremioAddonDefinition.custom(manifestUrl, name), clock)
    }

    @Provides
    @Singleton
    fun playbackSettings(): dev.yourstream.playback.media3.PlaybackSettings = dev.yourstream.playback.media3.PlaybackSettings(
        adaptive = BuildConfig.PLAYBACK_ADAPTIVE,
        limits = dev.yourstream.playback.adaptive.TransferLimits(
            maxConcurrentDownloads = BuildConfig.PLAYBACK_MAX_CONCURRENT_DOWNLOADS,
            maxConcurrentPrefetches = minOf(1, BuildConfig.PLAYBACK_MAX_CONCURRENT_DOWNLOADS - 1),
        ),
        buffer = dev.yourstream.playback.adaptive.BufferConfig(allowMeteredPrefetch = BuildConfig.PLAYBACK_PREFETCH_ON_MOBILE_DATA),
    )

    /**
     * Telegram configuration. API id/hash are built into the APK (secrets.properties / CI secrets,
     * docs/TELEGRAM_SETUP.md); users only sign in with their phone number or a QR code.
     *
     * The database lives in noBackupFilesDir: it holds the session and must persist across
     * launches, but never be backed up. Downloaded files go to the cache directory.
     */
    @Provides
    @Singleton
    fun telegramConfig(@ApplicationContext context: Context): TelegramConfig {
        forgetLegacyCredentials(context)
        return TelegramConfig(
            apiId = BuildConfig.TELEGRAM_API_ID,
            apiHash = BuildConfig.TELEGRAM_API_HASH,
            databaseDirectory = File(context.noBackupFilesDir, "tdlib/db").absolutePath,
            filesDirectory = File(context.cacheDir, "tdlib/files").absolutePath,
            useTestDc = BuildConfig.TELEGRAM_USE_TEST_DC,
            systemLanguageCode = Locale.getDefault().language.ifEmpty { "en" },
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            systemVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            applicationVersion = BuildConfig.VERSION_NAME,
            tdlibLogVerbosity = if (BuildConfig.DEBUG) 2 else 1,
            tdlibLogFile = File(context.cacheDir, "tdlib/tdlib.log").absolutePath,
        )
    }

    /**
     * Earlier versions let users enter their own API id/hash. A Telegram session belongs to the
     * api_id it was created with, so when one was entered and differs from the built-in id, the old
     * TDLib session is cleared once (library, progress and settings stay) and the entry forgotten.
     */
    private fun forgetLegacyCredentials(context: Context) {
        val prefs = context.getSharedPreferences("telegram_credentials", Context.MODE_PRIVATE)
        if (prefs.all.isEmpty()) return
        val entered = prefs.getInt("api_id", 0)
        if (prefs.getBoolean("reset_session_on_start", false) || (entered > 0 && entered != BuildConfig.TELEGRAM_API_ID)) {
            File(context.noBackupFilesDir, "tdlib/db").deleteRecursively()
            File(context.cacheDir, "tdlib/files").deleteRecursively()
        }
        prefs.edit().clear().commit()
    }
}
