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
import androidx.paging.PagingData
import dev.yourstream.library.api.LibraryItemSummary
import dev.yourstream.library.api.LibraryQuery
import dev.yourstream.library.api.LibrarySort
import dev.yourstream.library.api.MovieRepository
import dev.yourstream.library.api.PlaybackTarget
import dev.yourstream.library.api.SeriesRepository
import dev.yourstream.library.api.WatchFilter
import dev.yourstream.ui.screens.browse.BrowseAction
import dev.yourstream.ui.screens.browse.LibraryViewModel
import dev.yourstream.ui.screens.home.HeroItem
import dev.yourstream.ui.screens.navigation.OpenTarget
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun main() = Dispatchers.setMain(dispatcher)
    @After fun reset() = Dispatchers.resetMain()

    private val auth = MutableStateFlow<AuthState>(AuthState.WaitPhoneNumber)
    private val chats = MutableStateFlow<List<ChatIndexStatus>>(emptyList())
    private val count = MutableStateFlow(0)
    private val added = MutableStateFlow<List<AddedEntry>>(emptyList())

    private val media = stub<MediaRepository> { name, _ ->
        when (name) {
            "recentlyAdded", "favorites" -> flowOf(PagingData.empty<LibraryItemSummary>())
            "count" -> count
            "getItem" -> null
            else -> error(name)
        }
    }

    private fun home() = HomeViewModel(
        stub<WatchIntelligence> { name, _ -> when (name) { "continueWatching", "recentlyWatched" -> flowOf(emptyList<Any>()); "recentlyAdded" -> added; else -> error(name) } },
        media,
        stub<MovieRepository> { _, _ -> flowOf(PagingData.empty<LibraryItemSummary>()) },
        stub<SeriesRepository> { _, _ -> flowOf(PagingData.empty<LibraryItemSummary>()) },
        stub<MediaIndexRepository> { name, _ -> if (name == "observeChats") chats else error(name) },
        stub<TelegramAuthManager> { name, _ -> if (name == "getState") auth else error(name) },
        stub<TelegramManager> { name, _ -> if (name == "getConnectionState") MutableStateFlow(TelegramConnectionState.READY) else error(name) },
        ArtworkLookup(media),
        TileActionHandler(media, stub { _, _ -> error("unused") }, stub { _, _ -> error("unused") }),
        assistant,
        IndexingMonitor(stub<MediaIndexRepository> { name, _ -> if (name == "observeChats") chats else error(name) }),
        HomeCatalogs { flowOf(emptyList()) },
    )

    private val brainAsked = mutableListOf<String>()
    private val assistant = object : dev.yourstream.assistant.engine.MediaAssistant {
        override suspend fun ask(text: String): dev.yourstream.assistant.engine.AssistantResponse {
            brainAsked += text
            val i = dev.yourstream.assistant.core.IntentParser().parse(text)
            val items = if (text.contains("tamil")) listOf(dev.yourstream.assistant.engine.AssistantItem(9, ItemKind.MOVIE, "Jailer", 2023)) else emptyList()
            return dev.yourstream.assistant.engine.AssistantResponse(i, dev.yourstream.assistant.core.Planner.plan(i), "${items.size} Tamil movies.", items, items.size, 0)
        }
        override suspend fun recommendations(limit: Int) = dev.yourstream.assistant.engine.Recommendations(
            "Tamil, action", listOf(dev.yourstream.assistant.engine.AssistantItem(9, ItemKind.MOVIE, "Jailer", 2023, reason = "Tamil · action")),
        )
    }

    @Test
    fun homeGuidesThroughSignInAndSourcesThenShowsTheLibrary() = runTest(dispatcher) {
        val vm = home()
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        assertEquals(HomeBanner.NOT_SIGNED_IN, vm.state.value.banner)
        assertEquals(EmptyKind.NOT_SIGNED_IN, vm.state.value.blocking)

        auth.value = AuthState.Ready
        advanceUntilIdle()
        assertEquals(EmptyKind.NO_SOURCES, vm.state.value.blocking)

        chats.value = listOf(ChatIndexStatus(1, "Movies", true, IndexState.IDLE, 0, 0, false, null, null, null, null))
        advanceUntilIdle()
        assertEquals(EmptyKind.LIBRARY_EMPTY, vm.state.value.blocking, "sources chosen, nothing indexed yet")

        count.value = 1
        added.value = listOf(AddedEntry(7, LibraryKind.MOVIE, "Vikram", 2022, 1, WatchStatus.UNWATCHED, 0, false))
        advanceUntilIdle()
        assertNull(vm.state.value.banner)
        assertNull(vm.state.value.blocking)
        val hero = (vm.state.value.hero as UiState.Content).value
        assertEquals("Vikram" to HeroItem.Kind.NEW, hero.title to hero.kind)
        assertEquals(OpenTarget.Play(PlaybackTarget(7)), hero.primary, "a movie plays straight from the hero")

        // Local recommendations with their reason (no "check matches" banner since 0.11.5).
        val forYou = (vm.state.value.forYou as UiState.Content).value
        assertEquals("Tamil, action", forYou.basis)
        assertEquals(TileCaption.Reason("Tamil · action"), forYou.tiles.single().caption)
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
        val vm = SearchViewModel(SavedStateHandle(), search, ArtworkLookup(media), assistant, noAddons, dev.yourstream.ui.screens.search.InMemorySearchMemory())
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        assertNull(vm.state.value.results, "blank query: hint, no search")
        for (c in listOf("v", "vi", "vik", "vikram")) { vm.onAction(SearchAction.QueryChanged(c)); advanceTimeBy(50) }
        advanceUntilIdle()
        assertEquals(listOf("vikram"), asked, "typing is debounced into one search")
        val r = (vm.state.value.results as UiState.Content).value
        assertEquals("Vikram", r.top!!.card.title, "a single hit is the top result")
        assertEquals(emptyList(), r.rest)
        assertEquals(listOf("read as vikram"), vm.state.value.interpretation)
        assertNull(vm.state.value.brain, "a bare title is a search, not a request")
        vm.onAction(SearchAction.QueryChanged("unwatched tamil movies")); advanceUntilIdle()
        val brain = vm.state.value.brain!!
        assertEquals("1 Tamil movies.", brain.message)
        assertEquals("Jailer", brain.tiles.single().card.title)
        assertTrue(brain.understood.isNotEmpty())
        vm.onAction(SearchAction.QueryChanged("zzz")); advanceUntilIdle()
        assertEquals(UiState.Empty(EmptyKind.NO_RESULTS), vm.state.value.results)
        vm.onAction(SearchAction.QueryChanged("boom")); advanceUntilIdle()
        assertIs<UiState.Error>(vm.state.value.results, "an engine failure is an error state, not a crash")
        vm.onAction(SearchAction.Clear); advanceUntilIdle()
        assertNull(vm.state.value.results)
    }

    private val noAddons = object : AddonSearch { override suspend fun sections(query: String) = AddonSections(emptyList()) }

    @Test
    fun searchRemembersMeantSearchesAndDiscoverBrowsesCatalogs() = runTest(dispatcher) {
        val movie = { k: String -> Tile(dev.yourstream.ui.core.MediaCardModel(k, k), OpenTarget.Details(1)) }
        val top = DiscoverCatalog("cat:a:MOVIE:top", "Top", "Alpha", dev.yourstream.catalog.api.CatalogType.MOVIE, listOf("Drama", "Comedy"))
        val shows = DiscoverCatalog("cat:a:SERIES:top", "Top series", "Alpha", dev.yourstream.catalog.api.CatalogType.SERIES, listOf("Anime"), genreRequired = true)
        val pages = mutableListOf<String>()
        val addons = object : AddonSearch {
            override suspend fun sections(query: String) = AddonSections(listOf(SearchRow("a:MOVIE", "Alpha", dev.yourstream.catalog.api.CatalogType.MOVIE, listOf(movie("hit")))))
            override suspend fun catalogs() = listOf(top, shows)
            override suspend fun page(catalog: DiscoverCatalog, genre: String?, skip: Int): DiscoverPage {
                pages += "${catalog.name}:$genre:$skip"
                return DiscoverPage(listOf(movie("${catalog.name}-$skip")), if (skip == 0) 1 else null)
            }
        }
        val search = object : LibrarySearch {
            override suspend fun search(request: SearchRequest) = SearchPage(emptyList(), 0, 0, null, emptyList(), 1)
            override suspend fun suggest(prefix: String, limit: Int) = emptyList<String>()
        }
        val memory = dev.yourstream.ui.screens.search.InMemorySearchMemory(listOf("older"))
        val vm = SearchViewModel(SavedStateHandle(), search, ArtworkLookup(media), assistant, addons, memory)
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()

        // Discover: the first type and catalog, all genres; more on demand; choices are kept.
        val d = vm.state.value.discover
        assertEquals(listOf("Top:null:0"), pages)
        assertEquals(top, d.catalog)
        vm.onAction(SearchAction.DiscoverMore); advanceUntilIdle()
        assertEquals(listOf("Top-0", "Top-1"), vm.state.value.discover.tiles.map { it.card.key })
        vm.onAction(SearchAction.DiscoverGenre("Comedy")); advanceUntilIdle()
        assertEquals("Top:Comedy:0", pages.last())
        // A catalog that requires a genre starts on its first.
        vm.onAction(SearchAction.DiscoverType(dev.yourstream.catalog.api.CatalogType.SERIES)); advanceUntilIdle()
        assertEquals("Top series:Anime:0", pages.last())
        assertEquals(dev.yourstream.ui.screens.search.DiscoverChoice(dev.yourstream.catalog.api.CatalogType.SERIES, shows.key, "Anime"), memory.discover())

        // Typing: the add-ons' rows; typing alone is not remembered, a meant search is.
        vm.onAction(SearchAction.QueryChanged("leo")); advanceUntilIdle()
        assertEquals(listOf("hit"), vm.state.value.addons!!.rows.single().tiles.map { it.card.key })
        assertEquals(listOf("older"), vm.state.value.recent)
        vm.onAction(SearchAction.Submit)
        vm.onAction(SearchAction.PickRecent("OLDER")); advanceUntilIdle()
        assertEquals(listOf("OLDER", "leo"), vm.state.value.recent, "newest first, no repeats in any case")
        vm.onAction(SearchAction.RemoveRecent("leo"))
        assertEquals(listOf("OLDER"), memory.recent())
    }

    private fun fakeTelegram(status: MutableStateFlow<dev.yourstream.telegram.api.model.TelegramStatus>, calls: MutableList<String>) =
        stub<dev.yourstream.telegram.api.TelegramManager> { name, _ ->
            when (name) {
                "getStatus" -> status
                "start" -> { calls += "start"; AppResult.Ok(Unit) }
                "restart" -> { calls += "restart"; AppResult.Ok(Unit) }
                else -> error(name)
            }
        }

    /**
     * Reported from a device: sign-in loaded forever. Telegram had not started (safe mode after
     * "early exits" that were ordinary closes), and the screen only showed a spinner.
     */
    @Test
    fun signInNeverSpinsForeverWhenTelegramIsNotReady() = runTest(dispatcher) {
        val starting = MutableStateFlow<AuthState>(AuthState.Unknown)
        val status = MutableStateFlow<dev.yourstream.telegram.api.model.TelegramStatus>(dev.yourstream.telegram.api.model.TelegramStatus.Stopped)
        val calls = mutableListOf<String>()
        val authManager = stub<TelegramAuthManager> { name, _ -> if (name == "getState") starting else error(name) }
        val noData = object : dev.yourstream.ui.screens.settings.AccountDataStore {
            override suspend fun hasAccountData() = false
            override suspend fun wipe() = Unit
            override var signedOutNotice = false
        }
        val vm = AccountViewModel(
            authManager,
            stub<TelegramUserRepository> { name, _ -> if (name == "getCurrentUser") MutableStateFlow(null) else error(name) },
            dev.yourstream.ui.screens.settings.AccountSession(authManager, noData, dev.yourstream.core.common.NoOpLogger),
            fakeTelegram(status, calls),
        )
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        // Not running (safe mode or stopped): said at once, with a button that starts it.
        assertEquals(dev.yourstream.ui.screens.settings.StartProblem.NotRunning, vm.state.value.startProblem)
        vm.onAction(AccountAction.StartTelegram); advanceUntilIdle()
        assertEquals(listOf("start"), calls)

        // Running but not getting ready: a normal spinner first, then "taking longer" and a retry.
        status.value = dev.yourstream.telegram.api.model.TelegramStatus.Running
        advanceTimeBy(1_000)
        assertNull(vm.state.value.startProblem, "starting normally: just the spinner")
        advanceTimeBy(AccountViewModel.SLOW_START_MS)
        assertEquals(dev.yourstream.ui.screens.settings.StartProblem.Slow, vm.state.value.startProblem)
        vm.onAction(AccountAction.StartTelegram); advanceUntilIdle()
        assertEquals(listOf("start", "restart"), calls, "a stuck running instance is restarted")

        // Telegram gets ready: the phone-number step, no problem shown.
        starting.value = AuthState.WaitPhoneNumber
        advanceUntilIdle()
        assertEquals(dev.yourstream.ui.screens.settings.AccountStep.Phone, vm.state.value.step)
        assertNull(vm.state.value.startProblem)

        // A failure is said in words.
        assertEquals(
            dev.yourstream.ui.screens.settings.StartProblem.Failed("no TDLib"),
            AccountViewModel.startProblem(dev.yourstream.telegram.api.model.TelegramStatus.Failed(AppError.Unknown("no TDLib")), slow = false),
        )
    }

    @Test
    fun accountSubmitsToTheRightAuthCallAndShowsErrors() = runTest(dispatcher) {
        val calls = mutableListOf<String>()
        val authManager = stub<TelegramAuthManager> { name, args ->
            when (name) {
                "getState" -> auth
                "submitPhoneNumber" -> { calls += "phone:${args[0]}"; AppResult.Ok(Unit) }
                "submitCode" -> { calls += "code:${args[0]}"; AppResult.Err(AppError.InvalidInput("PHONE_CODE_INVALID", "Wrong code")) }
                else -> error(name)
            }
        }
        val noData = object : dev.yourstream.ui.screens.settings.AccountDataStore {
            override suspend fun hasAccountData() = false
            override suspend fun wipe() = Unit
            override var signedOutNotice = false
        }
        val vm = AccountViewModel(
            authManager,
            stub<TelegramUserRepository> { name, _ -> if (name == "getCurrentUser") MutableStateFlow(null) else error(name) },
            dev.yourstream.ui.screens.settings.AccountSession(authManager, noData, dev.yourstream.core.common.NoOpLogger),
            fakeTelegram(MutableStateFlow(dev.yourstream.telegram.api.model.TelegramStatus.Running), mutableListOf()),
        )
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        vm.onAction(AccountAction.Submit("+491701234567")); advanceUntilIdle()
        auth.value = AuthState.WaitCode(dev.yourstream.telegram.api.model.CodeInfo("+49***67", dev.yourstream.telegram.api.model.CodeDeliveryType.SMS, null, 60, 5))
        advanceUntilIdle()
        vm.onAction(AccountAction.Submit("12345")); advanceUntilIdle()
        assertEquals(listOf("phone:+491701234567", "code:12345"), calls)
        assertEquals(AppError.InvalidInput("PHONE_CODE_INVALID", "Wrong code"), vm.state.value.error)
    }

    @Test
    fun browseFiltersReachTheDomainQueryAndActionsTheRepositories() = runTest(dispatcher) {
        val queries = mutableListOf<LibraryQuery>()
        val favorites = mutableListOf<Pair<Long, Boolean>>()
        val repo = stub<MediaRepository> { name, args ->
            when (name) {
                "search" -> { queries += args[0] as LibraryQuery; flowOf(PagingData.empty<LibraryItemSummary>()) }
                "count" -> flowOf(42)
                "setFavorite" -> { favorites += (args[0] as Long) to (args[1] as Boolean); Unit }
                else -> error(name)
            }
        }
        val vm = LibraryViewModel(SavedStateHandle(), repo, TileActionHandler(repo, stub { _, _ -> error("unused") }, stub { _, _ -> error("unused") }))
        backgroundScope.launch { vm.state.collect {} }
        backgroundScope.launch { vm.items.collect {} }
        advanceUntilIdle()
        assertEquals(42, vm.state.value.count)
        vm.onAction(BrowseAction.SetKind(LibraryKind.SERIES)); vm.onAction(BrowseAction.SetWatch(WatchFilter.UNWATCHED)); advanceUntilIdle()
        assertEquals(LibraryQuery(kinds = setOf(LibraryKind.SERIES), myLibraryOnly = true, sort = LibrarySort.TITLE, watch = WatchFilter.UNWATCHED), queries.last())
        vm.onAction(BrowseAction.Tile(TileAction.SetFavorite(4, true))); advanceUntilIdle()
        assertEquals(listOf(4L to true), favorites)
    }

    @Test
    fun artworkLookupCachesHitsAndRetriesMisses() = runTest(dispatcher) {
        var now = 0L
        var poster: String? = null
        var lookups = 0
        val repo = stub<MediaRepository> { name, _ ->
            if (name != "getItem") error(name)
            lookups++
            dev.yourstream.library.api.LibraryItemDetails(summary(1).copy(posterUrl = poster), "", false, false, emptyList(), emptyList(), emptyList())
        }
        val art = ArtworkLookup(repo, { now }, missTtlMs = 1_000)
        assertNull(art.poster(1)); assertNull(art.poster(1))
        assertEquals(1, lookups, "a miss is cached for a while")
        poster = "https://img/x.jpg"; now = 2_000
        assertEquals("https://img/x.jpg", art.poster(1), "metadata matched later: retried after the TTL")
        assertEquals("https://img/x.jpg", art.poster(1))
        assertEquals(2, lookups)
    }
}
