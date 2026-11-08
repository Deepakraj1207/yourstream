package dev.yourstream.telegram.core

import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import dev.yourstream.core.common.err
import dev.yourstream.core.common.ok
import dev.yourstream.telegram.api.model.AuthState
import dev.yourstream.telegram.api.model.ChatKind
import dev.yourstream.telegram.api.model.ChatListType
import dev.yourstream.telegram.api.model.ChatPosition
import dev.yourstream.telegram.api.model.NetworkType
import dev.yourstream.telegram.api.model.TelegramChat
import dev.yourstream.telegram.api.model.TelegramConfig
import dev.yourstream.telegram.api.model.TelegramFile
import dev.yourstream.telegram.api.model.TelegramUser
import dev.yourstream.telegram.core.client.LoadChatsResult
import dev.yourstream.telegram.core.client.TelegramClient
import dev.yourstream.telegram.core.client.TelegramEvent
import dev.yourstream.telegram.core.client.TgBasicGroup
import dev.yourstream.telegram.core.client.TgBasicGroupFullInfo
import dev.yourstream.telegram.core.client.TgSupergroup
import dev.yourstream.telegram.core.client.TgSupergroupFullInfo
import dev.yourstream.telegram.core.client.TgUserFullInfo
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Scriptable in-memory TDLib stand-in. It behaves like a tiny TDLib: opening emits
 * WaitParameters, valid parameters lead to WaitPhoneNumber (or Ready if [hasSavedSession]),
 * and so on. Every call is recorded in [calls] (without secret values) for assertions.
 */
class FakeTelegramClient : TelegramClient {

    private val _events = MutableSharedFlow<TelegramEvent>(extraBufferCapacity = 1_000)
    override val events: SharedFlow<TelegramEvent> = _events

    val calls = mutableListOf<String>()

    var isOpen = false
        private set
    var openCount = 0
        private set

    // ---- scripting knobs ----
    var hasSavedSession = false
    var correctCode = "12345"
    var passwordRequired = false
    var correctPassword = "hunter2"
    var parametersError: AppError? = null
    var me = user(id = 42, first = "Ada")

    /** All chats the "server" has, in list order (first = top). */
    val serverChats = mutableListOf<TelegramChat>()

    /** How many chats of the main list have been "loaded" so far. */
    var loadedCount = 0
        private set

    /** If true, chats from loadChats are not announced as events (tests reconcile path). */
    var silentLoad = false
    var loadError: AppError? = null

    val users = mutableMapOf<Long, TelegramUser>()
    val supergroups = mutableMapOf<Long, TgSupergroup>()
    val supergroupFull = mutableMapOf<Long, TgSupergroupFullInfo>()
    val basicGroups = mutableMapOf<Long, TgBasicGroup>()

    var authState: AuthState = AuthState.Closed
        private set

    suspend fun emit(event: TelegramEvent) {
        _events.emit(event)
    }

    private suspend fun moveTo(state: AuthState) {
        authState = state
        _events.emit(TelegramEvent.AuthStateChanged(state))
    }

    override suspend fun open(): AppResult<Unit> {
        calls += "open"
        if (isOpen) return Unit.ok()
        isOpen = true
        openCount++
        loadedCount = 0
        moveTo(AuthState.WaitParameters)
        return Unit.ok()
    }

    override suspend fun close(): AppResult<Unit> {
        calls += "close"
        if (!isOpen) return Unit.ok()
        moveTo(AuthState.Closing)
        isOpen = false
        moveTo(AuthState.Closed)
        return Unit.ok()
    }

    override suspend fun getAuthState(): AppResult<AuthState> =
        if (isOpen) authState.ok() else AppError.InvalidState("not open").err()

    override suspend fun setParameters(config: TelegramConfig): AppResult<Unit> {
        calls += "setParameters"
        parametersError?.let { return it.err() }
        moveTo(if (hasSavedSession) AuthState.Ready else AuthState.WaitPhoneNumber)
        return Unit.ok()
    }

