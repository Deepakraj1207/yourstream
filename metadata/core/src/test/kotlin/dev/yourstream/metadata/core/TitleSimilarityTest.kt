package dev.yourstream.metadata.core

import dev.yourstream.metadata.core.match.TitleSimilarity.similarity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TitleSimilarityTest {

    private fun assertAtLeast(min: Double, a: String, b: String) = assertTrue(similarity(a, b) >= min, "'$a' vs '$b' = ${similarity(a, b)} < $min")
    private fun assertBelow(max: Double, a: String, b: String) = assertTrue(similarity(a, b) < max, "'$a' vs '$b' = ${similarity(a, b)} ≥ $max")

    @Test
    fun equalAfterNormalisation() {
        assertEquals(1.0, similarity("Doraemon", "doraemon"))
        assertEquals(1.0, similarity("Tom and Jerry", "Tom & Jerry"))
        assertEquals(1.0, similarity("Amélie", "Amelie"))
        assertEquals(1.0, similarity("The Matrix", "Matrix"))
        assertEquals(1.0, similarity("Ocean's Eleven", "Oceans Eleven"))
        assertEquals(1.0, similarity("KGF Chapter Two", "KGF: Chapter 2"))
        assertEquals(1.0, similarity("Rocky II", "Rocky 2"))
    }

    @Test
    fun spacingAndDotsBarelyMatter() {
        assertAtLeast(0.97, "K.G.F Chapter 2", "KGF Chapter 2")
        assertAtLeast(0.97, "Spider Man No Way Home", "Spider-Man: No Way Home")
        assertAtLeast(0.97, "Spiderman", "Spider-Man")
    }

    @Test
    fun typosAndPartialTitlesScoreHighButBelowExact() {
        assertAtLeast(0.85, "Manjumel Boys", "Manjummel Boys")
        assertAtLeast(0.55, "Dune", "Dune: Part One")
        assertBelow(1.0, "Dune", "Dune: Part One")
        assertAtLeast(0.75, "Pushpa 2", "Pushpa 2: The Rule")
    }

    @Test
    fun differentTitlesScoreLow() {
        assertBelow(0.5, "Leo", "Vikram")
        assertBelow(0.6, "Doraemon", "Pokemon")
        assertBelow(0.8, "The Office", "Office Space")
        assertEquals(0.0, similarity("", "Anything"))
    }

    @Test
    fun nonLatinScriptsCompareAsIs() {
        assertEquals(1.0, similarity("விக்ரம்", "விக்ரம்"))
        assertBelow(0.5, "விக்ரம்", "Vikram")
    }
}
