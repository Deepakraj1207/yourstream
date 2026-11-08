package dev.yourstream.e2e

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.robolectric.RobolectricUtil
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper.run
import androidx.test.core.app.ApplicationProvider
import dev.yourstream.catalog.core.DiscoverRequest
import dev.yourstream.core.common.AppResult
import dev.yourstream.library.api.SubtitleSource
import dev.yourstream.media.api.model.IndexMode
import dev.yourstream.playback.media3.PlaybackConfig
import dev.yourstream.playback.media3.PlaybackErrorKind
import dev.yourstream.playback.media3.PlaybackStatus
import dev.yourstream.playback.media3.TelegramPlaybackController
import dev.yourstream.playback.testing.TestMedia
import dev.yourstream.playback.tracks.LanguagePreferences
import dev.yourstream.playback.tracks.SubtitleMode
import dev.yourstream.search.core.SearchRequest
import dev.yourstream.telegram.api.model.TelegramMediaContentType
import dev.yourstream.watch.core.NextAction
import dev.yourstream.watch.core.NextReason
import dev.yourstream.watch.core.WatchStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowTrace
import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The functional audit, end to end: Telegram chats → chat selection → indexing → filename
 * parsing → library → metadata → search/assistant → playback (seek, buffering, subtitles, audio)
 * → watch progress → next episode → catalogs → offline → recovery.
 */
@RunWith(RobolectricTestRunner::class)
class PipelineTest {
    @get:Rule val codecs: ShadowMediaCodecConfig = ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    private val p = Pipeline(ApplicationProvider.getApplicationContext())
    private val janitor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { Thread(it).apply { isDaemon = true } }

    @Before fun noTraces() = ShadowTrace.setEnabled(false)
    @After fun close() { janitor.shutdownNow(); p.close() }

    private val report = mutableListOf<String>()
    private fun metric(name: String, value: Any) {
        val line = "E2E $name = $value"; println(line); report += line
        File("build/reports").mkdirs(); File("build/reports/e2e-metrics.txt").appendText(line + "\n")
    }

    private fun now() = System.nanoTime() / 1_000_000

    /** A realistic small library over three chats (one not selected). */
    private fun library(longVideo: File? = null) = runBlocking {
        p.chat(1, "Tamil Movies HD"); p.chat(2, "Kids Cartoons"); p.chat(3, "Random Chat", enabled = false)
        p.post(1, 1, "Vikram.2022.1080p.WEB-DL.Tamil.mp4", p.clip(TestMedia.RES_1080P))
        p.post(1, 2, "Vikram (2022) Tamil 720p HDRip.mp4", p.clip(TestMedia.RES_720P_MOOV_END))
        p.post(1, 3, "Leo.2023.Tam.Tel.Hin.480p.mkv", p.clip(TestMedia.MULTI_AUDIO))
        p.post(1, 4, "Comali.2019.Tamil.480p.mkv", p.clip(TestMedia.RES_480P_MKV))
        if (longVideo != null) p.post(1, 5, "Ponniyin.Selvan.2022.Tamil.1080p.mp4", longVideo, durationSeconds = (TestMedia.LONG_DURATION_MS / 1000).toInt())
        for (e in 1..3) p.post(2, 10L + e, "Ben.10.S01E%02d.Hindi.360p.mp4".format(e), p.clip(TestMedia.SMALL_360P), durationSeconds = 6)
        p.post(2, 20, "Ben.10.S01E01.Tamil.srt", p.clip(TestMedia.EXTERNAL_TAMIL_SRT), TelegramMediaContentType.DOCUMENT, null)
        p.post(2, 21, "Doraemon.S01E01.Tamil.360p.mp4", p.clip(TestMedia.SMALL_360P), durationSeconds = 6, downloaded = true)
        p.post(3, 30, "Should.Not.Index.2020.mp4", p.clip(TestMedia.SMALL_360P))
        with(p.tmdb) {
            movie("Vikram", 2022, "ta", "Action", "Thriller", imdb = "tt9179430"); movie("Leo", 2023, "ta", "Action")
            movie("Comali", 2019, "ta", "Comedy"); movie("Ponniyin Selvan", 2022, "ta", "History", "Drama")
            series("Ben 10", 2005, "en", "Animation", "Action"); series("Doraemon", 1979, "ja", "Animation", "Comedy")
        }
        val t = now()
        p.indexAll()
        metric("indexToSearchable.ms", now() - t)
    }

