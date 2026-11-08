package dev.yourstream.playback.tracks

import dev.yourstream.core.language.LanguageCatalog

import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanguageCatalogTest {
    @Test
    fun normalizesEverySpellingOfALanguage() {
        listOf("ta", "tam", "TAM", "ta-IN", "ta_IN", "Tamil", "TAMIL", "தமிழ்").forEach { assertEquals("ta", LanguageCatalog.normalizeCode(it), it) }
        assertEquals("hi", LanguageCatalog.normalizeCode("hin"))
        assertEquals("en", LanguageCatalog.normalizeCode("en-US"))
        assertEquals("zh", LanguageCatalog.normalizeCode("zh-Hant"))
        assertEquals("zh", LanguageCatalog.normalizeCode("yue"))
        assertEquals("he", LanguageCatalog.normalizeCode("iw"))
        assertEquals("no", LanguageCatalog.normalizeCode("nob"))
        assertEquals("tl", LanguageCatalog.normalizeCode("fil"))
        assertEquals("fr", LanguageCatalog.normalizeCode("fre"))
        assertEquals("es", LanguageCatalog.normalizeCode("Español"))
        listOf(null, "", "und", "UND", "mul", "zxx", "xx", "unknown", "12").forEach { assertNull(LanguageCatalog.normalizeCode(it), "$it") }
        assertEquals("xyz", LanguageCatalog.normalizeCode("xyz"), "well-formed unknown codes are kept")
    }

    @Test
    fun findsLanguagesInLabelsAndNamesWithoutFalseHits() {
        assertEquals(listOf("ta"), LanguageCatalog.findInText("Tamil 5.1"))
        assertEquals(listOf("hi"), LanguageCatalog.findInText("Hindi (Dubbed)"))
        assertEquals(listOf("en"), LanguageCatalog.findInText("[ENG] SDH"))
        assertEquals(listOf("ta", "te", "hi"), LanguageCatalog.findInText("Tam+Tel+Hin"))
        assertEquals(listOf("ta", "te", "hi"), LanguageCatalog.findInText("Leo.2023.Tamil.Telugu.Hindi.1080p.WEB-DL.mkv"))
        assertEquals(listOf("ta"), LanguageCatalog.findInText("தமிழ் (Original)"))
        assertEquals(emptyList(), LanguageCatalog.findInText("Director's commentary with Dan and the cast, may contain spoilers"), "ordinary words are not codes in prose")
        assertEquals(listOf("da"), LanguageCatalog.findInText("DAN"), "an upper-case short label is a code")
        assertEquals(emptyList(), LanguageCatalog.findInText("Stereo"))
    }
}

class LanguageDetectorTest {
    private fun audio(i: Int, tag: String? = null, label: String? = null, default: Boolean = false) =
        TrackCandidate("a$i", TrackType.AUDIO, i, tag, label, isDefault = default)

    private fun codes(tracks: List<AnalyzedTrack>) = tracks.map { it.language.code }

    @Test
    fun trustsConsistentMetadata() {
        val r = LanguageDetector.analyze(listOf(audio(0, "tam"), audio(1, "hin"), audio(2, "eng")))
        assertEquals(listOf("ta", "hi", "en"), codes(r))
        assertTrue(r.all { it.language.source == LanguageSource.METADATA && it.language.confidence >= 0.8 })
    }

    @Test
    fun copiedDefaultTagsLoseToLabels() {
        // Muxer left "eng" on every track; the titles say what they really are.
        val r = LanguageDetector.analyze(listOf(audio(0, "eng", "Tamil"), audio(1, "eng", "Hindi"), audio(2, "eng", "English")))
        assertEquals(listOf("ta", "hi", "en"), codes(r))
        assertTrue(r.all { it.language.source == LanguageSource.LABEL })
    }

    @Test
    fun untaggedTracksTakeTheFileNameLanguagesInOrder() {
        val r = LanguageDetector.analyze(
            listOf(audio(0, "und"), audio(1, null), audio(2, "und")),
            FileContext("Vikram.2022.Tam+Tel+Hin.mkv", fileNameAudioLanguages = listOf("ta", "te", "hi")),
        )
        assertEquals(listOf("ta", "te", "hi"), codes(r))
        assertTrue(r.all { it.language.source == LanguageSource.FILE_NAME_ORDER && it.language.confidence < 0.8 }, "weaker than real evidence")
    }