    override suspend fun setPhoneNumber(phoneNumber: String): AppResult<Unit> {
        calls += "setPhoneNumber:$phoneNumber"
        moveTo(
            AuthState.WaitCode(
                dev.yourstream.telegram.api.model.CodeInfo(
                    phoneNumber = phoneNumber,
                    type = dev.yourstream.telegram.api.model.CodeDeliveryType.TELEGRAM_MESSAGE,
                    nextType = dev.yourstream.telegram.api.model.CodeDeliveryType.SMS,
                    timeoutSeconds = 60,
                    codeLength = 5,
                ),
            ),
        )
        return Unit.ok()
    }

    override suspend fun resendCode(): AppResult<Unit> {
        calls += "resendCode"
        return Unit.ok()
    }

    override suspend fun checkCode(code: String): AppResult<Unit> {
        calls += "checkCode"
        if (code != correctCode) return AppError.InvalidInput("PHONE_CODE_INVALID", "The code is wrong.").err()
        if (passwordRequired) moveTo(AuthState.WaitPassword("hint", hasRecoveryEmail = false, recoveryEmailPattern = ""))
        else loggedIn()
        return Unit.ok()
    }

    override suspend fun checkPassword(password: String): AppResult<Unit> {
        calls += "checkPassword"
        if (password != correctPassword) return AppError.InvalidInput("PASSWORD_HASH_INVALID", "Wrong password.").err()
        loggedIn()
        return Unit.ok()
    }

    private suspend fun loggedIn() {
        hasSavedSession = true
        moveTo(AuthState.Ready)
    }

    override suspend fun setEmailAddress(email: String): AppResult<Unit> {
        calls += "setEmailAddress"
        return Unit.ok()
    }

    override suspend fun checkEmailCode(code: String): AppResult<Unit> {
        calls += "checkEmailCode"
        return Unit.ok()
    }

    override suspend fun requestQrCode(): AppResult<Unit> {
        calls += "requestQrCode"
        moveTo(AuthState.WaitOtherDeviceConfirmation("tg://login?token=abc"))
        return Unit.ok()
    }

    override suspend fun logOut(): AppResult<Unit> {
        calls += "logOut"
        hasSavedSession = false
        moveTo(AuthState.LoggingOut)
        moveTo(AuthState.Closing)
        isOpen = false
        moveTo(AuthState.Closed)
        return Unit.ok()
    }

    override suspend fun setNetworkType(type: NetworkType): AppResult<Unit> {
        calls += "setNetworkType:$type"
        return Unit.ok()
    }

    override suspend fun getMe(): AppResult<TelegramUser> {
        calls += "getMe"
        return if (authState == AuthState.Ready) me.ok() else AppError.Unauthorized().err()
    }

    override suspend fun getUser(userId: Long): AppResult<TelegramUser> =
        users[userId]?.ok() ?: AppError.NotFound("user $userId").err()

    override suspend fun getUserFullInfo(userId: Long): AppResult<TgUserFullInfo> = TgUserFullInfo(bio = "bio of $userId").ok()

    override suspend fun loadChats(list: ChatListType, limit: Int): AppResult<LoadChatsResult> {
        calls += "loadChats:$limit"
        loadError?.let { return it.err() }
        if (authState != AuthState.Ready) return AppError.Unauthorized().err()
        if (loadedCount >= serverChats.size) return LoadChatsResult.NO_MORE_CHATS.ok()
        val page = serverChats.subList(loadedCount, minOf(serverChats.size, loadedCount + limit)).toList()
        loadedCount += page.size
        if (!silentLoad) page.forEach { _events.emit(TelegramEvent.ChatAdded(it)) }
        return LoadChatsResult.LOADED.ok()
    }

    override suspend fun getChatIds(list: ChatListType, limit: Int): AppResult<List<Long>> =
        serverChats.take(minOf(limit, loadedCount)).map { it.id }.ok()

