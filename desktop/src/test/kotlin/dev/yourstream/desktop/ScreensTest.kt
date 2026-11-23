package dev.yourstream.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.DesktopComposeUiTest
import dev.yourstream.core.common.AppResult
import dev.yourstream.core.common.Clock
import dev.yourstream.core.common.NoOpLogger
import dev.yourstream.core.network.HttpClients
import dev.yourstream.desktop.data.LibraryBuilder
import dev.yourstream.desktop.data.MediaRecord
import dev.yourstream.desktop.library.PosterService
import dev.yourstream.desktop.ui.LibraryScreen
import dev.yourstream.desktop.ui.SignInScreen
import dev.yourstream.desktop.ui.TitleScreen
import dev.yourstream.desktop.ui.YourStreamTheme
import dev.yourstream.metadata.core.InMemoryMetadataCache
import dev.yourstream.metadata.core.MetadataProviderManager
import dev.yourstream.metadata.core.match.MetadataMatcher
import dev.yourstream.telegram.api.TelegramAuthManager
import dev.yourstream.telegram.api.model.AuthState
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals

/**
 * The desktop screens rendered for real (Compose for Desktop, software rendering) with sample
 * titles: what they show and what clicking does. Screenshots go to build/screenshots for review.
 * Posters are not fetched here (no metadata providers), so cards show their title instead.
 */