    @Test
    fun copiedDefaultsAreReplacedByFileNameOrderAndMismatchedCountsAreLeftAlone() {
        val r = LanguageDetector.analyze(listOf(audio(0, "eng"), audio(1, "eng")), FileContext(fileNameAudioLanguages = listOf("ta", "hi")))
        assertEquals(listOf("ta", "hi"), codes(r), "\"eng\" on both tracks of a Tamil+Hindi file is not trusted")
        val unsure = LanguageDetector.analyze(listOf(audio(0), audio(1), audio(2)), FileContext(fileNameAudioLanguages = listOf("ta", "hi")))
        assertEquals(listOf(null, null, null), codes(unsure), "3 tracks but 2 names: no guessing")
    }

    @Test
    fun aLabelContradictingItsTagWins() {
        val r = LanguageDetector.analyze(listOf(audio(0, "hin", "Tamil Original"), audio(1, "eng")))
        assertEquals(listOf("ta", "en"), codes(r))
    }

    @Test
    fun singleUntaggedTrackFallsBackToTheTitlesOriginalLanguage() {
        val r = LanguageDetector.analyze(listOf(audio(0)), FileContext(originalLanguage = "ml"))
        assertEquals("ml", r.single().language.code)
        assertEquals(LanguageSource.ORIGINAL_LANGUAGE, r.single().language.source)
    }

    @Test
    fun subtitleFileNamesAndContentAreEvidence() {
        assertEquals("ta", LanguageDetector.subtitleFileLanguage("Leo.2023.ta.srt"))
        assertEquals("en", LanguageDetector.subtitleFileLanguage("Leo.2023.eng.forced.srt"))
        assertEquals("ta", LanguageDetector.subtitleFileLanguage("Leo 2023 Tamil Subtitles.srt"))
        assertNull(LanguageDetector.subtitleFileLanguage("Leo.2023.1080p.srt"))
        assertEquals("en", LanguageDetector.subtitleFileLanguage("Leo.Tamil.English.srt"), "the suffix names the subtitle language")
        assertNull(LanguageDetector.subtitleFileLanguage("Tamil English Leo 2023.srt"), "two languages and no suffix: ambiguous")

        // Declared English, but the text is Tamil: content wins.
        val tamilText = SubtitleContent.guess(TAMIL_SRT)!!
        val r = LanguageDetector.analyze(listOf(TrackCandidate("s0", TrackType.SUBTITLE, 0, "eng", origin = TrackOrigin.EXTERNAL, fileName = "Leo.eng.srt", contentLanguage = tamilText)))
        assertEquals("ta", r.single().language.code)
        assertEquals(LanguageSource.CONTENT, r.single().language.source)
    }

    companion object {
        val TAMIL_SRT = """
            1
            00:00:01,000 --> 00:00:03,000
            நான் உன்னை எப்போதும் மறக்க மாட்டேன்

            2
            00:00:04,000 --> 00:00:06,500
            <i>இது என் வீடு, இங்கே யாரும் வர முடியாது</i>
        """.trimIndent()
    }
}

class SubtitleContentTest {
    @Test
    fun recognisesScriptsAndEnglish() {
        assertEquals("ta", SubtitleContent.guess(LanguageDetectorTest.TAMIL_SRT)?.code)
        val hindi = SubtitleContent.guess("1\n00:00:01,000 --> 00:00:02,000\nमैं तुम्हें कभी नहीं भूलूंगा, यह मेरा घर है और यहाँ कोई नहीं आ सकता")!!
        assertEquals("hi", hindi.code)
        assertTrue(hindi.confidence < 0.8, "Devanagari is shared with Marathi/Nepali: weaker")
        val english = SubtitleContent.guess(
            "1\n00:00:01,000 --> 00:00:02,000\nI don't know what you want from me.\n\n2\n00:00:03,000 --> 00:00:04,000\nThis is my house and you can't come in here, so go.",
        )
        assertEquals("en", english?.code)
        assertNull(SubtitleContent.guess("1\n00:00:01,000 --> 00:00:02,000\nNaan unnai eppovum marakka maatten, idhu en veedu, inga yaarum vara mudiyaadhu da"), "romanized Tamil stays unknown")
        val ass = "[Script Info]\nTitle: x\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
            "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,నేను నిన్ను ఎప్పటికీ మర్చిపోను, ఇది నా ఇల్లు"
        assertEquals("te", SubtitleContent.guess(ass)?.code)
    }
}

class TrackSelectorTest {
    private val prefs = LanguagePreferences(audio = listOf("Tamil", "English", "Hindi"), subtitles = listOf("ta", "en"), fallbackLanguage = null)

