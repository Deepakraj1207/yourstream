package dev.yourstream.ui.screens

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import dev.yourstream.catalog.api.AddonStream
import dev.yourstream.catalog.api.CatalogDescriptor
import dev.yourstream.catalog.api.CatalogExtra
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
import dev.yourstream.catalog.api.LibraryPresence
import dev.yourstream.catalog.api.LocalCatalogSource
import dev.yourstream.catalog.api.StreamProvider
import dev.yourstream.catalog.core.InMemoryCatalogCache
import dev.yourstream.catalog.sync.CatalogAddons
import dev.yourstream.catalog.sync.CatalogProviderFactory
import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import dev.yourstream.core.common.Clock
import dev.yourstream.core.common.NoOpLogger
import dev.yourstream.library.api.EpisodeRepository
import dev.yourstream.library.api.MediaRepository
import dev.yourstream.library.api.MediaVersion
import dev.yourstream.ui.core.WindowLayout
import dev.yourstream.ui.core.YourStreamTheme
import dev.yourstream.ui.screens.addon.CatalogTitleScreen
import dev.yourstream.ui.screens.addon.CatalogTitleViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertTrue

/**
 * The title page as the app runs it: the real view model, a Torrentio-like add-on, and one
 * Telegram file of the movie. Reported from a device in 0.12.0: the app closed while the streams
 * loaded (streams with and without a size could not be sorted together).
 */
@RunWith(RobolectricTestRunner::class)
class TitleStreamsCrashTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val torrentio = object : CatalogProvider, StreamProvider {
        override val id = CatalogProviderId("custom:torrentio.example")
        override val displayName = "Torrentio"
        override val attribution = "Torrentio"
        override suspend fun describe() = AppResult.Ok(CatalogProviderInfo(id, "Torrentio", "1", null, emptyList(), listOf("stream"), listOf("tt"), "https://torrentio.example"))
        override suspend fun page(catalog: CatalogDescriptor, skip: Int, genre: String?) = AppResult.Ok(CatalogPage(emptyList(), null))
        override suspend fun search(query: String, type: CatalogType?) = AppResult.Err(AppError.InvalidState("no"))
        override suspend fun metadata(ref: CatalogItemRef) = AppResult.Err(AppError.InvalidState("no"))
        override suspend fun hasStreams() = true
        override suspend fun streams(type: CatalogType, id: String): AppResult<List<AddonStream>> = AppResult.Ok((1..40).map { i ->
            AddonStream("Torrentio", listOf("Torrentio\n4k DV | HDR", "Torrentio\n1080p", "Torrentio\n720p", "Torrentio\nSCR")[i % 4],
                "Example.Film.2024.${listOf("2160p", "1080p", "720p", "CAM")[i % 4]}.WEB-DL\n👤 $i 💾 ${i}.5 GB ⚙️ ThePirateBay\nMulti Audio / 🇮🇳 / 🇬🇧",
                if (i % 5 == 0) "https://cdn.example/$i.mkv" else null, null,
                if (i % 5 == 0) null else "%040x".format(i), fileIdx = i % 3, sources = listOf("tracker:udp://t.example:80"),
                fileName = "Example.Film.$i.mkv", sizeBytes = if (i % 7 == 0) null else i * 1_000_000_000L)
        })
    }

    private val cinemeta = object : CatalogProvider {
        override val id = CatalogProviderId("cinemeta")
        override val displayName = "Cinemeta"
        override val attribution = "Cinemeta"
        override suspend fun describe() = AppResult.Ok(CatalogProviderInfo(id, "Cinemeta", "1", null,
            listOf(CatalogDescriptor(id, "top", CatalogType.MOVIE, "Popular", extras = listOf(CatalogExtra("skip")))), listOf("catalog", "meta"), listOf("tt"), "https://cinemeta.example"))
        override suspend fun page(catalog: CatalogDescriptor, skip: Int, genre: String?) = AppResult.Ok(CatalogPage(emptyList(), null))
        override suspend fun search(query: String, type: CatalogType?) = AppResult.Err(AppError.InvalidState("no"))
        override suspend fun metadata(ref: CatalogItemRef) = AppResult.Ok(CatalogMetadata(ref, "Example Film", 2024, "Plot."))
    }

    private fun addons() = CatalogAddons(
        context,
        object : CatalogProviderFactory {
            override fun builtIns(): List<CatalogProvider> = listOf(cinemeta)
            override fun custom(manifestUrl: String, name: String): CatalogProvider = torrentio
        },
        InMemoryCatalogCache(), object : Clock { override fun nowMillis() = 1_700_000_000_000L }, NoOpLogger,
        object : ExternalMetadataFallback {
            override suspend fun metadata(item: CatalogItem) = AppResult.Err(AppError.InvalidState("none"))
            override suspend fun search(query: String, type: CatalogType?) = AppResult.Err(AppError.InvalidState("none"))
        },
        object : LocalCatalogSource {
            override suspend fun discover(language: String?, type: CatalogType?, skip: Int, limit: Int) = emptyList<CatalogItem>()
            override suspend fun search(query: String, type: CatalogType?, limit: Int) = emptyList<CatalogItem>()
            override suspend fun metadata(item: CatalogItem): CatalogMetadata? = null
        },
        object : LibraryAvailability {
            override suspend fun presence(items: List<CatalogItem>) = items.associate { it.ref to LibraryPresence(7, it.title, 1) }
        },
    )

    private val version = MediaVersion(11, -100, 5, "Example.Film.2024.1080p.mkv", 2_000_000_000, 7200, 1080, "WEB-DL", "h264",
        listOf("ta", "en"), isDeleted = false, isDuplicate = false, episodeId = null, isManual = false, isExcluded = false, downloadState = "NONE")

    @Test
    fun streamsLoadWithTelegramFileAndManyTorrentsWithoutCrashing() {
        context.getSharedPreferences("catalog_addons", Context.MODE_PRIVATE).edit().clear().commit()
        val store = addons()
        kotlinx.coroutines.runBlocking { store.add("https://torrentio.example/manifest.json") }
        val media = stub<MediaRepository> { name, _ -> if (name == "versions") listOf(version) else error(name) }
        val episodes = stub<EpisodeRepository> { name, _ -> error(name) }
        val saved = SavedStateHandle(mapOf("provider" to "cinemeta", "type" to "MOVIE", "id" to "tt1234567", "title" to "Example Film", "year" to 2024, "poster" to null, "imdb" to "tt1234567"))
        val vm = CatalogTitleViewModel(saved, store, media, episodes)
        compose.setContent {
            YourStreamTheme(WindowLayout.COMPACT) {
                val state by vm.state.collectAsStateWithLifecycle()
                CatalogTitleScreen(state, vm::onAction, {}, {}, onStream = {})
            }
        }
        compose.waitUntil(10_000) { !vm.state.value.loadingStreams && vm.state.value.streams.isNotEmpty() }
        compose.waitForIdle()
        // The Telegram file first, then the add-on's 40 results (some without a size).
        kotlin.test.assertEquals(listOf("telegram" to 1, "custom:torrentio.example" to 40), vm.state.value.streams.map { it.id to it.streams.size })
        assertTrue(compose.onAllNodesWithTag("stream").fetchSemanticsNodes().isNotEmpty())
    }
}
