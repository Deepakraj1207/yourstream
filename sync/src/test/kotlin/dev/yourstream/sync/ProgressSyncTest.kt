package dev.yourstream.sync

import androidx.test.core.app.ApplicationProvider
import dev.yourstream.core.common.AppResult
import dev.yourstream.core.common.Clock
import dev.yourstream.core.database.YourStreamDatabase
import dev.yourstream.library.api.PlaybackTarget
import dev.yourstream.library.data.RoomWatchHistoryRepository
import dev.yourstream.telegram.api.SavedDocument
import dev.yourstream.telegram.api.TelegramSavedMessages
import dev.yourstream.telegram.api.TelegramUserRepository
import dev.yourstream.telegram.api.model.TelegramUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Two devices of one account, each with its own database (different local ids), sharing one Saved Messages chat. */
@RunWith(RobolectricTestRunner::class)
class ProgressSyncTest {
    /** The user's Saved Messages, as the Telegram layer exposes it. */
    private class Cloud : TelegramSavedMessages {
        val docs = mutableListOf<Pair<SavedDocument, ByteArray>>()
        private var next = 1L
        override suspend fun find(tag: String, limit: Int) = AppResult.Ok(docs.map { it.first }.filter { tag in it.caption }.sortedByDescending { it.messageId })
        override suspend fun read(doc: SavedDocument, maxBytes: Long) = AppResult.Ok(docs.first { it.first.messageId == doc.messageId }.second)
        override suspend fun replace(tag: String, fileName: String, caption: String, bytes: ByteArray): AppResult<Unit> {
            docs.removeAll { tag in it.first.caption }
            val id = next++
            docs += SavedDocument(id, id, id.toInt(), fileName, caption, bytes.size.toLong()) to bytes
            return AppResult.Ok(Unit)
        }
        override suspend fun delete(tag: String): AppResult<Unit> { docs.removeAll { tag in it.first.caption }; return AppResult.Ok(Unit) }
    }

    private class Settings(override val deviceName: String) : SyncSettings {
        override var enabled = false
        override var key: ByteArray? = null
        override var lastSyncAt: Long? = null
        override var lastFrom: String? = null
    }

    private class Users(id: Long) : TelegramUserRepository {
        override val currentUser: StateFlow<TelegramUser?> = MutableStateFlow(TelegramUser(id, "A", "", null, "", false, false, false))
        override suspend fun refreshCurrentUser(): AppResult<TelegramUser> = AppResult.Ok(currentUser.value!!)
        override suspend fun getUser(userId: Long): AppResult<TelegramUser> = AppResult.Ok(currentUser.value!!)
    }

    private val cloud = Cloud()
    private var now = 1_000_000L
    private val clock = Clock { now }

    private inner class Device(name: String, idOffset: Long, account: Long = 7) {
        val db = YourStreamDatabase.inMemory(ApplicationProvider.getApplicationContext())
        val history = RoomWatchHistoryRepository(db, clock)
        val settings = Settings(name)
        val sync = ProgressSync(ProgressSyncStore(db, history), cloud, Users(account), settings, clock, db, CoroutineScope(Dispatchers.Unconfined))
        val sql = db.openHelper.writableDatabase
        val vikram = 1 + idOffset
        val ben = 2 + idOffset
        var benS1E2 = 0L

        init {
            sql.execSQL("INSERT INTO indexed_chat VALUES (1, 'Movies', 'CHANNEL', 1, 'IDLE', NULL, NULL, NULL, 0, 0, 0, 0)")
            item(vikram, "MOVIE", "movie:vikram:2022")
            item(ben, "SERIES", "series:ben 10")
            sql.execSQL("INSERT INTO season (id, series_id, number, title, episode_count) VALUES (?, ?, 1, NULL, 2)", arrayOf<Any?>(10 + idOffset, ben))
            for (n in 1..2) sql.execSQL(
                "INSERT INTO episode (id, series_id, season_id, season_number, number, number_end, absolute_number, title, air_date, available_count, latest_added_at) VALUES (?, ?, ?, 1, ?, NULL, NULL, NULL, NULL, 1, 0)",
                arrayOf<Any?>(100 + n + idOffset, ben, 10 + idOffset, n),
            )
            benS1E2 = 102 + idOffset
            // The same Telegram uploads: same unique ids on every device, different local ids.
            file(500 + idOffset, "AgAD-vikram", vikram, null)
            file(600 + idOffset, "AgAD-ben-s1e2", ben, benS1E2)
        }

        private fun item(id: Long, kind: String, key: String) = sql.execSQL(
            """INSERT INTO library_item (id, kind, match_key, title, sort_title, normalized_title, year, search_text, title_confidence, locked, hidden,
            added_at, updated_at, available_count, latest_added_at, max_resolution, total_size, season_count, episode_count, watched_count, last_watched_at)
            VALUES (?, ?, ?, ?, ?, ?, NULL, '', 0.9, 0, 0, 0, 0, 1, 0, NULL, 0, 0, 0, 0, NULL)""",
            arrayOf<Any?>(id, kind, key, key, key, key),
        )

        private fun file(id: Long, unique: String, item: Long, episode: Long?) {
            sql.execSQL(
                """INSERT INTO media (id, chat_id, message_id, album_id, message_date, edit_date, file_id, file_unique_id, remote_file_id, file_name, mime_type,
                extension, size_bytes, duration_seconds, width, height, video_codec, audio_codecs, supports_streaming, media_type, content_type, caption,
                audio_title, audio_performer, download_state, downloaded_bytes, local_path, search_title, fingerprint, canonical_media_id, duplicate_kind,
                is_deleted, deleted_at, indexed_at, updated_at, last_seen_at)
                VALUES (?, 1, ?, 0, 0, 0, 1, ?, 'r', 'f.mkv', 'video/x-matroska', 'mkv', 1, NULL, NULL, NULL, NULL, '', 1, 'VIDEO', 'DOCUMENT', '',
                NULL, NULL, 'NOT_DOWNLOADED', 0, NULL, '', NULL, NULL, NULL, 0, NULL, 0, 0, 0)""",
                arrayOf<Any?>(id, id, unique),
            )
            sql.execSQL("INSERT INTO media_link (media_id, item_id, episode_id, method, signature, linked_at) VALUES (?, ?, ?, 'AUTO', '', 0)", arrayOf<Any?>(id, item, episode))
        }

        fun progress(t: PlaybackTarget) = runBlocking { history.progress(t).first() }
    }

