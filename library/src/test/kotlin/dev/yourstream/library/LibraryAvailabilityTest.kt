package dev.yourstream.library

import dev.yourstream.library.api.LibraryKind
import dev.yourstream.library.api.LibraryQuery
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which titles are listed when files are untitled or deleted (real Room + library sync). */
@RunWith(RobolectricTestRunner::class)
class LibraryAvailabilityTest {
    private val env = LibraryTestEnv()
    @After fun tearDown() = env.close()

    private suspend fun titles(q: LibraryQuery) = with(env) { media.search(q).all().map { it.title }.sorted() }

    @Test
    fun theUntitledListShowsExactlyWhatBrowsingHides() = runTest {
        with(env) {
            file("Vikram.2022.Tamil.720p.mkv")
            file("VID_20240101_120000.mp4")
            sync.sync()
        }
        assertEquals(listOf("Vikram"), titles(LibraryQuery()), "browsing leaves the untitled file out")
        val untitled = LibraryQuery(kinds = setOf(LibraryKind.VIDEO), includeUntitled = true, untitledOnly = true)
        assertEquals(1, titles(untitled).size, "…and the untitled list has it")
        assertFalse("Vikram" in titles(untitled))
        assertEquals(1, env.media.count(untitled).first())
    }

    @Test
    fun aTitleWhoseFilesWereDeletedIsListedOnlyWhenAsked() = runTest {
        // Titles with no files and no user data are simply removed; with user data they stay.
        val message = with(env) {
            file("Leo.2023.Tamil.1080p.mkv")
            file("Vikram.2022.Tamil.720p.mkv").also { sync.sync() }
        }
        // The user added it to "My library" (user data: the title is kept even without files)…
        val vikramId = with(env) { media.search(LibraryQuery(text = "vikram")).all().single().id }
        env.media.setInLibrary(vikramId, true)
        // …then its only file is deleted from Telegram.
        env.store.markDeleted(1, listOf(message))
        env.sync.sync()

        assertEquals(listOf("Leo"), titles(LibraryQuery()), "playable only (the default)")
        val all = with(env) { media.search(LibraryQuery(includeUnavailable = true)).all() }
        assertEquals(listOf("Leo", "Vikram"), all.map { it.title }.sorted())
        val vikram = all.single { it.title == "Vikram" }
        assertFalse(vikram.isAvailable, "marked unavailable, kept for its user data")
        assertTrue(all.single { it.title == "Leo" }.isAvailable)
        // The Library tab with "Playable only" off lists it.
        assertEquals(listOf("Vikram"), titles(LibraryQuery(myLibraryOnly = true, includeUnavailable = true)))
        assertEquals(emptyList(), titles(LibraryQuery(myLibraryOnly = true)), "with it on (the default), it is left out")
    }
}
