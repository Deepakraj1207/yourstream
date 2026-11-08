package dev.yourstream.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yourstream.catalog.api.CatalogItem
import dev.yourstream.catalog.api.CatalogLanguage
import dev.yourstream.catalog.api.CatalogMetadata
import dev.yourstream.catalog.api.CatalogProviderId
import dev.yourstream.catalog.api.CatalogSource
import dev.yourstream.catalog.api.CatalogType
import dev.yourstream.catalog.api.LibraryPresence
import dev.yourstream.catalog.api.Sourced
import dev.yourstream.catalog.core.CatalogManager
import dev.yourstream.catalog.core.DiscoverCursor
import dev.yourstream.catalog.core.DiscoverRequest
import dev.yourstream.catalog.core.ProviderAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CatalogRow(val item: CatalogItem, val presence: LibraryPresence?)

data class CatalogListing(
    val rows: List<CatalogRow> = emptyList(),
    val source: String = "",
    val stale: Boolean = false,
    val next: DiscoverCursor? = null,
    val skipped: List<String> = emptyList(),
)

/** Stage-1 debug state for catalogs. Talks to the catalog manager only. */
@HiltViewModel
class CatalogDebugViewModel @Inject constructor(private val catalogs: CatalogManager) : ViewModel() {

    private val _availability = MutableStateFlow<List<ProviderAvailability>>(catalogs.availability())
    val availability: StateFlow<List<ProviderAvailability>> = _availability.asStateFlow()

    private val _languages = MutableStateFlow<List<Pair<CatalogLanguage, List<CatalogProviderId>>>>(emptyList())
    val languages: StateFlow<List<Pair<CatalogLanguage, List<CatalogProviderId>>>> = _languages.asStateFlow()

    private val _request = MutableStateFlow(DiscoverRequest())
    val request: StateFlow<DiscoverRequest> = _request.asStateFlow()

    private val _listing = MutableStateFlow(CatalogListing())
    val listing: StateFlow<CatalogListing> = _listing.asStateFlow()

    private val _selected = MutableStateFlow<Pair<CatalogRow, Sourced<CatalogMetadata>?>?>(null)
    val selected: StateFlow<Pair<CatalogRow, Sourced<CatalogMetadata>?>?> = _selected.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    init {
        viewModelScope.launch {
            catalogs.purgeCache()
            refresh(force = false)
        }
    }

    fun refreshAvailability() = viewModelScope.launch { refresh(force = true) }

    private suspend fun refresh(force: Boolean) = work {
        _availability.value = catalogs.refreshAvailability(force)
        _languages.value = catalogs.languages()
        discoverNow(_request.value)
    }

    fun setLanguage(code: String?) = update(_request.value.copy(language = code, cursor = null))
    fun setType(type: CatalogType?) = update(_request.value.copy(type = type, cursor = null))
    fun setTag(tag: String?) = update(_request.value.copy(tag = tag, cursor = null))

    private fun update(r: DiscoverRequest) {
        _request.value = r
        viewModelScope.launch { work { discoverNow(r) } }
    }

    fun more() {
        val next = _listing.value.next ?: return
        viewModelScope.launch {
            work {
                val result = catalogs.discover(_request.value.copy(cursor = next))
                val rows = catalogs.withLibraryPresence(result.items).map { CatalogRow(it.first, it.second) }
                _listing.value = _listing.value.copy(rows = _listing.value.rows + rows, next = result.next, stale = _listing.value.stale || result.stale)
            }
        }
    }

    fun search(query: String) {
        if (query.isBlank()) return update(_request.value)
        viewModelScope.launch {
            work {
                val r = catalogs.search(query, _request.value.type)
                val rows = catalogs.withLibraryPresence(r.value).map { CatalogRow(it.first, it.second) }
                _listing.value = CatalogListing(rows, "search via ${r.source.label()}", r.stale, null, emptyList())
            }
        }
    }

    fun open(row: CatalogRow) {
        _selected.value = row to null
        viewModelScope.launch { work { _selected.value = row to catalogs.metadata(row.item) } }
    }

    fun close() { _selected.value = null }

    private suspend fun discoverNow(r: DiscoverRequest) {
        val result = catalogs.discover(r)
        val rows = catalogs.withLibraryPresence(result.items).map { CatalogRow(it.first, it.second) }
        val source = result.source.label() + (result.catalog?.let { " / ${it.name}" } ?: "")
        _listing.value = CatalogListing(rows, source, result.stale, result.next, result.skipped)
        _availability.value = catalogs.availability()
    }

    private suspend fun work(block: suspend () -> Unit) {
        _busy.value = true
        try { block() } finally { _busy.value = false }
    }

    val tags = listOf("trending", "ott", "new", "dubbed", "recommended")
}

fun CatalogSource.label(): String = when (this) {
    is CatalogSource.Provider -> "provider ${id.value}"
    is CatalogSource.ExternalMetadata -> "metadata $provider"
    CatalogSource.Local -> "local library"
    CatalogSource.Fallback -> "fallback"
}
