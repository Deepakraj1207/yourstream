package dev.yourstream.media.indexer

import dev.yourstream.core.common.AppResult
import dev.yourstream.media.api.model.IndexMode
import dev.yourstream.media.indexer.engine.ContextFinder
import dev.yourstream.telegram.api.model.ChatPost
import dev.yourstream.telegram.api.model.TelegramMediaContentType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Movie channels post the title, then files captioned "Tel+Hin 450MB": the files take the title. */
@RunWith(RobolectricTestRunner::class)
class ContextTitlesTest {
    private val env = IndexerTestEnv()
    private val chat = 7L
    private val video = TelegramMediaContentType.VIDEO

    @After fun tearDown() = env.close()

    private suspend fun parsed(messageId: Long) = env.repository.getMedia(env.db.mediaDao().findKey(chat, messageId)!!.id)!!.parsed!!

    private fun channel() {
        env.telegram.post(chat, 10, "🎬 Bumblebee (2018)\nTelugu + Hindi | 480p 720p\nJoin @SPEEDY_LinkZ")
        env.telegram.add(mediaMessage(chat, 11, "VID_20240101.mp4", video, caption = "Tel+Hin 450MB"))
        env.telegram.add(mediaMessage(chat, 12, "7744f81a5b3063b9.mp4", video, caption = "Tel+Hin 1GB"))
        env.telegram.post(chat, 13, "Leo (2023) Tamil HDRip")
        env.telegram.post(chat, 14, "Join our backup channel @speedy_backup")
        env.telegram.add(mediaMessage(chat, 15, "AgADBQADq7Ex.mkv", video, caption = "Tam"))
        env.telegram.add(mediaMessage(chat, 16, "Vikram.2022.1080p.mkv", video))
    }

    @Test
    fun filesTakeTheirTitleFromThePostAbove() = runTest {
        env.enable(chat, "Speedy Links Movies")
        channel()
        env.engine.run(chat, IndexMode.INCREMENTAL, 5.minutes)

        assertEquals("Bumblebee", parsed(11).title)
        assertEquals(2018, parsed(11).year)
        assertEquals("Bumblebee", parsed(12).title)
        assertEquals("Leo", parsed(15).title, "a promotion between the title and the file is skipped")
        assertEquals("Vikram", parsed(16).title, "a file with its own title keeps it")
        assertTrue(env.telegram.historyCalls.size <= 3, "neighbouring files share one page of history: ${env.telegram.historyCalls}")

        // Re-checking the files later keeps the titles (the post above is stored, not read again).
        val calls = env.telegram.historyCalls.size
        env.engine.run(chat, IndexMode.VERIFY, 5.minutes)
        assertEquals("Bumblebee", parsed(11).title)
        assertEquals("Leo", parsed(15).title)
        assertEquals(calls, env.telegram.historyCalls.size)
    }

    @Test
    fun filesIndexedBeforeAreFixedByTheNextRun() = runTest {
        env.enable(chat, "Speedy Links Movies")
        channel()
        env.telegram.historyFails = true // like an older version: no lookups happened
        env.engine.run(chat, IndexMode.INCREMENTAL, 5.minutes)
        assertFalse(parsed(11).title == "Tel", "language tags never become the title")
        assertTrue(parsed(11).title == null || parsed(11).title == "Vid 20240101", "only the machine name, shown as untitled")

        env.telegram.historyFails = false
        env.engine.run(chat, IndexMode.INCREMENTAL, 5.minutes)
        assertEquals("Bumblebee", parsed(11).title)
        assertEquals("Leo", parsed(15).title)
    }

    @Test
    fun theWalkStopsAtTheLimits() = runTest {
        val posts = (1L..40L).map { ChatPost(it, 1_600_000_000L + it * 60, 0, if (it == 1L) "Bumblebee (2018)" else "", hasFile = it != 1L) }
        val finder = ContextFinder({ _, from -> AppResult.Ok(posts.filter { it.messageId <= from }.sortedByDescending { it.messageId }.take(10)) })
        assertEquals("", finder.find(1, 40, 1_600_000_000L + 40 * 60), "more than 12 messages above: no context")
        assertEquals("Bumblebee (2018)", finder.find(1, 5, 1_600_000_000L + 5 * 60))
        val old = ContextFinder({ _, _ -> AppResult.Ok(listOf(ChatPost(9, 0, 0, "Bumblebee (2018)", hasFile = false))) })
        assertEquals("", old.find(1, 10, 1_600_000_000L), "posts more than 6 hours older are another story")
        assertTrue(ContextFinder.namesTitle("📽 Leo (2023) Tamil 1080p"))
        assertFalse(ContextFinder.namesTitle("Join @SPEEDY_LinkZ for more"))
        assertFalse(ContextFinder.namesTitle("Tel + Hin 450MB"))
        assertFalse(ContextFinder.namesTitle("https://t.me/xyz"))
    }
}