    private fun scalar(sql: String, vararg args: Any?): String? =
        p.db.openHelper.readableDatabase.query(sql, args).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun TelegramPlaybackController.await(state: Int, timeoutMs: Long = 60_000, ignoreErrors: Boolean = false) =
        run(player as ExoPlayer).withTimeoutMs(timeoutMs).let { if (ignoreErrors) it.ignoringNonFatalErrors() else it }.untilState(state)

    @Test
    fun indexingParsingLibraryMetadataSearchAssistantCatalogs(): Unit = runBlocking {
        library()
        // Chat selection: only enabled chats are read.
        assertEquals("0", scalar("SELECT COUNT(*) FROM media WHERE chat_id = 3"), "disabled chat not indexed")
        assertEquals("9", scalar("SELECT COUNT(*) FROM media WHERE chat_id IN (1, 2)"))

        // Filename parsing.
        val vikram = p.db.mediaDao().getWithParsed(p.db.mediaDao().findKey(1, 1)!!.id)!!.parsed!!
        assertEquals("Vikram" to 2022, vikram.title to vikram.year)
        assertEquals(1080, vikram.resolution)
        assertTrue("ta" in vikram.languages, vikram.languages)
        val ep2 = p.db.mediaDao().getWithParsed(p.db.mediaDao().findKey(2, 12)!!.id)!!.parsed!!
        assertEquals(1 to 2, ep2.season to ep2.episode)
        val leo = p.db.mediaDao().getWithParsed(p.db.mediaDao().findKey(1, 3)!!.id)!!.parsed!!
        assertEquals(listOf("ta", "te", "hi"), leo.languages.split(','), "multiple languages from Tam.Tel.Hin")

        // Library: movies with versions, series with episodes, subtitles attached.
        val vikramId = p.itemOf("Vikram")
        assertEquals("2", scalar("SELECT available_count FROM library_item WHERE id = ?", vikramId), "two versions of Vikram")
        val ben10 = p.itemOf("Ben 10")
        assertEquals("SERIES", scalar("SELECT kind FROM library_item WHERE id = ?", ben10))
        assertEquals("3", scalar("SELECT COUNT(*) FROM episode WHERE series_id = ?", ben10))
        val e1 = scalar("SELECT id FROM episode WHERE series_id = ? AND number = 1", ben10)!!.toLong()
        assertEquals(listOf(SubtitleSource.EXTERNAL), p.subtitles.subtitles(p.target(ben10, e1)).first().map { it.source }, "Telegram .srt linked to S01E01")
        assertEquals("0", scalar("SELECT COUNT(*) FROM library_item WHERE title LIKE 'Should%'"))

        // Metadata matching.
        assertEquals("Action,Thriller", scalar("SELECT genres FROM item_metadata WHERE item_id = ?", vikramId))
        assertEquals("tt9179430", scalar("SELECT imdb_id FROM item_metadata WHERE item_id = ?", vikramId))
        assertEquals("Animation,Action", scalar("SELECT genres FROM item_metadata WHERE item_id = ?", ben10))
        assertEquals("Episode 2 title", scalar("SELECT em.title FROM episode_metadata em JOIN episode e ON e.id = em.episode_id WHERE e.series_id = ? AND e.number = 2", ben10))

        // Search.
        fun ids(q: String) = runBlocking { p.search.search(SearchRequest(q)).hits.map { it.itemId } }
        assertEquals(vikramId, ids("vikram").first())
        assertEquals(p.itemOf("Doraemon"), ids("doremon").first(), "typo")
        assertEquals(ben10, ids("ben 10 s1").first())
        assertTrue(ids("tamil action").take(2).toSet() == setOf(vikramId, p.itemOf("Leo")), ids("tamil action").toString())

        // Assistant.
        assertEquals(setOf("Vikram", "Leo", "Comali"), p.assistant.ask("show Tamil movies").items.map { it.title }.toSet())
        assertEquals(3, p.assistant.ask("find ben 10 episodes").total)

        // Catalogs: provider listing with presence in the library.
        val discovered = p.catalogs.discover(DiscoverRequest(language = "ta"))
        val presence = p.catalogs.withLibraryPresence(discovered.items).associate { it.first.title to it.second?.itemId }
        assertEquals(mapOf("Vikram" to vikramId, "Jailer" to null), presence, "Vikram is on Telegram, Jailer is not")
    }