    private val phone = Device("Phone", idOffset = 0)
    private val tv = Device("TV", idOffset = 1000)

    @After fun close() { phone.db.close(); tv.db.close() }

    @Test
    fun progressFollowsTheUserAcrossDevicesEncryptedAndOnlyWhenEnabled() = runBlocking {
        // Off by default: nothing goes to Telegram.
        now += 60_000
        phone.history.updateProgress(PlaybackTarget(phone.vikram), 500, 40 * 60_000, 150 * 60_000)
        assertFalse(phone.sync.status.value.enabled)
        phone.sync.syncNow()
        assertTrue(cloud.docs.isEmpty())

        // Turned on with a passphrase on the phone: an encrypted snapshot appears.
        assertNull(phone.sync.enable("our family passphrase".toCharArray()).problem)
        assertEquals(1, cloud.docs.size)
        assertFalse(cloud.docs.single().second.decodeToString().contains("vikram"), "titles never appear in clear text")

        // The TV joins with the same passphrase and picks the movie up at 40 minutes (by file).
        val joined = tv.sync.enable("our family passphrase".toCharArray())
        assertEquals(1, joined.lastPulled)
        assertEquals("Phone", joined.lastFrom)
        assertEquals(40 * 60_000L, tv.progress(PlaybackTarget(tv.vikram))!!.positionMs)

        // The TV finishes an episode later; the phone gets it on its next sync. Newer wins.
        now += 3_600_000
        tv.history.updateProgress(PlaybackTarget(tv.ben, tv.benS1E2), 600 + 1000, 22 * 60_000, 22 * 60_000)
        tv.sync.syncNow()
        phone.sync.syncNow()
        assertTrue(phone.progress(PlaybackTarget(phone.ben, phone.benS1E2))!!.completed)
        assertEquals(1, cloud.docs.size, "one snapshot, replaced in place")

        // Older progress from elsewhere never rewinds newer local progress.
        now += 60_000
        phone.history.updateProgress(PlaybackTarget(phone.vikram), 500, 90 * 60_000, 150 * 60_000)
        tv.sync.syncNow() // TV still has 40 min, older: it must not overwrite the phone's 90
        phone.sync.syncNow()
        assertEquals(90 * 60_000L, phone.progress(PlaybackTarget(phone.vikram))!!.positionMs)
        tv.sync.syncNow()
        assertEquals(90 * 60_000L, tv.progress(PlaybackTarget(tv.vikram))!!.positionMs)
    }

    @Test
    fun aDifferentPassphraseIsRefusedAndTurningOffCanDeleteTheSharedCopy() = runBlocking {
        phone.sync.enable("first".toCharArray())
        val result = tv.sync.enable("second".toCharArray())
        assertEquals(SyncProblem.WRONG_PASSPHRASE, result.problem)
        assertFalse(tv.settings.enabled, "not enabled with a key that cannot read the others' progress")
        assertNull(tv.settings.key)

        phone.sync.disable(deleteShared = true)
        assertTrue(cloud.docs.isEmpty())
        assertFalse(phone.settings.enabled)
        assertNull(phone.settings.key)
    }
}
