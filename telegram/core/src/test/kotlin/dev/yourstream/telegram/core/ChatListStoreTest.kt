package dev.yourstream.telegram.core

import dev.yourstream.telegram.api.model.ChatKind
import dev.yourstream.telegram.api.model.ChatListType
import dev.yourstream.telegram.api.model.ChatPosition
import dev.yourstream.telegram.core.FakeTelegramClient.Companion.chat
import dev.yourstream.telegram.core.chat.ChatListStore
import dev.yourstream.telegram.core.client.TelegramEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatListStoreTest {

    private val store = ChatListStore()
    private fun mainIds() = ChatListStore.sortedIn(store.chats.value, ChatListType.Main).map { it.id }

    @Test
    fun chatsAreSortedByDescendingOrder() {
        store.apply(TelegramEvent.ChatAdded(chat(1, ChatKind.Private(1), order = 10)))
        store.apply(TelegramEvent.ChatAdded(chat(2, ChatKind.Private(2), order = 30)))
        store.apply(TelegramEvent.ChatAdded(chat(3, ChatKind.Private(3), order = 20)))
        assertEquals(listOf(2L, 3L, 1L), mainIds())
    }

    @Test
    fun positionUpdatesReorderAndOrderZeroRemovesFromList() {
        store.apply(TelegramEvent.ChatAdded(chat(1, ChatKind.Private(1), order = 10)))
        store.apply(TelegramEvent.ChatAdded(chat(2, ChatKind.Private(2), order = 20)))

        store.apply(TelegramEvent.ChatPositionChanged(1, ChatPosition(ChatListType.Main, 99, isPinned = true)))
        assertEquals(listOf(1L, 2L), mainIds())

        store.apply(TelegramEvent.ChatPositionChanged(2, ChatPosition(ChatListType.Main, 0, isPinned = false)))
        assertEquals(listOf(1L), mainIds())
        assertTrue(store.chats.value.containsKey(2), "chat is still known, just not in the list")
    }

    @Test
    fun aChatCanBeInSeveralLists() {
        store.apply(TelegramEvent.ChatAdded(chat(1, ChatKind.Channel(7), order = 10)))
        store.apply(TelegramEvent.ChatPositionChanged(1, ChatPosition(ChatListType.Folder(3), 5, isPinned = false)))
        assertEquals(1, store.countIn(ChatListType.Main))
        assertEquals(1, store.countIn(ChatListType.Folder(3)))
        assertEquals(0, store.countIn(ChatListType.Archive))
    }

    @Test
    fun lastMessageReplacesPositionsAndDate() {
        store.apply(TelegramEvent.ChatAdded(chat(1, ChatKind.Private(1), order = 10)))
        store.apply(
            TelegramEvent.ChatLastMessageChanged(1, lastMessageDate = 1_700_000_000, positions = listOf(ChatPosition(ChatListType.Archive, 4, false))),
        )
        val chat = store.chats.value.getValue(1)
        assertEquals(1_700_000_000L, chat.lastMessageDate)
        assertEquals(0, store.countIn(ChatListType.Main))
        assertEquals(1, store.countIn(ChatListType.Archive))
    }

    @Test
    fun titleAndUnreadPatchesIgnoreUnknownChats() {
        store.apply(TelegramEvent.ChatAdded(chat(1, ChatKind.Private(1), order = 10)))
        store.apply(TelegramEvent.ChatTitleChanged(1, "Renamed"))
        store.apply(TelegramEvent.ChatUnreadCountChanged(1, 7))
        store.apply(TelegramEvent.ChatTitleChanged(999, "Ghost"))
        assertEquals("Renamed", store.chats.value.getValue(1).title)
        assertEquals(7, store.chats.value.getValue(1).unreadCount)
        assertFalse(store.chats.value.containsKey(999))
    }

    @Test
    fun unrelatedEventsAreNotHandled() {
        assertFalse(store.apply(TelegramEvent.FileChanged(FakeTelegramClient.file(1))))
    }
}
