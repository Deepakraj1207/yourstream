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

    @Test
    fun v3ToV4AddsMetadataTablesWithoutTouchingTheLibrary() = runTest {
        helper.createDatabase(name, 3).apply {
            execSQL("INSERT INTO library_item (id, kind, match_key, title, sort_title, normalized_title, year, search_text, title_confidence, locked, hidden, added_at, updated_at, available_count, latest_added_at, max_resolution, total_size, season_count, episode_count, watched_count, last_watched_at) VALUES (1, 'MOVIE', 'movie:leo:2023', 'Leo', 'leo', 'leo', 2023, 'leo', 0.9, 0, 0, 0, 0, 1, 0, NULL, 0, 0, 0, 0, NULL)")
            execSQL("INSERT INTO favorite VALUES (1, 5)")
            close()
        }
        helper.runMigrationsAndValidate(name, 4, true).close()
        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), YourStreamDatabase::class.java, name)
            .allowMainThreadQueries().addCallback(YourStreamDatabase.CALLBACK).build()
        assertEquals("Leo", db.libraryDao().item(1)!!.title)
        assertEquals(true, db.libraryDao().isFavorite(1))
        assertEquals(listOf(1L), db.metadataDao().matchWork(now = 0, limit = 10).map { it.id }, "existing items are queued for matching")
        db.close()
    }
    @Test
    fun v4ToV5AddsTheCatalogCacheWithoutTouchingLibraryOrMetadata() = runTest {
        helper.createDatabase(name, 4).apply {
            execSQL("INSERT INTO library_item (id, kind, match_key, title, sort_title, normalized_title, year, search_text, title_confidence, locked, hidden, added_at, updated_at, available_count, latest_added_at, max_resolution, total_size, season_count, episode_count, watched_count, last_watched_at) VALUES (1, 'MOVIE', 'movie:leo:2023', 'Leo', 'leo', 'leo', 2023, 'leo', 0.9, 0, 0, 0, 0, 1, 0, NULL, 0, 0, 0, 0, NULL)")
            execSQL("INSERT INTO item_metadata (item_id, provider, external_id, imdb_id, title, original_title, overview, poster_url, backdrop_url, genres, release_date, year, original_language, runtime_minutes, rating, `cast`, directors, series_status, fetched_at) VALUES (1, 'tmdb', '1', 'tt15654328', 'Leo', NULL, NULL, NULL, NULL, 'Action', NULL, 2023, 'ta', NULL, NULL, '[]', '', NULL, 0)")
            execSQL("INSERT INTO metadata_cache VALUES ('tmdb', 'movie:1:en', '{}', 1, 2)")
            close()
        }
        helper.runMigrationsAndValidate(name, 5, true).close()
        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), YourStreamDatabase::class.java, name)
            .allowMainThreadQueries().addCallback(YourStreamDatabase.CALLBACK).build()
        assertEquals("Leo", db.libraryDao().item(1)!!.title)
        assertEquals("tt15654328", db.metadataDao().itemMetadata(1)!!.imdbId)
        assertEquals("{}", db.metadataDao().cacheEntry("tmdb", "movie:1:en")!!.payload)
        assertEquals(0, db.catalogDao().cacheSize())
        assertEquals(listOf(1L), db.catalogDao().presenceByImdb(listOf("tt15654328")).map { it.id })
        db.close()
    }

    @Test
    fun v5ToV6KeepsTrackPreferencesAndAddsLanguageSettings() = runTest {
        helper.createDatabase(name, 5).apply {
            execSQL("INSERT INTO library_item (id, kind, match_key, title, sort_title, normalized_title, year, search_text, title_confidence, locked, hidden, added_at, updated_at, available_count, latest_added_at, max_resolution, total_size, season_count, episode_count, watched_count, last_watched_at) VALUES (1, 'SERIES', 'series:doraemon', 'Doraemon', 'doraemon', 'doraemon', NULL, 'doraemon', 0.9, 0, 0, 0, 0, 1, 0, NULL, 0, 0, 0, 0, NULL)")
            execSQL("INSERT INTO track_preference VALUES (1, 'ta', 'en', 1, 250, 7)")
            close()
        }
        helper.runMigrationsAndValidate(name, 6, true).close()
        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), YourStreamDatabase::class.java, name)
            .allowMainThreadQueries().addCallback(YourStreamDatabase.CALLBACK).build()
        val pref = db.trackDao().preference(1)!!
        assertEquals("ta", pref.audioLanguage)
        assertEquals(250, pref.subtitleOffsetMs)
        assertNull(pref.audioSignature)
        assertNull(pref.scope)
        assertNull(db.trackDao().languageSettings(), "no settings until the user sets them (defaults apply)")
        db.close()
    }

    @Test
    fun v6ToV7AddsTheSearchIndexAndQueuesEveryItem() = runTest {
        helper.createDatabase(name, 6).apply {
            execSQL("INSERT INTO library_item (id, kind, match_key, title, sort_title, normalized_title, year, search_text, title_confidence, locked, hidden, added_at, updated_at, available_count, latest_added_at, max_resolution, total_size, season_count, episode_count, watched_count, last_watched_at) VALUES (1, 'SERIES', 'series:doraemon', 'Doraemon', 'doraemon', 'doraemon', NULL, 'doraemon', 0.9, 0, 0, 0, 0, 1, 0, NULL, 0, 0, 0, 0, NULL)")
            close()
        }
        helper.runMigrationsAndValidate(name, 7, true).close()
        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), YourStreamDatabase::class.java, name)
            .allowMainThreadQueries().addCallback(YourStreamDatabase.CALLBACK).build()
        assertEquals(listOf(1L), db.searchDao().dirty(10), "existing items wait for indexing")
        assertEquals("Doraemon", db.libraryDao().item(1)!!.title)
        // Triggers are live: a metadata change re-queues the item.
        db.searchDao().clearDirty(listOf(1L))
        db.openHelper.writableDatabase.execSQL("UPDATE library_item SET title = 'Doraemon (2005)' WHERE id = 1")
        assertEquals(listOf(1L), db.searchDao().dirty(10))
        db.close()
    }

    @Test
    fun v7ToV8KeepsWatchDataAndAddsDismissals() = runTest {
        helper.createDatabase(name, 7).apply {
            execSQL("INSERT INTO library_item (id, kind, match_key, title, sort_title, normalized_title, year, search_text, title_confidence, locked, hidden, added_at, updated_at, available_count, latest_added_at, max_resolution, total_size, season_count, episode_count, watched_count, last_watched_at) VALUES (1, 'MOVIE', 'movie:vikram', 'Vikram', 'vikram', 'vikram', 2022, 'vikram', 0.9, 0, 0, 0, 0, 1, 0, NULL, 0, 0, 0, 0, 5)")
            execSQL("INSERT INTO watch_state VALUES (1, 0, 600000, 6000000, 0, NULL, 0, 5, NULL)")
            execSQL("INSERT INTO favorite VALUES (1, 3)")
            close()
        }
        helper.runMigrationsAndValidate(name, 8, true).close()
        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), YourStreamDatabase::class.java, name)
            .allowMainThreadQueries().addCallback(YourStreamDatabase.CALLBACK).build()
        val row = db.watchIntelligenceDao().items(listOf(1L)).single()
        assertEquals(600000L, row.positionMs, "progress survives")
        assertEquals(3L, row.favoriteAt, "favorite survives")
        assertEquals(null, row.dismissedAt)
        db.close()
    }

    @Test
    fun v8ToV9AddsTheTitleIndexOnly() = runTest {
        helper.createDatabase(name, 8).apply {
            execSQL("INSERT INTO library_item (id, kind, match_key, title, sort_title, normalized_title, year, search_text, title_confidence, locked, hidden, added_at, updated_at, available_count, latest_added_at, max_resolution, total_size, season_count, episode_count, watched_count, last_watched_at) VALUES (1, 'MOVIE', 'movie:leo', 'Leo', 'leo', 'leo', 2023, 'leo', 0.9, 0, 0, 0, 0, 1, 0, NULL, 0, 0, 0, 0, NULL)")
            execSQL("INSERT INTO watch_dismissal VALUES (1, 4)")
            close()
        }
        helper.runMigrationsAndValidate(name, 9, true).apply {
            query("SELECT COUNT(*) FROM watch_dismissal").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'library_item' AND name = 'index_library_item_sort_title_id'").use { assertEquals(1, it.count) }
            close()
        }
    }

    @Test
    fun v12ToV13AddsThePausedFlagDefaultingToNotPaused() = runTest {
        helper.createDatabase(name, 12).apply {
            execSQL("INSERT INTO indexed_chat (chat_id, title, category, enabled, state, last_error, last_indexed_at, last_verified_at, verify_cursor, added_at, updated_at) VALUES (7, 'Tamil Movies', 'CHANNEL', 1, 'COMPLETED', NULL, 5, NULL, 0, 1, 2)")
            close()
        }
        helper.runMigrationsAndValidate(name, 13, true).apply {
            query("SELECT title, state, paused FROM indexed_chat WHERE chat_id = 7").use {
                it.moveToFirst()
                assertEquals("Tamil Movies", it.getString(0), "the chat survives")
                assertEquals("COMPLETED", it.getString(1))
                assertEquals(0, it.getInt(2), "existing chats are not paused")
            }
            close()
        }
    }

    @Test
    fun v13ToV14AddsTheSourceAndLocatorKeepingEverythingTelegram() = runTest {
        helper.createDatabase(name, 13).apply {
            execSQL("INSERT INTO indexed_chat (chat_id, title, category, enabled, state, last_error, last_indexed_at, last_verified_at, verify_cursor, added_at, updated_at, paused) VALUES (7, 'Tamil Movies', 'CHANNEL', 1, 'COMPLETED', NULL, 5, NULL, 0, 1, 2, 0)")
            close()
        }
        helper.runMigrationsAndValidate(name, 14, true).apply {
            query("SELECT title, source FROM indexed_chat WHERE chat_id = 7").use {
                it.moveToFirst()
                assertEquals("Tamil Movies", it.getString(0), "the chat survives")
                assertEquals("TELEGRAM", it.getString(1), "existing chats are Telegram chats")
            }
            query("SELECT locator FROM media LIMIT 0").use { assertEquals(1, it.columnCount, "media has a locator column") }
            close()
        }
    }
}
