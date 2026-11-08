package dev.yourstream.core.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Every schema step is migrated with real data and validated against the exported schema. */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    private val name = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), YourStreamDatabase::class.java)

    @Test
    fun v1ToV2KeepsDataAndAddsParsedColumns() = runTest {
        helper.createDatabase(name, 1).apply {
            execSQL("INSERT INTO indexed_chat VALUES (1, 'Chat', 'CHANNEL', 1, 'IDLE', NULL, NULL, NULL, 0, 0, 0)")
            execSQL(
                """INSERT INTO media (id, chat_id, message_id, album_id, message_date, edit_date, file_id, file_unique_id,
                remote_file_id, file_name, mime_type, extension, size_bytes, duration_seconds, width, height, video_codec,
                audio_codecs, supports_streaming, media_type, content_type, caption, audio_title, audio_performer,
                download_state, downloaded_bytes, local_path, search_title, fingerprint, canonical_media_id, duplicate_kind,
                is_deleted, deleted_at, indexed_at, updated_at, last_seen_at)
                VALUES (7, 1, 70, 0, 0, 0, 5, 'u', 'r', 'Movie.Name.2024.1080p.mkv', '', 'mkv', 2000000, NULL, NULL, NULL, NULL,
                '', 0, 'VIDEO', 'DOCUMENT', '', NULL, NULL, 'NOT_DOWNLOADED', 0, NULL, 'movie name', NULL, NULL, NULL, 0, NULL, 0, 0, 0)""",
            )
            execSQL(
                """INSERT INTO media_parsed VALUES (7, 'Movie Name', 2024, NULL, NULL, NULL, 1080, 'WEB-DL', NULL, '', NULL, '', '',
                NULL, NULL, NULL, 0.9, 1)""",
            )
            close()
        }

        helper.runMigrationsAndValidate(name, 2, true).close()

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), YourStreamDatabase::class.java, name)
            .allowMainThreadQueries().build()
        val row = db.mediaDao().getWithParsed(7)!!
        assertEquals("Movie.Name.2024.1080p.mkv", row.media.fileName, "original name untouched")
        val parsed = row.parsed!!
        assertEquals("Movie Name", parsed.title)
        assertEquals("WEB-DL", parsed.quality)
        assertEquals(1, parsed.parserVersion, "old rows keep their version so they get re-parsed")
        assertEquals("UNKNOWN", parsed.kind)
        assertEquals("", parsed.fieldConfidence)
        assertNull(parsed.normalizedTitle)
        assertEquals(1, db.mediaDao().countOutdatedParses(2))
        db.close()
    }

    private val v1Media = """INSERT INTO media (id, chat_id, message_id, album_id, message_date, edit_date, file_id, file_unique_id,
                remote_file_id, file_name, mime_type, extension, size_bytes, duration_seconds, width, height, video_codec,
                audio_codecs, supports_streaming, media_type, content_type, caption, audio_title, audio_performer,
                download_state, downloaded_bytes, local_path, search_title, fingerprint, canonical_media_id, duplicate_kind,
                is_deleted, deleted_at, indexed_at, updated_at, last_seen_at)
                VALUES (%d, 1, %d, 0, 0, 0, 5, 'u%d', 'r', 'Doraemon.S01E0%d.mkv', '', 'mkv', 2000000, NULL, NULL, NULL, NULL,
                '', 0, 'VIDEO', 'DOCUMENT', '', NULL, NULL, 'NOT_DOWNLOADED', 0, NULL, 'doraemon', NULL, NULL, NULL, 0, NULL, 0, 0, 0)"""

    @Test
    fun v2ToV3AddsTheLibraryAndQueuesExistingFiles() = runTest {
        helper.createDatabase(name, 2).apply {
            execSQL("INSERT INTO indexed_chat VALUES (1, 'Chat', 'CHANNEL', 1, 'IDLE', NULL, NULL, NULL, 0, 0, 0)")
            (1..3).forEach { execSQL(v1Media.format(it, it * 10, it, it)) }
            close()
        }
        helper.runMigrationsAndValidate(name, 3, true).close()

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), YourStreamDatabase::class.java, name)
            .allowMainThreadQueries().addCallback(YourStreamDatabase.CALLBACK).build()
        assertEquals(listOf(1L, 2L, 3L), db.libraryDao().pendingMedia(10), "existing files queued for the first library sync")
        assertEquals("Doraemon.S01E01.mkv", db.mediaDao().getWithParsed(1)!!.media.fileName)

        // Triggers are live after the upgrade.
        db.libraryDao().clearPending(listOf(1L, 2L, 3L))
        db.mediaDao().markDeleted(listOf(2L), 1)
        assertEquals(listOf(2L), db.libraryDao().pendingMedia(10))
        db.close()
    }

    @Test
    fun fullChainFromV1() = runTest {
        helper.createDatabase(name, 1).apply {
            execSQL("INSERT INTO indexed_chat VALUES (1, 'Chat', 'CHANNEL', 1, 'IDLE', NULL, NULL, NULL, 0, 0, 0)")
            execSQL(v1Media.format(9, 90, 9, 9))
            close()
        }
        helper.runMigrationsAndValidate(name, 3, true).close()
        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), YourStreamDatabase::class.java, name)
            .allowMainThreadQueries().addCallback(YourStreamDatabase.CALLBACK).build()
        assertEquals(listOf(9L), db.libraryDao().pendingMedia(10))
        db.close()
    }
}
