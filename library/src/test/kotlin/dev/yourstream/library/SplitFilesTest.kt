package dev.yourstream.library

import dev.yourstream.library.api.LibraryQuery
import dev.yourstream.library.api.MediaVersion
import dev.yourstream.library.api.PlaybackTarget
import dev.yourstream.library.api.SplitFiles
import dev.yourstream.library.api.SplitPart
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Movies cut into raw pieces (".mkv.001", ".002", …): one version holding every piece, through
 * the real indexer, database and library (Robolectric), plus the pure rules.
 */
@RunWith(RobolectricTestRunner::class)
class SplitFilesTest {
    private val env = LibraryTestEnv()
    private val gb = 1_000_000_000L

    @After fun tearDown() = env.close()

    @Test
    fun recognisesRawVideoPiecesOnly() {
        assertEquals("Film.2160p.mkv" to 1, SplitFiles.piece("Film.2160p.mkv.001"))
        assertEquals("Film.mp4" to 12, SplitFiles.piece("Film.mp4.012"))
        assertEquals("@Chan_Film_2024_4K.MKV" to 2, SplitFiles.piece("@Chan_Film_2024_4K.MKV.002"), "names with underscores and upper case")
        assertNull(SplitFiles.piece("Film.7z.001"), "archive pieces have to be unpacked")
        assertNull(SplitFiles.piece("Film.part1.rar"))
        assertNull(SplitFiles.piece("Film.mkv.000"))
        assertNull(SplitFiles.piece("Film.001.mkv"), "a normal file")
        assertNull(SplitFiles.piece("Film.mkv"))
    }

    @Test
    fun aMovieInPiecesIsOneVersionWithEveryPiece() = runTest {
        val name = "Kalki.2898.AD.2024.2160p.WEB-DL.HEVC.mkv"
        env.file("$name.001", size = 2 * gb)
        env.file("$name.002", size = 2 * gb)
        env.file("$name.003", size = 1 * gb)
        env.file("Some.Other.Film.2023.1080p.mkv")
        env.sync.sync()

        val kalki = with(env) { media.search(LibraryQuery(text = "kalki")).all() }.single()
        val version = env.media.versions(PlaybackTarget(kalki.id)).single()
        assertTrue(version.isSplit)
        assertEquals(listOf(1, 2, 3), version.parts.map { it.number })
        assertEquals(listOf("$name.001", "$name.002", "$name.003"), version.parts.map { it.fileName })
        assertEquals(5 * gb, version.sizeBytes, "the whole movie's size")
        assertEquals(version.mediaId, version.parts.first().mediaId, "the version is the first piece")
        assertEquals(emptyList(), SplitFiles.missing(version.parts))
        // The title page lists the same single version.
        assertEquals(1, env.media.getItem(kalki.id)!!.versions.size)
    }

    @Test
    fun aMissingPieceIsNamed() = runTest {
        val name = "Film.2022.2160p.mkv"
        env.file("$name.001", size = 2 * gb)
        env.file("$name.003", size = gb)
        env.sync.sync()
        val film = with(env) { media.search(LibraryQuery(text = "film")).all() }.single()
        val version = env.media.versions(PlaybackTarget(film.id)).single()
        assertEquals(listOf(2), SplitFiles.missing(version.parts))
    }

    private fun v(id: Long, name: String, message: Long = id, size: Long = gb, state: String = "NOT_DOWNLOADED", locator: String? = null) = MediaVersion(
        id, 1, message, name, size, null, 2160, null, null, emptyList(), isDeleted = false, isDuplicate = false, episodeId = null,
        isManual = false, isExcluded = false, downloadState = state, locator = locator,
    )

    private fun p(n: Int, message: Long, size: Long = gb, state: String = "NOT_DOWNLOADED", deleted: Boolean = false) =
        SplitPart(n, 100L + message, message, "F.mkv.%03d".format(n), size, deleted, state)

    @Test
    fun joiningRules() = runTest {
        val joined = SplitFiles.join(listOf(v(1, "F.mkv.001", message = 10), v(2, "F.mkv.002", message = 11), v(3, "Other.mkv"))) { _, base ->
            assertEquals("F.mkv", base)
            // Piece 2 was posted twice: the copy next to piece 1 wins.
            listOf(p(2, 11), p(2, 90), p(3, 12, size = 500))
        }
        assertEquals(listOf("F.mkv.001", "Other.mkv"), joined.map { it.fileName }, "later pieces are not versions of their own")
        val f = joined.first()
        assertEquals(listOf(10L, 11L, 12L), f.parts.map { it.messageId })
        assertEquals(2 * gb + 500, f.sizeBytes)

        // A lone first piece stays a normal file; files on the device are never joined.
        val lone = SplitFiles.join(listOf(v(1, "G.mkv.001"))) { _, _ -> emptyList() }.single()
        assertFalse(lone.isSplit)
        val local = v(5, "H.mkv.001", locator = "content://x")
        assertEquals(listOf(local), SplitFiles.join(listOf(local)) { _, _ -> error("not asked") })
    }

    @Test
    fun onThisDeviceOnlyWhenEveryPieceIs() {
        assertEquals("COMPLETE", SplitFiles.downloadState(listOf(p(1, 1, state = "COMPLETE"), p(2, 2, state = "COMPLETE"))))
        assertEquals("PARTIAL", SplitFiles.downloadState(listOf(p(1, 1, state = "COMPLETE"), p(2, 2))))
        assertEquals("NOT_DOWNLOADED", SplitFiles.downloadState(listOf(p(1, 1), p(2, 2))))
        assertEquals(listOf(2, 3), SplitFiles.missing(listOf(p(1, 1), p(2, 2, deleted = true), p(4, 4))), "deleted pieces and gaps")
    }
}
