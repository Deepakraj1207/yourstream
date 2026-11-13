package dev.yourstream.media.indexer.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yourstream.media.api.LocalSource
import dev.yourstream.media.api.LocalSourceIds
import dev.yourstream.media.api.LocalSourceKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The local sources the user added, in device preferences ("local_sources"): a device choice,
 * like the indexing policy, so it outlives a sign-out that empties the index. Ids are allocated
 * from a counter and never reused.
 */
@Singleton
class LocalSourceStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("local_sources", Context.MODE_PRIVATE)
    private val _sources = MutableStateFlow(load())
    val sources: StateFlow<List<LocalSource>> = _sources.asStateFlow()

    fun get(id: Long): LocalSource? = _sources.value.firstOrNull { it.id == id }

    @Synchronized
    fun add(kind: LocalSourceKind, title: String, uri: String?): LocalSource {
        // The same folder (or the device's videos) twice is the same source.
        _sources.value.firstOrNull { it.kind == kind && it.uri == uri }?.let { return it }
        val n = prefs.getLong(KEY_NEXT, 0L)
        val source = LocalSource(LocalSourceIds.of(n), kind, title, uri)
        save(_sources.value + source, next = n + 1)
        return source
    }

    @Synchronized
    fun remove(id: Long) = save(_sources.value.filterNot { it.id == id }, next = prefs.getLong(KEY_NEXT, 0L))

    private fun save(list: List<LocalSource>, next: Long) {
        val json = JSONArray(list.map { s -> JSONObject().put("id", s.id).put("kind", s.kind.name).put("title", s.title).put("uri", s.uri ?: JSONObject.NULL) })
        prefs.edit().putString(KEY_LIST, json.toString()).putLong(KEY_NEXT, next).apply()
        _sources.value = list
    }

    private fun load(): List<LocalSource> {
        val raw = prefs.getString(KEY_LIST, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.getJSONObject(i)
                val kind = runCatching { LocalSourceKind.valueOf(o.getString("kind")) }.getOrNull() ?: return@mapNotNull null
                LocalSource(o.getLong("id"), kind, o.getString("title"), o.optString("uri").takeIf { !o.isNull("uri") && it.isNotEmpty() })
            }
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val KEY_LIST = "sources"
        const val KEY_NEXT = "next_number"
    }
}
