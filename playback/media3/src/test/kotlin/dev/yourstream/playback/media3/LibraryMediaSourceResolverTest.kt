package dev.yourstream.playback.media3

import androidx.test.core.app.ApplicationProvider
import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import dev.yourstream.core.database.YourStreamDatabase
import dev.yourstream.library.api.EpisodeRepository
import dev.yourstream.library.api.MediaRepository
import dev.yourstream.library.api.MediaVersion
import dev.yourstream.library.api.PlaybackTarget
import dev.yourstream.playback.testing.SimulatedTelegramFiles
import dev.yourstream.playback.testing.TestMedia
import dev.yourstream.telegram.api.TelegramMessageRepository
import dev.yourstream.telegram.api.model.MessageLookup
import dev.yourstream.telegram.api.model.TelegramMediaContentType
import dev.yourstream.telegram.api.model.TelegramMediaFile
import dev.yourstream.telegram.api.model.TelegramMediaMessage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** File availability and version choice before playback starts. */
@RunWith(RobolectricTestRunner::class)
class LibraryMediaSourceResolverTest {

    private val dir: File = Files.createTempDirectory("resolver").toFile()
    private val sim = SimulatedTelegramFiles(File(dir, "tdlib"))
    private val db = YourStreamDatabase.inMemory(ApplicationProvider.getApplicationContext())

    /** What lookupMessages answers. */
    private var lookup: AppResult<List<MessageLookup>> = AppResult.Ok(emptyList())
    private var versions: List<MediaVersion> = emptyList()

    @After fun tearDown() { sim.close(); db.close(); dir.deleteRecursively() }

