package dev.yourstream.ui.screens.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yourstream.media.api.MediaIndexRepository
import dev.yourstream.ui.screens.IndexingMonitor
import dev.yourstream.ui.screens.IndexingStatus
import dev.yourstream.ui.screens.firstrun.FirstRunStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** State of the app shell itself: the indexing pill shown on every tab, and the first-run guide. */
@HiltViewModel
class ShellViewModel @Inject constructor(
    monitor: IndexingMonitor,
    index: MediaIndexRepository,
    firstRun: FirstRunStore,
) : ViewModel() {
    val indexing: StateFlow<IndexingStatus> = monitor.status.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IndexingStatus())

    private val _openFirstRun = MutableStateFlow(false)

    /** True once per launch when the guided first run should open (see [FirstRunStore.shouldShow]). */
    val openFirstRun: StateFlow<Boolean> = _openFirstRun.asStateFlow()

    init {
        if (!firstRun.done) viewModelScope.launch {
            val hasSources = runCatching { index.observeChats().first().any { it.enabled } }.getOrDefault(false)
            _openFirstRun.value = firstRun.shouldShow(hasSources)
        }
    }

    /** The guide was opened: not again in this process (rotation, returning from sign-in). */
    fun firstRunOpened() {
        _openFirstRun.value = false
    }
}