@OptIn(ExperimentalTestApi::class)
class ScreensTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(60)

    private val posters by lazy {
        PosterService(MetadataMatcher(MetadataProviderManager(emptyList(), InMemoryMetadataCache(), Clock.System, NoOpLogger)), HttpClients.create(), tmp.newFolder("posters"), NoOpLogger)
    }

    private var nextId = 1L
    private fun file(title: String, year: Int? = null, season: Int? = null, episode: Int? = null, res: Int = 1080, size: Long = 1_500_000_000) = (nextId++).let { id ->
        MediaRecord(1, id, 1_700_000_000 + id, id.toInt(), "r$id", "$title ${year ?: ""} ${res}p.mkv", size, video = true,
            title = title, titleKey = title.lowercase(), year = year, season = season, episode = episode, resolution = res, languages = listOf("ta", "en"))
    }

    private val sample by lazy {
        listOf(
            file("Example Film", 2024, res = 2160, size = 14_000_000_000), file("Example Film", 2024),
            file("Another Movie", 2023, res = 720), file("A Long Movie Title That Wraps Over Lines", 2021),
            file("Sample Series", season = 1, episode = 1), file("Sample Series", season = 1, episode = 2), file("Sample Series", season = 2, episode = 1),
            file("Documentary", 2020),
        )
    }

    private fun DesktopComposeUiTest.save(name: String, node: SemanticsNodeInteraction = onRoot()) {
        val bitmap = node.captureToImage()
        val dir = File("build/screenshots").apply { mkdirs() }
        val image = Image.makeFromBitmap(org.jetbrains.skia.Bitmap().also { b ->
            val px = IntArray(bitmap.width * bitmap.height)
            bitmap.readPixels(px)
            b.allocN32Pixels(bitmap.width, bitmap.height)
            val bytes = ByteArray(px.size * 4)
            for (i in px.indices) {
                val c = px[i]
                bytes[i * 4] = (c and 0xFF).toByte(); bytes[i * 4 + 1] = (c shr 8 and 0xFF).toByte()
                bytes[i * 4 + 2] = (c shr 16 and 0xFF).toByte(); bytes[i * 4 + 3] = (c shr 24 and 0xFF).toByte()
            }
            b.installPixels(bytes)
        })
        File(dir, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }

    /** As in the app window: the theme and a background surface (which sets text colours). */
    @androidx.compose.runtime.Composable
    private fun Themed(content: @androidx.compose.runtime.Composable () -> Unit) = YourStreamTheme(true) {
        androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background, content = content)
    }

    /** The manga style (Settings → Appearance), light and dark: lettering, inked panels, screentones. */
    @Test
    fun mangaStyle() {
        for (dark in listOf(false, true)) runDesktopComposeUiTest(1280, 820) {
            val titles = LibraryBuilder.build(sample)
            setContent {
                YourStreamTheme(dark, manga = true) {
                    androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
                        LibraryScreen(titles, posters, indexing = false, onOpen = {}, onAddChats = {})
                    }
                }
            }
            onNodeWithText("LIBRARY").assertExists()
            save(if (dark) "manga-library-dark" else "manga-library-light")
        }
        runDesktopComposeUiTest(1280, 820) {
            val movie = LibraryBuilder.build(sample).first { it.title == "Example Film" }
            setContent {
                YourStreamTheme(false, manga = true) {
                    androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
                        TitleScreen(movie, posters, resumeMs = { 0 }, onBack = {}) { _, _ -> }
                    }
                }
            }
            onNodeWithText("EXAMPLE FILM").assertExists()
            save("manga-title")
        }
    }

    @Test
    fun libraryShowsTitlesFiltersAndOpens() = runDesktopComposeUiTest(1280, 820) {
        val titles = LibraryBuilder.build(sample)
        var opened: String? = null
        setContent { Themed { LibraryScreen(titles, posters, indexing = false, onOpen = { opened = it.title }, onAddChats = {}) } }
        onNodeWithText("Sample Series").assertExists()
        onNodeWithText("4K").assertExists()
        save("library")
        onNodeWithText("Series").performClick()
        onNodeWithText("Example Film").assertDoesNotExist()
        onNodeWithText("Sample Series").performClick()
        assertEquals("Sample Series", opened)
    }

    @Test
    fun searchNarrowsTheLibrary() = runDesktopComposeUiTest(1280, 820) {
        val titles = LibraryBuilder.build(sample)
        setContent { Themed { LibraryScreen(titles, posters, indexing = false, onOpen = {}, onAddChats = {}) } }
        onNodeWithText("Search your library").performTextInput("documentary")
        onNodeWithText("Documentary").assertExists()
        onNodeWithText("Another Movie").assertDoesNotExist()
    }

    @Test
    fun seriesPageListsSeasonsAndPlaysAnEpisodeWithTheRestQueued() = runDesktopComposeUiTest(1280, 820) {
        val series = LibraryBuilder.build(sample).first { it.title == "Sample Series" }
        var played: Pair<MediaRecord, List<MediaRecord>>? = null
        setContent { Themed { TitleScreen(series, posters, resumeMs = { 0 }, onBack = {}) { f, q -> played = f to q } } }
        onNodeWithText("Season 1").assertExists()
        onNodeWithText("Season 2").assertExists()
        save("series")
        onNodeWithText("Episode 1").performClick()
        assertEquals(listOf(1, 2), played!!.second.map { it.episode })
    }

    @Test
    fun moviePageOffersPlayAndVersions() = runDesktopComposeUiTest(1280, 820) {
        val movie = LibraryBuilder.build(sample).first { it.title == "Example Film" }
        var played: MediaRecord? = null
        setContent { Themed { TitleScreen(movie, posters, resumeMs = { if (it.resolution == 2160) 60_000 else 0 }, onBack = {}) { f, _ -> played = f } } }
        onNodeWithText("Versions").assertExists()
        save("movie")
        // "Resume" is the button and also a badge on the version row; press the button.
        onAllNodesWithText("Resume").filter(hasClickAction())[0].performClick()
        assertEquals(2160, played?.resolution, "the best version plays, from where it was left")
    }

    private class FakeAuth(initial: AuthState) : TelegramAuthManager {
        override val state = MutableStateFlow(initial)
        val calls = mutableListOf<String>()
        override suspend fun submitPhoneNumber(phoneNumber: String) = AppResult.Ok(Unit).also { calls += "phone" }
        override suspend fun submitCode(code: String) = AppResult.Ok(Unit).also { calls += "code" }
        override suspend fun resendCode() = AppResult.Ok(Unit).also { calls += "resend" }
        override suspend fun submitPassword(password: String) = AppResult.Ok(Unit).also { calls += "password" }
        override suspend fun submitEmailAddress(email: String) = AppResult.Ok(Unit)
        override suspend fun submitEmailCode(code: String) = AppResult.Ok(Unit)
        override suspend fun requestQrCodeLogin() = AppResult.Ok(Unit).also { calls += "qr" }
        override suspend fun logOut() = AppResult.Ok(Unit)
    }

    @Test
    fun signInWithPhoneThenQr() = runDesktopComposeUiTest(900, 700) {
        val auth = FakeAuth(AuthState.WaitPhoneNumber)
        setContent { Themed { SignInScreen(auth) } }
        onNodeWithText("Phone number, with country code").performTextInput("+91 98765 43210")
        onNodeWithText("Next").performClick()
        waitUntil { auth.calls == listOf("phone") }
        save("sign-in")
        auth.state.value = AuthState.WaitOtherDeviceConfirmation("tg://login?token=AAAAexampletoken")
        onNodeWithText("Scan with Telegram on your phone").assertExists()
        save("sign-in-qr")
        // From the QR code, back to typing a number (Telegram accepts one in that state).
        onNodeWithText("Use a phone number instead").performClick()
        onNodeWithText("Phone number, with country code").assertExists()
    }
}
