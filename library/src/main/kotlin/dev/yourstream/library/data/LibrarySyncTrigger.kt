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
            for (request in requests) {
                delay(DEBOUNCE_MS)
                runCatching { synchronizer.sync() }.onFailure { logger.e("LibrarySync", "sync failed", it) }
            }
        }
    }

    override fun onIndexChanged() {
        requests.trySend(Unit)
    }

    private companion object {
        const val DEBOUNCE_MS = 750L
    }
}