    private fun a(i: Int, lang: String?, label: String? = null, default: Boolean = false, commentary: Boolean = false, channels: Int? = 2) =
        TrackCandidate("a$i", TrackType.AUDIO, i, lang, label, channels = channels, isDefault = default, isCommentary = commentary)

    private fun s(i: Int, lang: String?, forced: Boolean = false, sdh: Boolean = false, origin: TrackOrigin = TrackOrigin.EMBEDDED) =
        TrackCandidate("s$i", TrackType.SUBTITLE, i, lang, isForced = forced, isHearingImpaired = sdh, origin = origin)

    private fun pick(tracks: List<TrackCandidate>, p: LanguagePreferences = prefs, remembered: RememberedChoice? = null, context: FileContext = FileContext()) =
        TrackSelector.select(LanguageDetector.analyze(tracks, context), p, remembered)

    @Test
    fun theSpecExampleFallbackChain() {
        // Tamil available → Tamil
        val all = pick(listOf(a(0, "hin", default = true), a(1, "tam"), a(2, "eng")))
        assertEquals("ta", all.audio.track?.language?.code)
        assertEquals(SelectionRule.PREFERRED_LANGUAGE, all.audio.rule)
        // Tamil unavailable → English
        assertEquals("en", pick(listOf(a(0, "hin"), a(1, "eng"))).audio.track?.language?.code)
        // Neither Tamil nor English → next preference (Hindi)
        assertEquals("hi", pick(listOf(a(0, "tel"), a(1, "hin"))).audio.track?.language?.code)
        // Nothing preferred → the first available track
        val none = pick(listOf(a(0, "jpn"), a(1, "kor")))
        assertEquals("a0", none.audio.track?.id)
        assertEquals(SelectionRule.FIRST_AVAILABLE, none.audio.rule)
        // …or the file's default track when the file marks one
        assertEquals("a1", pick(listOf(a(0, "jpn"), a(1, "kor", default = true))).audio.track?.id)
        // Fallback language after the list
        val withFallback = pick(listOf(a(0, "jpn"), a(1, "fre")), prefs.copy(fallbackLanguage = "fr"))
        assertEquals(SelectionRule.FALLBACK_LANGUAGE, withFallback.audio.rule)
        assertEquals("a1", withFallback.audio.track?.id)
    }

    @Test
    fun wrongMetadataStillSelectsTheRightTrack() {
        // Every track tagged "eng" but labelled correctly.
        val r = pick(listOf(a(0, "eng", "Hindi"), a(1, "eng", "Tamil"), a(2, "eng", "English")))
        assertEquals("a1", r.audio.track?.id)
        // Untagged, languages only in the file name.
        val n = pick(listOf(a(0, "und"), a(1, "und")), context = FileContext(fileNameAudioLanguages = listOf("hi", "ta")))
        assertEquals("a1", n.audio.track?.id)
    }

    @Test
    fun commentaryAndUnsupportedTracksAreAvoided() {
        val r = pick(listOf(a(0, "tam", "Director commentary", commentary = true), a(1, "tam"), a(2, "eng")))
        assertEquals("a1", r.audio.track?.id)
        assertEquals("a0", pick(listOf(a(0, "tam", commentary = true))).audio.track?.id, "used when it is the only track")
        val unsupported = pick(listOf(a(0, "tam").copy(supported = false), a(1, "eng")))
        assertEquals("a1", unsupported.audio.track?.id)
    }

    @Test
    fun subtitlesFollowTheModeAndTheAudio() {
        val subs = listOf(s(0, "eng"), s(1, "tam"), s(2, "tam", forced = true))
        // Tamil audio is understood: only forced Tamil subtitles (signs).
        val understood = pick(listOf(a(0, "tam")) + subs)
        assertEquals("s2", understood.subtitle.track?.id)
        assertEquals(SelectionRule.FORCED_FOR_AUDIO, understood.subtitle.rule)
        assertNull(pick(listOf(a(0, "tam"), s(0, "eng"), s(1, "tam"))).subtitle.track, "no forced track: none")
        // Japanese audio: full subtitles in preference order.
        val foreign = pick(listOf(a(0, "jpn")) + subs)
        assertEquals("s1", foreign.subtitle.track?.id)
        assertEquals("s0", pick(listOf(a(0, "jpn"), s(0, "eng"), s(1, "fre"))).subtitle.track?.id)
        assertNull(pick(listOf(a(0, "jpn"), s(0, "fre"))).subtitle.track, "a subtitle in an unwanted language is not shown")
        // ALWAYS and OFF.
        assertEquals("s1", pick(listOf(a(0, "tam")) + subs, prefs.copy(subtitleMode = SubtitleMode.ALWAYS)).subtitle.track?.id)
        assertNull(pick(listOf(a(0, "jpn")) + subs, prefs.copy(subtitleMode = SubtitleMode.OFF)).subtitle.track)
        // SDH preference.
        val sdh = listOf(a(0, "jpn"), s(0, "eng", sdh = true), s(1, "eng"))
        assertEquals("s1", pick(sdh).subtitle.track?.id)
        assertEquals("s0", pick(sdh, prefs.copy(preferSdh = true)).subtitle.track?.id)
    }

