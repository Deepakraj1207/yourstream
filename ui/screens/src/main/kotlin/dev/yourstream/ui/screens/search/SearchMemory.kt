package dev.yourstream.ui.screens.search

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yourstream.catalog.api.CatalogType
import javax.inject.Inject
import javax.inject.Singleton

/** What Discover shows: a type, a catalog ([dev.yourstream.ui.screens.DiscoverCatalog.key]) and a genre (null: all). */
data class DiscoverChoice(val type: CatalogType? = null, val catalog: String? = null, val genre: String? = null)

/** What the Search tab remembers between visits: recent searches and the Discover choice. */
interface SearchMemory {
    fun recent(): List<String>
    fun saveRecent(list: List<String>)
    fun discover(): DiscoverChoice
    fun saveDiscover(choice: DiscoverChoice)
}

/** [SearchMemory] in the app's private preferences (queries stay on the device). */
@Singleton
class SearchPrefs @Inject constructor(@ApplicationContext context: Context) : SearchMemory {
    private val prefs = context.getSharedPreferences("search", Context.MODE_PRIVATE)

    override fun recent(): List<String> = prefs.getString(RECENT, null).orEmpty().split('\n').filter { it.isNotBlank() }
    override fun saveRecent(list: List<String>) = prefs.edit().putString(RECENT, list.joinToString("\n")).apply()

    override fun discover() = DiscoverChoice(
        prefs.getString(TYPE, null)?.let { n -> CatalogType.entries.firstOrNull { it.name == n } },
        prefs.getString(CATALOG, null), prefs.getString(GENRE, null),
    )
    override fun saveDiscover(choice: DiscoverChoice) =
        prefs.edit().putString(TYPE, choice.type?.name).putString(CATALOG, choice.catalog).putString(GENRE, choice.genre).apply()

    private companion object {
        const val RECENT = "recent"
        const val TYPE = "discover_type"
        const val CATALOG = "discover_catalog"
        const val GENRE = "discover_genre"
    }
}

/** Kept in memory only (previews and tests). */
class InMemorySearchMemory(private var recentList: List<String> = emptyList(), private var choice: DiscoverChoice = DiscoverChoice()) : SearchMemory {
    override fun recent() = recentList
    override fun saveRecent(list: List<String>) { recentList = list }
    override fun discover() = choice
    override fun saveDiscover(choice: DiscoverChoice) { this.choice = choice }
}
