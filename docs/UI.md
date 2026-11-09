# UI skeleton (Stage 2, step 1)

A functional, unpolished UI on top of the frozen domain layer. It uses:

* Jetpack Compose and Material 3;
* Compose for TV for the TV navigation drawer;
* Navigation Compose with type-safe routes;
* Hilt view models.

No backend code was changed for it.

```
MainActivity ─ YourStreamUi (theme + window layout)
                 └─ AdaptiveShell: bottom bar | rail | drawer | TV drawer   (player: full screen)
                      └─ AppNavHost (typed routes)
                           └─ XxxRoute(vm = hiltViewModel())  ── state: StateFlow<XxxUiState>, onAction(XxxAction)
                                └─ XxxScreen(state, onAction, navigation callbacks)   ← stateless, previewable, tested
                                     └─ :ui:core components (StateContent, MediaRow/Grid, ListRow, ChipRow, …)
ViewModels → existing domain interfaces only (MediaRepository, WatchIntelligence, LibrarySearch,
TelegramAuthManager, MediaIndexer, TelegramPlaybackControllerFactory, TrackPreferenceStore, …)
```

## Modules

| Module | Contents | Depends on |
|---|---|---|
| `:ui:core` | `UiState` / `UiError` / `EmptyKind`; state views; media components; `WindowLayout`; theme; focus highlight | `core:common` only (for `AppError` → `UiError`) |
| `:ui:screens` | Routes, `AppNavigator`, `AdaptiveShell`, `AppNavHost`; one package per screen (contract + ViewModel + screen); `Presentation` mappers | `:ui:core` and the domain API modules |
| `:app` | `MainActivity` (launcher + TV leanback launcher); the Stage-1 debug activity (Settings → Developer tools) | everything |

## Rules

1. **Screens are stateless.** `XxxScreen(state, onAction, …)` renders the state and reports
   intents. The `XxxRoute` wrapper collects the ViewModel with `collectAsStateWithLifecycle` and
   wires navigation callbacks.
2. **No business logic in Compose or ViewModels.** ViewModels call domain interfaces and map
   results to UI models. Every decision stays in the domain:
   * what is "next", what to resume, which version to play;
   * completion, search ranking;
   * when to index.
3. **One state vocabulary.**
   * Every screen or section is `UiState.Loading | Empty(kind) | Error(UiError) | Content`.
   * `StateContent` / `PagedContent` render it the same way everywhere.
   * `AppError.toUiError()` is the only domain-error → UI mapping.
4. **Presentation models only below the screens.** `:ui:core` components take
   `MediaCardModel` / plain values, never domain types. `Presentation` maps domain → `Tile`
   (card + `OpenTarget`).
5. **Navigation via `AppNavigator`.** Typed routes (`@Serializable`). Top-level switches
   save/restore state. "Play next" replaces the player instead of stacking it.
6. **Saved state.** Filters (browse) and the search query live in `SavedStateHandle` and survive
   process death. Login codes and passwords are deliberately never saved.

## Navigation

| Destination | Route | Reached from |
|---|---|---|
| Home | `HomeRoute` (start) | top level |
| Library | `LibraryRoute` | top level; Home → "See all" (recently added) |
| Movies / Series | `MoviesRoute` / `SeriesRoute` | top level (rail/drawer/TV); Library kind chips on phones |
| Search | `SearchRoute` | top level |
| Continue watching | `ContinueWatchingRoute` | top level (rail/drawer/TV); Home → "See all" |
| Favorites | `FavoritesRoute` | top level |
| Settings | `SettingsRoute` | top level |
| Details | `DetailsRoute(itemId)` | any card |
| Episodes | `EpisodesRoute(seriesId, seasonId?)` | Details → season |
| Player | `PlayerRoute(itemId, episodeId?, mediaId?)` | Play / Resume / version / episode / continue-watching card / search `s2e5` |
| Account | `AccountRoute` | Settings; Home "Sign in" |
| Sources | `SourcesRoute` | Settings; Home "Choose chats" |
| Audio & subtitles | `LanguagesRoute` | Settings |

## Screen contracts

