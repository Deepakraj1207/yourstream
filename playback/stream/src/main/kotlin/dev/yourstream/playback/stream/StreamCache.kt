package dev.yourstream.playback.stream

import dev.yourstream.telegram.api.TelegramFileRepository
import dev.yourstream.telegram.api.model.TelegramFile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Temporary cache of streamed files. The bytes themselves stay where Telegram downloaded them
 * (its file cache), so re-watching or seeking back costs nothing; this class only bounds how
 * much of that streaming leaves behind.
 *
 * Only files that were *not* already complete before streaming are tracked, and never one the
 * user asked to keep ([keep]: Download on a title): a file downloaded on purpose is never deleted
 * here, even when it was played while still downloading. When the tracked total exceeds [budgetBytes], the
 * least recently streamed, inactive files lose their local copy (never the newest one).
 */
class StreamCache(
    private val repo: TelegramFileRepository,
    private val budgetBytes: Long = 2L * 1024 * 1024 * 1024,
    private val clock: () -> Long = System::currentTimeMillis,
    private val keep: (TelegramFile) -> Boolean = { false },
) {
    private data class Entry(val fileId: Int, val bytes: Long, val lastUsed: Long)

    private val lock = Mutex()
    private val entries = LinkedHashMap<Int, Entry>()

    val trackedBytes: Long get() = entries.values.sumOf { it.bytes }

    /** Records a finished streaming session and evicts the oldest copies above the budget. Returns evicted ids. */
    suspend fun recordAndTrim(file: TelegramFile, wasCompleteBefore: Boolean, activeFileIds: Set<Int>): List<Int> = lock.withLock {
        if (!wasCompleteBefore && file.downloadedSize > 0 && !keep(file)) {
            entries.remove(file.id)
            entries[file.id] = Entry(file.id, file.downloadedSize, clock())
        }
        val evicted = mutableListOf<Int>()
        // The most recently streamed copy is always kept (the user may come straight back to it).
        val newest = entries.values.maxByOrNull { it.lastUsed }?.fileId
        val iterator = entries.values.sortedBy { it.lastUsed }.iterator()
        while (trackedBytes > budgetBytes && iterator.hasNext()) {
            val e = iterator.next()
            if (e.fileId in activeFileIds || e.fileId == newest) continue
            repo.deleteLocalCopy(e.fileId)
            entries.remove(e.fileId)
            evicted += e.fileId
        }
        evicted
    }
}
