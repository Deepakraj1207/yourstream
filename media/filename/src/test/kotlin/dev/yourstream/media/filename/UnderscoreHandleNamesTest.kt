package dev.yourstream.media.filename

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Names reported from a device (13 Nov 2026): channel handles glued to the name with underscores
 * ("@SPEEDY_LinkZ_Nowhere_2023_…") were removed together with the whole name, leaving "Tel" (from
 * "Tam_+_Tel") or nothing; site brands ("Toonworld4all") stayed in the title.
 */
class UnderscoreHandleNamesTest {
    private fun parse(name: String, hints: ParseHints = ParseHints()) = MediaFilenameParser.parse(name, hints)

    private fun movie(name: String, title: String, year: Int) {
        val m = parse(name)
        assertEquals(title, m.title?.value, name)
        assertEquals(year, m.year?.value, name)
    }

    @Test
    fun handleGluedWithUnderscoresKeepsTheTitle() {
        movie("@SPEEDY_LinkZ_Nowhere_2023_HQ_HDRip_1080p_x264_Tam_+_Tel.mkv", "Nowhere", 2023)
        movie("@TamilMob_LinkZz_Popcorn_2023_TRUE_WEB_DL_1080p_AVC_Tam_+_Tel_+.mkv", "Popcorn", 2023)
        movie("@SPEEDY_LinkZ_The_Moon_2023_HQ_HDRip_1080p_x264_Tam_+_Tel_+_.mkv", "The Moon", 2023)
        movie("@SPEEDY_LinkZ_Smugglers_2023_HQ_HDRip_1080p_x264_Tam_+_Tel_+.mkv", "Smugglers", 2023)
        movie("@SPEEDY_LinkZ_Dragon_Blade_2015_1080p_BDRip_x264_Tamil_+_Tel.mkv", "Dragon Blade", 2015)
        movie("@SPEEDY_LinkZ_No_Hard_Feelings_2023_BluRay_1080p_Tam_+_Tel_+.mkv", "No Hard Feelings", 2023)
        movie("@SPEEDY_LinkZ_Godzilla_1998_BluRay_1080p_x264_Tam_+_Tel_.mkv", "Godzilla", 1998)
        // A title that starts with a word channels also use is kept.
        movie("@SPEEDY_LinkZ_World_War_Z_2013_1080p_Tam.mkv", "World War Z", 2013)
    }

    @Test
    fun languagesAfterThePlusAreLanguagesNotTheTitle() {
        val m = parse("@SPEEDY_LinkZ_Nowhere_2023_HQ_HDRip_1080p_x264_Tam_+_Tel.mkv")
        assertEquals(listOf("ta", "te"), m.languages.map { it.value.code })
    }

    @Test
    fun seriesPackWithUnderscores() {
        val m = parse("@TR_linkz_Scam_2003_The_Telgi_Story_2023_S02_EP_01_05_WEB_DL_360p.mkv")
        assertEquals("Scam 2003 The Telgi Story", m.title?.value)
        assertEquals(2, m.season?.value)
        assertEquals(1, m.episode?.value)
        assertEquals(5, m.episodeEnd)
        // A plain number after an episode is not a range end.
        assertNull(parse("Show S01E05 10 bit 1080p.mkv").episodeEnd)
        assertNull(parse("Show_S01_E05_2023_1080p.mkv").episodeEnd)
    }

    @Test
    fun handlesWithSpacesStillWork() {
        movie("@TamilMovies Vikram 2022 1080p.mkv", "Vikram", 2022)
        movie("@Tamil_Movies_HD Leo 2023 720p.mkv", "Leo", 2023)
    }

    @Test
    fun siteBrandsAreNotPartOfTheTitle() {
        for (name in listOf(
            "Toonworld4all_Naruto_Episode_03_Hindi_480p.mkv",
            "Toonworld4all - Naruto Episode 03 Hindi.mkv",
            "@Toonworld4all_Naruto_Ep_03_Hindi_480p.mkv",
            "Naruto Episode 03 Toonworld4all.mkv",
            "[Toonworld4all] Naruto - 03 [480p].mkv",
        )) assertEquals("Naruto", parse(name).title?.value, name)
        assertEquals(3, parse("Toonworld4all_Naruto_Episode_03_Hindi_480p.mkv").episode?.value)
        movie("Movies4u.Jawan.2023.1080p.mkv", "Jawan", 2023)
    }

    @Test
    fun linkPhrasesInThePostAboveAreNotTitles() {
        val m = parse("VID_20240101_120000.mp4", ParseHints(context = "Link to this message\nhttps://t.me/c/1/2"))
        assertEquals(false, m.title?.value.orEmpty().contains("Link", ignoreCase = true), "got ${m.title?.value}")
        assertEquals("Vikram", parse("VID_20240101_120000.mp4", ParseHints(context = "Click here to download\nVikram (2022)")).title?.value)
    }
}