| Screen | UiState | Actions | Domain sources |
|---|---|---|---|
| Home | `HomeUiState(banner, continueWatching, recentlyAdded, favorites)`. Each row is a `UiState`; `blocking` = full-screen empty state when nothing exists | `Retry`; callbacks open / see all / sign in / choose sources | `WatchIntelligence.continueWatching/recentlyAdded/favorites`, `TelegramAuthManager.state`, `MediaIndexRepository.observeChats`, `TelegramManager.connectionState` |
| Library, Movies, Series | `BrowseUiState(filters, showKindFilter)` + `items: Flow<PagingData<Tile>>` | `SetKind`, `SetSort`, `SetWatch` | `MediaRepository.search`, `MovieRepository.movies`, `SeriesRepository.series` (`LibraryQuery`) |
| Details | `DetailsUiState(content: UiState<DetailsContent>)`. `primary`: Play / Resume(position) / Episode(label, resume) | `ToggleFavorite`, `Retry` | `MediaRepository.getItem/setFavorite`, `WatchIntelligence.summary` (incl. next-episode decision) |
| Episodes | `EpisodesUiState(seasons, selectedSeasonId, nextUp)` + `items: Flow<PagingData<EpisodeItem>>` | `SelectSeason`, `SetWatched` | `SeriesRepository.seasons`, `EpisodeRepository.episodes`, `WatchIntelligence.nextEpisode`, `WatchHistoryRepository.markWatched` |
| Search | `SearchUiState(query, results?, suggestions, interpretation)`. `results == null` = blank query (hint) | `QueryChanged` (250 ms debounce), `PickSuggestion`, `Clear` | `LibrarySearch.search/suggest` |
| Continue watching | `ContinueWatchingUiState(items)` | `Remove` (dismiss), `Retry` | `WatchIntelligence.continueWatching/dismiss` |
| Favorites | `FavoritesUiState(items)` | `Remove`, `Retry` | `WatchIntelligence.favorites`, `MediaRepository.setFavorite` |
| Player | `PlayerUiState(status, position, duration, error, recovery, audio/subtitle options + selection, next)` | `TogglePlay`, `Pause`, `SeekTo`, `SeekBy`, `SelectAudio`, `SelectSubtitle`, `Retry` | `TelegramPlaybackControllerFactory` (one controller per screen), `WatchHistoryRepository.progress`, `PlaybackSessionStore`, `WatchIntelligence.nextEpisode` |
| Settings | `SettingsUiState(signedInAs, enabledSources, languages)` | callbacks to sub-screens and developer tools | auth, user, index, `TrackPreferenceStore` |
| Account | `AccountUiState(step, busy, error)`. `AccountStep` mirrors `AuthState` | `Submit(text)`, `Resend`, `UseQr`, `LogOut` | `TelegramAuthManager` |
| Sources | `SourcesUiState(chats: UiState<List<SourceRow>>, hasMore, actionError)` | `Toggle`, `Rescan`, `LoadMore` | `TelegramChatRepository.chatList/loadMore`, `MediaIndexer.enableChat/disableChat/schedule`, `MediaIndexRepository.observeChats` |
| Languages | `LanguagesUiState(prefs)` | `PreferAudio`, `PreferSubtitles`, `SetSubtitleMode` | `TrackPreferenceStore` |

## Loading, empty and error states

* **Loading:** a centred progress indicator, announced as "Loading". Paged lists show it only
  on the first load.
* **Empty:** `EmptyKind` decides the title, the message and the call to action.
  * `NOT_SIGNED_IN` → Sign in;
  * `NO_SOURCES` → Choose chats;
  * `LIBRARY_EMPTY`, `NO_RESULTS` (when filters or a query are set), `NOTHING_IN_PROGRESS`,
    `NO_FAVORITES`, `NO_EPISODES`, `NO_CHATS`.
* **Error:** `ErrorKind` decides the message: OFFLINE ("your library still works"), TELEGRAM,
  SIGNED_OUT, NOT_FOUND, SERVICE, INPUT, UNKNOWN. The domain's safe detail is shown below it.
  Retry appears only when `retryable`.
