package dev.yourstream.ui.screens.home

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Settings → Layout → Home layout → Catalogs: which add-on catalogs Home shows, in what order and
 * under what name. Keys are [CatalogRow.key] ("cat:<add-on>:<type>:<catalog>"). Catalogs not in
 * [order] come after the ordered ones, in the add-ons' own order (pure; tested).
 */
data class HomeCatalogLayout(
    val hidden: Set<String> = emptySet(),
    val order: List<String> = emptyList(),
    val names: Map<String, String> = emptyMap(),
    /**
     * Catalogs feeding Home's hero carousel (Nuvio's "Hero catalogs"), at most [HERO_LIMIT]; none:
     * the hero shows what to continue or the newest title of the library.
     */
    val hero: List<String> = emptyList(),
) {
    /** [key] added to or removed from the hero catalogs (adding beyond [HERO_LIMIT] does nothing). */
    fun withHero(key: String, on: Boolean): HomeCatalogLayout = when {
        !on -> copy(hero = hero - key)
        key in hero || hero.size >= HERO_LIMIT -> this
        else -> copy(hero = hero + key)
    }

    companion object {
        const val HERO_LIMIT = 3
    }

    fun <T> arrange(items: List<T>, key: (T) -> String): List<T> =
        items.filter { key(it) !in hidden }.sortedBy { i -> order.indexOf(key(i)).let { if (it < 0) Int.MAX_VALUE else it } }

    fun nameOf(key: String, default: String): String = names[key]?.takeIf { it.isNotBlank() } ?: default

    /** [key] moved one place up (-1) or down (+1) among [all] (every catalog, in their current order). */
    fun move(all: List<String>, key: String, by: Int): HomeCatalogLayout {
        val list = all.sortedBy { k -> order.indexOf(k).let { if (it < 0) Int.MAX_VALUE else it } }.toMutableList()
        val from = list.indexOf(key)
        val to = from + by
        if (from < 0 || to !in list.indices) return this
        list.add(to, list.removeAt(from))
        return copy(order = list)
    }
}

/** Stored on the device (account-independent, like the other layout settings). */
@Singleton
class HomeCatalogPrefs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("home_catalogs", Context.MODE_PRIVATE)
    private val _layout = MutableStateFlow(load())
    val layout: StateFlow<HomeCatalogLayout> = _layout.asStateFlow()

    fun update(change: (HomeCatalogLayout) -> HomeCatalogLayout) {
        val l = change(_layout.value)
        prefs.edit()
            .putStringSet("hidden", l.hidden)
            .putString("order", l.order.joinToString("\n"))
            .putString("names", JSONObject(l.names).toString())
            .putString("hero", l.hero.joinToString("\n"))
            .apply()
        _layout.value = l
    }

    /** "Reset to default": every catalog shown, in the add-ons' order, under its own name (hero choice kept). */
    fun reset() = update { HomeCatalogLayout(hero = it.hero) }

    private fun load(): HomeCatalogLayout {
        val names = runCatching {
            val o = JSONObject(prefs.getString("names", "{}") ?: "{}")
            o.keys().asSequence().associateWith { o.getString(it) }
        }.getOrDefault(emptyMap())
        return HomeCatalogLayout(
            hidden = prefs.getStringSet("hidden", emptySet()).orEmpty(),
            order = prefs.getString("order", "").orEmpty().lines().filter { it.isNotBlank() },
            names = names,
            hero = prefs.getString("hero", "").orEmpty().lines().filter { it.isNotBlank() },
        )
    }
}
