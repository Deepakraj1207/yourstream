package dev.yourstream.telegram.tdlib

import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.EmailAddressAuthenticationCode
import dev.g000sha256.tdl.dto.LogStreamFile
import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import dev.yourstream.core.common.Logger
import dev.yourstream.core.common.err
import dev.yourstream.core.common.ok
import dev.yourstream.telegram.api.model.AuthState
import dev.yourstream.telegram.api.model.ChatListType
import dev.yourstream.telegram.api.model.MediaMessagePage
import dev.yourstream.telegram.api.model.MediaSearchFilter
import dev.yourstream.telegram.api.model.MessageLookup
import dev.yourstream.telegram.api.model.NetworkType
import dev.yourstream.telegram.api.model.TelegramChat
import dev.yourstream.telegram.api.model.TelegramConfig
import dev.yourstream.telegram.api.model.TelegramFile
import dev.yourstream.telegram.api.model.TelegramUser
import dev.yourstream.telegram.core.TelegramScope
import dev.yourstream.telegram.core.client.LoadChatsResult
import dev.yourstream.telegram.core.client.TelegramClient
import dev.yourstream.telegram.core.client.TelegramEvent
import dev.yourstream.telegram.core.client.TgBasicGroup
import dev.yourstream.telegram.core.client.TgBasicGroupFullInfo
import dev.yourstream.telegram.core.client.TgSupergroup
import dev.yourstream.telegram.core.client.TgSupergroupFullInfo
import dev.yourstream.telegram.core.client.TgUserFullInfo
import dev.yourstream.telegram.core.error.TelegramErrorMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [TelegramClient] backed by TDLib via tdl-coroutines. The only class that holds a [TdlClient].
 *
 * One [TdlClient] is one TDLib instance. It dies on authorizationStateClosed (after logOut or
 * close), so [open] creates a fresh one each time. All of an instance's updates are mapped and
 * re-emitted on the single, never-completing [events] flow.
 */
