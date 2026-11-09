package dev.yourstream.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import dev.yourstream.ui.core.CardStyle
import dev.yourstream.ui.core.EmptyKind
import dev.yourstream.ui.core.EmptyState
import dev.yourstream.ui.core.ErrorState
import dev.yourstream.ui.core.HeroBanner
import dev.yourstream.ui.core.HeroModel
import dev.yourstream.ui.core.LocalDimens
import dev.yourstream.ui.core.LocalWindowLayout
import dev.yourstream.ui.core.MediaRow
import dev.yourstream.ui.core.PagedMediaRow
import dev.yourstream.ui.core.SkeletonHero
import dev.yourstream.ui.core.SkeletonRow
import dev.yourstream.ui.core.UiState
import dev.yourstream.ui.core.focusHighlight
import dev.yourstream.ui.core.inkPanel
import dev.yourstream.ui.screens.R
import dev.yourstream.ui.screens.Tile
import dev.yourstream.ui.screens.navigation.OpenTarget
import dev.yourstream.ui.screens.tileActions
import dev.yourstream.ui.screens.tileCard

/** Paged rows of Home; collected by the route, passed in so the screen stays testable. */
class HomeRows(
    val recentlyAdded: LazyPagingItems<Tile>,
    val series: LazyPagingItems<Tile>,
)

@Composable
fun HomeRoute(onOpen: (OpenTarget) -> Unit, onSeeAll: (HomeSection) -> Unit, onSignIn: () -> Unit, onChooseSources: () -> Unit, onReview: () -> Unit = {}, vm: HomeViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val rows = HomeRows(vm.recentlyAdded.collectAsLazyPagingItems(), vm.series.collectAsLazyPagingItems())
    HomeScreen(state, rows, vm::onAction, onOpen, onSeeAll, onSignIn, onChooseSources, onReview)
}

/** Stateless: renders [HomeUiState] + [HomeRows]; every user intent goes out through the callbacks. */
@Composable
fun HomeScreen(
    state: HomeUiState,
    rows: HomeRows,
    onAction: (HomeAction) -> Unit,
    onOpen: (OpenTarget) -> Unit,
    onSeeAll: (HomeSection) -> Unit,
    onSignIn: () -> Unit,
    onChooseSources: () -> Unit,
    onReview: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        // Pinned above the rows: what indexing is doing (the rows below update live).
        // Indexing progress floats above the navigation on every tab (IndexingPill).
        HomeContent(state, rows, onAction, onOpen, onSeeAll, onSignIn, onChooseSources, onReview)
    }
}

