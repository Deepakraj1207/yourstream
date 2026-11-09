package dev.yourstream.media.filename

import org.junit.Test
import kotlin.test.assertEquals

/** Machine-made file names never become titles; the caption names the video instead. */
class RandomNamesTest {
    private val hints = ParseHints(caption = "Vikram (2022) Tamil HDRip", chatTitle = "Tamil Movies HD")
    private fun title(name: String) = MediaFilenameParser.parse(name, hints).title?.value

    @Test
    fun randomNamesUseTheCaption() {
        listOf(
            "ee4e5c5b2f7c4a9e9b6d.mkv", "3f2a8c1e-4b5d-4e6f-8a9b-0c1d2e3f4a5b.mp4", "AgADBQADq7ExGwbKjVQ.mp4",
            "BQACAgUAAxkBAAIBY2X.mkv", "a7F3k9Qz2LmP8xY1.mp4", "x7k2p9.mp4", "hd_7f8e9d0c.mp4", "telegram_c3a9.mp4",
        ).forEach { assertEquals("Vikram", title(it), it) }
    }

    @Test
    fun realTitlesWithDigitsStay() {
        mapOf(
            "M3GAN.2022.1080p.mkv" to "M3GAN", "Se7en.1995.720p.mkv" to "Se7en", "K3G.2001.mkv" to "K3G",
            "Kaithi2019.mkv" to "Kaithi2019", "iCarly.S01E01.mkv" to "iCarly", "Ben.10.S01E06.mkv" to "Ben 10",
            "McDonald.and.Dodds.S01E01.mkv" to "McDonald and Dodds", "Ultra.HD.6f1b2c3d4e5f6a7b.mkv" to "Ultra",
        ).forEach { (name, expected) -> assertEquals(expected, title(name), name) }
    }

    @Test
    fun aRandomSuffixIsDroppedFromARealTitle() {
        assertEquals("Leo", MediaFilenameParser.parse("Leo a7F3k9Qz2LmP8xY1.mkv").title?.value)
    }
}

class MachineNameTest {
    @Test
    fun machineNamesAreRecognised() {
        listOf(
            "7744f81a5b3063b96a3f54785bd51330.mp4", "74f2165fc2dbbe5d3747a4ccae8d27c4", "AgADBQADq7ExGwbKjVQ.mkv",
            "VID_20230505_WA0003.mp4", "video_123.mp4", "3f2a8c1e-4b5d-4e6f-8a9b-0c1d2e3f4a5b.mp4", "",
        ).forEach { kotlin.test.assertTrue(MediaFilenameParser.isMachineName(it), it) }
        listOf("Vikram (2022) Tamil HDRip.mkv", "Ben.10.S01E06.mkv", "M3GAN.2022.1080p.mkv", "Kaithi2019.mkv", "The Frog", "Lupin")
            .forEach { kotlin.test.assertFalse(MediaFilenameParser.isMachineName(it), it) }
    }
}
