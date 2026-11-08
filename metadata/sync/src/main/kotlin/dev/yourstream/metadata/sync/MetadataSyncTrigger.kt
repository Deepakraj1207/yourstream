package dev.yourstream.metadata.sync

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
annotation class MetadataScope

/**
 * After index changes (and therefore library changes), match new items. Waits longer than the
 * library sync so the library is up to date first, and coalesces bursts into one run.
 */
@Singleton
class MetadataSyncTrigger @Inject constructor(
    private val match: MatchLibraryMetadata,
    @MetadataScope private val scope: CoroutineScope,
    private val logger: Logger,
) : MediaIndexListener {

    private val requests = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (request in requests) {
                delay(DELAY_MS)
                runCatching { match() }.onFailure { logger.e("MetadataSync", "metadata run failed", it) }
            }
        }
    }

    override fun onIndexChanged() {
        requests.trySend(Unit)
    }

    fun requestRun() = onIndexChanged()

    private companion object { const val DELAY_MS = 5_000L }
}