    @Test
    fun playbackSeekingProgressAndNextEpisode(): Unit = runBlocking {
        library()
        val vikramId = p.itemOf("Vikram")
        val c = p.controller()
        val t = now()
        assertIs<AppResult.Ok<*>>(c.open(p.target(vikramId)))
        c.await(Player.STATE_READY)
        metric("open1080p.startupMs", now() - t)
        assertEquals(1080, c.snapshot().videoHeight, "the best (1080p) version was chosen")
        c.seekTo(1_000)
        c.await(Player.STATE_READY)
        assertTrue(abs(c.snapshot().positionMs - 1_000) < 600, "seek landed at ${c.snapshot().positionMs}")
        c.await(Player.STATE_ENDED)
        RobolectricUtil.runMainLooperUntil { runBlocking { p.watch.summary(vikramId)?.status == WatchStatus.COMPLETED } }
        assertEquals(1, p.watch.summary(vikramId)!!.watchCount)

        // Series: finishing S01E01 makes S01E02 the next episode.
        val ben10 = p.itemOf("Ben 10")
        val eps = p.watch.episodes(ben10)
        val ep = p.controller(audio = false)
        ep.open(p.target(ben10, eps[0].episodeId))
        ep.await(Player.STATE_ENDED)
        RobolectricUtil.runMainLooperUntil { runBlocking { p.watch.nextEpisode(ben10).episode?.episodeId == eps[1].episodeId } }
        assertEquals(NextReason.NEXT_AFTER_COMPLETED, p.watch.nextEpisode(ben10).reason)
        // S01E02 at 35 % → Continue Watching resumes it.
        p.history.updateProgress(p.target(ben10, eps[1].episodeId), null, 462_000, 1_320_000) // 35 % of 22 min
        val cw = p.watch.continueNow()
        assertEquals(eps[1].episodeId, cw.first().target.episodeId)
        assertEquals(NextAction.RESUME, cw.first().action)
        assertEquals("ben 10", p.assistant.ask("continue watching ben 10").interpretation.entities.title)
        assertEquals(eps[1].episodeId, p.assistant.ask("continue watching ben 10").items.single().episodeId)
        // Resume position is used when opening again.
        assertEquals(462_000L, p.history.progress(p.target(ben10, eps[1].episodeId)).first()!!.positionMs)
        assertTrue(p.watch.recentlyWatchedNow().map { it.summary.itemId }.containsAll(listOf(vikramId, ben10)))
    }

    @Test
    fun audioAndSubtitleSelection(): Unit = runBlocking {
        library()
        p.trackStore.setPreferences(LanguagePreferences(audio = listOf("ta", "en"), subtitles = listOf("ta", "en"), subtitleMode = SubtitleMode.FOREIGN_ONLY))
        // Leo: Hindi default, Tamil preferred.
        val leo = p.controller()
        val tLeo = now()
        leo.open(p.target(p.itemOf("Leo")), playWhenReady = false)
        leo.await(Player.STATE_READY)
        RobolectricUtil.runMainLooperUntil { leo.tracks.value.selection != null }
        assertEquals("ta", leo.tracks.value.selection!!.audio.track?.language?.code, leo.tracks.value.selection.toString())
        assertEquals(3, leo.tracks.value.audio.size)
        metric("multiAudio.trackSelectionMs", now() - tLeo)

        // Ben 10 S01E01 (Hindi audio by name): the user's Tamil .srt from Telegram is downloaded, recognised as Tamil and shown.
        val ben10 = p.itemOf("Ben 10")
        val e1 = p.watch.episodes(ben10).first()
        val ben = p.controller(audio = false)
        val tBen = now()
        ben.open(p.target(ben10, e1.episodeId), playWhenReady = false)
        metric("externalSubtitle.openMs", now() - tBen)
        ben.await(Player.STATE_READY)
        metric("externalSubtitle.readyMs", now() - tBen)
        // The sideloaded source is analysed a moment after the embedded tracks: wait for it.
        RobolectricUtil.runMainLooperUntil { ben.tracks.value.selection?.subtitle?.track?.id?.contains("ext:") == true }
        assertTrue(now() - tBen < 5_000, "subtitle loading must not hold playback back")
        metric("externalSubtitle.selectedMs", now() - tBen)
        val external = ben.tracks.value.subtitles.filter { "ext:" in it.id } // Media3 prefixes merged sources ("1:ext:1")
        assertEquals(1, external.size, ben.tracks.value.subtitles.toString())
        assertEquals("ta", external.single().language.code, "content detection overrides the name")
        assertEquals(external.single().id, ben.tracks.value.selection!!.subtitle.track?.id, "foreign audio → Tamil subtitles")
    }

