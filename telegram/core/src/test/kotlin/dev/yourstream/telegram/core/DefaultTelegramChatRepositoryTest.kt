package dev.yourstream.telegram.core

import app.cash.turbine.test
import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import dev.yourstream.telegram.api.model.ChatFilter
import dev.yourstream.telegram.api.model.ChatKind
import dev.yourstream.telegram.api.model.ChatListType
import dev.yourstream.telegram.api.model.ChatPosition
import dev.yourstream.telegram.core.FakeTelegramClient.Companion.chat
import dev.yourstream.telegram.core.FakeTelegramClient.Companion.user
import dev.yourstream.telegram.core.client.TelegramEvent
import dev.yourstream.telegram.core.client.TgSupergroup
import dev.yourstream.telegram.core.client.TgSupergroupFullInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultTelegramChatRepositoryTest {

    /** Five chats: private, basic group, supergroup, channel, private. Ordered 50 → 10. */
    private suspend fun TestScope.loggedIn(configure: FakeTelegramClient.() -> Unit = {}): TelegramTestEnv {
        val env = TelegramTestEnv(this)
        env.client.hasSavedSession = true
        env.client.serverChats += listOf(
            chat(1, ChatKind.Private(101), order = 50),
            chat(2, ChatKind.BasicGroup(201), order = 40),
            chat(3, ChatKind.Supergroup(301), order = 30),
            chat(4, ChatKind.Channel(401), order = 20),
            chat(5, ChatKind.Private(105), order = 10),
        )
        env.client.configure()
        env.manager.start()
        advanceUntilIdle()
        return env
    }

    @Test
    fun loadMorePagesUntilTelegramHasNoMore() = runTest {
        val env = loggedIn()

        env.chats.loadMore(ChatListType.Main, pageSize = 2)
        advanceUntilIdle()
        var state = env.chats.chatList().first()
        assertEquals(listOf(1L, 2L), state.chats.map { it.id })
        assertTrue(state.hasMore)

        env.chats.loadMore(ChatListType.Main, pageSize = 2)
        env.chats.loadMore(ChatListType.Main, pageSize = 2)
        advanceUntilIdle()
        state = env.chats.chatList().first()
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), state.chats.map { it.id })
        assertTrue(state.hasMore, "TDLib only says 'no more' on the next call")

        env.chats.loadMore(ChatListType.Main, pageSize = 2)
        advanceUntilIdle()
        state = env.chats.chatList().first()
        assertFalse(state.hasMore)
        assertFalse(state.isLoading)

        val loadCalls = env.client.calls.count { it.startsWith("loadChats") }
        env.chats.loadMore(ChatListType.Main, pageSize = 2)
        assertEquals(loadCalls, env.client.calls.count { it.startsWith("loadChats") }, "no calls after the end")
    }

    @Test
    fun loadAtLeastLoadsSeveralPages() = runTest {
        val env = loggedIn()
        env.chats.loadAtLeast(ChatListType.Main, minCount = 4)
        advanceUntilIdle()
        assertTrue(env.chats.chatList().first().chats.size >= 4)
    }

    @Test
    fun filtersSplitPrivateGroupsAndChannels() = runTest {
        val env = loggedIn()
        env.chats.loadAtLeast(ChatListType.Main, 5)
        advanceUntilIdle()

        suspend fun ids(filter: ChatFilter) = env.chats.chatList(ChatListType.Main, filter).first().chats.map { it.id }
        assertEquals(listOf(1L, 5L), ids(ChatFilter.PRIVATE))
        assertEquals(listOf(2L, 3L), ids(ChatFilter.GROUPS))
        assertEquals(listOf(4L), ids(ChatFilter.CHANNELS))
        assertEquals(5, ids(ChatFilter.ALL).size)
    }

    @Test
    fun missingUpdatesAreReconciledWithGetChat() = runTest {
        val env = loggedIn { silentLoad = true }
        env.chats.loadMore(ChatListType.Main, pageSize = 3)
        advanceUntilIdle()

        assertEquals(listOf(1L, 2L, 3L), env.chats.chatList().first().chats.map { it.id })
        assertTrue("getChat:1" in env.client.calls)
    }

    @Test
    fun liveUpdatesReorderTheList() = runTest {
        val env = loggedIn()
        env.chats.loadAtLeast(ChatListType.Main, 5)
        advanceUntilIdle()

        env.chats.chatList().test {
            assertEquals(1L, awaitItem().chats.first().id)
            env.client.emit(TelegramEvent.ChatPositionChanged(5, ChatPosition(ChatListType.Main, 999, isPinned = true)))
            assertEquals(5L, awaitItem().chats.first().id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun errorsAreSurfacedInTheState() = runTest {
        val env = loggedIn { loadError = AppError.RateLimited(30) }
        val result = env.chats.loadMore()
        assertEquals(AppError.RateLimited(30), (result as AppResult.Err).error)
        val state = env.chats.chatList().first()
        assertEquals(AppError.RateLimited(30), state.error)
        assertFalse(state.isLoading)
    }

    @Test
    fun logoutClearsChatsFromMemory() = runTest {
        val env = loggedIn()
        env.chats.loadAtLeast(ChatListType.Main, 5)
        advanceUntilIdle()

        env.auth.logOut()
        advanceUntilIdle()

        val state = env.chats.chatList().first()
        assertTrue(state.chats.isEmpty())
        assertTrue(state.hasMore)
        assertNull(env.chats.observeChat(1).first())
    }

    @Test
    fun channelDetailsUseFullInfo() = runTest {
        val env = loggedIn {
            supergroups[401] = TgSupergroup(401, username = "movies", memberCount = 10, isChannel = true, isForum = false, date = 1_600_000_000)
            supergroupFull[401] = TgSupergroupFullInfo(description = "All the films", memberCount = 1234, linkedChatId = 0)
        }
        val details = (env.chats.getChatDetails(4) as AppResult.Ok).value
        assertEquals("movies", details.username)
        assertEquals("All the films", details.description)
        assertEquals(1234, details.memberCount)
        assertEquals(1_600_000_000L, details.createdAt)
        assertNull(details.linkedChatId)
    }

    @Test
    fun supergroupDetailsSurviveRefusedFullInfo() = runTest {
        val env = loggedIn {
            supergroups[301] = TgSupergroup(301, username = null, memberCount = 77, isChannel = false, isForum = true, date = 0)
        }
        val details = (env.chats.getChatDetails(3) as AppResult.Ok).value
        assertNull(details.description)
        assertEquals(77, details.memberCount)
        assertTrue(details.isForum)
    }

    @Test
    fun privateChatDetailsIncludeTheUser() = runTest {
        val env = loggedIn { users[101] = user(101, "Grace", username = "grace") }
        val details = (env.chats.getChatDetails(1) as AppResult.Ok).value
        assertEquals("Grace", details.user?.displayName)
        assertEquals("grace", details.username)
        assertEquals("bio of 101", details.description)
    }

    @Test
    fun unknownChatIsNotFound() = runTest {
        val env = loggedIn()
        assertIs<AppError.NotFound>((env.chats.getChatDetails(999) as AppResult.Err).error)
    }
}
