package dev.yourstream.ui.screens

import dev.yourstream.media.api.model.ChatIndexStatus
import dev.yourstream.media.api.model.IndexMode
import dev.yourstream.media.api.model.IndexPhase
import dev.yourstream.media.api.model.IndexProgress
import dev.yourstream.media.api.model.IndexState
import org.junit.Test
import kotlin.test.assertEquals

class IndexingStatusTest {
    private fun chat(id: Long, state: IndexState, progress: IndexProgress? = null, enabled: Boolean = true) =
        ChatIndexStatus(id, "Chat $id", enabled, state, 10, 0, false, null, null, null, progress)

    private fun progress(id: Long, seen: Int, added: Int, total: Int) = IndexProgress(id, IndexMode.INCREMENTAL, IndexPhase.HEAD, null, 1, seen, added, 1, 0, total, 0)

    @Test
    fun statusSumsRunningChats() {
        val s = IndexingStatus.of(listOf(
            chat(1, IndexState.RUNNING, progress(1, 100, 9, 400)),
            chat(2, IndexState.RUNNING, progress(2, 50, 2, -1)),
            chat(3, IndexState.QUEUED),
            chat(4, IndexState.RUNNING, progress(4, 1, 1, 1), enabled = false),
            chat(5, IndexState.COMPLETED),
        ))
        assertEquals(IndexingStatus(chats = 2, queued = 1, found = 13, scanned = 150, total = 400), s)
        assertEquals(true, s.active)
        assertEquals(false, IndexingStatus.of(listOf(chat(5, IndexState.COMPLETED))).active)
    }
}