    override suspend fun getChat(chatId: Long): AppResult<TelegramChat> {
        calls += "getChat:$chatId"
        return serverChats.firstOrNull { it.id == chatId }?.ok() ?: AppError.NotFound("chat $chatId").err()
    }

    override suspend fun getSupergroup(supergroupId: Long): AppResult<TgSupergroup> =
        supergroups[supergroupId]?.ok() ?: AppError.NotFound("supergroup").err()

    override suspend fun getSupergroupFullInfo(supergroupId: Long): AppResult<TgSupergroupFullInfo> =
        supergroupFull[supergroupId]?.ok() ?: AppError.Telegram(400, "CHAT_ADMIN_REQUIRED").err()

    override suspend fun getBasicGroup(basicGroupId: Long): AppResult<TgBasicGroup> =
        basicGroups[basicGroupId]?.ok() ?: AppError.NotFound("basic group").err()

    override suspend fun getBasicGroupFullInfo(basicGroupId: Long): AppResult<TgBasicGroupFullInfo> =
        TgBasicGroupFullInfo("basic description").ok()

    override suspend fun searchChatMedia(
        chatId: Long,
        filter: dev.yourstream.telegram.api.model.MediaSearchFilter,
        fromMessageId: Long,
        limit: Int,
    ): AppResult<dev.yourstream.telegram.api.model.MediaMessagePage> {
        calls += "searchChatMedia:$chatId:$filter:$fromMessageId"
        return dev.yourstream.telegram.api.model.MediaMessagePage(emptyList(), 0, 0).ok()
    }

    override suspend fun getMessages(
        chatId: Long,
        messageIds: List<Long>,
    ): AppResult<List<dev.yourstream.telegram.api.model.MessageLookup>> =
        messageIds.map { dev.yourstream.telegram.api.model.MessageLookup.Missing(it) }.ok()

    override suspend fun getFile(fileId: Int): AppResult<TelegramFile> = file(fileId).ok()

    override suspend fun downloadFile(fileId: Int, priority: Int, offset: Long, limit: Long): AppResult<TelegramFile> {
        calls += "downloadFile:$fileId:$priority"
        return file(fileId).copy(isDownloadingActive = true).ok()
    }

    override suspend fun cancelDownloadFile(fileId: Int): AppResult<Unit> = Unit.ok()

    override suspend fun getFileDownloadedPrefixSize(fileId: Int, offset: Long): AppResult<Long> {
        calls += "getFileDownloadedPrefixSize:$fileId:$offset"
        return 0L.ok()
    }

    override suspend fun readFilePart(fileId: Int, offset: Long, count: Long): AppResult<ByteArray> = ByteArray(0).ok()

    override suspend fun deleteFile(fileId: Int): AppResult<Unit> {
        calls += "deleteFile:$fileId"
        return Unit.ok()
    }

    companion object {
        fun user(id: Long, first: String, username: String? = null) = TelegramUser(
            id = id, firstName = first, lastName = "", username = username, phoneNumber = "",
            isPremium = false, isBot = false, isDeleted = false,
        )

        /** A chat in the main list; higher [order] sorts first. */
        fun chat(id: Long, kind: ChatKind, order: Long, title: String = "Chat $id") = TelegramChat(
            id = id, title = title, kind = kind,
            positions = listOf(ChatPosition(ChatListType.Main, order, isPinned = false)),
            unreadCount = 0, lastMessageDate = null, hasProtectedContent = false, smallPhotoFileId = null,
        )

        fun file(id: Int) = TelegramFile(
            id = id, remoteUniqueId = "u$id", remoteId = "r$id", size = 100, expectedSize = 100, localPath = null,
            canBeDownloaded = true, isDownloadingActive = false, isDownloadingCompleted = false,
            downloadOffset = 0, downloadedPrefixSize = 0, downloadedSize = 0,
        )
    }
}
