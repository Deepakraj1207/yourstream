package dev.yourstream.desktop.data

import dev.yourstream.core.common.Logger
import dev.yourstream.media.api.model.IndexMode
import dev.yourstream.media.api.model.IndexRunResult
import dev.yourstream.media.api.model.IndexState
import dev.yourstream.media.api.model.MediaType
import dev.yourstream.media.indexer.store.ChatRecord
import dev.yourstream.media.indexer.store.IndexStore
import dev.yourstream.media.indexer.store.ScanCursor
import dev.yourstream.media.indexer.store.WriteResult
import dev.yourstream.media.parser.NormalizedMedia
import dev.yourstream.telegram.api.model.MediaSearchFilter
import dev.yourstream.telegram.api.model.TelegramFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.Writer
import java.util.concurrent.atomic.AtomicLong

/** One indexed Telegram file, as the desktop library needs it. */
@Serializable
data class MediaRecord(
    val chatId: Long,
    val messageId: Long,
    val messageDate: Long,
    val fileId: Int,
    val remoteFileId: String,
    val fileName: String,
    val sizeBytes: Long,
    val durationSeconds: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val mimeType: String = "",
    val video: Boolean,
    val caption: String = "",
    val title: String? = null,
    val titleKey: String? = null,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeEnd: Int? = null,
    val absoluteEpisode: Int? = null,
    val resolution: Int? = null,
    val quality: String? = null,
    val videoCodec: String? = null,
    val hdr: String? = null,
    val languages: List<String> = emptyList(),
    val kind: String = "UNKNOWN",
    val partNumber: Int? = null,
    val deleted: Boolean = false,
) {
    val isEpisode: Boolean get() = season != null || episode != null || absoluteEpisode != null || kind == "EPISODE" || kind == "SEASON_PACK"

    companion object {
        fun from(m: NormalizedMedia): MediaRecord {
            val p = m.parsed
            return MediaRecord(
                chatId = m.chatId, messageId = m.messageId, messageDate = m.messageDate, fileId = m.fileId,
                remoteFileId = m.remoteFileId, fileName = m.fileName, sizeBytes = m.sizeBytes,
                durationSeconds = m.durationSeconds, width = m.width, height = m.height, mimeType = m.mimeType,
                video = m.mediaType == MediaType.VIDEO, caption = m.caption.take(500),
                title = p.title, titleKey = p.normalizedTitle ?: p.title?.lowercase(), year = p.year,
                season = p.season, episode = p.episode, episodeEnd = p.episodeEnd, absoluteEpisode = p.absoluteEpisode,
                resolution = p.resolution ?: m.height, quality = p.quality, videoCodec = p.videoCodec ?: m.videoCodec,
                hdr = p.hdr, languages = p.languages, kind = p.kind, partNumber = p.partNumber,
            )
        }
    }
}

@Serializable
data class ChatRow(
    val chatId: Long,
    val title: String? = null,
    val enabled: Boolean = true,
    val paused: Boolean = false,
    val verifyCursor: Long = 0,
    val lastVerifiedAt: Long? = null,
    val state: IndexState = IndexState.IDLE,
    val error: String? = null,
    val lastIndexedAt: Long? = null,
)

@Serializable
data class CursorRow(
    val chatId: Long,
    val filter: MediaSearchFilter,
    val newestMessageId: Long = 0,
    val backfillFromMessageId: Long = 0,
    val backfillComplete: Boolean = false,
    val estimatedTotal: Int = -1,
    val segmentLowerBound: Long? = null,
) {
    fun toCursor() = ScanCursor(chatId, filter, newestMessageId, backfillFromMessageId, backfillComplete, estimatedTotal, segmentLowerBound)

    companion object {
        fun of(c: ScanCursor) = CursorRow(c.chatId, c.filter, c.newestMessageId, c.backfillFromMessageId, c.backfillComplete, c.estimatedTotal, c.segmentLowerBound)
    }
}

/** One line of the journal. */
@Serializable
sealed interface Entry {
    @Serializable @SerialName("m") data class Media(val r: MediaRecord) : Entry
    @Serializable @SerialName("c") data class Cursor(val r: CursorRow) : Entry
    @Serializable @SerialName("cd") data class CursorsDeleted(val chatId: Long, val filter: MediaSearchFilter? = null, val segmentsOnly: Boolean = false) : Entry
    @Serializable @SerialName("h") data class Chat(val r: ChatRow) : Entry
    @Serializable @SerialName("hd") data class ChatRemoved(val chatId: Long) : Entry
    @Serializable @SerialName("d") data class Deleted(val chatId: Long, val messageIds: List<Long>) : Entry
}

