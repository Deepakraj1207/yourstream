package dev.yourstream.telegram.core.file

import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import dev.yourstream.core.common.err
import dev.yourstream.core.common.onOk
import dev.yourstream.telegram.api.TelegramFileRepository
import dev.yourstream.telegram.api.model.TelegramFile
import dev.yourstream.telegram.core.client.TelegramClient
import dev.yourstream.telegram.core.client.TelegramEvent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultTelegramFileRepository @Inject constructor(
    private val client: TelegramClient,
) : TelegramFileRepository {

    override val fileUpdates: Flow<TelegramFile> =
        client.events.filterIsInstance<TelegramEvent.FileChanged>().map { it.file }

    override suspend fun getFile(fileId: Int): AppResult<TelegramFile> = client.getFile(fileId)

    override fun observeFile(fileId: Int): Flow<TelegramFile> = channelFlow {
        // Subscribe before asking for the current state so no update is missed in between.
        launch(start = CoroutineStart.UNDISPATCHED) {
            client.events
                .filterIsInstance<TelegramEvent.FileChanged>()
                .map { it.file }
                .filter { it.id == fileId }
                .collect { send(it) }
        }
        client.getFile(fileId).onOk { send(it) }
    }.distinctUntilChanged()

    override suspend fun download(fileId: Int, priority: Int, offset: Long, limit: Long): AppResult<TelegramFile> {
        if (offset < 0 || limit < 0) return AppError.InvalidInput("RANGE", "offset and limit must be >= 0").err()
        return client.downloadFile(fileId, priority.coerceIn(1, 32), offset, limit)
    }

    override suspend fun cancelDownload(fileId: Int): AppResult<Unit> = client.cancelDownloadFile(fileId)

    override suspend fun downloadedPrefixSize(fileId: Int, offset: Long): AppResult<Long> {
        if (offset < 0) return AppError.InvalidInput("RANGE", "offset must be >= 0").err()
        return client.getFileDownloadedPrefixSize(fileId, offset)
    }

    override suspend fun readPart(fileId: Int, offset: Long, count: Long): AppResult<ByteArray> {
        if (offset < 0 || count < 0) return AppError.InvalidInput("RANGE", "offset and count must be >= 0").err()
        return client.readFilePart(fileId, offset, count)
    }

    override suspend fun deleteLocalCopy(fileId: Int): AppResult<Unit> = client.deleteFile(fileId)

    override suspend fun deleteAllLocalCopies(): AppResult<Unit> = client.deleteAllLocalFiles()
}
