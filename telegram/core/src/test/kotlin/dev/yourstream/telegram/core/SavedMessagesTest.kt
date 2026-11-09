package dev.yourstream.telegram.core

import dev.yourstream.core.common.AppResult
import dev.yourstream.telegram.core.file.DefaultTelegramFileRepository
import dev.yourstream.telegram.core.saved.DefaultTelegramSavedMessages
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SavedMessagesTest {
    private val client = FakeTelegramClient()
    private val store = DefaultTelegramSavedMessages(client, DefaultTelegramFileRepository(client)).apply {
        uploadDir = Files.createTempDirectory("saved").toFile()
    }

    @Test
    fun replaceKeepsOneTaggedCopyAndLeavesOtherMessagesAlone() = runBlocking {
        client.saved += dev.yourstream.telegram.core.client.TgDocumentMessage(500, 1, 1, "notes.pdf", "my own notes", 10)
        store.replace("#app-sync", "sync.bin", "#app-sync v1", byteArrayOf(1, 2, 3))
        store.replace("#app-sync", "sync.bin", "#app-sync v1", byteArrayOf(4, 5))

        val found = (store.find("#app-sync") as AppResult.Ok).value
        assertEquals(1, found.size, "the older copy was deleted")
        assertContentEquals(byteArrayOf(4, 5), (store.read(found.single()) as AppResult.Ok).value)
        assertEquals(setOf(500L, 2L), client.saved.map { it.messageId }.toSet(), "untagged messages are never touched")
        assertIs<AppResult.Err>(store.read(found.single(), maxBytes = 1), "size limit")
        Unit
    }
}
