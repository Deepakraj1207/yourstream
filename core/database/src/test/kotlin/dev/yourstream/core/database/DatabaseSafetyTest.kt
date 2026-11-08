package dev.yourstream.core.database

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class DatabaseSafetyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "safety-test.db"
    private val opened = mutableListOf<YourStreamDatabase>()

    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), YourStreamDatabase::class.java)

    @After fun cleanup() {
        opened.forEach { it.close() }
        context.getDatabasePath(name).parentFile?.deleteRecursively()
    }

    private fun open() = DatabaseOpener.open(context, name).also { opened += it.first }

    private fun YourStreamDatabase.sql() = openHelper.writableDatabase
    private fun YourStreamDatabase.count(t: String) = sql().query("SELECT COUNT(*) FROM $t").use { it.moveToFirst(); it.getInt(0) }

    private fun seed(db: YourStreamDatabase) {
        val sql = db.sql()
        sql.execSQL("INSERT INTO library_item (id, kind, match_key, title, sort_title, normalized_title, year, search_text, title_confidence, locked, hidden, added_at, updated_at, available_count, latest_added_at, max_resolution, total_size, season_count, episode_count, watched_count, last_watched_at) VALUES (1, 'MOVIE', 'movie:vikram', 'Vikram', 'vikram', 'vikram', 2022, 'vikram', 0.9, 0, 0, 0, 0, 1, 0, NULL, 0, 0, 0, 0, 5)")
        sql.execSQL("INSERT INTO watch_state VALUES (1, 0, 600000, 6000000, 0, NULL, 1, 5, NULL)")
        sql.execSQL("INSERT INTO favorite VALUES (1, 3)")
        sql.execSQL("INSERT INTO watch_dismissal VALUES (1, 4)")
    }

    @Test
    fun createThenReopen() {
        val (db, report) = open()
        assertEquals(DatabaseOpenReport.Outcome.CREATED, report.outcome)
        assertEquals(DatabaseOpener.CURRENT_VERSION, db.sql().version, "CURRENT_VERSION matches @Database(version)")
        seed(db); db.close(); opened.clear()
        val (again, r2) = open()
        assertEquals(DatabaseOpenReport.Outcome.OPENED, r2.outcome)
        assertEquals(DatabaseOpener.CURRENT_VERSION, r2.previousVersion)
        assertEquals(1, again.count("watch_state"))
    }

    @Test
    fun upgradeTakesABackupFirst() {
        helper.createDatabase(name, 7).apply {
            execSQL("INSERT INTO library_item (id, kind, match_key, title, sort_title, normalized_title, year, search_text, title_confidence, locked, hidden, added_at, updated_at, available_count, latest_added_at, max_resolution, total_size, season_count, episode_count, watched_count, last_watched_at) VALUES (1, 'MOVIE', 'movie:leo', 'Leo', 'leo', 'leo', 2023, 'leo', 0.9, 0, 0, 0, 0, 1, 0, NULL, 0, 0, 0, 0, NULL)")
            close()
        }
        val (db, report) = open()
        assertEquals(DatabaseOpenReport.Outcome.MIGRATED, report.outcome)
        assertEquals(7, report.previousVersion)
        val backup = assertNotNull(report.backup)
        assertEquals(7, DatabaseOpener.readVersion(backup), "the backup is the untouched v7 file")
        assertEquals(1, db.count("library_item"))
    }

    @Test
    fun failedOpenSalvagesUserData() {
        val (db, _) = open()
        seed(db)
        // Simulate a database the current build cannot open (e.g. written by a newer build).
        db.sql().execSQL("PRAGMA user_version = 99")
        db.close(); opened.clear()

        val (recovered, report) = open()
        assertEquals(DatabaseOpenReport.Outcome.SALVAGED, report.outcome, report.error)
        assertEquals(99, report.previousVersion)
        assertTrue(report.error!!.isNotBlank())
        assertNotNull(report.quarantined).let { assertTrue(it.exists(), "the unreadable file is kept, never deleted") }
        assertEquals(1, recovered.count("library_item"))
        assertEquals(1, recovered.count("watch_state"), "progress survives")
        assertEquals(1, recovered.count("favorite"))
        assertEquals(1, recovered.count("watch_dismissal"))
        assertEquals(1, recovered.count("search_dirty"), "search index queued for rebuild")
        assertEquals(DatabaseOpener.CURRENT_VERSION, recovered.sql().version)
        assertTrue(DatabaseIntegrity.check(recovered.sql()).ok)
    }

    @Test
    fun corruptedFileStillGivesAWorkingDatabase() {
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        file.writeBytes(ByteArray(8192) { (it * 31).toByte() })
        val (db, report) = open()
        assertEquals(DatabaseOpenReport.Outcome.RECREATED, report.outcome)
        assertEquals(0, db.count("library_item"))
        assertTrue(report.quarantined!!.exists())
        assertEquals(1, DatabaseOpener.quarantined(context, name).size)
    }

    @Test
    fun integrityCheckAndRepairOfCorruptedMetadata() {
        val (db, _) = open()
        seed(db)
        val sql = db.sql()
        sql.execSQL("INSERT INTO item_metadata (item_id, provider, external_id, imdb_id, title, original_title, overview, poster_url, backdrop_url, genres, release_date, year, original_language, runtime_minutes, rating, `cast`, directors, series_status, fetched_at) VALUES (1, 'tmdb', 'x', NULL, 'Vikram', NULL, NULL, NULL, NULL, '', NULL, 2022, 'ta', NULL, NULL, '[{\"name\": broken', '', NULL, 99)")
        sql.execSQL("INSERT INTO metadata_cache (provider, cache_key, payload, fetched_at, expires_at) VALUES ('tmdb', 'k', '{not json', 0, 0)")
        // A row pointing at a missing item (written with foreign keys off, as a crash mid-write could leave).
        sql.execSQL("PRAGMA foreign_keys = OFF")
        sql.execSQL("INSERT INTO favorite VALUES (42, 1)")
        sql.execSQL("PRAGMA foreign_keys = ON")

        val before = DatabaseIntegrity.check(sql)
        assertTrue(!before.ok)
        assertEquals(1, before.foreignKeyViolations)
        assertEquals(1, before.invalidCacheRows)
        assertEquals(1, before.invalidMetadataRows)

        val repair = DatabaseIntegrity.repair(sql)
        assertTrue(repair.after.ok, repair.toString())
        assertEquals(4, repair.actions.size, repair.actions.toString())
        sql.query("SELECT `cast`, fetched_at FROM item_metadata WHERE item_id = 1").use { c ->
            c.moveToFirst(); assertEquals("[]", c.getString(0)); assertEquals(0, c.getInt(1), "scheduled for refresh")
        }
        assertEquals(1, db.count("favorite"), "only the broken favorite was removed")
    }
}
