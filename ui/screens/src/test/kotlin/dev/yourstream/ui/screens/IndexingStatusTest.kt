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
            chat(1, IndexState.SCANNING, progress(1, 100, 9, 400)),
            chat(2, IndexState.SCANNING, progress(2, 50, 2, -1)),
            chat(3, IndexState.QUEUED),
            chat(4, IndexState.SCANNING, progress(4, 1, 1, 1), enabled = false),
            chat(5, IndexState.COMPLETED),
        ))
        assertEquals(IndexingStatus(chats = 2, queued = 1, found = 13, scanned = 150, total = 400), s)
        assertEquals(true, s.active)
        assertEquals(false, IndexingStatus.of(listOf(chat(5, IndexState.COMPLETED))).active)
    }

    @Test
    fun speedAndTimeLeftAreSmoothed() {
        val base = IndexingStatus(chats = 1, scanned = 0, total = 10_000)
        var speed = IndexingMonitor.Speed().next(base, 1_000)
        assertEquals(null, speed.status.perSecond, "one sample: no speed yet")
        speed = speed.next(base.copy(scanned = 1_000), 11_000) // 100/s
        assertEquals(100f, speed.status.perSecond)
        assertEquals(90L, speed.status.etaSeconds, "9,000 left at 100/s")
        speed = speed.next(base.copy(scanned = 4_000), 21_000) // 300/s now: smoothed towards it
        assertEquals(160f, speed.status.perSecond)
        assertEquals(37L, speed.status.etaSeconds)
        assertEquals(null, speed.next(IndexingStatus(), 22_000).status.perSecond, "idle: reset")
        assertEquals("1 min", dev.yourstream.ui.screens.navigation.durationLabel(40))
        assertEquals("1 h 5 min", dev.yourstream.ui.screens.navigation.durationLabel(3_900))
    }
}
