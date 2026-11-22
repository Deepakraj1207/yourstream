package dev.yourstream.desktop

import dev.yourstream.core.common.NoOpLogger
import dev.yourstream.desktop.data.DesktopIndexStore
import dev.yourstream.media.api.model.IndexState
import dev.yourstream.media.indexer.store.ScanCursor
import dev.yourstream.media.parser.MediaNormalizer
import dev.yourstream.telegram.api.model.MediaSearchFilter
import dev.yourstream.telegram.api.model.TelegramMediaContentType
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopIndexStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val normalizer = MediaNormalizer()

    private fun media(chat: Long, id: Long, name: String) =
        normalizer.normalize(mediaMessage(chat, id, name, TelegramMediaContentType.VIDEO, mime = "video/x-matroska"))!!

    @Test
    fun survivesRestartWithMediaCursorsAndChats() = runTest {
        val file = File(tmp.root, "index.jsonl")
        val a = DesktopIndexStore(file, NoOpLogger)
        a.addChat(1, "Movies")
        val cursor = ScanCursor(1, MediaSearchFilter.VIDEO, newestMessageId = 30, backfillFromMessageId = 10)
        val r = a.savePage(listOf(media(1, 30, "Vikram (2022) 1080p.mkv"), media(1, 20, "Leo.2023.720p.mkv")), cursor)
        assertEquals(2, r.added)
        // The same page again changes nothing.
        assertEquals(2, a.savePage(listOf(media(1, 30, "Vikram (2022) 1080p.mkv"), media(1, 20, "Leo.2023.720p.mkv")), cursor).unchanged)
        a.saveCursor(cursor.copy(segmentLowerBound = 5, backfillFromMessageId = 8))
        a.setState(1, IndexState.IDLE)
        a.markDeleted(1, listOf(20))
        a.close()

        val b = DesktopIndexStore(file, NoOpLogger)
        assertEquals(cursor, b.cursor(1, MediaSearchFilter.VIDEO))
        assertEquals(1, b.segments(1, MediaSearchFilter.VIDEO).size)
        assertEquals(listOf("Vikram"), b.allMedia().map { it.title })
        assertEquals(listOf(30L), b.liveMessageIdsAfter(1, 0, 10))
        assertNotNull(b.chat(1))
        assertTrue(b.isEnabled(1))
        b.deleteSegments(1, MediaSearchFilter.VIDEO)
        assertEquals(0, b.segments(1, MediaSearchFilter.VIDEO).size)
        b.close()
    }

    /** A crash in the middle of a write leaves a cut-off last line: it is skipped, the rest loads. */
    @Test
    fun cutOffLastLineIsSkipped() = runTest {
        val file = File(tmp.root, "index.jsonl")
        val a = DesktopIndexStore(file, NoOpLogger)
        a.addChat(1, "Movies")
        a.savePage(listOf(media(1, 30, "Vikram (2022) 1080p.mkv")), ScanCursor(1, MediaSearchFilter.VIDEO, 30, 30))
        a.close()
        file.appendText("{\"t\":\"m\",\"r\":{\"chatId\":1,\"messageId\":")
        val b = DesktopIndexStore(file, NoOpLogger)
        assertEquals(1, b.allMedia().size)
        assertEquals(30, b.cursor(1, MediaSearchFilter.VIDEO).newestMessageId)
        b.close()
    }

    @Test
    fun removingAChatForgetsItsFiles() = runTest {
        val file = File(tmp.root, "index.jsonl")
        val a = DesktopIndexStore(file, NoOpLogger)
        a.addChat(1, "A")
        a.addChat(2, "B")
        a.savePage(listOf(media(1, 1, "Vikram (2022).mkv"), media(2, 1, "Leo (2023).mkv")), null)
        a.removeChat(1)
        assertEquals(listOf(2L), a.allMedia().map { it.chatId })
        assertNull(a.chat(1))
        a.close()
        assertEquals(listOf(2L), DesktopIndexStore(file, NoOpLogger).allMedia().map { it.chatId })
    }

    @Test
    fun largeJournalIsCompactedOnLoad() = runTest {
        val file = File(tmp.root, "index.jsonl")
        val a = DesktopIndexStore(file, NoOpLogger)
        a.addChat(1, "A")
        // 12 000 cursor saves for one cursor: many lines, one live record.
        repeat(12_000) { a.saveCursor(ScanCursor(1, MediaSearchFilter.VIDEO, newestMessageId = it.toLong())) }
        a.close()
        val before = file.readLines().size
        val b = DesktopIndexStore(file, NoOpLogger)
        b.close()
        assertTrue(file.readLines().size < before / 100, "journal rewritten")
        assertEquals(11_999, DesktopIndexStore(file, NoOpLogger).cursor(1, MediaSearchFilter.VIDEO).newestMessageId)
    }
}
