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
        assertNull(CatalogAddons.normalize("http://a.example.com/manifest.json"), "https only on the internet")
        assertEquals("http://192.168.1.20:7000/manifest.json", CatalogAddons.normalize("http://192.168.1.20:7000/"), "own network")
        assertEquals("http://localhost:11470/x/manifest.json", CatalogAddons.normalize("HTTP://localhost:11470/x"))
        assertEquals("http://nas.local/manifest.json", CatalogAddons.normalize("http://nas.local/manifest.json"))
        assertNull(CatalogAddons.normalize("http://8.8.8.8/manifest.json"))
        assertEquals("https://a.example.com/providers=yts|qualityfilter=480p/manifest.json",
            CatalogAddons.normalize("stremio://a.example.com/providers=yts|qualityfilter=480p/manifest.json"), "configured add-on links keep their options")
        assertNull(CatalogAddons.normalize("ftp://a.example.com"))
        assertNull(CatalogAddons.normalize("hello"))
        assertNull(CatalogAddons.normalize("  "))
    }
}
