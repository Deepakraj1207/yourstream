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
}
