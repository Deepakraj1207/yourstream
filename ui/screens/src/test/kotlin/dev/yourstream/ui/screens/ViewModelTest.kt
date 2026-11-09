package dev.yourstream.ui.screens

import androidx.lifecycle.SavedStateHandle
import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import dev.yourstream.library.api.LibraryKind
import dev.yourstream.library.api.MediaRepository
import dev.yourstream.media.api.MediaIndexRepository
import dev.yourstream.media.api.model.ChatIndexStatus
import dev.yourstream.media.api.model.IndexState
import dev.yourstream.search.core.ItemKind
import dev.yourstream.search.core.SearchHit
import dev.yourstream.search.core.SearchPage
import dev.yourstream.search.core.SearchRequest
import dev.yourstream.search.engine.LibrarySearch
import dev.yourstream.telegram.api.TelegramAuthManager
import dev.yourstream.telegram.api.TelegramManager
import dev.yourstream.telegram.api.TelegramUserRepository
import dev.yourstream.telegram.api.model.AuthState
import dev.yourstream.telegram.api.model.TelegramConnectionState
import dev.yourstream.ui.core.EmptyKind
import dev.yourstream.ui.core.ErrorKind
import dev.yourstream.ui.core.UiState
import dev.yourstream.ui.screens.favorites.FavoritesAction
import dev.yourstream.ui.screens.favorites.FavoritesViewModel
import dev.yourstream.ui.screens.home.HomeBanner
import dev.yourstream.ui.screens.home.HomeViewModel
import dev.yourstream.ui.screens.search.SearchAction
import dev.yourstream.ui.screens.search.SearchViewModel
import dev.yourstream.ui.screens.settings.AccountAction
import dev.yourstream.ui.screens.settings.AccountViewModel
import dev.yourstream.watch.engine.AddedEntry
import dev.yourstream.watch.engine.WatchIntelligence
import dev.yourstream.watch.core.WatchStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun main() = Dispatchers.setMain(dispatcher)
    @After fun reset() = Dispatchers.resetMain()

    private val auth = MutableStateFlow<AuthState>(AuthState.WaitPhoneNumber)
    private val chats = MutableStateFlow<List<ChatIndexStatus>>(emptyList())
    private val added = MutableStateFlow<List<AddedEntry>>(emptyList())

    private fun home() = HomeViewModel(
        stub<WatchIntelligence> { name, _ ->
            when (name) { "continueWatching" -> flowOf(emptyList<Any>()); "recentlyAdded" -> added; "favorites" -> flowOf(emptyList<Any>()); else -> error(name) }
        },
        stub<MediaIndexRepository> { name, _ -> if (name == "observeChats") chats else error(name) },
        stub<TelegramAuthManager> { name, _ -> if (name == "getState") auth else error(name) },
        stub<TelegramManager> { name, _ -> if (name == "getConnectionState") MutableStateFlow(TelegramConnectionState.READY) else error(name) },
    )

    @Test
    fun homeGuidesThroughSignInAndSourcesThenShowsRows() = runTest(dispatcher) {
        val vm = home()
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        assertEquals(HomeBanner.NOT_SIGNED_IN, vm.state.value.banner)
        assertEquals(EmptyKind.NOT_SIGNED_IN, vm.state.value.blocking)

        auth.value = AuthState.Ready
        advanceUntilIdle()
        assertEquals(EmptyKind.NO_SOURCES, vm.state.value.blocking)

        chats.value = listOf(ChatIndexStatus(1, "Movies", true, IndexState.IDLE, 0, 0, false, null, null, null, null))
        added.value = listOf(AddedEntry(7, LibraryKind.MOVIE, "Vikram", 2022, 1, WatchStatus.UNWATCHED, 0, false))
        advanceUntilIdle()
        assertNull(vm.state.value.banner)
        assertNull(vm.state.value.blocking)
        assertEquals(listOf("Vikram"), (vm.state.value.recentlyAdded as UiState.Content).value.map { it.card.title })
    }

    @Test
    fun searchDebouncesAndMapsResults() = runTest(dispatcher) {
        val asked = mutableListOf<String>()
        val search = object : LibrarySearch {
            override suspend fun search(request: SearchRequest): SearchPage {
                asked += request.text
                if (request.text == "boom") error("index broken")
                val hits = if (request.text == "vikram") listOf(SearchHit(1, ItemKind.MOVIE, "Vikram", 2022, 1.0, emptyList(), emptyList())) else emptyList()
                return SearchPage(hits, hits.size, 0, null, listOf("read as ${request.text}"), 1)
            }
            override suspend fun suggest(prefix: String, limit: Int) = listOf("vikram")
        }
        val vm = SearchViewModel(SavedStateHandle(), search)
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        assertNull(vm.state.value.results, "blank query: hint, no search")
        for (c in listOf("v", "vi", "vik", "vikram")) { vm.onAction(SearchAction.QueryChanged(c)); advanceTimeBy(50) }
        advanceUntilIdle()
        assertEquals(listOf("vikram"), asked, "typing is debounced into one search")
        assertEquals(listOf("Vikram"), (vm.state.value.results as UiState.Content).value.map { it.card.title })
        assertEquals(listOf("read as vikram"), vm.state.value.interpretation)
        vm.onAction(SearchAction.QueryChanged("zzz")); advanceUntilIdle()
        assertEquals(UiState.Empty(EmptyKind.NO_RESULTS), vm.state.value.results)
        vm.onAction(SearchAction.QueryChanged("boom")); advanceUntilIdle()
        assertIs<UiState.Error>(vm.state.value.results, "an engine failure is an error state, not a crash")
        vm.onAction(SearchAction.Clear); advanceUntilIdle()
        assertNull(vm.state.value.results)
    }

    @Test
    fun accountSubmitsToTheRightAuthCallAndShowsErrors() = runTest(dispatcher) {
        val calls = mutableListOf<String>()
        val vm = AccountViewModel(
            stub<TelegramAuthManager> { name, args ->
                when (name) {
                    "getState" -> auth
                    "submitPhoneNumber" -> { calls += "phone:${args[0]}"; AppResult.Ok(Unit) }
                    "submitCode" -> { calls += "code:${args[0]}"; AppResult.Err(AppError.InvalidInput("PHONE_CODE_INVALID", "Wrong code")) }
                    else -> error(name)
                }
            },
            stub<TelegramUserRepository> { name, _ -> if (name == "getCurrentUser") MutableStateFlow(null) else error(name) },
        )
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        vm.onAction(AccountAction.Submit("+491701234567")); advanceUntilIdle()
        auth.value = AuthState.WaitCode(dev.yourstream.telegram.api.model.CodeInfo("+49***67", dev.yourstream.telegram.api.model.CodeDeliveryType.SMS, null, 60, 5))
        advanceUntilIdle()
        vm.onAction(AccountAction.Submit("12345")); advanceUntilIdle()
        assertEquals(listOf("phone:+491701234567", "code:12345"), calls)
        assertEquals(ErrorKind.INPUT, vm.state.value.error!!.kind)
        assertEquals("Wrong code", vm.state.value.error!!.detail)
    }

    @Test
    fun favoritesRemoveGoesToTheRepository() = runTest(dispatcher) {
        val removed = mutableListOf<Pair<Long, Boolean>>()
        val vm = FavoritesViewModel(
            stub<WatchIntelligence> { name, _ -> if (name == "favorites") flowOf(emptyList<Any>()) else error(name) },
            stub<MediaRepository> { name, args -> if (name == "setFavorite") { removed += (args[0] as Long) to (args[1] as Boolean); Unit } else error(name) },
        )
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        assertEquals(UiState.Empty(EmptyKind.NO_FAVORITES), vm.state.value.items)
        vm.onAction(FavoritesAction.Remove(4)); advanceUntilIdle()
        assertEquals(listOf(4L to false), removed)
    }
}
