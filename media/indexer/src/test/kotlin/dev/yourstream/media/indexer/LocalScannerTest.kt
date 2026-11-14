package dev.yourstream.media.indexer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.yourstream.core.common.NoOpLogger
import dev.yourstream.media.api.LocalSource
import dev.yourstream.media.api.LocalSourceIds
import dev.yourstream.media.api.LocalSourceKind
import dev.yourstream.media.api.MediaIndexListener
import dev.yourstream.media.api.model.IndexRunResult.Outcome
import dev.yourstream.media.api.model.IndexState
import dev.yourstream.media.indexer.local.AndroidLocalFileLister
import dev.yourstream.media.indexer.local.LocalFile
import dev.yourstream.media.indexer.local.LocalScanner
import dev.yourstream.media.indexer.local.LocalSourceStore
import dev.yourstream.media.indexer.work.IndexScheduler
import dev.yourstream.media.api.model.IndexingPolicy
import androidx.work.NetworkType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Files on this device, on the real database and parser: what a scan writes, re-scans (changed
 * and removed files), lost access, and where local sources sit among Telegram chats. The file
 * listing itself (Storage Access Framework / MediaStore) is a stand-in here: it runs on a device.
 */
@RunWith(RobolectricTestRunner::class)
class LocalScannerTest {
    private val env = IndexerTestEnv()
    private val folder = LocalSource(LocalSourceIds.of(0), LocalSourceKind.FOLDER, "Movies", "content://tree/movies")
    private var files = mutableListOf<LocalFile>()
    private var denied = false
    private var changes = 0
    private val scanner = LocalScanner(
        env.db, env.store, { if (denied) throw SecurityException("no access") else files.toList() },
        env.normalizer, env.clock, NoOpLogger, setOf(MediaIndexListener { changes++ }),
    )

    @After fun tearDown() = env.close()

    private fun file(key: String, name: String, size: Long = 1_500_000_000L, modified: Long = 1_000L) =
        LocalFile(key, "content://tree/movies/document/$key", name, "video/x-matroska", size, modified)

    @Test
    fun aScanIndexesTheFolderLikeAChat() = runTest {
        files += file("1", "Vikram.2022.1080p.WEB-DL.mkv")
        files += file("2", "Ben.10.S01E02.720p.mkv")
        files += file("3", "holiday-clip.mp4", size = 5_000_000)
        val run = scanner.scan(folder)
        assertEquals(Outcome.COMPLETED, run.outcome)
        assertEquals(3, run.mediaAdded)
        assertEquals(1, changes, "the library is told")

        val chat = env.db.indexedChatDao().get(folder.id)!!
        assertEquals("LOCAL", chat.source)
        assertEquals("Movies", chat.title)
        assertEquals(IndexState.COMPLETED.name, chat.state)

        val vikram = env.db.query("SELECT locator, file_name, message_id FROM media WHERE file_name LIKE 'Vikram%'", null).use { c ->
            c.moveToFirst(); Triple(c.getString(0), c.getString(1), c.getLong(2))
        }
        assertEquals("content://tree/movies/document/1", vikram.first)
        assertEquals(LocalScanner.messageIdOf("1"), vikram.third)
        val parsed = env.db.query("SELECT p.title, p.year, p.resolution FROM media_parsed p JOIN media m ON m.id = p.media_id WHERE m.file_name LIKE 'Vikram%'", null).use { c ->
            c.moveToFirst(); Triple(c.getString(0), c.getInt(1), c.getInt(2))
        }
        assertEquals(Triple("Vikram", 2022, 1080), parsed, "parsed like a Telegram file name")
    }

