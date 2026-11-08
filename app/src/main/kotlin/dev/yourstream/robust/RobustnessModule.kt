package dev.yourstream.robust

import android.content.Context
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet
import dev.yourstream.catalog.core.CatalogManager
import dev.yourstream.core.database.OpenedDatabase
import dev.yourstream.core.diagnostics.DiagnosticsRunner
import dev.yourstream.core.diagnostics.EventJournal
import dev.yourstream.core.diagnostics.HealthCheck
import dev.yourstream.core.diagnostics.PlaybackSessionStore
import dev.yourstream.metadata.core.MetadataProviderManager
import dev.yourstream.telegram.api.TelegramManager
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RobustnessModule {
    companion object {
        @Provides @Singleton
        fun diagnostics(@ApplicationContext context: Context): AppDiagnostics = AppDiagnostics.get(context)

        @Provides fun journal(d: AppDiagnostics): EventJournal = d.events


        @Provides fun playbackSession(d: AppDiagnostics): PlaybackSessionStore = d.playbackSession

        /** Lazy: checks must work even when one subsystem cannot be built. */
        @Provides @ElementsIntoSet
        fun healthChecks(
            @ApplicationContext context: Context,
            d: AppDiagnostics,
            db: Lazy<OpenedDatabase>,
            telegram: TelegramManager,
            metadata: Lazy<MetadataProviderManager>,
            catalogs: Lazy<CatalogManager>,
        ): Set<HealthCheck> = setOf(
            DatabaseCheck(context) { db.get() },
            TelegramCheck(telegram, d.safeMode),
            NetworkCheck(context),
            MetadataProvidersCheck { metadata.get() },
            CatalogProvidersCheck { catalogs.get() },
            BackgroundWorkCheck(context),
            MemoryCheck(context, d),
            StorageCheck(context),
            PowerCheck(context),
            CrashCheck(d),
            LoggingCheck(d),
        )

        @Provides
        fun runner(checks: Set<@JvmSuppressWildcards HealthCheck>, d: AppDiagnostics): DiagnosticsRunner = DiagnosticsRunner(checks, d.events)
    }
}
