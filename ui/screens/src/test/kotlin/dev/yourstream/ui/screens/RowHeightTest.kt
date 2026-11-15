package dev.yourstream.ui.screens

import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.onChildAt
import dev.yourstream.ui.core.MediaCardModel
import dev.yourstream.ui.core.MediaRow
import dev.yourstream.ui.core.WindowLayout
import dev.yourstream.ui.core.YourStreamTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/**
 * Rows must not change height while scrolling sideways (the user's screen recording: the row
 * under "Recently added" moved up and down as posters with longer titles scrolled in).
 */
@RunWith(RobolectricTestRunner::class)
class RowHeightTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun cardsWithShortAndLongTitlesAreTheSameHeight() {
        val cards = listOf(
            MediaCardModel("a", "Leo", "2023"),
            MediaCardModel("b", "Project C (Chapter 2) The Very Long Title That Wraps", "2022"),
            MediaCardModel("c", "Thedal", null),
            MediaCardModel("d", "Jailer 2", "2026", caption = "In your library"),
        ) + (1..12).map { MediaCardModel("x$it", "Film $it", "2024") }
        compose.setContent {
            YourStreamTheme(WindowLayout.COMPACT) { MediaRow("Recently added", cards, { it }, {}) }
        }
        fun h(key: String) = compose.onNodeWithTag("card:$key").getBoundsInRoot().let { it.bottom - it.top }
        val short = h("a")
        assertEquals(short, h("b"), "a title that wraps does not make the card taller")
        assertEquals(short, h("c"), "no subtitle: same height")
        val row = compose.onNodeWithTag("row:Recently added").getBoundsInRoot().let { it.bottom - it.top }
        compose.onNodeWithTag("row:Recently added").onChildAt(1).performScrollToIndex(3)
        assertEquals(short, h("d"), "a caption is reserved for the whole row")
        compose.onNodeWithTag("row:Recently added").onChildAt(1).performScrollToIndex(14)
        assertEquals(row, compose.onNodeWithTag("row:Recently added").getBoundsInRoot().let { it.bottom - it.top }, "the row keeps its height after scrolling")
    }
}
