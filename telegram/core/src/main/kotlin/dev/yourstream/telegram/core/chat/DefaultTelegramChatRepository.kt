package dev.yourstream.telegram.core.chat

import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import dev.yourstream.core.common.Logger
import dev.yourstream.core.common.getOrNull
import dev.yourstream.core.common.ok
import dev.yourstream.core.common.onOk
import dev.yourstream.telegram.api.TelegramChatRepository
import dev.yourstream.telegram.api.model.AuthState
import dev.yourstream.telegram.api.model.ChatFilter
import dev.yourstream.telegram.api.model.ChatKind
import dev.yourstream.telegram.api.model.ChatListState
import dev.yourstream.telegram.api.model.ChatListType
import dev.yourstream.telegram.api.model.TelegramChat
import dev.yourstream.telegram.api.model.TelegramChatDetails
import dev.yourstream.telegram.core.TelegramScope
import dev.yourstream.telegram.core.client.LoadChatsResult
import dev.yourstream.telegram.core.client.TelegramClient
import dev.yourstream.telegram.core.client.TelegramEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultTelegramChatRepository @Inject constructor(
    private val client: TelegramClient,
    @TelegramScope scope: CoroutineScope,
    private val logger: Logger,
) : TelegramChatRepository {

    private val store = ChatListStore()

    private data class PageState(val isLoading: Boolean = false, val hasMore: Boolean = true, val error: AppError? = null)

    private val pages = MutableStateFlow<Map<ChatListType, PageState>>(emptyMap())

    /** TDLib must not run two loadChats for the same list at once; one lock keeps it simple. */
    private val loadLock = Mutex()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            client.events.collect { event ->
                if (event is TelegramEvent.AuthStateChanged) {
                    // A closed instance's chats are gone; a logout must not leave chats in memory.
                    if (event.state == AuthState.Closed || event.state == AuthState.LoggingOut) reset()
                } else {
                    store.apply(event)
                }
            }
        }
    }

    override fun chatList(list: ChatListType): Flow<ChatListState> =
        combine(store.chats, pages) { chats, pageMap ->
            val page = pageMap[list] ?: PageState()
            ChatListState(
                list = list,
                chats = ChatListStore.sortedIn(chats, list),
                isLoading = page.isLoading,
                hasMore = page.hasMore,
                error = page.error,
            )
        }.distinctUntilChanged()

    override fun chatList(list: ChatListType, filter: ChatFilter): Flow<ChatListState> =
        if (filter == ChatFilter.ALL) chatList(list)
        else chatList(list).map { state -> state.copy(chats = state.chats.filter(filter::accepts)) }.distinctUntilChanged()

    override suspend fun loadMore(list: ChatListType, pageSize: Int): AppResult<Unit> = loadLock.withLock {
        loadPageLocked(list, pageSize.coerceIn(1, MAX_PAGE_SIZE))
    }

    override suspend fun loadAtLeast(list: ChatListType, minCount: Int): AppResult<Unit> = loadLock.withLock {
        var rounds = 0
        while (store.countIn(list) < minCount && (pages.value[list]?.hasMore != false) && rounds < MAX_ROUNDS) {
            val missing = minCount - store.countIn(list)
            val result = loadPageLocked(list, missing.coerceIn(1, MAX_PAGE_SIZE))
            if (result is AppResult.Err) return@withLock result
            rounds++
        }
        Unit.ok()
    }

    private suspend fun loadPageLocked(list: ChatListType, pageSize: Int): AppResult<Unit> {
        if (pages.value[list]?.hasMore == false) return Unit.ok()
        setPage(list) { it.copy(isLoading = true, error = null) }

        val before = store.countIn(list)
        val loaded = client.loadChats(list, pageSize)
        if (loaded is AppResult.Err) {
            logger.w(TAG, "loadChats($list) failed: ${loaded.error.message}")
            setPage(list) { it.copy(isLoading = false, error = loaded.error) }
            return loaded
        }
        val noMore = (loaded as AppResult.Ok).value == LoadChatsResult.NO_MORE_CHATS

        // TDLib sends updateNewChat before answering, but our collector may not have applied
        // them yet. Ask for the ids TDLib now has and fetch any we do not, so the page is
        // complete when this returns.
        val reconciled = reconcile(list, before + pageSize)
        logger.d(TAG, "loadChats($list, $pageSize): ${store.countIn(list)} chats, noMore=$noMore")
        setPage(list) { it.copy(isLoading = false, hasMore = !noMore, error = reconciled.errorOrNullValue()) }
        return Unit.ok()
    }

    private suspend fun reconcile(list: ChatListType, limit: Int): AppResult<Unit> {
        val ids = when (val result = client.getChatIds(list, limit)) {
            is AppResult.Ok -> result.value
            is AppResult.Err -> return result
        }
        val known = store.chats.value
        for (id in ids) {
            val chat = known[id]
            if (chat == null || chat.positionIn(list) == null) {
                client.getChat(id).onOk(store::upsert)
            }
        }
        return Unit.ok()
    }

    override fun observeChat(chatId: Long): Flow<TelegramChat?> =
        store.chats.map { it[chatId] }.distinctUntilChanged()

    override suspend fun getChat(chatId: Long): AppResult<TelegramChat> {
        store.chats.value[chatId]?.let { return it.ok() }
        return client.getChat(chatId).onOk(store::upsert)
    }

    override suspend fun getChatDetails(chatId: Long): AppResult<TelegramChatDetails> {
        val chat = when (val result = getChat(chatId)) {
            is AppResult.Ok -> result.value
            is AppResult.Err -> return result
        }
        // Full info can be refused (e.g. no admin rights); details are still useful without it.
        return when (val kind = chat.kind) {
            is ChatKind.Private -> userDetails(chat, kind.userId)
            is ChatKind.Secret -> userDetails(chat, kind.userId)
            is ChatKind.Supergroup -> supergroupDetails(chat, kind.supergroupId)
            is ChatKind.Channel -> supergroupDetails(chat, kind.supergroupId)
            is ChatKind.BasicGroup -> {
                val group = client.getBasicGroup(kind.basicGroupId)
                if (group is AppResult.Err) return group
                val info = (group as AppResult.Ok).value
                val full = client.getBasicGroupFullInfo(kind.basicGroupId).getOrNull()
                TelegramChatDetails(
                    chat = chat,
                    username = null,
                    description = full?.description?.ifBlank { null },
                    memberCount = info.memberCount,
                    isForum = false,
                    createdAt = null,
                    linkedChatId = info.upgradedToSupergroupId.takeIf { it != 0L },
                    user = null,
                ).ok()
            }
        }
    }

    private suspend fun userDetails(chat: TelegramChat, userId: Long): AppResult<TelegramChatDetails> {
        val user = client.getUser(userId)
        if (user is AppResult.Err) return user
        val value = (user as AppResult.Ok).value
        val full = client.getUserFullInfo(userId).getOrNull()
        return TelegramChatDetails(
            chat = chat,
            username = value.username,
            description = full?.bio?.ifBlank { null },
            memberCount = null,
            isForum = false,
            createdAt = null,
            linkedChatId = null,
            user = value,
        ).ok()
    }

    private suspend fun supergroupDetails(chat: TelegramChat, supergroupId: Long): AppResult<TelegramChatDetails> {
        val group = client.getSupergroup(supergroupId)
        if (group is AppResult.Err) return group
        val info = (group as AppResult.Ok).value
        val full = client.getSupergroupFullInfo(supergroupId).getOrNull()
        return TelegramChatDetails(
            chat = chat,
            username = info.username,
            description = full?.description?.ifBlank { null },
            memberCount = (full?.memberCount ?: 0).takeIf { it > 0 } ?: info.memberCount.takeIf { it > 0 },
            isForum = info.isForum,
            createdAt = info.date.takeIf { it > 0 },
            linkedChatId = full?.linkedChatId?.takeIf { it != 0L },
            user = null,
        ).ok()
    }

    private fun reset() {
        store.clear()
        pages.value = emptyMap()
    }

    private inline fun setPage(list: ChatListType, crossinline change: (PageState) -> PageState) {
        pages.update { it + (list to change(it[list] ?: PageState())) }
    }

    private fun AppResult<*>.errorOrNullValue(): AppError? = (this as? AppResult.Err)?.error


    private companion object {
        const val TAG = "TgChats"
        const val MAX_PAGE_SIZE = 100
        const val MAX_ROUNDS = 50
    }
}
