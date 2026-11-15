package dev.yourstream.ui.screens

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import dev.yourstream.catalog.api.CatalogType
import dev.yourstream.ui.core.WindowLayout
import dev.yourstream.ui.core.YourStreamTheme
import dev.yourstream.ui.screens.home.HeroCarousel
import dev.yourstream.ui.screens.home.HeroSlide
import dev.yourstream.ui.screens.home.HomeCatalogLayout
import dev.yourstream.ui.screens.navigation.OpenTarget
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/** Home's hero carousel from the user's hero catalogs (Nuvio-style), and its settings rules. */
@RunWith(RobolectricTestRunner::class)
class HeroCarouselTest {
    @get:Rule val compose = createComposeRule()

    private fun slide(n: Int, title: String, open: OpenTarget = OpenTarget.Details(n.toLong())) =
        HeroSlide("hero:$n", title, null, null, CatalogType.MOVIE, "Romance", 131, 2026, 7.0, listOf("ta"), open)

    @Test
    fun slidesShowTheirDetailsAndOpen() {
        val opened = mutableListOf<OpenTarget>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            YourStreamTheme(WindowLayout.COMPACT) { HeroCarousel(listOf(slide(1, "Immortal"), slide(2, "Jailer 2"), slide(3, "Lenin Pandiyan")), { opened += it }) }
        }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("Immortal").assertExists()
        compose.onNodeWithTag("hero-meta").assertTextContains("Movie  •  Romance  •  2h 11m  •  2026")
        compose.onNodeWithTag("hero-rating").assertTextContains("★ 7.0")
        compose.onNodeWithText("TA").assertExists()
        compose.onNodeWithTag("hero-dots").assertExists()
        compose.onNodeWithTag("hero-details").performClick()
        assertEquals(OpenTarget.Details(1), opened.last())
        // Swiping moves to the next slide.
        compose.onNodeWithTag("hero-carousel").performTouchInput { swipeLeft() }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("hero-slide:hero:2").assertExists()
    }

    @Test
    fun atMostThreeHeroCatalogs() {
        var l = HomeCatalogLayout()
        listOf("a", "b", "c", "d").forEach { l = l.withHero(it, true) }
        assertEquals(listOf("a", "b", "c"), l.hero)
        assertEquals(listOf("a", "c"), l.withHero("b", false).hero)
        assertEquals("2h 11m", dev.yourstream.ui.screens.home.runtimeLabel(131))
        assertEquals("45m", dev.yourstream.ui.screens.home.runtimeLabel(45))
    }

    @Test
    fun mangaLookDrawsInkedPanels() {
        val opened = mutableListOf<OpenTarget>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            YourStreamTheme(WindowLayout.COMPACT, manga = true) { HeroCarousel(listOf(slide(1, "Immortal"), slide(2, "Jailer 2")), { opened += it }) }
        }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("hero-manga-panel").assertExists()
        compose.onNodeWithTag("hero-manga-eyebrow").assertTextContains("FEATURED")
        compose.onNodeWithText("IMMORTAL").assertExists()
        compose.onNodeWithTag("hero-meta").assertTextContains("Movie  ·  Romance  ·  2h 11m  ·  2026")
        compose.onNodeWithTag("hero-details").performClick()
        assertEquals(OpenTarget.Details(1), opened.last())
    }

    /**
     * Reported from a device: the carousel loaded slowly at every start. The last slides are saved
     * and shown at once next time; what opens a slide survives the round trip.
     */
    @Test
    fun savedSlidesComeBackTheSame() {
        val route = dev.yourstream.ui.screens.navigation.CatalogTitleRoute("cinemeta", "MOVIE", "tt1", "Immortal", 2026, "https://p/1.jpg", "tt1")
        val slides = listOf(
            slide(1, "Immortal", OpenTarget.CatalogTitle(route)).copy(posterUrl = "https://p/1.jpg", logoUrl = "https://l/1.png", textlessPosterUrl = "https://t/1.jpg"),
            HeroSlide("hero:2", "No Rating", null, null, CatalogType.SERIES, null, null, null, null, emptyList(),
                OpenTarget.CatalogTitle(route.copy(id = "tt2", title = "No Rating", year = null, poster = null, imdb = null))),
        )
        val back = dev.yourstream.ui.screens.home.HomeSources.decode(dev.yourstream.ui.screens.home.HomeSources.encode(slides))
        assertEquals(slides, back)
        // Slides that do not open a catalog title are not saved.
        assertEquals(emptyList(), dev.yourstream.ui.screens.home.HomeSources.decode(dev.yourstream.ui.screens.home.HomeSources.encode(listOf(slide(3, "Local")))))
    }
}
