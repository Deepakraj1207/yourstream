package dev.yourstream.telegram.core.saved

import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import dev.yourstream.telegram.api.SavedDocument
import dev.yourstream.telegram.api.TelegramFileRepository
import dev.yourstream.telegram.api.TelegramSavedMessages
import dev.yourstream.telegram.core.client.TelegramClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Saved Messages as a tiny private store. Uploads go through a temporary file (TDLib uploads from
 * a path); old temporary files are removed on the next upload.
 */
@Singleton
class DefaultTelegramSavedMessages @Inject constructor(
    private val client: TelegramClient,
    private val files: TelegramFileRepository,
) : TelegramSavedMessages {
    /** Where upload files are written (the process temp dir: the app's cache dir on Android). */
    var uploadDir: File = File(System.getProperty("java.io.tmpdir"), "saved-uploads")

    override suspend fun find(tag: String, limit: Int): AppResult<List<SavedDocument>> {
        val chat = when (val c = client.savedMessagesChatId()) { is AppResult.Ok -> c.value; is AppResult.Err -> return c }
        return when (val r = client.searchDocuments(chat, tag, limit)) {
            is AppResult.Err -> r
            is AppResult.Ok -> AppResult.Ok(
                r.value.filter { tag in it.caption }.sortedByDescending { it.messageId }
                    .map { SavedDocument(it.messageId, it.date * 1000, it.fileId, it.fileName, it.caption, it.size) },
            )
        }
    }

    override suspend fun read(doc: SavedDocument, maxBytes: Long): AppResult<ByteArray> {
        if (doc.size > maxBytes) return AppResult.Err(AppError.InvalidState("Saved file too large (${doc.size} bytes)"))
        (files.download(doc.fileId, priority = 32) as? AppResult.Err)?.let { return it }
        val done = withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) { files.observeFile(doc.fileId).first { it.isDownloadingCompleted } }
            ?: return AppResult.Err(AppError.Network("Download of the saved file timed out"))
        val local = done.localPath?.let(::File)?.takeIf { it.canRead() }
        if (local != null) return AppResult.Ok(withContext(Dispatchers.IO) { local.readBytes() })
        return files.readPart(doc.fileId, 0, done.size)
    }

    override suspend fun replace(tag: String, fileName: String, caption: String, bytes: ByteArray): AppResult<Unit> {
        val chat = when (val c = client.savedMessagesChatId()) { is AppResult.Ok -> c.value; is AppResult.Err -> return c }
        val previous = (client.searchDocuments(chat, tag, 20) as? AppResult.Ok)?.value.orEmpty().filter { tag in it.caption }
        val file = withContext(Dispatchers.IO) {
            uploadDir.mkdirs()
            // Earlier uploads have finished by now (sync runs minutes apart): clean them up.
            uploadDir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > STALE_UPLOAD_MS }?.forEach { it.delete() }
            File(uploadDir, "${System.nanoTime()}-$fileName").apply { writeBytes(bytes) }
        }
        val sent = client.sendDocument(chat, file.absolutePath, caption)
        if (sent is AppResult.Err) return sent
        if (previous.isNotEmpty()) client.deleteMessages(chat, previous.map { it.messageId })
        return AppResult.Ok(Unit)
    }

    override suspend fun delete(tag: String): AppResult<Unit> {
        val chat = when (val c = client.savedMessagesChatId()) { is AppResult.Ok -> c.value; is AppResult.Err -> return c }
        val tagged = when (val r = client.searchDocuments(chat, tag, 50)) { is AppResult.Ok -> r.value.filter { tag in it.caption }; is AppResult.Err -> return r }
        return if (tagged.isEmpty()) AppResult.Ok(Unit) else client.deleteMessages(chat, tagged.map { it.messageId })
    }

    private companion object {
        const val DOWNLOAD_TIMEOUT_MS = 60_000L
        const val STALE_UPLOAD_MS = 60 * 60_000L
    }
}