    @Test
    fun largeFilePoorNetworkAndNetworkSwitching(): Unit = runBlocking {
        val long = TestMedia.longVideo()
        assumeTrue("long test video needs ffmpeg", long != null)
        library(long)
        p.files.bandwidthBytesPerSecond = 1L shl 20 // ~8 Mbit/s
        p.files.latencyMs = 150
        val id = p.itemOf("Ponniyin Selvan")
        val c = p.controller(audio = false)
        val handler = android.os.Handler(c.player.playbackLooper)
        janitor.scheduleWithFixedDelay({ handler.post { ShadowTrace.reset() } }, 50, 50, java.util.concurrent.TimeUnit.MILLISECONDS)
        val t = now()
        c.open(p.target(id))
        c.await(Player.STATE_READY, 90_000)
        metric("long85min.poorNetwork.startupMs", now() - t)
        val s = now()
        c.seekTo(4_800_000)
        c.await(Player.STATE_READY, 90_000)
        metric("long85min.poorNetwork.farSeekMs", now() - s)
        assertTrue(abs(c.snapshot().positionMs - 4_800_000) < 3_000)
        val fid = c.snapshot().media!!.fileId
        val fraction = p.files.file(fid).downloadedFraction()
        metric("long85min.downloadedPercent", "%.2f".format(fraction * 100))
        assertTrue(fraction < 0.10, "only around the seek target: $fraction")
        // Network switch (Wi-Fi → mobile): connection drops briefly, downloads are re-issued.
        p.files.online = false
        Thread.sleep(800)
        p.files.online = true
        p.streamer.refreshDownloads()
        run(c.player).withTimeoutMs(90_000).ignoringNonFatalErrors().untilPositionAtLeast(4_803_000)
        assertEquals(null, c.snapshot().error)

        // 720p with the index at the end, slow network: plays through.
        p.files.bandwidthBytesPerSecond = 256L * 1024
        val v720 = p.controller(audio = false)
        v720.open(p.target(p.itemOf("Vikram")), mediaId = p.db.mediaDao().findKey(1, 2)!!.id)
        v720.await(Player.STATE_ENDED, 90_000, ignoreErrors = true)
        assertEquals(720, v720.snapshot().videoHeight)
    }

    @Test
    fun offlineTelegramAndProviderOutages(): Unit = runBlocking {
        library()
        p.catalogs.discover(DiscoverRequest(language = "ta")) // caches the catalog while online
        // Everything external goes away.
        p.telegram.online = false; p.files.online = false; p.tmdb.down = true; p.catalog.down = true
        p.chat(1, "Tamil Movies HD")
        p.post(1, 40, "Jailer.2023.Tamil.1080p.mp4", p.clip(TestMedia.SMALL_360P)) // not reachable now
        p.indexAll() // must not throw
        assertTrue(scalar("SELECT last_error FROM indexed_chat WHERE chat_id = 1") != null || scalar("SELECT state FROM indexed_chat WHERE chat_id = 1") != "IDLE",
            "the failed run is recorded on the chat")

        // Browsing, search, assistant, watch lists: all local.
        assertEquals(p.itemOf("Vikram"), p.search.search(SearchRequest("vikram")).hits.first().itemId)
        assertTrue(p.assistant.ask("what should I watch next?").items.isNotEmpty())
        assertTrue(p.watch.recentlyAddedNow().isNotEmpty())
        // Catalogs: served from the cache, flagged stale.
        val cached = p.catalogs.discover(DiscoverRequest(language = "ta"))
        assertEquals(2, cached.items.size, "cached listing while the provider is down")
        // Metadata provider down: matching reports failures, nothing crashes, titles stay.
        p.db.openHelper.writableDatabase.execSQL("UPDATE metadata_match SET next_attempt_at = 0")
        p.matchMetadata()
        assertEquals("Vikram", scalar("SELECT title FROM library_item WHERE id = ?", p.itemOf("Vikram")))

        // Playback offline: a file already on the device plays (stored file id, no message lookup).
        val doraemon = p.itemOf("Doraemon")
        val c = p.controller(audio = false)
        assertIs<AppResult.Ok<*>>(c.open(p.target(doraemon, p.watch.episodes(doraemon).first().episodeId)))
        c.await(Player.STATE_ENDED)
        // One that is not downloaded: a clear, retryable state — no crash.
        val v = p.controller(audio = false, config = PlaybackConfig(loadRetries = 1, retryBaseDelayMs = 100, retryMaxDelayMs = 200))
        val r = v.open(p.target(p.itemOf("Vikram")))
        if (r is AppResult.Ok) {
            RobolectricUtil.runMainLooperUntil({ v.snapshot().status == PlaybackStatus.ERROR || v.snapshot().status == PlaybackStatus.PREPARING || v.snapshot().status == PlaybackStatus.BUFFERING }, 30_000, androidx.media3.common.util.Clock.DEFAULT)
        } else {
            assertEquals(PlaybackStatus.ERROR, v.state.value.status)
        }
        metric("offline.unreachableFile.state", v.snapshot().status.name + " / " + (v.snapshot().error?.kind ?: "-"))
    }