/**
 * The desktop app's index: what Room is on Android, as an append-only journal of JSON lines
 * (`index.jsonl` in the data folder).
 *
 * Every write appends its lines and flushes them in one call, items before the cursor that covers
 * them, so after a crash the cursor never points past media that were not stored (the engine's
 * contract). A cut-off last line is skipped when loading. When the journal holds far more lines
 * than live records it is rewritten (to a temporary file, then renamed over the old one).
 */
class DesktopIndexStore(private val file: File, private val logger: Logger, private val clock: () -> Long = System::currentTimeMillis) : IndexStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; classDiscriminator = "t" }
    private val lock = Mutex()
    private val chats = LinkedHashMap<Long, ChatRow>()
    private val cursors = LinkedHashMap<Triple<Long, MediaSearchFilter, Long?>, CursorRow>()
    private val media = LinkedHashMap<Pair<Long, Long>, MediaRecord>()
    private var lines = 0L
    private var writer: Writer? = null
    private val runIds = AtomicLong()

    private val _version = MutableStateFlow(0L)

    /** Changes whenever the library's records change (new, updated or deleted files). */
    val version: StateFlow<Long> = _version.asStateFlow()

    private val _chatRows = MutableStateFlow<List<ChatRow>>(emptyList())
    val chatRows: StateFlow<List<ChatRow>> = _chatRows.asStateFlow()

    init { load() }

    private fun load() {
        file.parentFile?.mkdirs()
        if (file.isFile) {
            var bad = 0
            file.bufferedReader(Charsets.UTF_8).useLines { seq ->
                for (line in seq) {
                    if (line.isBlank()) continue
                    val e = try { json.decodeFromString(Entry.serializer(), line) } catch (_: Exception) { bad++; null } ?: continue
                    apply(e)
                    lines++
                }
            }
            if (bad > 0) logger.w(TAG, "skipped $bad unreadable journal lines")
        }
        if (lines > 2 * (media.size + cursors.size + chats.size) + 10_000) compact()
        _chatRows.value = chats.values.toList()
    }

    private fun apply(e: Entry) {
        when (e) {
            is Entry.Media -> media[e.r.chatId to e.r.messageId] = e.r
            is Entry.Cursor -> cursors[Triple(e.r.chatId, e.r.filter, e.r.segmentLowerBound)] = e.r
            is Entry.CursorsDeleted -> cursors.keys.removeAll { k ->
                k.first == e.chatId && (e.filter == null || k.second == e.filter) && (!e.segmentsOnly || k.third != null)
            }
            is Entry.Chat -> chats[e.r.chatId] = e.r
            is Entry.ChatRemoved -> {
                chats.remove(e.chatId)
                cursors.keys.removeAll { it.first == e.chatId }
                media.keys.removeAll { it.first == e.chatId }
            }
            is Entry.Deleted -> for (id in e.messageIds) media[e.chatId to id]?.let { media[e.chatId to id] = it.copy(deleted = true) }
        }
    }

    /** Applies and appends [entries] as one flushed write. */
    private fun append(entries: List<Entry>) {
        if (entries.isEmpty()) return
        val w = writer ?: java.io.FileOutputStream(file, true).bufferedWriter(Charsets.UTF_8).also { writer = it }
        val text = StringBuilder()
        for (e in entries) {
            apply(e)
            text.append(json.encodeToString(Entry.serializer(), e)).append('\n')
        }
        w.write(text.toString())
        w.flush()
        lines += entries.size
    }

    private fun compact() {
        writer?.close(); writer = null
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.bufferedWriter(Charsets.UTF_8).use { w ->
            fun put(e: Entry) { w.write(json.encodeToString(Entry.serializer(), e)); w.write("\n") }
            chats.values.forEach { put(Entry.Chat(it)) }
            cursors.values.forEach { put(Entry.Cursor(it)) }
            media.values.forEach { put(Entry.Media(it)) }
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
        lines = (chats.size + cursors.size + media.size).toLong()
        logger.i(TAG, "journal compacted to $lines lines")
    }

    // ---- What the desktop app reads and changes ----

    suspend fun allMedia(): List<MediaRecord> = lock.withLock { media.values.filter { !it.deleted && chats[it.chatId]?.enabled == true } }

    suspend fun mediaByFileId(fileId: Int): MediaRecord? = lock.withLock { media.values.firstOrNull { it.fileId == fileId } }

    /** Adds a chat to index (or turns it back on), keeping what was indexed before. */
    suspend fun addChat(chatId: Long, title: String?) = lock.withLock {
        val row = chats[chatId]?.copy(title = title ?: chats[chatId]?.title, enabled = true) ?: ChatRow(chatId, title)
        append(listOf(Entry.Chat(row)))
        publish()
    }

    /** Stops indexing a chat and forgets its files. */
    suspend fun removeChat(chatId: Long) = lock.withLock {
        append(listOf(Entry.ChatRemoved(chatId)))
        publish()
    }

    suspend fun setPaused(chatId: Long, paused: Boolean) = lock.withLock {
        val row = chats[chatId] ?: return@withLock
        append(listOf(Entry.Chat(row.copy(paused = paused))))
        publish()
    }

    suspend fun enabledChatIds(): List<Long> = lock.withLock { chats.values.filter { it.enabled }.map { it.chatId } }

    private fun publish() {
        _chatRows.value = chats.values.toList()
        _version.value = _version.value + 1
    }

    // ---- IndexStore ----

    override suspend fun chat(chatId: Long): ChatRecord? = lock.withLock {
        chats[chatId]?.let { ChatRecord(it.chatId, it.title, it.enabled, it.paused, it.verifyCursor, it.lastVerifiedAt) }
    }

    override suspend fun albumCaption(chatId: Long, albumId: Long): String? = null

    override suspend fun cursor(chatId: Long, filter: MediaSearchFilter): ScanCursor = lock.withLock {
        cursors[Triple(chatId, filter, null)]?.toCursor() ?: ScanCursor(chatId, filter)
    }

    override suspend fun segments(chatId: Long, filter: MediaSearchFilter): List<ScanCursor> = lock.withLock {
        cursors.values.filter { it.chatId == chatId && it.filter == filter && it.segmentLowerBound != null }
            .sortedByDescending { it.segmentLowerBound }.map { it.toCursor() }
    }

    override suspend fun deleteSegments(chatId: Long, filter: MediaSearchFilter) = lock.withLock {
        append(listOf(Entry.CursorsDeleted(chatId, filter, segmentsOnly = true)))
    }

    override suspend fun saveCursor(cursor: ScanCursor) = lock.withLock { append(listOf(Entry.Cursor(CursorRow.of(cursor)))) }

    override suspend fun resetCursors(chatId: Long) = lock.withLock { append(listOf(Entry.CursorsDeleted(chatId))) }

    override suspend fun savePage(items: List<NormalizedMedia>, cursor: ScanCursor?): WriteResult = lock.withLock {
        var added = 0
        var updated = 0
        var unchanged = 0
        val entries = ArrayList<Entry>(items.size + 1)
        for (item in items) {
            val record = MediaRecord.from(item)
            val old = media[record.chatId to record.messageId]
            when {
                old == null -> { added++; entries += Entry.Media(record) }
                old == record -> unchanged++
                else -> { updated++; entries += Entry.Media(record) }
            }
        }
        if (cursor != null) entries += Entry.Cursor(CursorRow.of(cursor))
        append(entries)
        if (added + updated > 0) _version.value = _version.value + 1
        WriteResult(added, updated, unchanged)
    }

    override suspend fun markDeleted(chatId: Long, messageIds: List<Long>): Int = lock.withLock {
        val live = messageIds.filter { media[chatId to it]?.deleted == false }
        if (live.isNotEmpty()) {
            append(listOf(Entry.Deleted(chatId, live)))
            _version.value = _version.value + 1
        }
        live.size
    }

    override suspend fun touch(chatId: Long, messageIds: List<Long>) = Unit

    override suspend fun liveMessageIdsAfter(chatId: Long, afterMessageId: Long, limit: Int): List<Long> = lock.withLock {
        media.values.asSequence().filter { it.chatId == chatId && !it.deleted && it.messageId > afterMessageId }
            .map { it.messageId }.sorted().take(limit).toList()
    }

    override suspend fun setVerifyCursor(chatId: Long, cursor: Long, finished: Boolean) = lock.withLock {
        val row = chats[chatId] ?: return@withLock
        append(listOf(Entry.Chat(row.copy(verifyCursor = cursor, lastVerifiedAt = if (finished) clock() else row.lastVerifiedAt))))
    }

    override suspend fun setState(chatId: Long, state: IndexState, error: String?) = lock.withLock {
        val row = chats[chatId] ?: return@withLock
        append(listOf(Entry.Chat(row.copy(state = state, error = error))))
        _chatRows.value = chats.values.toList()
    }

    override suspend fun markIndexed(chatId: Long) = lock.withLock {
        val row = chats[chatId] ?: return@withLock
        append(listOf(Entry.Chat(row.copy(lastIndexedAt = clock()))))
        _chatRows.value = chats.values.toList()
    }

    override suspend fun startRun(chatId: Long, mode: IndexMode): Long = runIds.incrementAndGet().also {
        logger.i(TAG, "run $it: chat $chatId $mode")
    }

    override suspend fun finishRun(runId: Long, result: IndexRunResult) {
        logger.i(TAG, "run $runId: ${result.outcome}, +${result.mediaAdded} files")
    }

    override suspend fun isEnabled(chatId: Long): Boolean = lock.withLock { chats[chatId]?.enabled == true }

    override suspend fun updateDownload(file: TelegramFile) = Unit

    fun close() {
        writer?.close()
        writer = null
    }

    private companion object { const val TAG = "Index" }
}
