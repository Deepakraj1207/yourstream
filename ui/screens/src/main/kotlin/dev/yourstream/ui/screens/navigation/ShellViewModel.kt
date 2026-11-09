package dev.yourstream.ui.screens.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yourstream.ui.screens.IndexingMonitor
import dev.yourstream.ui.screens.IndexingStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** State of the app shell itself: the indexing pill shown on every tab. */
@HiltViewModel
class ShellViewModel @Inject constructor(monitor: IndexingMonitor) : ViewModel() {
    val indexing: StateFlow<IndexingStatus> = monitor.status.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IndexingStatus())
}