    @Test
    fun rescansPickUpChangedAndRemovedFiles() = runTest {
        files += file("1", "Vikram.2022.1080p.mkv")
        files += file("2", "Jailer.2023.mkv")
        scanner.scan(folder)

        files.removeAll { it.key == "2" }
        files[0] = file("1", "Vikram.2022.1080p.mkv", size = 1_600_000_000L, modified = 2_000L)
        val again = scanner.scan(folder)
        assertEquals(0, again.mediaAdded)
        assertEquals(1, again.mediaUpdated, "replaced in place")
        assertEquals(1, again.mediaDeleted, "gone from the folder")
        val deleted = env.db.query("SELECT is_deleted FROM media WHERE file_name LIKE 'Jailer%'", null).use { c -> c.moveToFirst(); c.getInt(0) }
        assertEquals(1, deleted)

        val third = scanner.scan(folder)
        assertEquals(0, third.mediaAdded + third.mediaUpdated + third.mediaDeleted, "nothing changed: nothing written")
    }

    @Test
    fun lostAccessIsAPlainFailureAndKeepsTheFiles() = runTest {
        files += file("1", "Vikram.2022.mkv")
        scanner.scan(folder)
        denied = true
        val run = scanner.scan(folder)
        assertEquals(Outcome.FAILED, run.outcome)
        val chat = env.db.indexedChatDao().get(folder.id)!!
        assertEquals(IndexState.FAILED.name, chat.state)
        assertEquals(LocalScanner.NO_FOLDER_ACCESS, chat.lastError)
        val live = env.db.query("SELECT COUNT(*) FROM media WHERE chat_id = ${folder.id} AND is_deleted = 0", null).use { c -> c.moveToFirst(); c.getInt(0) }
        assertEquals(1, live, "a failed listing deletes nothing")
    }

    @Test
    fun localIdsNeverMeetTelegramIds() {
        assertTrue(LocalSourceIds.isLocal(LocalSourceIds.of(0)))
        assertTrue(LocalSourceIds.isLocal(LocalSourceIds.of(999_999)))
        for (telegram in listOf(42L, 7_000_000_000L, -4_321L, -1_002_345_678_901L, -1_009_999_999_999_999L)) assertFalse(LocalSourceIds.isLocal(telegram), "$telegram")
        assertNotEquals(LocalScanner.messageIdOf("a"), LocalScanner.messageIdOf("b"))
        assertTrue(LocalScanner.messageIdOf("primary:Movies/x.mkv") > 0)
    }

    @Test
    fun videosAreRecognisedByTypeOrExtension() {
        assertTrue(AndroidLocalFileLister.isVideo("x.bin", "video/mp4"))
        assertTrue(AndroidLocalFileLister.isVideo("Movie.MKV", ""))
        assertTrue(AndroidLocalFileLister.isVideo("Movie.m2ts", "application/octet-stream"))
        assertFalse(AndroidLocalFileLister.isVideo("cover.jpg", "image/jpeg"))
        assertFalse(AndroidLocalFileLister.isVideo("notes.txt", "text/plain"))
    }

    @Test
    fun theSourceListIsKeptAndIdsAreNotReused() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("local_sources", Context.MODE_PRIVATE).edit().clear().commit()
        val store = LocalSourceStore(context)
        val a = store.add(LocalSourceKind.FOLDER, "Movies", "content://a")
        assertEquals(a, store.add(LocalSourceKind.FOLDER, "Movies", "content://a"), "the same folder twice is one source")
        val b = store.add(LocalSourceKind.DEVICE_VIDEOS, "All videos", null)
        store.remove(a.id)
        val c = store.add(LocalSourceKind.FOLDER, "Series", "content://c")
        assertNotEquals(a.id, c.id, "ids are never reused")
        assertEquals(listOf(b, c), LocalSourceStore(context).sources.value, "kept across restarts")
        assertNull(LocalSourceStore(context).get(a.id))
    }

    @Test
    fun localScansDoNotWaitForTheNetwork() {
        val c = IndexScheduler.localConstraintsFor(IndexingPolicy(wifiOnly = true, batteryAware = true))
        assertEquals(NetworkType.NOT_REQUIRED, c.requiredNetworkType)
        assertTrue(c.requiresBatteryNotLow())
    }
}