@Singleton
class TdlibTelegramClient @Inject constructor(
    @TelegramScope private val scope: CoroutineScope,
    private val logger: Logger,
) : TelegramClient {

    private val _events = MutableSharedFlow<TelegramEvent>(extraBufferCapacity = EVENT_BUFFER)
    override val events: Flow<TelegramEvent> = _events.asSharedFlow()

    @Volatile private var td: TdlClient? = null
    private val instanceLock = Mutex()

    override suspend fun open(): AppResult<Unit> {
        val client = instanceLock.withLock {
            if (td != null) return Unit.ok()
            val created = try {
                TdlClient.create()
            } catch (t: Throwable) {
                // UnsatisfiedLinkError when the native library is missing for this ABI.
                logger.e(TAG, "cannot create TDLib client", t)
                return AppError.Unknown("TDLib could not be loaded: ${t.message}", t).err()
            }
            td = created
            logger.i(TAG, "TDLib ${TdlClient.TDL_VERSION} instance created")
            // Subscribe before the first request: tdl-coroutines starts receiving on the first
            // send, and its update flow has no replay.
            scope.launch(start = CoroutineStart.UNDISPATCHED) { forwardUpdates(created) }
            created
        }
        // First request: starts the receiver; TDLib then reports its authorization state.
        call("getAuthorizationState", client) { getAuthorizationState() }
        return Unit.ok()
    }

    private suspend fun forwardUpdates(client: TdlClient) {
        client.allUpdates.collect { update ->
            val event = try {
                TdMappers.event(update)
            } catch (t: Throwable) {
                logger.w(TAG, "skipping unmappable update ${update::class.simpleName}", t)
                null
            } ?: return@collect
            if (event is TelegramEvent.AuthStateChanged && event.state == AuthState.Closed) {
                instanceLock.withLock { if (td === client) td = null }
                logger.i(TAG, "TDLib instance closed")
            }
            _events.emit(event)
        }
    }

    override suspend fun close(): AppResult<Unit> {
        val client = td ?: return Unit.ok()
        val result = call("close", client, CLOSE_TIMEOUT) { close() }
        if (result is AppResult.Err) {
            // A wedged instance: forget it so a new one can be opened, and tell listeners.
            logger.w(TAG, "close failed (${result.error.message}); discarding instance")
            instanceLock.withLock { if (td === client) td = null }
            _events.emit(TelegramEvent.AuthStateChanged(AuthState.Closed))
        }
        return Unit.ok()
    }

    override suspend fun getAuthState(): AppResult<AuthState> =
        request("getAuthorizationState") { getAuthorizationState() }.mapValue(TdMappers::authState)

    override suspend fun setParameters(config: TelegramConfig): AppResult<Unit> {
        val client = td ?: return notOpen()
        File(config.databaseDirectory).mkdirs()
        File(config.filesDirectory).mkdirs()
        // Logging first so problems with the parameters themselves are captured.
        call("setLogVerbosityLevel", client) { setLogVerbosityLevel(config.tdlibLogVerbosity) }
        config.tdlibLogFile?.let { path ->
            call("setLogStream", client) { setLogStream(LogStreamFile(path, config.tdlibLogMaxBytes, false)) }
        }
        return call("setTdlibParameters", client) {
            setTdlibParameters(
                useTestDc = config.useTestDc,
                databaseDirectory = config.databaseDirectory,
                filesDirectory = config.filesDirectory,
                databaseEncryptionKey = config.databaseEncryptionKey,
                useFileDatabase = true,
                useChatInfoDatabase = true,
                useMessageDatabase = true,
                useSecretChats = false,
                apiId = config.apiId,
                apiHash = config.apiHash,
                systemLanguageCode = config.systemLanguageCode,
                deviceModel = config.deviceModel,
                systemVersion = config.systemVersion,
                applicationVersion = config.applicationVersion,
            )
        }.unit()
    }

    override suspend fun setPhoneNumber(phoneNumber: String) =
        request("setAuthenticationPhoneNumber", AUTH_TIMEOUT) { setAuthenticationPhoneNumber(phoneNumber, null) }.unit()

    override suspend fun resendCode() = request("resendAuthenticationCode", AUTH_TIMEOUT) { resendAuthenticationCode(null) }.unit()

    override suspend fun checkCode(code: String) = request("checkAuthenticationCode", AUTH_TIMEOUT) { checkAuthenticationCode(code) }.unit()

    override suspend fun checkPassword(password: String) =
        request("checkAuthenticationPassword", AUTH_TIMEOUT) { checkAuthenticationPassword(password) }.unit()

    override suspend fun setEmailAddress(email: String) =
        request("setAuthenticationEmailAddress", AUTH_TIMEOUT) { setAuthenticationEmailAddress(email) }.unit()

    override suspend fun checkEmailCode(code: String) =
        request("checkAuthenticationEmailCode", AUTH_TIMEOUT) { checkAuthenticationEmailCode(EmailAddressAuthenticationCode(code)) }.unit()

    override suspend fun requestQrCode() =
        request("requestQrCodeAuthentication", AUTH_TIMEOUT) { requestQrCodeAuthentication(LongArray(0)) }.unit()

    override suspend fun logOut() = request("logOut", AUTH_TIMEOUT) { logOut() }.unit()

    override suspend fun setNetworkType(type: NetworkType) =
        request("setNetworkType") { setNetworkType(TdMappers.tdNetworkType(type)) }.unit()

    override suspend fun getMe(): AppResult<TelegramUser> = request("getMe") { getMe() }.mapValue(TdMappers::user)

    override suspend fun getUser(userId: Long): AppResult<TelegramUser> = request("getUser") { getUser(userId) }.mapValue(TdMappers::user)

    override suspend fun getUserFullInfo(userId: Long): AppResult<TgUserFullInfo> =
        request("getUserFullInfo") { getUserFullInfo(userId) }.mapValue(TdMappers::userFullInfo)

    override suspend fun loadChats(list: ChatListType, limit: Int): AppResult<LoadChatsResult> {
        val client = td ?: return notOpen()
        return try {
            withTimeout(LOAD_TIMEOUT) {
                when (val r = client.loadChats(TdMappers.tdChatList(list), limit)) {
                    is TdlResult.Success -> LoadChatsResult.LOADED.ok()
                    // 404 is TDLib's documented "all chats are loaded", not an error.
                    is TdlResult.Failure ->
                        if (r.code == 404) LoadChatsResult.NO_MORE_CHATS.ok() else failure("loadChats", r)
                }
            }
        } catch (e: TimeoutCancellationException) {
            AppError.Timeout("loadChats").err()
        }
    }

    override suspend fun getChatIds(list: ChatListType, limit: Int): AppResult<List<Long>> =
        request("getChats") { getChats(TdMappers.tdChatList(list), limit) }.mapValue { it.chatIds.toList() }

    override suspend fun getChat(chatId: Long): AppResult<TelegramChat> =
        request("getChat") { getChat(chatId) }.mapValue(TdMappers::chat)

    override suspend fun getSupergroup(supergroupId: Long): AppResult<TgSupergroup> =
        request("getSupergroup") { getSupergroup(supergroupId) }.mapValue(TdMappers::supergroup)

    override suspend fun getSupergroupFullInfo(supergroupId: Long): AppResult<TgSupergroupFullInfo> =
        request("getSupergroupFullInfo") { getSupergroupFullInfo(supergroupId) }.mapValue(TdMappers::supergroupFullInfo)

    override suspend fun getBasicGroup(basicGroupId: Long): AppResult<TgBasicGroup> =
        request("getBasicGroup") { getBasicGroup(basicGroupId) }.mapValue(TdMappers::basicGroup)

    override suspend fun getBasicGroupFullInfo(basicGroupId: Long): AppResult<TgBasicGroupFullInfo> =
        request("getBasicGroupFullInfo") { getBasicGroupFullInfo(basicGroupId) }.mapValue(TdMappers::basicGroupFullInfo)

    override suspend fun searchChatMedia(
        chatId: Long,
        filter: MediaSearchFilter,
        fromMessageId: Long,
        limit: Int,
    ): AppResult<MediaMessagePage> =
        request("searchChatMessages", LOAD_TIMEOUT) {
            searchChatMessages(
                chatId = chatId,
                topicId = null,
                query = "",
                senderId = null,
                fromMessageId = fromMessageId,
                offset = 0,
                limit = limit,
                filter = TdMappers.tdSearchFilter(filter),
            )
        }.mapValue { found ->
            MediaMessagePage(
                messages = found.messages.mapNotNull(TdMappers::mediaMessage),
                nextFromMessageId = found.nextFromMessageId,
                totalCount = found.totalCount,
            )
        }

    override suspend fun getMessages(chatId: Long, messageIds: List<Long>): AppResult<List<MessageLookup>> =
        request("getMessages", LOAD_TIMEOUT) { getMessages(chatId, messageIds.toLongArray()) }.mapValue { result ->
            messageIds.mapIndexed { index, id ->
                val message = result.messages.getOrNull(index)
                when {
                    message == null -> MessageLookup.Missing(id)
                    else -> TdMappers.mediaMessage(message)?.let { MessageLookup.Found(it) } ?: MessageLookup.NoMedia(id)
                }
            }
        }

    override suspend fun getFile(fileId: Int): AppResult<TelegramFile> = request("getFile") { getFile(fileId) }.mapValue(TdMappers::file)

    override suspend fun downloadFile(fileId: Int, priority: Int, offset: Long, limit: Long): AppResult<TelegramFile> =
        request("downloadFile") { downloadFile(fileId, priority, offset, limit, false) }.mapValue(TdMappers::file)

    override suspend fun cancelDownloadFile(fileId: Int): AppResult<Unit> =
        request("cancelDownloadFile") { cancelDownloadFile(fileId, false) }.unit()

    // ---- plumbing ----

    private suspend fun <T> request(
        name: String,
        timeout: Duration = DEFAULT_TIMEOUT,
        block: suspend TdlClient.() -> TdlResult<T>,
    ): AppResult<T> {
        val client = td ?: return notOpen()
        return call(name, client, timeout, block)
    }

    /**
     * TDLib never fails a request just because the network is down; it waits. Every call is
     * therefore bounded by a timeout so the UI can report the problem instead of hanging.
     */
    private suspend fun <T> call(
        name: String,
        client: TdlClient,
        timeout: Duration = DEFAULT_TIMEOUT,
        block: suspend TdlClient.() -> TdlResult<T>,
    ): AppResult<T> = try {
        withTimeout(timeout) {
            when (val r = client.block()) {
                is TdlResult.Success -> r.result.ok()
                is TdlResult.Failure -> failure(name, r)
            }
        }
    } catch (e: TimeoutCancellationException) {
        logger.w(TAG, "$name timed out after $timeout")
        AppError.Timeout(name).err()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        logger.e(TAG, "$name crashed", t)
        AppError.Unknown("$name failed: ${t.message}", t).err()
    }

    private fun failure(name: String, r: TdlResult.Failure): AppResult<Nothing> {
        val error = TelegramErrorMapper.map(r.code, r.message)
        logger.d(TAG, "$name -> ${r.code} ${r.message}")
        return error.err()
    }

    private fun notOpen(): AppResult<Nothing> = AppError.InvalidState("Telegram is not running.").err()

    private inline fun <T, R> AppResult<T>.mapValue(transform: (T) -> R): AppResult<R> = when (this) {
        is AppResult.Ok -> transform(value).ok()
        is AppResult.Err -> this
    }

    private fun AppResult<*>.unit(): AppResult<Unit> = mapValue { }

    private companion object {
        const val TAG = "TdClient"
        const val EVENT_BUFFER = 4_096
        val DEFAULT_TIMEOUT = 30.seconds
        val AUTH_TIMEOUT = 60.seconds
        val LOAD_TIMEOUT = 60.seconds
        val CLOSE_TIMEOUT = 15.seconds
    }
}
