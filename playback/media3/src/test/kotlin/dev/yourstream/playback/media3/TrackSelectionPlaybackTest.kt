package dev.yourstream.playback.media3

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.robolectric.RobolectricUtil
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper.run
import androidx.test.core.app.ApplicationProvider
import dev.yourstream.library.api.PlaybackTarget
import dev.yourstream.playback.stream.TelegramFileStreamer
import dev.yourstream.playback.testing.SimulatedTelegramFiles
import dev.yourstream.playback.testing.TestMedia
import dev.yourstream.playback.tracks.InMemoryTrackPreferenceStore
import dev.yourstream.playback.tracks.LanguagePreferences
import dev.yourstream.playback.tracks.LanguageSource
import dev.yourstream.playback.tracks.SelectionRule
import dev.yourstream.playback.tracks.SubtitleContent
import dev.yourstream.playback.tracks.TrackOrigin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowTrace
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Track intelligence inside the real ExoPlayer (Robolectric) with real multi-track files. */
@RunWith(RobolectricTestRunner::class)
class TrackSelectionPlaybackTest {
    @get:Rule val codecs: ShadowMediaCodecConfig = ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dir: File = Files.createTempDirectory("tracks").toFile()
    private val sim = SimulatedTelegramFiles(File(dir, "tdlib"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val streamer = TelegramFileStreamer(sim, scope)
    private val controllers = mutableListOf<TelegramPlaybackController>()

    @Before fun noTraceRecording() = ShadowTrace.setEnabled(false)

    @After fun tearDown() { controllers.forEach { runCatching { it.release() } }; sim.close(); scope.cancel(); dir.deleteRecursively() }

    private fun controller(store: InMemoryTrackPreferenceStore) = TelegramPlaybackController(
        context, streamer,
        playerFactory = { ctx, sources, load ->
            ExoPlayer.Builder(ctx).setClock(FakeClock(true)).setMediaSourceFactory(sources).setLoadControl(load)
                .setStuckBufferingDetectionTimeoutMs(Int.MAX_VALUE).setStuckPlayingDetectionTimeoutMs(Int.MAX_VALUE)
                .setStuckPlayingNotEndingTimeoutMs(Int.MAX_VALUE).setStuckSuppressedDetectionTimeoutMs(Int.MAX_VALUE).build()
        },
        trackSupport = TrackSupport(store),
    ).also { controllers += it }

    private fun media(id: Int, name: String, target: PlaybackTarget, subtitles: List<SideloadedSubtitle> = emptyList()): PlayableMedia {
        val file = TestMedia.file(name, dir)
        if (runCatching { sim.file(id) }.isFailure) sim.addFile(id, file)
        return PlayableMedia(name, name, id, "u$id", file.length(), 3_000, null, null, null, false, target, id.toLong(), subtitles = subtitles)
    }

    /** Plays until the track decision has been applied to the player. */
    private fun TelegramPlaybackController.playAndSelect(m: PlayableMedia) {
        play(m, playWhenReady = false)
        run(player).withTimeoutMs(30_000).untilState(Player.STATE_READY)
        RobolectricUtil.runMainLooperUntil { tracks.value.selection != null }
        RobolectricUtil.runMainLooperUntil { selectedFormat(player, C.TRACK_TYPE_AUDIO)?.label == tracks.value.selection?.audio?.track?.track?.label }
    }

    private fun selectedFormat(player: ExoPlayer, type: Int): Format? =
        player.currentTracks.groups.firstOrNull { it.type == type && it.isSelected }?.let { g -> (0 until g.length).firstOrNull(g::isTrackSelected)?.let(g::getTrackFormat) }

    /** Media3 normalizes tags ("tam" → "ta"); compare canonical codes. */
    private fun lang(f: Format?) = dev.yourstream.core.language.LanguageCatalog.normalizeCode(f?.language)

    private val tamilFirst = LanguagePreferences(audio = listOf("ta", "en", "hi"), subtitles = listOf("ta", "en"), fallbackLanguage = null)

    @Test
    fun preferredLanguageIsSelectedOverTheFilesDefault() {
        val c = controller(InMemoryTrackPreferenceStore(tamilFirst))
        c.playAndSelect(media(1, TestMedia.MULTI_AUDIO, PlaybackTarget(10)))
        assertEquals("Tamil", selectedFormat(c.player, C.TRACK_TYPE_AUDIO)?.label, "file default is Hindi; preference says Tamil")
        assertEquals(3, c.tracks.value.audio.size)
        assertEquals(listOf("hi", "ta", "en"), c.tracks.value.audio.map { it.language.code })
        // Tamil audio is understood: no subtitles.
        assertNull(selectedFormat(c.player, C.TRACK_TYPE_TEXT))
        assertEquals(SelectionRule.AUDIO_UNDERSTOOD, c.tracks.value.selection!!.subtitle.rule)
    }

    @Test
    fun wrongContainerTagsAreOverruledByTrackTitles() {
        val c = controller(InMemoryTrackPreferenceStore(tamilFirst))
        c.playAndSelect(media(2, TestMedia.MULTI_AUDIO_WRONG_TAGS, PlaybackTarget(11)))
        assertEquals("Tamil", selectedFormat(c.player, C.TRACK_TYPE_AUDIO)?.label)
        assertTrue(c.tracks.value.audio.all { it.language.source == LanguageSource.LABEL }, c.tracks.value.audio.toString())
    }

    @Test
    fun fallsBackThroughThePreferenceListAndShowsSubtitlesForForeignAudio() {
        // Prefers Japanese (absent): no fallback language → the file's default (Hindi), not understood → subtitles.
        val c = controller(InMemoryTrackPreferenceStore(LanguagePreferences(audio = listOf("ja"), subtitles = listOf("ta", "en"), fallbackLanguage = null)))
        c.playAndSelect(media(1, TestMedia.MULTI_AUDIO, PlaybackTarget(12)))
        assertEquals("Hindi", selectedFormat(c.player, C.TRACK_TYPE_AUDIO)?.label)
        assertEquals(SelectionRule.FILE_DEFAULT, c.tracks.value.selection!!.audio.rule)
        RobolectricUtil.runMainLooperUntil { selectedFormat(c.player, C.TRACK_TYPE_TEXT) != null }
        assertEquals("ta", lang(selectedFormat(c.player, C.TRACK_TYPE_TEXT)), "first subtitle preference")

        // English preferred over Tamil: English audio.
        val en = controller(InMemoryTrackPreferenceStore(LanguagePreferences(audio = listOf("en", "ta"), subtitles = listOf("en"))))
        en.playAndSelect(media(1, TestMedia.MULTI_AUDIO, PlaybackTarget(13)))
        assertEquals("English", selectedFormat(en.player, C.TRACK_TYPE_AUDIO)?.label)
    }

    @Test
    fun aUserChoiceIsRememberedForTheWholeSeries() {
        val store = InMemoryTrackPreferenceStore(tamilFirst)
        val ep1 = controller(store)
        ep1.playAndSelect(media(1, TestMedia.MULTI_AUDIO, PlaybackTarget(itemId = 50, episodeId = 501)))
        val english = ep1.tracks.value.audio.first { it.language.code == "en" }
        ep1.selectAudio(english.id)
        RobolectricUtil.runMainLooperUntil { selectedFormat(ep1.player, C.TRACK_TYPE_AUDIO)?.label == "English" }
        ep1.selectSubtitle(ep1.tracks.value.subtitles.first { it.language.code == "en" }.id)
        RobolectricUtil.runMainLooperUntil { lang(selectedFormat(ep1.player, C.TRACK_TYPE_TEXT)) == "en" }

        // Next episode (another file of the series): same choice, even though Tamil is preferred globally.
        val ep2 = controller(store)
        ep2.playAndSelect(media(2, TestMedia.MULTI_AUDIO_WRONG_TAGS, PlaybackTarget(itemId = 50, episodeId = 502)))
        assertEquals("English", selectedFormat(ep2.player, C.TRACK_TYPE_AUDIO)?.label)
        assertEquals(SelectionRule.REMEMBERED, ep2.tracks.value.selection!!.audio.rule)
        RobolectricUtil.runMainLooperUntil { selectedFormat(ep2.player, C.TRACK_TYPE_TEXT) != null }
        assertEquals("en", lang(selectedFormat(ep2.player, C.TRACK_TYPE_TEXT)))

        // A movie is not affected by the series memory.
        val movie = controller(store)
        movie.playAndSelect(media(1, TestMedia.MULTI_AUDIO, PlaybackTarget(itemId = 60)))
        assertEquals("Tamil", selectedFormat(movie.player, C.TRACK_TYPE_AUDIO)?.label)
    }

    @Test
    fun externalSubtitleFileIsLoadedAndItsRealLanguageDetected() {
        val srt = TestMedia.file(TestMedia.EXTERNAL_TAMIL_SRT, dir)
        val text = ExternalSubtitleLoader.decode(srt.readBytes())
        val external = SideloadedSubtitle(
            id = "ext:7", localPath = srt.absolutePath, mimeType = ExternalSubtitleLoader.mimeFor(null, srt.name)!!, language = "en", label = srt.name,
            forced = false, sdh = false, fileName = srt.name, contentLanguage = SubtitleContent.guess(text), origin = TrackOrigin.EXTERNAL,
        )
        val c = controller(InMemoryTrackPreferenceStore(LanguagePreferences(audio = listOf("ja"), subtitles = listOf("ta"), fallbackLanguage = null)))
        c.playAndSelect(media(3, TestMedia.SMALL_360P, PlaybackTarget(14), listOf(external)))
        val sub = c.tracks.value.subtitles.single()
        assertEquals("ta", sub.language.code, "the text is Tamil although name and tag say English")
        assertEquals(LanguageSource.CONTENT, sub.language.source)
        assertEquals(TrackOrigin.EXTERNAL, sub.track.origin)
        RobolectricUtil.runMainLooperUntil { selectedFormat(c.player, C.TRACK_TYPE_TEXT) != null }
        assertTrue(selectedFormat(c.player, C.TRACK_TYPE_TEXT)?.id?.endsWith("ext:7") == true, "the external file is the selected subtitle")
        run(c.player).untilState(Player.STATE_READY)
    }
}