@Composable
private fun HomeContent(
    state: HomeUiState,
    rows: HomeRows,
    onAction: (HomeAction) -> Unit,
    onOpen: (OpenTarget) -> Unit,
    onSeeAll: (HomeSection) -> Unit,
    onSignIn: () -> Unit,
    onChooseSources: () -> Unit,
    onReview: () -> Unit,
) {
    val blocking = state.blocking
    if (blocking != null) {
        val setupAction = when (blocking) {
            EmptyKind.NOT_SIGNED_IN -> onSignIn
            EmptyKind.NO_SOURCES -> onChooseSources
            else -> null
        }
        if (state.catalogs.isEmpty()) {
            EmptyState(blocking, Modifier.testTag("home"), onAction = setupAction)
            return
        }
        // Nothing of your own yet: the add-on catalogs are still there to browse, under a short
        // panel that says what to do next (sign in, choose chats).
        val card = tileCard()
        val actions = tileActions({ onAction(HomeAction.Tile(it)) }, onOpen)
        LazyColumn(
            Modifier.fillMaxSize().testTag("home"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(LocalDimens.current.rowGap),
        ) {
            item(key = "setup") { SetupPanel(blocking, setupAction) }
            state.catalogs.forEach { c ->
                item(key = c.key) { MediaRow(c.title, c.tiles, card, { onOpen(it.open) }, actions = actions) }
            }
        }
        return
    }
    val actions = tileActions({ onAction(HomeAction.Tile(it)) }, onOpen)
    val card = tileCard()
    val heroFocus = remember { FocusRequester() }
    val d = LocalDimens.current
    LazyColumn(
        Modifier.fillMaxSize().testTag("home"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = if (LocalWindowLayout.current.isTv) 32.dp else 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(d.rowGap),
    ) {
        state.banner?.let { b -> item(key = "banner") { Banner(b, state.reviewCount, onSignIn, onChooseSources, onReview) } }
        item(key = "hero") {
            when (val h = state.hero) {
                UiState.Loading -> SkeletonHero()
                is UiState.Content -> HeroBanner(heroModel(h.value), { onOpen(h.value.primary) }, { onOpen(h.value.secondary) }, primaryFocus = heroFocus)
                else -> Unit
            }
        }
        continueWatching(state.continueWatching, card, actions, onOpen, onSeeAll, onAction)
        (state.forYou as? UiState.Content)?.value?.let { f ->
            item(key = "for-you") {
                Column(Modifier.testTag("for-you")) {
                    MediaRow(stringResource(R.string.home_for_you), f.tiles, card, { onOpen(it.open) }, actions = actions)
                    f.basis?.let { b ->
                        Text(stringResource(R.string.home_for_you_basis, b), style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = d.gutter, vertical = 4.dp))
                    }
                }
            }
        }
        paged("recent", R.string.home_recently_added, rows.recentlyAdded, HomeSection.RECENTLY_ADDED, card, actions, onOpen, onSeeAll)
        paged("series", R.string.home_series, rows.series, HomeSection.SERIES, card, actions, onOpen, onSeeAll)
        // Catalog add-ons (Settings → Add-ons): information only; titles in the library open their details.
        state.catalogs.forEach { c ->
            item(key = c.key) { MediaRow(c.title, c.tiles, card, { onOpen(it.open) }, actions = actions) }
        }
    }
    // Remote users start on the hero's main button.
    LaunchedEffect(state.hero is UiState.Content) { if (state.hero is UiState.Content) runCatching { heroFocus.requestFocus() } }
}

@Composable
private fun heroModel(h: HeroItem) = HeroModel(
    key = h.key, title = h.title,
    eyebrow = stringResource(if (h.kind == HeroItem.Kind.CONTINUE) R.string.home_continue else R.string.hero_new),
    meta = listOfNotNull(h.episodeLabel, h.meta).joinToString(" · ").ifEmpty { null },
    posterUrl = h.posterUrl, progress = h.progress,
    primaryLabel = when {
        h.kind == HeroItem.Kind.NEW && h.primary is OpenTarget.Details -> stringResource(R.string.action_episodes)
        h.kind == HeroItem.Kind.NEW -> stringResource(R.string.action_play)
        h.episodeLabel != null -> stringResource(R.string.action_resume_episode, h.episodeLabel)
        else -> stringResource(R.string.action_resume)
    },
    secondaryLabel = stringResource(R.string.action_more_info),
)

private fun LazyListScope.continueWatching(
    state: UiState<List<Tile>>, card: (Tile) -> dev.yourstream.ui.core.MediaCardModel, actions: (Tile) -> List<dev.yourstream.ui.core.CardAction>,
    onOpen: (OpenTarget) -> Unit, onSeeAll: (HomeSection) -> Unit, onAction: (HomeAction) -> Unit,
) {
    item(key = "continue") {
        when (state) {
            UiState.Loading -> SkeletonRow(stringResource(R.string.home_continue), CardStyle.WIDE)
            is UiState.Content -> MediaRow(stringResource(R.string.home_continue), state.value, card, { onOpen(it.open) },
                style = CardStyle.WIDE, actions = actions, onSeeAll = { onSeeAll(HomeSection.CONTINUE_WATCHING) })
            is UiState.Error -> ErrorState(state.error, Modifier.fillMaxWidth().padding(16.dp)) { onAction(HomeAction.Retry) }
            is UiState.Empty -> Unit
        }
    }
}

private fun LazyListScope.paged(
    key: String, title: Int, items: LazyPagingItems<Tile>, section: HomeSection,
    card: (Tile) -> dev.yourstream.ui.core.MediaCardModel, actions: (Tile) -> List<dev.yourstream.ui.core.CardAction>,
    onOpen: (OpenTarget) -> Unit, onSeeAll: (HomeSection) -> Unit,
) {
    item(key = key) { PagedMediaRow(stringResource(title), items, card, { onOpen(it.open) }, actions = actions, onSeeAll = { onSeeAll(section) }) }
}

@Composable
private fun Banner(banner: HomeBanner, reviewCount: Int, onSignIn: () -> Unit, onChooseSources: () -> Unit, onReview: () -> Unit) {
    val (text, action) = when (banner) {
        HomeBanner.NOT_SIGNED_IN -> stringResource(R.string.home_banner_signed_out) to onSignIn
        HomeBanner.NO_SOURCES -> stringResource(R.string.home_banner_no_sources) to onChooseSources
        HomeBanner.OFFLINE -> stringResource(R.string.home_banner_offline) to null
        HomeBanner.REVIEW -> androidx.compose.ui.res.pluralStringResource(R.plurals.home_banner_review, reviewCount, reviewCount) to onReview
    }
    val m = Modifier.padding(horizontal = LocalDimens.current.gutter).testTag("banner")
    if (action != null) {
        AssistChip(onClick = action, label = { Text(text) }, leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) }, modifier = m.focusHighlight())
    } else {
        // Information only: not a button (screen readers would announce an action that does nothing).
        androidx.compose.foundation.layout.Row(m, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Icon(Icons.Filled.Info, contentDescription = null, tint = androidx.compose.material3.MaterialTheme.colorScheme.primary)
            Text(text, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** "Indexing 2 chats · 134 files found" with progress. Status only. */
/** "Sign in to watch your own videos" above the catalogs, while the library is still empty. */
@Composable
private fun SetupPanel(kind: EmptyKind, action: (() -> Unit)?) {
    val d = LocalDimens.current
    val (title, message, button) = when (kind) {
        EmptyKind.NOT_SIGNED_IN -> Triple(R.string.home_setup_sign_in_title, R.string.home_setup_sign_in_message, R.string.home_setup_sign_in)
        EmptyKind.NO_SOURCES -> Triple(R.string.home_setup_sources_title, R.string.home_setup_sources_message, R.string.home_setup_sources)
        else -> Triple(R.string.home_setup_indexing_title, R.string.home_setup_indexing_message, null)
    }
    androidx.compose.material3.Surface(
        color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainer,
        shape = androidx.compose.material3.MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = d.gutter).padding(end = 4.dp, bottom = 4.dp)
            .inkPanel(androidx.compose.material3.MaterialTheme.shapes.large).testTag("home-setup"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(title), style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
            Text(stringResource(message), style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
            if (button != null && action != null) {
                androidx.compose.material3.Button(onClick = action, modifier = Modifier.padding(top = 4.dp).focusHighlight().testTag("home-setup-action")) {
                    Text(stringResource(button))
                }
            }
        }
    }
}
