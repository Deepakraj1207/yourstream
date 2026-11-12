package dev.yourstream.telegram.core.client

import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
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
import kotlinx.coroutines.flow.Flow

/**
 * Low-level port over one TDLib instance. The only implementation that touches TDLib lives in
 * `:telegram:tdlib`; tests use a fake.
 *
 * Contract:
 *  - [events] is hot and never completes. It carries events of whichever instance is current.
 *  - [open] creates a new TDLib instance if none is open. The instance reports its first
 *    [TelegramEvent.AuthStateChanged] through [events].
 *  - After [AuthState.Closed] the instance is dead; [open] must be called to get a new one.
 *  - Every call returns [AppResult]; none throws for Telegram or network failures. Calls made
 *    with no open instance fail with [dev.yourstream.core.common.AppError.InvalidState].
 */
interface TelegramClient {
    val events: Flow<TelegramEvent>

    suspend fun open(): AppResult<Unit>
    suspend fun close(): AppResult<Unit>

    suspend fun getAuthState(): AppResult<AuthState>
    suspend fun setParameters(config: TelegramConfig): AppResult<Unit>
    suspend fun setPhoneNumber(phoneNumber: String): AppResult<Unit>
    suspend fun resendCode(): AppResult<Unit>
    suspend fun checkCode(code: String): AppResult<Unit>
    suspend fun checkPassword(password: String): AppResult<Unit>
    suspend fun setEmailAddress(email: String): AppResult<Unit>
    suspend fun checkEmailCode(code: String): AppResult<Unit>
    suspend fun requestQrCode(): AppResult<Unit>
    suspend fun logOut(): AppResult<Unit>

    suspend fun setNetworkType(type: NetworkType): AppResult<Unit>

    suspend fun getMe(): AppResult<TelegramUser>
    suspend fun getUser(userId: Long): AppResult<TelegramUser>
    suspend fun getUserFullInfo(userId: Long): AppResult<TgUserFullInfo>

    /** Asks Telegram for [limit] more chats of [list]. */
    suspend fun loadChats(list: ChatListType, limit: Int): AppResult<LoadChatsResult>

    /** The first [limit] chat ids of [list], in order, from what is loaded locally. */
    suspend fun getChatIds(list: ChatListType, limit: Int): AppResult<List<Long>>
    suspend fun getChat(chatId: Long): AppResult<TelegramChat>

    suspend fun getSupergroup(supergroupId: Long): AppResult<TgSupergroup>
    suspend fun getSupergroupFullInfo(supergroupId: Long): AppResult<TgSupergroupFullInfo>
    suspend fun getBasicGroup(basicGroupId: Long): AppResult<TgBasicGroup>
    suspend fun getBasicGroupFullInfo(basicGroupId: Long): AppResult<TgBasicGroupFullInfo>

    /** searchChatMessages with a media filter. Messages without a supported file are dropped. */
    suspend fun searchChatMedia(chatId: Long, filter: MediaSearchFilter, fromMessageId: Long, limit: Int): AppResult<MediaMessagePage>

    /** getChatHistory from [fromMessageId] (included) towards older messages. */
    suspend fun getChatHistory(chatId: Long, fromMessageId: Long, limit: Int): AppResult<List<dev.yourstream.telegram.api.model.ChatPost>> = AppResult.Ok(emptyList())

    /** getMessages: one [MessageLookup] per requested id, same order. */
    suspend fun getMessages(chatId: Long, messageIds: List<Long>): AppResult<List<MessageLookup>>

    suspend fun getFile(fileId: Int): AppResult<TelegramFile>
    suspend fun downloadFile(fileId: Int, priority: Int, offset: Long, limit: Long): AppResult<TelegramFile>
    suspend fun cancelDownloadFile(fileId: Int): AppResult<Unit>
    suspend fun getFileDownloadedPrefixSize(fileId: Int, offset: Long): AppResult<Long>
    suspend fun readFilePart(fileId: Int, offset: Long, count: Long): AppResult<ByteArray>
    suspend fun deleteFile(fileId: Int): AppResult<Unit>

    /** optimizeStorage to zero: deletes every downloaded file (streamed or saved). Remote files are untouched. */
    suspend fun deleteAllLocalFiles(): AppResult<Unit> = AppResult.Err(AppError.InvalidState("Not supported by this client"))

    // ---- Saved Messages (optional progress sync)

    /** Id of the user's own "Saved Messages" chat (a private chat with themselves). */
    suspend fun savedMessagesChatId(): AppResult<Long>

    /** Document messages of [chatId] whose text matches [query], newest first. */
    suspend fun searchDocuments(chatId: Long, query: String, limit: Int): AppResult<List<TgDocumentMessage>>

    /** Sends a local file as a document; returns the (pending) message id. */
    suspend fun sendDocument(chatId: Long, localPath: String, caption: String): AppResult<Long>

    suspend fun deleteMessages(chatId: Long, messageIds: List<Long>): AppResult<Unit>
}

enum class LoadChatsResult {
    /** More chats were (or may have been) loaded. */
    LOADED,
    /** Telegram has no more chats in this list (TDLib answers 404). */
    NO_MORE_CHATS,
}
