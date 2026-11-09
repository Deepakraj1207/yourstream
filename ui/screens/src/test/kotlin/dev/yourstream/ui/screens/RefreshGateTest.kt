package dev.yourstream.ui.screens

import dev.yourstream.media.api.MediaIndexRepository
import dev.yourstream.media.api.model.ChatIndexStatus
import dev.yourstream.media.api.model.IndexMode
import dev.yourstream.media.api.model.IndexPhase
import dev.yourstream.media.api.model.IndexProgress
import dev.yourstream.media.api.model.IndexState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class RefreshGateTest {
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

    @Test
    fun holdsUpdatesWhileIndexingUntilLoadOrDone() = runTest {
        val chats = MutableStateFlow(listOf(chat(1, IndexState.RUNNING, progress(1, 1, 0, -1))))
        val gate = LibraryRefreshGate(stub<MediaIndexRepository> { name, _ -> if (name == "observeChats") chats else error(name) })
        val source = MutableStateFlow(1)
        val seen = mutableListOf<Int>()
        val job = launch { gate.hold(source).collect { seen += it } }
        advanceUntilIdle()
        source.value = 2; advanceUntilIdle()
        source.value = 3; advanceUntilIdle()
        assertEquals(listOf(1), seen, "indexing: the first value stays")
        gate.load(); advanceUntilIdle()
        assertEquals(listOf(1, 3), seen, "Load files applies the latest")
        source.value = 4; advanceUntilIdle()
        assertEquals(listOf(1, 3), seen)
        chats.value = listOf(chat(1, IndexState.COMPLETED)); advanceUntilIdle()
        assertEquals(listOf(1, 3, 4), seen, "indexing finished: live again")
        source.value = 5; advanceUntilIdle()
        assertEquals(listOf(1, 3, 4, 5), seen)
        job.cancel()
    }
}
