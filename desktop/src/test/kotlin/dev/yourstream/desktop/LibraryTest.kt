package dev.yourstream.desktop

import dev.yourstream.core.common.Clock
import dev.yourstream.core.common.NoOpLogger
import dev.yourstream.desktop.data.DesktopIndexStore
import dev.yourstream.desktop.data.LibraryBuilder
import dev.yourstream.desktop.data.MediaRecord
import dev.yourstream.desktop.data.resolutionLabel
import dev.yourstream.media.api.model.IndexMode
import dev.yourstream.media.api.model.IndexRunResult
import dev.yourstream.media.indexer.engine.EngineConfig
import dev.yourstream.media.indexer.engine.IndexProgressTracker
import dev.yourstream.media.indexer.engine.IndexingEngine
import dev.yourstream.media.parser.MediaNormalizer
import dev.yourstream.telegram.api.model.TelegramMediaContentType
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class LibraryTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(60)

    private fun video(chat: Long, id: Long, name: String, height: Int? = 1080) =
        mediaMessage(chat, id, name, TelegramMediaContentType.VIDEO, mime = "video/x-matroska", height = height)

    /**
     * The shared indexing engine (the Android app's, unchanged) over a fake Telegram chat, writing
     * into the desktop store, then the desktop library built from it.
     */
    @Test
    fun indexesAChatIntoTitles() = runTest {
        val telegram = FakeMessages()
        listOf(
            video(1, 1, "Vikram (2022) Tamil 1080p WEB-DL.mkv"),
            video(1, 2, "Vikram.2022.2160p.HEVC.mkv", height = 2160),
            video(1, 3, "Leo (2023) 720p HDRip.mkv", height = 720),
            video(1, 4, "Breaking.Bad.S01E01.720p.mkv", height = 720),
            video(1, 5, "Breaking.Bad.S01E02.720p.mkv", height = 720),
            video(1, 6, "Breaking Bad S02E01 1080p.mkv"),
            video(1, 7, "Interstellar.2014.2160p.mkv.001", height = 2160),
            video(1, 8, "Interstellar.2014.2160p.mkv.002", height = 2160),
        ).forEach(telegram::add)
        val store = DesktopIndexStore(File(tmp.root, "index.jsonl"), NoOpLogger)
        store.addChat(1, "My movies")
        val engine = IndexingEngine(telegram, store, MediaNormalizer(), IndexProgressTracker(), Clock.System, NoOpLogger,
            EngineConfig(pageSize = 3, pageDelay = 0.milliseconds, retryBaseDelay = 1.milliseconds), emptySet())

        val result = engine.run(1, IndexMode.INCREMENTAL, 5.minutes)
        assertEquals(IndexRunResult.Outcome.COMPLETED, result.outcome)
        assertEquals(8, result.mediaAdded)

        val titles = LibraryBuilder.build(store.allMedia())
        val byName = titles.associateBy { it.title }
        assertEquals(setOf("Vikram", "Leo", "Breaking Bad", "Interstellar"), byName.keys)
        // Two versions of one movie, best (4K) first.
        assertEquals(2, byName.getValue("Vikram").files.size)
        assertEquals("4K", resolutionLabel(byName.getValue("Vikram").bestResolution))
        assertEquals(2160, byName.getValue("Vikram").files.first().resolution)
        // A series with two seasons.
        val bb = byName.getValue("Breaking Bad")
        assertTrue(bb.isSeries)
        assertEquals(listOf(1, 2), bb.seasons.keys.toList())
        assertEquals(listOf(1, 2), bb.seasons.getValue(1).keys.toList())
        // Only the first piece of a split movie is listed.
        assertEquals(1, byName.getValue("Interstellar").files.size)

        // Running again finds nothing new.
        assertEquals(0, engine.run(1, IndexMode.INCREMENTAL, 5.minutes).mediaAdded)
        store.close()
    }

    private fun record(title: String, year: Int? = null, season: Int? = null, episode: Int? = null, res: Int? = 1080, id: Long = title.hashCode().toLong()) =
        MediaRecord(1, id, id, id.toInt(), "r$id", "$title.mkv", 1_000_000, video = true, title = title, titleKey = title.lowercase(), year = year, season = season, episode = episode, resolution = res)

    @Test
    fun remakesStayApartAndSearchIgnoresAccents() {
        val titles = LibraryBuilder.build(listOf(
            record("Pokémon Detective", 2019, id = 1),
            record("Dune", 1984, id = 2),
            record("Dune", 2021, id = 3),
            record("The Office", season = 1, episode = 1, id = 4),
            record("The Office", year = 2005, season = 1, episode = 2, id = 5),
        ))
        assertEquals(2, titles.count { it.title == "Dune" }, "two films with the same name, different years")
        assertEquals(1, titles.count { it.title == "The Office" }, "a series' episodes stay together even when only some carry a year")
        assertEquals(listOf("Pokémon Detective"), LibraryBuilder.search(titles, "pokemon").map { it.title })
        assertEquals(listOf(2021), LibraryBuilder.search(titles, "dune 2021").map { it.year })
    }
}