    /** Interface stubs: only the methods the resolver uses are answered. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> stub(type: Class<T>, answer: (String, Array<Any?>) -> Any?): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, m, args -> answer(m.name, args ?: emptyArray()) } as T

    private val mediaRepo = stub(MediaRepository::class.java) { name, _ ->
        when (name) { "versions" -> versions; "getItem" -> null; else -> error("unexpected $name") }
    }
    private val episodeRepo = stub(EpisodeRepository::class.java) { name, _ -> if (name == "versions") versions else error(name) }
    private val messages = stub(TelegramMessageRepository::class.java) { name, _ -> if (name == "lookupMessages") lookup else error(name) }
    private val resolver = LibraryMediaSourceResolver(mediaRepo, episodeRepo, db, messages, sim)

    private fun version(mediaId: Long, resolution: Int?, size: Long = 1000, deleted: Boolean = false, duplicate: Boolean = false, excluded: Boolean = false) = MediaVersion(
        mediaId = mediaId, chatId = 1, messageId = mediaId * 10, fileName = "Movie.$resolution.mkv", sizeBytes = size, durationSeconds = 6,
        resolution = resolution, quality = null, videoCodec = null, languages = emptyList(), isDeleted = deleted, isDuplicate = duplicate,
        episodeId = null, isManual = false, isExcluded = excluded, downloadState = "NOT_DOWNLOADED",
    )

    private fun found(fileId: Int, messageId: Long) = MessageLookup.Found(
        TelegramMediaMessage(
            chatId = 1, messageId = messageId, date = 0, editDate = 0, albumId = 0, caption = "",
            contentType = TelegramMediaContentType.VIDEO,
            media = TelegramMediaFile(sim.file(fileId).let { runBlocking { (sim.getFile(fileId) as AppResult.Ok).value } }, "Movie.mkv", "video/x-matroska",
                6, 640, 360, true, "h264", null, null, null),
        ),
    )

    @Test
    fun choosesTheBestVersionAndRefreshesTheFileFromTheMessage(): Unit = runBlocking {
        sim.addFile(42, TestMedia.file(TestMedia.SMALL_360P, dir))
        versions = listOf(version(1, 720), version(2, 1080, duplicate = true), version(3, 1080, deleted = true), version(4, 480), version(5, 2160, excluded = true))
        assertEquals(1L, LibraryMediaSourceResolver.chooseVersion(versions)!!.mediaId, "originals before duplicates; deleted/excluded skipped")
        lookup = AppResult.Ok(listOf(found(42, 10)))
        val media = (resolver.resolve(PlaybackTarget(7)) as AppResult.Ok).value
        assertEquals(42, media.fileId, "session file id comes from the message lookup")
        assertEquals("video/x-matroska", media.mimeType)
        assertEquals(6_000L, media.durationMs)
        assertEquals(1L, media.mediaId)
        assertTrue(media.mediaItem().localConfiguration!!.uri.toString().startsWith("tgfile://file/42"))
        // A specific version can be asked for.
        assertEquals(4L, (resolver.resolve(PlaybackTarget(7), mediaId = 4) as AppResult.Ok).value.mediaId)
    }

    @Test
    fun deletedMessagesAndUndownloadableFilesAreUnavailable(): Unit = runBlocking {
        versions = listOf(version(1, 720))
        lookup = AppResult.Ok(listOf(MessageLookup.Missing(10)))
        assertIs<AppError.NotFound>((resolver.resolve(PlaybackTarget(7)) as AppResult.Err).error)

        sim.addFile(42, TestMedia.file(TestMedia.SMALL_360P, dir))
        sim.makeUnavailable(42)
        lookup = AppResult.Ok(listOf(found(42, 10)))
        assertIs<AppError.NotFound>((resolver.resolve(PlaybackTarget(7)) as AppResult.Err).error)

        versions = listOf(version(1, 720, deleted = true))
        assertIs<AppError.NotFound>((resolver.resolve(PlaybackTarget(7), mediaId = 1) as AppResult.Err).error)
        versions = emptyList()
        assertIs<AppError.NotFound>((resolver.resolve(PlaybackTarget(7)) as AppResult.Err).error)
    }

    @Test
    fun offlineUsesTheStoredFileIdWhenTelegramStillKnowsIt(): Unit = runBlocking {
        // Indexed media row: file_id 5, unique id "u5" (the simulator's ids are "u<id>").
        db.openHelper.writableDatabase.apply {
            execSQL("INSERT INTO indexed_chat VALUES (1, 'Chat', 'CHANNEL', 1, 'IDLE', NULL, NULL, NULL, 0, 0, 0)")
            execSQL(
                """INSERT INTO media (id, chat_id, message_id, album_id, message_date, edit_date, file_id, file_unique_id,
                remote_file_id, file_name, mime_type, extension, size_bytes, duration_seconds, width, height, video_codec,
                audio_codecs, supports_streaming, media_type, content_type, caption, audio_title, audio_performer,
                download_state, downloaded_bytes, local_path, search_title, fingerprint, canonical_media_id, duplicate_kind,
                is_deleted, deleted_at, indexed_at, updated_at, last_seen_at)
                VALUES (1, 1, 10, 0, 0, 0, 5, 'u5', 'r', 'Movie.mp4', 'video/mp4', 'mp4', 2000000, 6, 640, 360, NULL,
                '', 1, 'VIDEO', 'VIDEO', '', NULL, NULL, 'NOT_DOWNLOADED', 0, NULL, 'movie', NULL, NULL, NULL, 0, NULL, 0, 0, 0)""",
            )
        }
        sim.addFile(5, TestMedia.file(TestMedia.SMALL_360P, dir))
        versions = listOf(version(1, 360))
        lookup = AppResult.Err(AppError.Network())
        val media = (resolver.resolve(PlaybackTarget(7)) as AppResult.Ok).value
        assertEquals(5, media.fileId)
        assertEquals("video/mp4", media.mimeType)

        sim.remove(5)
        assertIs<AppError.Network>((resolver.resolve(PlaybackTarget(7)) as AppResult.Err).error, "unknown stored file: the network error is reported")
    }
}
