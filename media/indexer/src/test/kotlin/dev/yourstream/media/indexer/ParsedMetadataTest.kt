package dev.yourstream.media.indexer

import dev.yourstream.core.common.NoOpLogger
import dev.yourstream.media.api.model.IndexMode
import dev.yourstream.media.api.model.MediaQuery
import dev.yourstream.media.filename.MediaFilenameParser
import dev.yourstream.media.indexer.reparse.MediaReparser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

@RunWith(RobolectricTestRunner::class)
class ParsedMetadataTest {

    private val env = IndexerTestEnv()
    private val chat = 100L

    @After fun tearDown() = env.close()

    private suspend fun index(vararg names: Pair<Long, String>, caption: String = "") {
        env.enable(chat, title = "Doraemon Tamil Episodes")
        names.forEach { (id, name) -> env.telegram.add(mediaMessage(chat, id, name, caption = caption)) }
        env.engine.run(chat, IndexMode.INCREMENTAL, 5.minutes)
    }

    private suspend fun media(messageId: Long) = env.repository.getMedia(env.db.mediaDao().findKey(chat, messageId)!!.id)!!

    @Test
    fun originalNameAndParsedMetadataAreBothStored() = runTest {
        index(1L to "Doraemon.S03E12.Tamil.720p.mkv", 2L to "Shinchan.125.Tamil.mp4")
        val episode = media(1)
        assertEquals("Doraemon.S03E12.Tamil.720p.mkv", episode.fileName)
        val p = episode.parsed!!
        assertEquals("Doraemon", p.title)
        assertEquals("doraemon", p.normalizedTitle)
        assertEquals(3, p.season)
        assertEquals(12, p.episode)
        assertEquals(listOf("ta"), p.languages)
        assertEquals(720, p.resolution)
        assertEquals("EPISODE", p.kind)
        assertEquals(MediaFilenameParser.VERSION, p.parserVersion)
        assertTrue(p.fieldConfidence.getValue("episode") > 0.9f, "${p.fieldConfidence}")

        val absolute = media(2).parsed!!
        assertEquals("Shinchan", absolute.title)
        assertEquals(125, absolute.absoluteEpisode)
        assertEquals(125, env.repository.search(MediaQuery()).let { env.db.mediaDao().duplicateGroup(media(2).id).first().absoluteEpisode })
    }

    @Test
    fun emptyTelegramNamesStayEmptyAndChatTitleHelps() = runTest {
        // Telegram video messages often have no file name at all.
        env.enable(chat, title = "Doraemon Tamil Episodes")
        env.telegram.add(mediaMessage(chat, 5, "", type = dev.yourstream.telegram.api.model.TelegramMediaContentType.VIDEO))
        env.engine.run(chat, IndexMode.INCREMENTAL, 5.minutes)
        val record = media(5)
        assertEquals("", record.fileName, "never replaced by a made-up name")
        assertEquals("Doraemon", record.parsed!!.title)
        assertTrue(record.parsed!!.warnings.any { "chat title" in it })
    }

    @Test
    fun reparseUpgradesOldRowsFromStoredNamesWithoutTelegram() = runTest {
        index(1L to "Tom.and.Jerry.1995.720p.Hindi.mkv")
        val id = env.db.mediaDao().findKey(chat, 1)!!.id
        // Simulate a row written by an older parser: wrong title, old version.
        val old = env.db.mediaDao().getWithParsed(id)!!.parsed!!
        env.db.mediaDao().upsertParsed(old.copy(title = "tom and jerry 1995 720p hindi", parserVersion = 1))
        env.db.mediaDao().setSearchTitle(id, "garbage")
        env.telegram.calls.clear()

        val reparser = MediaReparser(env.db, NoOpLogger)
        assertEquals(1, reparser.outdatedCount())
        assertEquals(1, reparser.run(batchSize = 1))

        val fixed = env.repository.getMedia(id)!!
        assertEquals("Tom.and.Jerry.1995.720p.Hindi.mkv", fixed.fileName)
        assertEquals("Tom and Jerry", fixed.parsed!!.title)
        assertEquals(MediaFilenameParser.VERSION, fixed.parsed!!.parserVersion)
        assertEquals(0, reparser.outdatedCount())
        assertTrue(env.telegram.calls.isEmpty(), "no Telegram access needed")
        assertEquals(1, env.repository.count(MediaQuery(text = "tom jerry")).first(), "search text refreshed")
    }

    @Test
    fun reparseWalksManyRowsInBatches() = runTest {
        env.enable(chat)
        val rows = (1..450).map { env.normalizer.normalize(mediaMessage(chat, it.toLong(), "Show.S01E${it % 50}.mkv"))!! }
        env.store.upsert(rows)
        val dao = env.db.mediaDao()
        (1..450L).forEach { m -> val key = dao.findKey(chat, m)!!; dao.upsertParsed(dao.getWithParsed(key.id)!!.parsed!!.copy(parserVersion = 1)) }

        assertEquals(450, MediaReparser(env.db, NoOpLogger).run(batchSize = 100))
        assertEquals(0, dao.countOutdatedParses(MediaFilenameParser.VERSION))
    }
}
