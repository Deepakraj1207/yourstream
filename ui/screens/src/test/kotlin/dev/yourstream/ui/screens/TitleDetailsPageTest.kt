package dev.yourstream.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import dev.yourstream.catalog.api.CatalogItemRef
import dev.yourstream.catalog.api.CatalogMetadata
import dev.yourstream.catalog.api.CatalogProviderId
import dev.yourstream.catalog.api.CatalogType
import dev.yourstream.metadata.api.MetadataType
import dev.yourstream.metadata.api.TitleDetails
import dev.yourstream.ui.core.WindowLayout
import dev.yourstream.ui.core.YourStreamTheme
import dev.yourstream.ui.screens.addon.CatalogTitleAction
import dev.yourstream.ui.screens.addon.CatalogTitleScreen
import dev.yourstream.ui.screens.addon.CatalogTitleUiState
import dev.yourstream.ui.screens.navigation.OpenTarget
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The title page like Nuvio's, from TMDB's details; Play opens the streams. Real text layout
 * (native graphics), so the overview's three-line cut and "Show More" are measured.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class TitleDetailsPageTest {
    @get:Rule val compose = createComposeRule()

    private val ref = CatalogItemRef(CatalogProviderId("cinemeta"), CatalogType.MOVIE, "tt9000001", "tt9000001")

    private val details = TitleDetails(
        tmdbId = "555", type = MetadataType.MOVIE, imdbId = "tt9000001", title = "Baththa", overview = "In 1986 Madras, a timid eight-year-old boy " + "traps a gangster. ".repeat(12),
        genres = listOf("Drama", "Comedy", "Action"), year = 2026, releaseDate = "2026-01-10", runtimeMinutes = 159, certification = "U/A 13+",
        status = "Released", tmdbRating = 7.9, logoUrl = "https://image.tmdb.org/t/p/w500/logo.png", backdropUrl = null, posterUrl = null,
        directors = listOf("Balaji Tharaneetharan"), writers = listOf("Balaji Tharaneetharan"), creators = emptyList(),
        companies = listOf(TitleDetails.Company("Star Studios", null)),
        cast = listOf(TitleDetails.Person("Vijay Sethupathi", "Bathvachalam", null), TitleDetails.Person("Sai Prithviraj", "Sabari", null)),
        videos = listOf(TitleDetails.Video("Official Trailer [Subtitled]", "abc123", "Trailer", 2026, true)),
        originCountries = listOf("IN"), originalLanguage = "ta",
        recommendations = listOf(TitleDetails.Recommendation("7", MetadataType.MOVIE, "Airborne", 1993, null)),
    )

    @Test
    fun detailsPageLikeNuvio() {
        val actions = mutableListOf<CatalogTitleAction>()
        val opened = mutableListOf<OpenTarget>()
        val urls = mutableListOf<String>()
        var state by mutableStateOf(CatalogTitleUiState("Baththa", 2026, null, meta = CatalogMetadata(ref, "Baththa", 2026, rating = 8.5), loadingMeta = false,
            details = details, ratingIsImdb = true, libraryItemId = 7, inLibrary = false))
        compose.setContent {
            YourStreamTheme(WindowLayout.COMPACT) { CatalogTitleScreen(state, { actions += it }, {}, {}, onOpen = { opened += it }, onOpenUrl = { urls += it }, onStream = {}) }
        }
        compose.onNodeWithTag("title-logo").assertExists()
        compose.onNodeWithTag("title-genres").assertTextContains("Drama • Comedy • Action")
        compose.onNodeWithTag("title-certification").assertTextContains("U/A 13+")
        compose.onNodeWithText("2h 39m").assertExists()
        compose.onNodeWithText("8.5", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("7.9", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Director:  Balaji Tharaneetharan").assertExists()
        compose.onNodeWithTag("catalog-title").performScrollToNode(hasTestTag("title-overview"))
        compose.onNodeWithTag("title-show-more").performClick()
        compose.onNodeWithText("Show Less ▴").assertExists()

        // Play opens the streams; + adds to My library.
        compose.onNodeWithTag("catalog-title").performScrollToNode(hasTestTag("title-play"))
        compose.onNodeWithTag("title-play").performClick()
        compose.onNodeWithTag("title-library").performClick()
        assertEquals(listOf<CatalogTitleAction>(CatalogTitleAction.Play, CatalogTitleAction.ToggleLibrary), actions)

        val list = compose.onNodeWithTag("catalog-title")
        list.performScrollToNode(hasTestTag("title-production"))
        compose.onNodeWithText("Star Studios").assertExists()
        list.performScrollToNode(hasText("Bathvachalam"))
        list.performScrollToNode(hasTestTag("trailer:abc123"))
        compose.onNodeWithTag("trailer:abc123").performClick()
        assertEquals(listOf("https://www.youtube.com/watch?v=abc123"), urls)
        list.performScrollToNode(hasText("Original Language"))
        compose.onNodeWithText("TA").assertExists()
        compose.onNodeWithText("IN").assertExists()
        list.performScrollToNode(hasText("Airborne"))
        compose.onNodeWithText("Airborne").performClick()
        val route = assertIs<OpenTarget.CatalogTitle>(opened.single()).route
        assertEquals("tmdb:7", route.id, "TMDB's recommendations open by TMDB id")
        list.performScrollToNode(hasText("Powered by TMDB"))

        // Play's page: the streams, with a way back.
        state = state.copy(streamsOpen = true)
        compose.onNodeWithTag("streams").assertExists()
    }

    @Test
    fun withoutTmdbTheAddonsDetailsFillThePage() {
        val state = CatalogTitleUiState("Example Film", 2024, null, loadingMeta = false,
            meta = CatalogMetadata(ref, "Example Film", 2024, "Plot.", genres = listOf("Drama"), cast = listOf("Someone"), directors = listOf("A Director"), runtimeMinutes = 95, rating = 7.1))
        compose.setContent { YourStreamTheme(WindowLayout.COMPACT) { CatalogTitleScreen(state, {}, {}, {}, onStream = {}) } }
        compose.onNodeWithText("Example Film").assertExists()
        compose.onNodeWithText("1h 35m").assertExists()
        compose.onNodeWithText("★ 7.1").assertExists()
        compose.onNodeWithText("Director:  A Director").assertExists()
        compose.onNodeWithTag("catalog-title").performScrollToNode(hasText("Someone"))
        compose.onNodeWithText("Powered by TMDB").assertDoesNotExist()
        compose.onNodeWithTag("title-library").assertDoesNotExist()
    }
}
