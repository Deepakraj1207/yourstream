package dev.yourstream.library.data

import dev.yourstream.core.common.Logger
import dev.yourstream.media.api.MediaIndexListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LibraryScope

/**
 * Index → library glue. Index changes are coalesced (a burst of pages triggers one sync after
 * a short pause) and synced off the caller's thread, one sync at a time.
 */
@Singleton
class LibrarySyncTrigger @Inject constructor(
    private val synchronizer: LibrarySynchronizer,
    @LibraryScope private val scope: CoroutineScope,
    private val logger: Logger,
) : MediaIndexListener {

    private val requests = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            var last = 0L
            for (request in requests) {
                delay(DEBOUNCE_MS)
                // While a big chat is indexed, changes arrive continuously: every sync rewrites
                // library rows and every list on screen re-reads them. At most one every 8 s.
                val wait = last + MIN_INTERVAL_MS - System.currentTimeMillis()
                if (wait > 0) delay(wait)
                runCatching { synchronizer.sync() }.onFailure { logger.e("LibrarySync", "sync failed", it) }
                last = System.currentTimeMillis()
            }
        }
    }

    override fun onIndexChanged() {
        requests.trySend(Unit)
    }

    private companion object {
        const val DEBOUNCE_MS = 750L
        const val MIN_INTERVAL_MS = 8_000L
    }
}
