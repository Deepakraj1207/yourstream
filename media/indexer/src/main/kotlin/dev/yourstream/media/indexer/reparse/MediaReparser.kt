package dev.yourstream.media.indexer.reparse

import androidx.room.withTransaction
import dev.yourstream.core.common.Logger
import dev.yourstream.media.api.MediaIndexListener
import dev.yourstream.core.database.YourStreamDatabase
import dev.yourstream.media.indexer.store.toEntity
import dev.yourstream.media.parser.MediaNormalizer
import dev.yourstream.media.parser.ParsedMediaMapper
import kotlinx.coroutines.yield
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recomputes parsed metadata for rows produced by an older parser version, from the original
 * file names already stored in the index. No Telegram calls: improving the parser never
 * requires re-scanning chats. The original file_name column is read, never written.
 */
@Singleton
class MediaReparser @Inject constructor(
    private val db: YourStreamDatabase,
    private val logger: Logger,
    private val listeners: Set<@JvmSuppressWildcards MediaIndexListener>,
) {
    private val media = db.mediaDao()

    suspend fun outdatedCount(): Int = media.countOutdatedParses(ParsedMediaMapper.PARSER_VERSION)

    /** Re-parses up to [maxRows] rows (keyset batches of [batchSize]); returns how many were updated. */
    suspend fun run(batchSize: Int = 200, maxRows: Int = Int.MAX_VALUE): Int {
        val version = ParsedMediaMapper.PARSER_VERSION
        var after = 0L
        var done = 0
        while (done < maxRows) {
            val rows = media.outdatedParses(version, after, minOf(batchSize, maxRows - done))
            if (rows.isEmpty()) break
            db.withTransaction {
                for (row in rows) {
                    val parsed = ParsedMediaMapper.parse(row.fileName, row.caption, row.chatTitle)
                    media.upsertParsed(parsed.toEntity(row.id))
                    media.setSearchTitle(row.id, MediaNormalizer.searchTitle(parsed, row.audioTitle, row.audioPerformer))
                }
            }
            after = rows.last().id
            done += rows.size
            yield()
        }
        if (done > 0) {
            logger.i(TAG, "re-parsed $done records with parser v$version")
            listeners.forEach { it.onIndexChanged() }
        }
        return done
    }

    private companion object {
        const val TAG = "Reparse"
    }
}
