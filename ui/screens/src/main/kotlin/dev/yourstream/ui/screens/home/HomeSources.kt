package dev.yourstream.ui.screens.home

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yourstream.catalog.api.CatalogType
import dev.yourstream.catalog.sync.CatalogAddons
import dev.yourstream.metadata.api.TitleDetailsProvider
import dev.yourstream.ui.screens.ScreensModule
import dev.yourstream.ui.screens.navigation.CatalogTitleRoute
import dev.yourstream.ui.screens.navigation.OpenTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Home's add-on content, kept for the whole app run: the hero carousel and the catalog rows.
 * Reported from a device: the carousel loaded slowly at start and again every time the user came
 * back from another tab, because Home's screen stopped listening and everything was asked for
 * again. Here it is asked once and kept up to date (add-ons, hero choice, Home layout).
 *
 * The carousel's last slides are also saved, so the next start shows them at once and refreshes
 * them quietly. They are used only while the hero choice and the add-ons are the same.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class HomeSources @Inject constructor(
    @ApplicationContext context: Context,
    private val addons: CatalogAddons,
    layout: HomeCatalogPrefs,
    details: TitleDetailsProvider,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs = context.getSharedPreferences("home_hero_cache", Context.MODE_PRIVATE)

    private fun keyOf(enabled: List<String>, hero: List<String>) = (enabled + "|" + hero).joinToString("\n")

    private val savedKey = keyOf(addons.addons.value.filter { it.enabled }.map { it.id }, layout.layout.value.hero)

    /** Null until the first answer (or the saved slides for the same choice). */
    val hero: StateFlow<List<HeroSlide>?> = ScreensModule.heroKey(addons, layout)
        .mapLatest { (enabled, hero) -> keyOf(enabled, hero) to ScreensModule.heroSlidesFor(addons, enabled, hero, details) }
        .onEach { (key, slides) -> save(key, slides) }
        .mapLatest<Pair<String, List<HeroSlide>>, List<HeroSlide>?> { it.second }
        .catch { emit(emptyList()) }
        .stateIn(scope, SharingStarted.Eagerly, load(savedKey))

    /** Null until the first answer. */
    val catalogs: StateFlow<List<CatalogRow>?> = ScreensModule.catalogRowsFlow(addons, layout)
        .mapLatest<List<CatalogRow>, List<CatalogRow>?> { it }
        .catch { emit(emptyList()) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private fun save(key: String, slides: List<HeroSlide>) {
        runCatching { prefs.edit().putString(KEY, key).putString(SLIDES, encode(slides)).apply() }
    }

    private fun load(key: String): List<HeroSlide>? = runCatching {
        if (prefs.getString(KEY, null) != key) null else prefs.getString(SLIDES, null)?.let(::decode)?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    internal companion object {
        private const val KEY = "key"
        private const val SLIDES = "slides"

        /** Only slides that open a catalog title are saved (the carousel's own). */
        fun encode(slides: List<HeroSlide>): String = JSONArray(slides.mapNotNull { s ->
            val r = (s.open as? OpenTarget.CatalogTitle)?.route ?: return@mapNotNull null
            JSONObject().apply {
                put("key", s.key); put("title", s.title); put("poster", s.posterUrl); put("backdrop", s.backdropUrl); put("type", s.type.name)
                put("genre", s.genre); put("runtime", s.runtimeMinutes); put("year", s.year); put("rating", s.rating)
                put("languages", JSONArray(s.languages)); put("logo", s.logoUrl); put("textless", s.textlessPosterUrl)
                put("route", JSONObject().apply {
                    put("provider", r.provider); put("type", r.type); put("id", r.id); put("title", r.title)
                    put("year", r.year); put("poster", r.poster); put("imdb", r.imdb)
                })
            }
        }).toString()

        fun decode(json: String): List<HeroSlide> {
            val a = JSONArray(json)
            return (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val r = o.getJSONObject("route")
                HeroSlide(
                    o.getString("key"), o.getString("title"), o.str("poster"), o.str("backdrop"), CatalogType.valueOf(o.getString("type")),
                    o.str("genre"), o.int("runtime"), o.int("year"), o.optDouble("rating").takeIf { !it.isNaN() },
                    o.optJSONArray("languages")?.let { l -> (0 until l.length()).map { l.getString(it) } }.orEmpty(),
                    OpenTarget.CatalogTitle(CatalogTitleRoute(r.getString("provider"), r.getString("type"), r.getString("id"), r.getString("title"),
                        r.int("year"), r.str("poster"), r.str("imdb"))),
                    logoUrl = o.str("logo"), textlessPosterUrl = o.str("textless"),
                )
            }
        }

        private fun JSONObject.str(name: String): String? = if (isNull(name)) null else optString(name).ifEmpty { null }
        private fun JSONObject.int(name: String): Int? = if (isNull(name) || !has(name)) null else optInt(name)
    }
}
