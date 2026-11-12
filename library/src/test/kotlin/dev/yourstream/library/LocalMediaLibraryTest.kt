package dev.yourstream.library

import dev.yourstream.core.common.NoOpLogger
import dev.yourstream.library.api.LibraryQuery
import dev.yourstream.library.api.PlaybackTarget
import dev.yourstream.media.api.LocalSource
import dev.yourstream.media.api.LocalSourceIds
import dev.yourstream.media.api.LocalSourceKind
import dev.yourstream.media.indexer.local.LocalFile
import dev.yourstream.media.indexer.local.LocalScanner
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Files on this device in the library (real Room, real scanner, real library sync). */
@RunWith(RobolectricTestRunner::class)
class LocalMediaLibraryTest {
    private val env = LibraryTestEnv()
    private val folder = LocalSource(LocalSourceIds.of(0), LocalSourceKind.FOLDER, "Movies", "content://tree/movies")
    private val files = mutableListOf<LocalFile>()
    private val scanner = LocalScanner(env.db, env.store, { files.toList() }, env.normalizer, env.clock, NoOpLogger, emptySet())

    @After fun tearDown() = env.close()

    private fun local(key: String, name: String) = LocalFile(key, "content://tree/movies/document/$key", name, "video/mp4", 2_000_000_000L, 5_000L)

    @Test
    fun localFilesBecomeTitlesAndEpisodesWithTheirAddress() = runTest {
        files += local("1", "Vikram.2022.Tamil.1080p.mkv")
        files += local("2", "Ben.10.S01E03.720p.mkv")
        scanner.scan(folder)
        env.sync.sync()

        val titles = with(env) { media.search(LibraryQuery()).all() }
        assertEquals(listOf("Ben 10", "Vikram"), titles.map { it.title }.sorted())
        val vikram = titles.single { it.title == "Vikram" }
        val version = env.media.versions(PlaybackTarget(vikram.id)).single()
        assertEquals("content://tree/movies/document/1", version.locator)
        assertTrue(version.isLocal)
    }

    @Test
    fun theSameMovieOnTelegramAndOnTheDeviceIsOneTitleWithTwoVersions() = runTest {
        env.file("Vikram.2022.Tamil.720p.mkv")
        files += local("1", "Vikram.2022.Tamil.1080p.mkv")
        scanner.scan(folder)
        env.sync.sync()

        val vikram = with(env) { media.search(LibraryQuery(text = "vikram")).all() }.single()
        val versions = env.media.versions(PlaybackTarget(vikram.id))
        assertEquals(2, versions.size)
        assertEquals("content://tree/movies/document/1", versions.single { it.resolution == 1080 }.locator)
        assertNull(versions.single { it.resolution == 720 }.locator, "the Telegram copy has no local address")
    }

    @Test
    fun removingAFolderRemovesItsTitles() = runTest {
        files += local("1", "Vikram.2022.Tamil.1080p.mkv")
        scanner.scan(folder)
        env.sync.sync()
        env.db.indexedChatDao().delete(folder.id) // what removing the source does
        env.sync.sync()
        assertEquals(emptyList(), with(env) { media.search(LibraryQuery()).all() })
    }
}
