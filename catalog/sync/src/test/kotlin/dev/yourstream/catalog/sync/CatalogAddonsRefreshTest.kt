package dev.yourstream.catalog.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.yourstream.catalog.api.CatalogDescriptor
import dev.yourstream.catalog.api.CatalogItem
import dev.yourstream.catalog.api.CatalogItemRef
import dev.yourstream.catalog.api.CatalogMetadata
import dev.yourstream.catalog.api.CatalogPage
import dev.yourstream.catalog.api.CatalogProvider
import dev.yourstream.catalog.api.CatalogProviderId
import dev.yourstream.catalog.api.CatalogProviderInfo
import dev.yourstream.catalog.api.CatalogType
import dev.yourstream.catalog.api.ExternalMetadataFallback
import dev.yourstream.catalog.api.LibraryAvailability
import dev.yourstream.catalog.api.LocalCatalogSource
import dev.yourstream.catalog.core.InMemoryCatalogCache
import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import dev.yourstream.core.common.NoOpLogger
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull

/**
 * The Add-ons page's refresh: the manifest is fetched again, a new name is kept (also after a
 * restart), and an add-on that does not answer is left as it was. Fake add-ons (no network): this
 * proves the store's bookkeeping, not that real add-ons answer.
 */
@RunWith(RobolectricTestRunner::class)
class CatalogAddonsRefreshTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** What each fake add-on's manifest says right now, by link; null: not reachable. */
    private val manifests = HashMap<String, String?>()

    private inner class FakeAddon(private val url: String, override val displayName: String) : CatalogProvider {
        override val id = CatalogProviderId("custom:" + url.removePrefix("https://").removeSuffix("/manifest.json"))
        override val attribution = displayName
        override suspend fun describe(): AppResult<CatalogProviderInfo> {
            val name = manifests[url] ?: return AppResult.Err(AppError.Network("offline"))
            val catalog = CatalogDescriptor(id, "top", CatalogType.MOVIE, "Top")
            return AppResult.Ok(CatalogProviderInfo(id, name, "1", null, listOf(catalog), listOf("catalog"), emptyList(), url))
        }
        override suspend fun page(catalog: CatalogDescriptor, skip: Int, genre: String?): AppResult<CatalogPage> = AppResult.Ok(CatalogPage(emptyList(), null))
        override suspend fun search(query: String, type: CatalogType?) = AppResult.Err(AppError.InvalidState("no search"))
        override suspend fun metadata(ref: CatalogItemRef): AppResult<CatalogMetadata> = AppResult.Err(AppError.InvalidState("no meta"))
    }

    private val factory = object : CatalogProviderFactory {
        override fun builtIns(): List<CatalogProvider> = emptyList()
        override fun custom(manifestUrl: String, name: String): CatalogProvider = FakeAddon(manifestUrl, name)
    }

    private fun store() = CatalogAddons(
        context, factory, InMemoryCatalogCache(), TestClock(), NoOpLogger,
        object : ExternalMetadataFallback {
            override suspend fun metadata(item: CatalogItem) = AppResult.Err(AppError.InvalidState("none"))
            override suspend fun search(query: String, type: CatalogType?) = AppResult.Err(AppError.InvalidState("none"))
        },
        object : LocalCatalogSource {
            override suspend fun discover(language: String?, type: CatalogType?, skip: Int, limit: Int) = emptyList<CatalogItem>()
            override suspend fun search(query: String, type: CatalogType?, limit: Int) = emptyList<CatalogItem>()
            override suspend fun metadata(item: CatalogItem): CatalogMetadata? = null
        },
        object : LibraryAvailability { override suspend fun presence(items: List<CatalogItem>) = emptyMap<CatalogItemRef, dev.yourstream.catalog.api.LibraryPresence>() },
    )

    @Before fun clear() {
        context.getSharedPreferences("catalog_addons", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun refreshKeepsTheManifestsNewNameAndRebuildsTheCatalogs() = runTest {
        val url = "https://alpha.example.com/manifest.json"
        manifests[url] = "Alpha"
        val store = store()
        val added = assertIs<AppResult.Ok<CatalogAddon>>(store.add(url)).value
        assertEquals("Alpha", added.name)
        val before = store.manager()

        manifests[url] = "Alpha Streams"
        val info = assertNotNull(store.refresh(added.id))
        assertEquals("Alpha Streams", info.name)
        assertEquals("Alpha Streams", store.addons.value.single().name)
        assertNotSame(before, store.manager(), "Home and Search use the add-on's catalogs as they are now")
        assertEquals("Alpha Streams", store().addons.value.single().name, "kept after a restart")
    }

    @Test
    fun anAddonThatDoesNotAnswerIsLeftAsItWas() = runTest {
        val url = "https://beta.example.com/manifest.json"
        manifests[url] = "Beta"
        val store = store()
        val added = assertIs<AppResult.Ok<CatalogAddon>>(store.add(url)).value
        manifests[url] = null
        assertNull(store.refresh(added.id))
        assertEquals(listOf("Beta"), store.addons.value.map { it.name })
        assertNull(store.refresh("custom:unknown"), "unknown add-on")
    }
}
