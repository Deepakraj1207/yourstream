package dev.yourstream.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dagger.hilt.android.AndroidEntryPoint

/** Stage-1 test harness for the Telegram layer. Replaced by real UI in Stage 2. */
@AndroidEntryPoint
class DebugActivity : ComponentActivity() {

    private val viewModel: DebugViewModel by viewModels()
    private val indexViewModel: IndexDebugViewModel by viewModels()
    private val libraryViewModel: LibraryDebugViewModel by viewModels()
    private val catalogViewModel: CatalogDebugViewModel by viewModels()
    private val searchViewModel: SearchDebugViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { DebugApp(viewModel, indexViewModel, libraryViewModel, catalogViewModel, searchViewModel) { target, mediaId ->
            startActivity(PlayerActivity.intent(this, target, mediaId))
        } }
    }
}
