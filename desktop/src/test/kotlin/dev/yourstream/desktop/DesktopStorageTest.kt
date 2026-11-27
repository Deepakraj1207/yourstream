package dev.yourstream.desktop

import dev.yourstream.core.common.AppResult
import dev.yourstream.core.common.Clock
import dev.yourstream.core.common.NoOpLogger
import dev.yourstream.core.network.HttpClients
import dev.yourstream.desktop.data.CacheArea
import dev.yourstream.desktop.data.DesktopStorage
import dev.yourstream.desktop.library.PosterService
import dev.yourstream.desktop.update.DesktopUpdates
import dev.yourstream.metadata.core.InMemoryMetadataCache
import dev.yourstream.metadata.core.MetadataProviderManager
import dev.yourstream.metadata.core.match.MetadataMatcher
import dev.yourstream.playback.testing.SimulatedTelegramFiles
import dev.yourstream.telegram.api.TelegramFileRepository
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Settings → Storage & cache on the desktop: each cache measured on disk and cleared through its
 * owner (Telegram's files through the file repository, as TDLib must delete its own files), and
 * what is never cleared: the Telegram session, the index, settings, the poster matches.
 */
class DesktopStorageTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun write(f: File, bytes: Int) { f.parentFile.mkdirs(); f.writeBytes(ByteArray(bytes)) }

    private class Setup(val data: File, val storage: DesktopStorage, val deleteAllCalls: () -> Int, val warmerStops: () -> Int)

    private fun setup(): Setup {
        val data = tmp.newFolder("data")
        write(File(data, "telegram/files/videos/a.mkv"), 3_000_000)
        write(File(data, "telegram/files/thumbnails/t.jpg"), 20_000)
        write(File(data, "telegram/db/td.binlog"), 50_000) // the session: never cleared
        write(File(data, "cache/posters/1.img"), 200_000)
        write(File(data, "cache/posters/2.img"), 100_000)
        File(data, "cache/posters/matches.json").writeText("{}") // poster matches: kept
        write(File(data, "updates/YourStream-v9.9.9-windows-x64.msi"), 1_000_000)
        File(data, "index.jsonl").writeText("{}\n")
        File(data, "settings.json").writeText("{}")

        // TDLib deletes its own files; the stand-in empties telegram/files the same way.
        var deleteAll = 0
        val sim = SimulatedTelegramFiles(tmp.newFolder("sim"))
        val files = object : TelegramFileRepository by sim {
            override suspend fun deleteAllLocalCopies(): AppResult<Unit> {
                deleteAll++
                File(data, "telegram/files").walkBottomUp().filter { it.isFile }.forEach { it.delete() }
                return AppResult.Ok(Unit)
            }
        }
        val posters = PosterService(MetadataMatcher(MetadataProviderManager(emptyList(), InMemoryMetadataCache(), Clock.System, NoOpLogger)), HttpClients.create(), File(data, "cache/posters"), NoOpLogger)
        val updates = DesktopUpdates("0.0.1", File(data, "updates"), NoOpLogger)
        var stops = 0
        return Setup(data, DesktopStorage(data, files, posters, updates, { stops++ }, NoOpLogger), { deleteAll }, { stops })
    }

    @Test
    fun measuresEachCache(): Unit = runBlocking {
        val s = setup()
        val usage = s.storage.usage()
        assertEquals(3_020_000L, usage[CacheArea.TELEGRAM])
        assertEquals(300_000L, usage[CacheArea.POSTERS], "images only")
        assertEquals(1_000_000L, usage[CacheArea.UPDATES])
    }

    @Test
    fun clearCacheEmptiesEveryCacheAndKeepsYourData(): Unit = runBlocking {
        val s = setup()
        val failed = s.storage.clearAll()
        assertTrue(failed.isEmpty(), "failed: $failed")
        assertEquals(1, s.deleteAllCalls(), "Telegram's files are deleted by TDLib")
        assertEquals(1, s.warmerStops(), "the warm-up download stops first")
        assertEquals(mapOf(CacheArea.TELEGRAM to 0L, CacheArea.POSTERS to 0L, CacheArea.UPDATES to 0L), s.storage.usage())
        // Kept: the Telegram session, the poster matches, the index, settings.
        assertTrue(File(s.data, "telegram/db/td.binlog").isFile)
        assertTrue(File(s.data, "cache/posters/matches.json").isFile)
        assertTrue(File(s.data, "index.jsonl").isFile)
        assertTrue(File(s.data, "settings.json").isFile)
    }

    @Test
    fun oneCacheAtATime(): Unit = runBlocking {
        val s = setup()
        assertIs<AppResult.Ok<Unit>>(s.storage.clear(CacheArea.POSTERS))
        val usage = s.storage.usage()
        assertEquals(0L, usage[CacheArea.POSTERS])
        assertEquals(3_020_000L, usage[CacheArea.TELEGRAM], "the others untouched")
        assertEquals(1_000_000L, usage[CacheArea.UPDATES])
        assertEquals(0, s.deleteAllCalls())
        // An empty cache is skipped by Clear cache.
        s.storage.clearAll()
        assertEquals(1, s.deleteAllCalls())
        assertFalse(File(s.data, "updates/YourStream-v9.9.9-windows-x64.msi").exists())
    }

    @Test
    fun sizes() {
        assertEquals("512 B", DesktopStorage.sizeText(512))
        assertEquals("2 KB", DesktopStorage.sizeText(2048))
        assertEquals("300 MB", DesktopStorage.sizeText(300L shl 20))
        assertEquals("3.0 GB", DesktopStorage.sizeText(3L shl 30))
    }
}
