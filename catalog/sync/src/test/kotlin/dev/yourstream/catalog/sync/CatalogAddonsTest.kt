package dev.yourstream.catalog.sync

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CatalogAddonsTest {
    @Test
    fun normalizesWhatUsersPaste() {
        assertEquals("https://a.example.com/manifest.json", CatalogAddons.normalize(" https://a.example.com/manifest.json "))
        assertEquals("https://a.example.com/x/manifest.json", CatalogAddons.normalize("stremio://a.example.com/x/manifest.json"))
        assertEquals("https://a.example.com/x/manifest.json", CatalogAddons.normalize("https://a.example.com/x/"))
        assertEquals("https://a.example.com/manifest.json", CatalogAddons.normalize("a.example.com"))
        assertNull(CatalogAddons.normalize("http://a.example.com/manifest.json"), "https only")
        assertNull(CatalogAddons.normalize("ftp://a.example.com"))
        assertNull(CatalogAddons.normalize("hello"))
        assertNull(CatalogAddons.normalize("  "))
    }
}
