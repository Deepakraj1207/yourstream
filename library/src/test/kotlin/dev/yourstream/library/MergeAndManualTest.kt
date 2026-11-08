package dev.yourstream.library

import dev.yourstream.core.common.AppResult
import dev.yourstream.library.api.LibraryKind
import dev.yourstream.library.api.LibraryQuery
import dev.yourstream.library.api.PlaybackTarget
import dev.yourstream.library.api.TrackPreference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class MergeAndManualTest {

    private val env = LibraryTestEnv()
    @After fun tearDown() = env.close()

    @Test
    fun mergingMoviesKeepsEverythingAndFollowsFutureFiles() = runTest {
        with(env) {
            val a = file("KGF.Chapter.2.2022.Kannada.1080p.mkv")
            val b = file("K.G.F.Chapter.Two.2022.Hindi.720p.mkv")
            sync.sync()
            val target = itemIdOf(a); val source = itemIdOf(b)
            assertTrue(target != source, "different spellings → two items at first")
            media.setFavorite(source, true)
            watch.updateProgress(PlaybackTarget(source), mediaId(b), 2_000_000, 9_000_000)
            subtitles.setSubtitlePreference(source, "en", enabled = true)

            assertIs<AppResult.Ok<Unit>>(media.mergeItems(target, source))

            val merged = media.getItem(target)!!
            assertEquals(2, merged.versions.size)
            assertTrue(merged.summary.isFavorite)
            assertEquals(2_000_000, merged.summary.progress!!.positionMs)
            assertEquals("en", subtitles.preference(target)!!.subtitleLanguage)
            assertTrue(merged.locked)
            assertNull(media.getItem(source))
            assertEquals(listOf("hi", "kn"), merged.languages)

            // A new file with the source's spelling follows the merge.
            val c = file("K.G.F.Chapter.Two.2022.Tamil.480p.mkv")
            sync.sync()
            assertEquals(target, itemIdOf(c))
            assertEquals(1, media.count(LibraryQuery()).first())
        }
    }

    @Test
    fun mergingSeriesCombinesEpisodesAndProgress() = runTest {
        with(env) {
            val a1 = file("Chhota.Bheem.S01E01.mkv")
            val b1 = file("Chota.Bheem.S01E01.Hindi.mkv")
            val b2 = file("Chota.Bheem.S01E02.Hindi.mkv")
            sync.sync()
            val target = itemIdOf(a1); val source = itemIdOf(b1)
            val sourceEpisodes = episodes.episodes(source).all()
            watch.markWatched(PlaybackTarget(source, sourceEpisodes[0].id), true)
            watch.updateProgress(PlaybackTarget(source, sourceEpisodes[1].id), mediaId(b2), 400_000, 1_300_000)

            media.mergeItems(target, source)

            val eps = episodes.episodes(target).all()
            assertEquals(listOf("S01E01", "S01E02"), eps.map { it.label })
            assertEquals(2, episodes.versions(eps[0].id).size, "E01 now has both files")
            assertEquals(true, eps[0].progress?.completed)
            assertEquals(400_000, eps[1].progress?.positionMs)
            assertEquals(1, media.getItem(target)!!.summary.watchedEpisodeCount)
        }
    }

    @Test
    fun incompatibleMergesAreRejected() = runTest {
        with(env) {
            val m = file("Movie.Name.2024.mkv"); val s = file("Show.S01E01.mkv"); sync.sync()
            assertIs<AppResult.Err>(media.mergeItems(itemIdOf(m), itemIdOf(s)))
            assertIs<AppResult.Err>(media.mergeItems(itemIdOf(m), itemIdOf(m)))
        }
    }

    @Test
    fun manualLinksAndExclusionsSurviveSync() = runTest {
        with(env) {
            val movie = file("Real.Movie.2020.mkv")
            val stray = file("VID_20240301.mp4", type = dev.yourstream.telegram.api.model.TelegramMediaContentType.VIDEO)
            sync.sync()
            val movieId = itemIdOf(movie)
            media.linkMedia(mediaId(stray), PlaybackTarget(movieId))
            // The stray file's parse changes, sync runs: the manual link holds.
            file("VID_20240301.mp4", messageId = stray, type = dev.yourstream.telegram.api.model.TelegramMediaContentType.VIDEO)
            sync.sync()
            assertEquals(movieId, itemIdOf(stray))
            assertEquals(2, media.getItem(movieId)!!.versions.size)

            media.excludeMedia(mediaId(stray))
            sync.sync()
            val versions = media.versions(PlaybackTarget(movieId))
            assertEquals(1, versions.size)
            assertEquals(1, media.getItem(movieId)!!.summary.availableCount)
        }
    }

    @Test
    fun editedAndDeletedItemsStayThatWay() = runTest {
        with(env) {
            val f = file("Wrong.Name.2020.mkv"); sync.sync()
            val id = itemIdOf(f)
            media.updateItem(id, "Right Name", 2021)
            file("Wrong.Name.2020.1080p.mkv"); sync.sync() // another file of the same key
            val item = media.getItem(id)!!
            assertEquals("Right Name", item.summary.title)
            assertEquals(2021, item.summary.year)
            assertEquals(listOf("Right Name"), media.search(LibraryQuery(text = "right")).all().map { it.title })
            assertEquals(listOf("Right Name"), media.search(LibraryQuery(text = "wrong")).all().map { it.title }, "old name still finds it")

            media.deleteItem(id)
            file("Wrong.Name.2020.720p.mkv"); sync.sync()
            assertEquals(0, media.count(LibraryQuery()).first(), "hidden items do not come back")
            media.restoreItem(id)
            assertEquals(1, media.count(LibraryQuery()).first())
        }
    }

    @Test
    fun audioAndSubtitleTracks() = runTest {
        with(env) {
            val f = file("RRR.2022.Tam+Tel+Hin.DDP5.1.1080p.mkv"); sync.sync()
            val item = itemIdOf(f); val mid = mediaId(f)
            val parsed = audio.tracks(mid)
            assertEquals(listOf("ta", "te", "hi"), parsed.map { it.language })
            assertEquals("DD+", parsed.first().codec)
            assertEquals(listOf("hi", "ta", "te"), audio.languages(item))

            audio.replaceProbed(mid, listOf(parsed[0].copy(language = "ta", codec = "E-AC-3", trackIndex = 0), parsed[1].copy(language = "en", trackIndex = 1)))
            assertEquals(listOf("ta", "en"), audio.tracks(mid).map { it.language }, "probed tracks win")
            audio.setPreferredLanguage(item, "ta")
            assertEquals("ta", audio.preferredLanguage(item))

            subtitles.replaceEmbedded(mid, listOf(
                dev.yourstream.library.api.SubtitleTrack(0, item, null, dev.yourstream.library.api.SubtitleSource.EMBEDDED, null, null, 2, "en", "English", "ass", false, false, null),
            ))
            subtitles.addLocal(PlaybackTarget(item), "/sdcard/rrr.srt", "ta", "Tamil", "srt")
            val subs = subtitles.subtitles(PlaybackTarget(item), mid).first()
            assertEquals(listOf("en", "ta"), subs.map { it.language })
            assertEquals(TrackPreference(item, "ta", null, true, 0), audio.let { subtitles.preference(item) })
        }
    }
}
