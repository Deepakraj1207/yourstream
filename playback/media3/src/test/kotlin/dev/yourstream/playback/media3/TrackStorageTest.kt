package dev.yourstream.playback.media3

import androidx.test.core.app.ApplicationProvider
import dev.yourstream.core.database.YourStreamDatabase
import dev.yourstream.core.database.entity.LibraryItemEntity
import dev.yourstream.core.database.entity.TrackPreferenceEntity
import dev.yourstream.playback.tracks.LanguagePreferences
import dev.yourstream.playback.tracks.PreferenceScope
import dev.yourstream.playback.tracks.SubtitleChoice
import dev.yourstream.playback.tracks.SubtitleMode
import dev.yourstream.playback.tracks.TrackSignature
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class TrackStorageTest {
    private val db = YourStreamDatabase.inMemory(ApplicationProvider.getApplicationContext())
    private val store = RoomTrackPreferenceStore(db)

    @After fun close() = db.close()

    private suspend fun item(id: Long) = db.libraryDao().insertItem(
        LibraryItemEntity(id = id, kind = "SERIES", matchKey = "s:$id", title = "S$id", sortTitle = "s", normalizedTitle = "s", year = null, searchText = "s", titleConfidence = 1f, addedAt = 0, updatedAt = 0),
    )

    @Test
    fun languageSettingsAndRememberedChoicesRoundTrip(): Unit = runBlocking {
        assertEquals(LanguagePreferences().normalized(), store.preferences(), "defaults before the user sets anything")
        store.setPreferences(LanguagePreferences(audio = listOf("Tamil", "eng", "hin"), subtitles = listOf("ta", "English"), fallbackLanguage = "en", subtitleMode = SubtitleMode.ALWAYS, preferSdh = true))
        val p = store.preferences()
        assertEquals(listOf("ta", "en", "hi"), p.audio)
        assertEquals(listOf("ta", "en"), p.subtitles)
        assertEquals(SubtitleMode.ALWAYS, p.subtitleMode)
        assertEquals(true, p.preferSdh)

        item(1)
        val sig = TrackSignature("hi", "hindi dubbed", 6)
        store.rememberAudio(1, PreferenceScope.SERIES, sig)
        store.rememberSubtitle(1, PreferenceScope.SERIES, SubtitleChoice.Off)
        val r = store.remembered(1)!!
        assertEquals(PreferenceScope.SERIES, r.scope)
        assertEquals(sig, r.audio)
        assertEquals(SubtitleChoice.Off, r.subtitle)
        store.rememberSubtitle(1, PreferenceScope.SERIES, SubtitleChoice.Track(TrackSignature("en", null, forced = true)))
        assertEquals(SubtitleChoice.Track(TrackSignature("en", null, forced = true)), store.remembered(1)!!.subtitle)
        assertEquals(sig, store.remembered(1)!!.audio, "changing subtitles keeps the audio choice")
        store.forget(1)
        assertNull(store.remembered(1))
    }

    @Test
    fun rowsFromBeforeVersion6StillWork(): Unit = runBlocking {
        item(2)
        // A Phase 4 preference: languages only, subtitles disabled, an offset.
        db.trackDao().upsertPreference(TrackPreferenceEntity(2, "ta", "en", subtitlesEnabled = false, subtitleOffsetMs = 300, updatedAt = 1))
        val r = store.remembered(2)!!
        assertEquals(TrackSignature("ta", null), r.audio)
        assertEquals(SubtitleChoice.Off, r.subtitle)
        store.rememberAudio(2, PreferenceScope.SERIES, TrackSignature("en", "english"))
        assertEquals(300, db.trackDao().preference(2)!!.subtitleOffsetMs, "the offset is kept")
        // The library API changes the language later without a signature: the language wins.
        db.trackDao().upsertPreference(db.trackDao().preference(2)!!.copy(audioLanguage = "hi"))
        assertEquals("hi", store.remembered(2)!!.audio!!.language)
    }

    @Test
    fun subtitleFilesAreDecodedAndOnlyTextFormatsAccepted() {
        assertEquals("application/x-subrip", ExternalSubtitleLoader.mimeFor(null, "Movie.ta.srt"))
        assertEquals("text/x-ssa", ExternalSubtitleLoader.mimeFor("ass", null))
        assertEquals("text/vtt", ExternalSubtitleLoader.mimeFor(null, "x.VTT"))
        assertNull(ExternalSubtitleLoader.mimeFor(null, "movie.sup"), "PGS bitmaps are not sideloaded")
        assertNull(ExternalSubtitleLoader.mimeFor(null, "movie.idx"))
        assertEquals("café", ExternalSubtitleLoader.decode(byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte())), "Windows-1252")
        assertEquals("தமிழ்", ExternalSubtitleLoader.decode("தமிழ்".toByteArray()))
        assertEquals("hi", ExternalSubtitleLoader.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x68, 0, 0x69, 0)), "UTF-16LE with BOM")
        assertEquals("ok", ExternalSubtitleLoader.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 0x6F, 0x6B)), "UTF-8 BOM stripped")
    }
}
