package dev.yourstream.library

import dev.yourstream.library.api.LibraryKind
import dev.yourstream.library.api.LibraryQuery
import dev.yourstream.library.data.LibraryQueryBuilder
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Files nothing names, without a poster, are not browsed (search and the Videos filter still find them). */
class UntitledFilesTest {
    private val rule = "NOT (i.kind = 'VIDEO' AND im.poster_url IS NULL)"

    @Test
    fun untitledFilesWithoutPostersAreLeftOutOfBrowsing() {
        assertTrue(rule in LibraryQueryBuilder.list(LibraryQuery()).sql)
        assertTrue(rule in LibraryQueryBuilder.count(LibraryQuery()).sql)
        assertFalse(rule in LibraryQueryBuilder.list(LibraryQuery(text = "tel hin")).sql, "a search still finds them")
        assertFalse(rule in LibraryQueryBuilder.list(LibraryQuery(kinds = setOf(LibraryKind.VIDEO))).sql, "the Videos filter still lists them")
        assertFalse(rule in LibraryQueryBuilder.list(LibraryQuery(includeUntitled = true)).sql)
    }
}