* **Partial failure:** a failed Home row shows its error in place and the others still render.
  The player overlays errors on the video, and its recovery text ("waiting for network",
  "switching to another version") comes from the playback layer.

## Responsive layouts

`WindowLayout.of(widthDp, isTv)` uses the Material breakpoints:

| Layout | When | Navigation | Cards |
|---|---|---|---|
| COMPACT | < 600 dp | bottom bar (Home, Library, Search, Favorites, Settings) | 112 dp min, 16 dp gutter |
| MEDIUM | 600–839 dp | navigation rail (all 8) | 136 dp |
| EXPANDED | ≥ 840 dp | permanent drawer | 160 dp |
| TV | leanback / TV UI mode | Compose for TV `NavigationDrawer` (expands on focus) | 196 dp, 48 dp overscan gutter, always dark |

* **Rotation and size changes:** `MainActivity` handles them in place, so the player keeps
  playing.
* **Switching shells:** the navigation graph is `movableContentOf`, so screens are kept.

## Keyboard, remote and touch

* **Focus:** every clickable has a visible focus border (`focusHighlight`). Cards, chips, rows,
  buttons, rail and drawer items are all reachable with D-pad / Tab and activated with
  Enter / centre (tested).
* **Initial focus:**
  * Details: the primary button;
  * Player: Play/Pause.
* **Player keys:** media play/pause/fast-forward/rewind and space work anywhere on the player
  screen.
* **Seeking:** the seek bar scrubs locally and seeks once on release.
* **Back:** the system or remote BACK key leaves screens. On TV the title bar has no back button,
  since the remote has one.
* **Touch:** standard Material targets, with no hover-only or focus-only controls.

## Accessibility

* **Cards:** each card is one merged node with a full description, e.g. "Vikram, 2022, 40 percent
  watched".
* **Headings and announcements:** section headers are headings. Empty and error messages are
  live regions: polite for empty, assertive for errors.
* **Labelled controls:**
  * Play/Pause has a content description that changes with the state;
  * the seek bar announces "1:02 / 22:00";
  * source switches say "Use Tamil Movies as a source";
  * icons in navigation are decorative, with the text label beside them.
* **Text:** all text is in string resources (translatable). Nothing depends on colour alone.

## Tests (22)

* **`:ui:core`:**
  * breakpoints, error mapping, state helpers;
  * `StateContent` in every state;
  * card accessibility, Enter / D-pad-centre activation and D-pad focus movement;
  * chips.
* **`:ui:screens`:**
  * pure mappers (tiles, details primary action, player state, account steps, source rows,
    browse query);
  * ViewModels with fake domain interfaces: Home sign-in → sources → rows, Search debounce and
    error, Account routing and error, Favorites;
  * Compose screens: Home, Details, Player (controls, error retry, next episode), sign-in,
    sources;
  * every layout's navigation;
  * typed routes with arguments, and "play next" replacing the player.

## Not done yet (by design: visual polish comes later)

* **Visuals:** artwork and posters (placeholders only), brand colours and typography,
  animations and transitions, a TV-style hero/featured row.
* **Player:** hiding system bars, auto-hiding controls, chapter/scrub thumbnails.
* **Focus:** restoring focus to the card you came from after BACK on TV.
* **Input:** search voice input, a TV on-screen keyboard tuned for D-pad.
* **Discovery:** catalog screens (the catalog domain exists; no screen yet).
* **Device testing:** all UI tests run on Robolectric; device and TV testing are still to do.

## Adding a screen

1. Add a `@Serializable` route in `Routes.kt` and, if top level, an entry in `TopLevel`.
2. In a new package, write a contract (`XxxUiState`, `XxxAction`) and a `@HiltViewModel` that
   only calls domain interfaces.
3. Write a stateless `XxxScreen(state, onAction, …)` from `:ui:core` components, using
   `StateContent` for its state.
4. Register `composable<XxxRoute>` in `AppNavHost`, wiring callbacks to `AppNavigator`.
5. Test the mapping (pure), the ViewModel (fakes) and the screen (Compose).
