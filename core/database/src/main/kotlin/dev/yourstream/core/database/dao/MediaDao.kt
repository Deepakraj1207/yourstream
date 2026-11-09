package dev.yourstream.core.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import dev.yourstream.core.database.entity.MediaEntity
import dev.yourstream.core.database.entity.ParsedMediaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {

    // ---------- writer side (called inside the indexer's transactions) ----------

    @Query(
        "SELECT id, file_unique_id, canonical_media_id, duplicate_kind, is_deleted, indexed_at FROM media " +
            "WHERE chat_id = :chatId AND message_id = :messageId",
    )
    suspend fun findKey(chatId: Long, messageId: Long): MediaKeyRow?

    @Insert
    suspend fun insert(media: MediaEntity): Long

    @Update
    suspend fun update(media: MediaEntity)

    @Upsert
    suspend fun upsertParsed(parsed: ParsedMediaEntity)

    /** Oldest live row of the same Telegram upload, excluding [excludeId]. */
    @Query("SELECT id FROM media WHERE file_unique_id = :uniqueId AND id != :excludeId AND is_deleted = 0 ORDER BY id LIMIT 1")
    suspend fun oldestByUniqueId(uniqueId: String, excludeId: Long): Long?

    /** Oldest live row with the same size+name fingerprint, excluding [excludeId]. */
    @Query("SELECT id FROM media WHERE fingerprint = :fingerprint AND id != :excludeId AND is_deleted = 0 ORDER BY id LIMIT 1")
    suspend fun oldestByFingerprint(fingerprint: String, excludeId: Long): Long?

    @Query("SELECT id, file_unique_id, fingerprint FROM media WHERE id = :id")
    suspend fun duplicateKey(id: Long): DuplicateKeyRow?

    @Query("SELECT canonical_media_id FROM media WHERE id = :id")
    suspend fun canonicalOf(id: Long): Long?

    @Query("UPDATE media SET canonical_media_id = :canonicalId, duplicate_kind = :kind WHERE id = :id")
    suspend fun setCanonical(id: Long, canonicalId: Long?, kind: String?)

    @Query("SELECT id FROM media WHERE canonical_media_id = :canonicalId AND is_deleted = 0 ORDER BY id")
    suspend fun dependentsOf(canonicalId: Long): List<Long>

    @Query("UPDATE media SET canonical_media_id = :newCanonical WHERE canonical_media_id = :oldCanonical AND id != :newCanonical")
    suspend fun repointDependents(oldCanonical: Long, newCanonical: Long)

    @Query("SELECT id FROM media WHERE chat_id = :chatId AND message_id IN (:messageIds) AND is_deleted = 0")
    suspend fun liveIdsForMessages(chatId: Long, messageIds: List<Long>): List<Long>

    @Query("UPDATE media SET is_deleted = 1, deleted_at = :now, updated_at = :now WHERE id IN (:ids)")
    suspend fun markDeleted(ids: List<Long>, now: Long)

    @Query("UPDATE media SET last_seen_at = :now WHERE chat_id = :chatId AND message_id IN (:messageIds)")
    suspend fun touch(chatId: Long, messageIds: List<Long>, now: Long)

    /** Keyset pagination for the deletion check: never OFFSET, so cost stays flat. */
    @Query(
        "SELECT message_id FROM media WHERE chat_id = :chatId AND is_deleted = 0 AND message_id > :afterMessageId " +
            "ORDER BY message_id LIMIT :limit",
    )
    suspend fun liveMessageIdsAfter(chatId: Long, afterMessageId: Long, limit: Int): List<Long>

    @Query("SELECT file_unique_id FROM media WHERE chat_id = :chatId AND message_id = :messageId")
    suspend fun uniqueIdOf(chatId: Long, messageId: Long): String?

    @Query(
        "UPDATE media SET download_state = :state, downloaded_bytes = :bytes, local_path = :path " +
            "WHERE file_id = :fileId AND (download_state != :state OR downloaded_bytes != :bytes)",
    )
    suspend fun updateDownload(fileId: Int, state: String, bytes: Long, path: String?): Int

    @Query("SELECT caption FROM media WHERE chat_id = :chatId AND album_id = :albumId AND caption != '' LIMIT 1")
    suspend fun albumCaption(chatId: Long, albumId: Long): String?

    @Query("SELECT COUNT(*) FROM media_parsed WHERE parser_version < :version")
    suspend fun countOutdatedParses(version: Int): Int

    /** Rows whose metadata came from an older parser, in id order (keyset paging). */
    @Query(
        """SELECT m.id, m.file_name,
          -- A file without a caption in an album uses the album's caption (Telegram stores it once).
          CASE WHEN m.caption = '' AND m.album_id != 0
               THEN IFNULL((SELECT a.caption FROM media a WHERE a.chat_id = m.chat_id AND a.album_id = m.album_id AND a.caption != '' LIMIT 1), '')
               ELSE m.caption END AS caption,
          m.audio_title, m.audio_performer, c.title AS chat_title, m.context_caption
        FROM media m JOIN media_parsed p ON p.media_id = m.id LEFT JOIN indexed_chat c ON c.chat_id = m.chat_id
        WHERE p.parser_version < :version AND m.id > :afterId ORDER BY m.id LIMIT :limit""",
    )
    suspend fun outdatedParses(version: Int, afterId: Long, limit: Int): List<ReparseRow>

    /** Stored posts-above for these messages (files whose title came from one). */
    @Query("SELECT message_id, context_caption FROM media WHERE chat_id = :chatId AND message_id IN (:messageIds) AND context_caption IS NOT NULL AND context_caption != ''")
    suspend fun contextCaptions(chatId: Long, messageIds: List<Long>): List<ContextCaptionRow>

    /**
     * Live files of [chatId] without a title of their own (missing, or guessed from the chat's
     * name) whose post above was never looked for, newest first.
     */
    @Query(
        """SELECT m.id, m.message_id, m.message_date, m.file_name, m.caption, m.audio_title, m.audio_performer, c.title AS chat_title
        FROM media m JOIN media_parsed p ON p.media_id = m.id LEFT JOIN indexed_chat c ON c.chat_id = m.chat_id
        WHERE m.chat_id = :chatId AND m.is_deleted = 0 AND m.context_caption IS NULL AND m.media_type = 'VIDEO'
          AND (p.title IS NULL OR p.warnings LIKE '%chat title%' OR p.warnings LIKE '%generic name%') AND m.message_id < :beforeMessageId
        ORDER BY m.message_id DESC LIMIT :limit""",
    )
    suspend fun untitledWithoutContext(chatId: Long, beforeMessageId: Long, limit: Int): List<UntitledMediaRow>

    @Query("UPDATE media SET context_caption = :context WHERE id = :id")
    suspend fun setContextCaption(id: Long, context: String)

    @Query("UPDATE media SET search_title = :searchTitle WHERE id = :id")
    suspend fun setSearchTitle(id: Long, searchTitle: String)

    // ---------- reader side ----------

    @Transaction
    @Query("SELECT * FROM media WHERE id = :id")
    suspend fun getWithParsed(id: Long): MediaWithParsed?

    @Query(
        SUMMARY_SELECT + """
        WHERE (m.id = :rootId OR m.canonical_media_id = :rootId)
        ORDER BY m.id""",
    )
    suspend fun duplicateGroup(rootId: Long): List<MediaSummaryRow>

    @Query(
        SUMMARY_SELECT + """
        JOIN media_fts ON media_fts.rowid = m.id
        WHERE media_fts MATCH :match""" + FILTERS + ORDER,
    )
    fun search(
        match: String,
        chatId: Long?,
        types: List<String>,
        typeCount: Int,
        includeDeleted: Boolean,
        includeDuplicates: Boolean,
    ): PagingSource<Int, MediaSummaryRow>

    @Query(SUMMARY_SELECT + " WHERE 1 = 1" + FILTERS + ORDER)
    fun browse(
        chatId: Long?,
        types: List<String>,
        typeCount: Int,
        includeDeleted: Boolean,
        includeDuplicates: Boolean,
    ): PagingSource<Int, MediaSummaryRow>

    @Query(
        """SELECT COUNT(*) FROM media m JOIN media_fts ON media_fts.rowid = m.id
        WHERE media_fts MATCH :match""" + FILTERS,
    )
    fun countSearch(
        match: String,
        chatId: Long?,
        types: List<String>,
        typeCount: Int,
        includeDeleted: Boolean,
        includeDuplicates: Boolean,
    ): Flow<Int>

    @Query("SELECT COUNT(*) FROM media m WHERE 1 = 1" + FILTERS)
    fun countBrowse(
        chatId: Long?,
        types: List<String>,
        typeCount: Int,
        includeDeleted: Boolean,
        includeDuplicates: Boolean,
    ): Flow<Int>

    private companion object {
        const val SUMMARY_SELECT = """
            SELECT m.id, m.chat_id, m.message_id, m.file_name, m.media_type, m.size_bytes, m.duration_seconds,
                   m.message_date, p.title AS parsed_title, p.season, p.episode, p.absolute_episode, p.resolution,
                   (m.canonical_media_id IS NOT NULL) AS is_duplicate, m.is_deleted
            FROM media m LEFT JOIN media_parsed p ON p.media_id = m.id"""

        const val FILTERS = """
            AND (:chatId IS NULL OR m.chat_id = :chatId)
            AND (:typeCount = 0 OR m.media_type IN (:types))
            AND (:includeDeleted OR m.is_deleted = 0)
            AND (:includeDuplicates OR m.canonical_media_id IS NULL)"""

        const val ORDER = " ORDER BY m.message_date DESC, m.id DESC"
    }
}