    @Test
    fun rememberedChoicesWinAndCarryAcrossFiles() {
        // Last episode the user picked Hindi audio and English subtitles for the series.
        val remembered = RememberedChoice(
            PreferenceScope.SERIES, 7,
            audio = TrackSignature("hi", "hindi dubbed", 2),
            subtitle = SubtitleChoice.Track(TrackSignature("en", null)),
        )
        // The next episode orders its tracks differently.
        val next = pick(listOf(a(0, "tam"), a(1, "eng"), a(2, "hin", "Hindi Dubbed"), s(0, "tam"), s(1, "eng")), remembered = remembered)
        assertEquals("a2", next.audio.track?.id)
        assertEquals(SelectionRule.REMEMBERED, next.audio.rule)
        assertEquals("s1", next.subtitle.track?.id, "remembered subtitles even though Tamil audio is understood")
        // The remembered language is missing in this file: preferences apply.
        assertEquals("a0", pick(listOf(a(0, "tam"), a(1, "eng")), remembered = remembered).audio.track?.id)
        // Remembered "off".
        assertNull(pick(listOf(a(0, "jpn"), s(0, "tam")), remembered = remembered.copy(subtitle = SubtitleChoice.Off)).subtitle.track)
        // An unknown-language pick is found again by its label.
        val byLabel = RememberedChoice(PreferenceScope.SERIES, 7, audio = TrackSignature(null, "original audio"))
        assertEquals("a1", pick(listOf(a(0, null, "Dub"), a(1, null, "Original Audio")), remembered = byLabel).audio.track?.id)
    }

    @Test
    fun signaturesSurviveStorage() {
        val sig = TrackSignature("ta", "tamil 5 1", 6, commentary = false, descriptive = false, forced = true, sdh = false)
        assertEquals(sig, TrackSignature.decode(sig.encode()))
        assertNull(TrackSignature.decode("garbage"))
    }
}

class TrackSessionTest {
    @Test
    fun userChoicesAreRememberedPerSeriesAndPerMovie() = runTest {
        val store = InMemoryTrackPreferenceStore(LanguagePreferences(audio = listOf("ta", "en"), subtitles = listOf("en")))
        val episode1 = TrackSession(store, TrackScope(itemId = 100, scope = PreferenceScope.SERIES))
        val tracks = listOf(
            TrackCandidate("a0", TrackType.AUDIO, 0, "tam"), TrackCandidate("a1", TrackType.AUDIO, 1, "jpn", "Japanese"),
            TrackCandidate("s0", TrackType.SUBTITLE, 0, "eng"),
        )
        assertEquals("a0", episode1.onTracks(tracks).audio.track?.id)
        episode1.userSelectedAudio("a1")
        episode1.userSelectedSubtitle("s0")

        // Next episode of the same series (another file, reordered tracks).
        val episode2 = TrackSession(store, TrackScope(100, PreferenceScope.SERIES))
        val sel = episode2.onTracks(listOf(TrackCandidate("x", TrackType.AUDIO, 0, "jpn", "Japanese"), TrackCandidate("y", TrackType.AUDIO, 1, "tam"), TrackCandidate("z", TrackType.SUBTITLE, 0, "eng")))
        assertEquals("x", sel.audio.track?.id)
        assertEquals("z", sel.subtitle.track?.id)
        assertEquals(PreferenceScope.SERIES, store.remembered(100)!!.scope)

        // A movie keeps its own memory; other titles use the global preferences.
        val movie = TrackSession(store, TrackScope(200, PreferenceScope.MOVIE))
        assertEquals("a0", movie.onTracks(tracks).audio.track?.id)
        movie.userSelectedSubtitle(null)
        assertEquals(SubtitleChoice.Off, store.remembered(200)!!.subtitle)
        assertEquals("s0", TrackSession(store, TrackScope(300, PreferenceScope.MOVIE)).onTracks(listOf(TrackCandidate("j", TrackType.AUDIO, 0, "jpn")) + tracks.drop(2)).subtitle.track?.id)
    }
}