    @Test
    fun deletedFilesFallBackToOtherVersions(): Unit = runBlocking {
        library()
        val vikramId = p.itemOf("Vikram")
        val v1080 = p.db.mediaDao().findKey(1, 1)!!.id
        val v720 = p.db.mediaDao().findKey(1, 2)!!.id
        // The 1080p message is deleted on Telegram after indexing.
        p.telegram.delete(1, 1)
        val media = (p.resolver.resolve(p.target(vikramId), null) as AppResult.Ok).value
        assertEquals(v720, media.mediaId, "falls through to the 720p version")
        // Verification notices the deletion; the library keeps the title with one version.
        p.indexAll(IndexMode.VERIFY)
        assertEquals("1", scalar("SELECT is_deleted FROM media WHERE id = ?", v1080))
        assertEquals("1", scalar("SELECT available_count FROM library_item WHERE id = ?", vikramId))
        // Both gone: a clear error, not a crash.
        p.telegram.delete(1, 2)
        val c = p.controller(audio = false)
        assertIs<AppResult.Err>(c.open(p.target(vikramId)))
        assertEquals(PlaybackErrorKind.FILE_UNAVAILABLE, c.state.value.error!!.kind)
        // A file that becomes undownloadable mid-playback switches version (Comali has one version → clear error).
        val comali = p.controller(audio = false, config = PlaybackConfig(loadRetries = 1, retryBaseDelayMs = 100, retryMaxDelayMs = 200))
        comali.open(p.target(p.itemOf("Comali")))
        p.files.makeUnavailable(comali.snapshot().media!!.fileId)
        RobolectricUtil.runMainLooperUntil { comali.snapshot().status == PlaybackStatus.ERROR || comali.snapshot().status == PlaybackStatus.ENDED }
        assertNotNull(comali.snapshot())
    }

    /** Player screens come and go: nothing may accumulate (sessions, downloads, threads, coroutines). */
    @Test
    fun repeatedOpenReleaseLeaksNothing(): Unit = runBlocking {
        library()
        val ben10 = p.itemOf("Ben 10")
        val eps = p.watch.episodes(ben10)
        // Warm up once so lazily created pools exist before the baseline.
        p.controller(audio = false).let { c -> c.open(p.target(ben10, eps[0].episodeId)); c.await(Player.STATE_READY); c.release() }
        Thread.sleep(300)
        val threadsBefore = Thread.activeCount()
        repeat(15) { i ->
            val c = p.controller(audio = false)
            c.open(p.target(ben10, eps[i % 3].episodeId))
            c.await(Player.STATE_READY)
            c.seekTo(2_000)
            c.await(Player.STATE_READY)
            c.release()
        }
        RobolectricUtil.runMainLooperUntil { p.streamer.activeFileIds.isEmpty() }
        Thread.sleep(500)
        assertEquals(emptySet(), p.streamer.activeFileIds, "every streaming session released")
        assertEquals(0, p.files.activeDownloads(), "no download left running")
        val threadsAfter = Thread.activeCount()
        metric("leak.threads.before/after15players", "$threadsBefore/$threadsAfter")
        assertTrue(threadsAfter <= threadsBefore + 4, "threads $threadsBefore → $threadsAfter after 15 player screens")
        val rt = Runtime.getRuntime(); System.gc()
        metric("leak.heapAfter15playersMB", (rt.totalMemory() - rt.freeMemory()) / (1 shl 20))
    }
}
