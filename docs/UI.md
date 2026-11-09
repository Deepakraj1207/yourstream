# UI (Stage 2)

The main application UI on top of the frozen domain layer. Step 1 built the skeleton (navigation,
contracts, states); step 2 turned it into a polished media library. Every screen shows real data
from the existing repositories and use cases. There is no demo data: fixture titles appear only in
tests. It uses:

* Jetpack Compose and Material 3;
* Compose for TV for the TV navigation drawer;
* Navigation Compose with type-safe routes;
* Hilt view models;
* Paging 3 for every long list;
* Coil 3 for posters from matched metadata.

No Telegram, indexing or playback code was changed for it.

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
| `:ui:core` | Design system (brand theme, `Dimens`, typography, shapes); `UiState` / `UiError` / `EmptyKind`; state views and skeletons; artwork; poster/wide cards, rows, grids, hero; card menus; controls; `WindowLayout`; focus highlight | `core:common` only (for `AppError` → `UiError`) |
| `:ui:screens` | Routes, `AppNavigator`, `AdaptiveShell`, `AppNavHost`; one package per screen (contract + ViewModel + screen); `Presentation` mappers; `TileActionHandler`; `ArtworkLookup` | `:ui:core` and the domain API modules |
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

## Design system

* **Brand:** amber primary on deep ink, teal secondary. Dark and light schemes; TV is always dark.
* **`Dimens` per layout** (`LocalDimens`): gutter, poster width, wide-card width, hero height, row
  and card gaps, focus scale.

  | Layout | Gutter | Poster | Wide card | Hero | Focus scale |
  |---|---|---|---|---|---|
  | COMPACT | 16 | 116 | 248 | 280 | 1.0 |
  | MEDIUM | 24 | 140 | 300 | 340 | 1.0 |
  | EXPANDED | 32 | 160 | 336 | 400 | 1.03 |
  | TV | 48 | 168 | 320 | 360 | 1.08 |
* **Type:** heavier titles; TV text is scaled ×1.15 for ten-foot viewing.
* **Artwork:** `Artwork(url, title)` shows the matched poster via Coil. While it loads, when it
  fails and when there is no match, `TitleArt` shows a gradient picked from a hash of the title
  plus its initials. Initials are hidden where the title is already drawn over the art (wide
  cards, hero background).
* **Cards:**
  * `PosterCard` (2:3): badge (4K / 1080p / "New episode"), watched check, progress line.
  * `WideCard` (16:9): title, episode and "14 min left" over a scrim. Used by Continue watching.
* **Progress:** `ProgressLine` is one continuous 4 dp bar with no gap or stop dot.
* **Card actions:** long press, the Menu key or a screen reader's custom actions open the card
  menu: Details, add/remove favorite, mark watched/unwatched, remove from Continue watching. Touch
  uses a bottom sheet and TV a focusable dialog. `TileActionHandler` runs each action through
  `MediaRepository`, `WatchHistoryRepository` or `WatchIntelligence`; Room flows refresh the lists.
* **Hero:** `HeroBanner` shows the top Continue watching entry (or else the newest addition) with
  Resume/Play and More info/Episodes. It has a side poster on wider layouts.
* **Posters for non-paged sources:** Continue watching, the hero and search hits carry item IDs
  only. `ArtworkLookup` reads the poster with `MediaRepository.getItem`, using a 300-entry LRU
  cache in which misses expire after 5 minutes.

## Navigation

| Destination | Route | Reached from |
|---|---|---|
| Home | `HomeRoute` (start) | top level |
| Library | `LibraryRoute` | top level |
| Movies / Series | `MoviesRoute` / `SeriesRoute` | top level (rail/drawer/TV); Library kind filter on phones; Home → "See all" |
| Search | `SearchRoute` | top level |
| Continue watching | `ContinueWatchingRoute` | top level (rail/drawer/TV); Home → "See all" |
| Recently added | `RecentlyAddedRoute` | top level (rail/drawer/TV); Home → "See all" |
| Favorites | `FavoritesRoute` | top level; Home → "See all" |
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
| Home | `HomeUiState(banner, hero, continueWatching, libraryCount)` + paged rows (recently added, unwatched movies, series, favorites). `blocking` = full-screen sign-in / choose-chats / empty-library state, only when the library has no titles | `Retry`, `Tile(action)`; callbacks open / see all / sign in / choose sources | `WatchIntelligence.continueWatching/recentlyAdded`, `MediaRepository.recentlyAdded/favorites/count`, `MovieRepository.movies`, `SeriesRepository.series`, `TelegramAuthManager.state`, `MediaIndexRepository.observeChats`, `TelegramManager.connectionState` |
| Library, Movies, Series, Recently added, Favorites | `BrowseUiState(filters, controls, count)` + `items: Flow<PagingData<Tile>>`. `controls` says which filters a screen offers | `SetKind`, `SetSort`, `SetWatch`, `Tile(action)` | `MediaRepository.search/recentlyAdded/favorites/count`, `MovieRepository.movies`, `SeriesRepository.series` (`LibraryQuery`) |
| Details | `DetailsUiState(content: UiState<DetailsContent>)`. `primary`: Play / Resume(position) / Episode(label, resume) | `ToggleFavorite`, `Retry` | `MediaRepository.getItem/setFavorite`, `WatchIntelligence.summary` (incl. next-episode decision) |
| Episodes | `EpisodesUiState(seasons, selectedSeasonId, nextUp)` + `items: Flow<PagingData<EpisodeItem>>` | `SelectSeason`, `SetWatched` | `SeriesRepository.seasons`, `EpisodeRepository.episodes`, `WatchIntelligence.nextEpisode`, `WatchHistoryRepository.markWatched` |
| Search | `SearchUiState(query, results?, suggestions, interpretation)`. `results` = top result (when it clearly leads) + the rest + total; `null` = blank query (example chips) | `QueryChanged` (250 ms debounce), `PickSuggestion`, `Clear` | `LibrarySearch.search/suggest` |
| Continue watching | `ContinueWatchingUiState(items)` | `Tile(action)` (incl. dismiss), `Retry` | `WatchIntelligence.continueWatching/dismiss` |
| Player | `PlayerUiState(title, subtitle, status, position, duration, buffered, speed, quality, error, recovery, audio/subtitle options + selection, previous, next, resumedFrom, episodes)` + `episodeItems: Flow<PagingData<EpisodeItem>>` | `TogglePlay`, `Pause`, `SeekTo`, `SeekBy`, `ScrubStart`, `ScrubEnd`, `SetSpeed`, `SelectAudio`, `SelectSubtitle`, `SelectSeason`, `StartOver`, `DismissResume`, `Retry` | `TelegramPlaybackControllerFactory` (one controller per screen), `WatchHistoryRepository.progress`, `PlaybackSessionStore`, `EpisodeRepository.episode/nextEpisode/previousEpisode/episodes`, `SeriesRepository.seasons` |
| Settings | `SettingsUiState(signedInAs, enabledSources, languages)`, grouped Library / Playback / About / Advanced, with the app version | callbacks to sub-screens and developer tools | auth, user, index, `TrackPreferenceStore` |
| Account | `AccountUiState(step, busy, error)`. `AccountStep` mirrors `AuthState` | `Submit(text)`, `Resend`, `UseQr`, `LogOut` | `TelegramAuthManager` |
| Sources | `SourcesUiState(chats: UiState<List<SourceRow>>, hasMore, actionError)` | `Toggle`, `Rescan`, `LoadMore` | `TelegramChatRepository.chatList/loadMore`, `MediaIndexer.enableChat/disableChat/schedule`, `MediaIndexRepository.observeChats` |
| Languages | `LanguagesUiState(prefs)` | `PreferAudio`, `PreferSubtitles`, `SetSubtitleMode` | `TrackPreferenceStore` |

## Player

The player UI (`:ui:screens` `player/`) is separate from the playback engine
(`:playback:media3`): the ViewModel calls `TelegramPlaybackController` and maps its `PlaybackState`
and `TrackState`; the screen is stateless over a `PlayerView` surface (`useController = false`).
The Telegram data source is unchanged.

**Engine additions** (additive, no behaviour change): `TelegramPlaybackController.setSpeed`, and
`PlaybackState.speed` / `PlaybackState.format` (`StreamFormat`: video/audio MIME type, frame rate,
bitrate, channels, sample rate). In the library, `EpisodeRepository.previousEpisode` mirrors
`nextEpisode` (same watch order, files only).

| Feature | Touch (phone, tablet) | Remote / keyboard (TV) |
|---|---|---|
| Play/pause | Large centre button | Play/Pause in the bottom bar (initial focus); centre with controls hidden; media keys, space |
| Seek ±10 s | Centre buttons; double-tap left/right half (running total "+30 s") | Left/right with controls hidden; rewind/fast-forward buttons and media keys |
| Timeline | Tap to jump, drag to scrub; time bubble; one exact seek on release (`beginScrub`/`endScrub`) | Focused: left/right scrub 10 s, faster when held; seeks on centre or after a pause |
| Position / duration | "7:43" … "22:00" (with the speed when not 1×) | same |
| Speed | 0.5–2× panel | same |
| Audio / subtitles | Panels listing analysed tracks (language + label); "Off" for subtitles | same; the selected option takes focus |
| Quality | Badge "1080p · HEVC" in the top bar opens the info panel (resolution, codec, frame rate, bitrate, audio, size, file) | Badge plus an info button |
| Previous / next episode | Bar buttons | Bar buttons; media previous/next keys |
| Episode list | Bottom sheet with season chips; opens at the playing episode ("Now playing") | Side sheet, same |
| Up next | Card in the last 30 s; at the end it counts down 10 s and plays (Cancel stops it) | Same; "Play now" takes focus at the end |
| Resume | Resumes from the saved session or history; "Resumed from 12:34 · Start over" for 10 s | same |
| Buffering | Spinner in place of the play button; the engine's recovery text ("waiting for network") on top | Centre spinner; same text |
| Errors | Full overlay with the engine's reason, Try again (when retryable), Play next episode instead, Back | Same; BACK leaves |
| Controls | Tap toggles; hide after 4 s of playback (the accessibility timeout can extend it); stay while paused, buffering, ended, with a panel or an error | Any key shows them; BACK hides them while playing |
| System bars | Hidden (immersive) while the player is shown | n/a |

Codec names, resolution classes (cinemascope counts by width: 1920 × 800 is 1080p), bitrate and
speed labels are pure functions in `PlayerViewModel` (tested).

## Loading, empty and error states

* **Loading:** skeletons shaped like the content (hero, rows, poster or wide grids) with a soft
  pulse that follows the system animation scale. They are announced as "Loading". Paged grids
  append skeleton cards while the next page loads.
* **Empty:** `EmptyKind` decides the title, the message and the call to action.
  * `NOT_SIGNED_IN` → Sign in;
  * `NO_SOURCES` → Choose chats;
  * `LIBRARY_EMPTY`, `NO_RESULTS` (when filters or a query are set), `NOTHING_IN_PROGRESS`,
    `NO_FAVORITES`, `NO_EPISODES`, `NO_CHATS`.
* **Error:** `ErrorKind` decides the message: OFFLINE ("your library still works"), TELEGRAM,
  SIGNED_OUT, NOT_FOUND, SERVICE, INPUT, UNKNOWN. The domain's safe detail is shown below it.
  Retry appears only when `retryable`.
* **Counts:** browse screens show "240 titles" (plurals) from `MediaRepository.count`. An
  unfiltered, empty list shows only its empty state, with no count and no filters.
* **Partial failure:** a failed Home row shows its error in place and the others still render.
  The player overlays errors on the video, and its recovery text ("waiting for network",
  "switching to another version") comes from the playback layer.

## Responsive layouts

`WindowLayout.of(widthDp, isTv)` uses the Material breakpoints:

| Layout | When | Navigation | Cards |
|---|---|---|---|
| COMPACT | < 600 dp | bottom bar (Home, Library, Search, Favorites, Settings) | see `Dimens` |
| MEDIUM | 600–839 dp | navigation rail (all 9) | see `Dimens` |
| EXPANDED | ≥ 840 dp | permanent drawer | see `Dimens` |
| TV | leanback / TV UI mode | Compose for TV `NavigationDrawer` (expands on focus) | 48 dp overscan gutter, always dark |

* **Rotation and size changes:** `MainActivity` handles them in place, so the player keeps
  playing.
* **Switching shells:** the navigation graph is `movableContentOf`, so screens are kept.

## Keyboard, remote and touch

* **Focus:** every clickable has a visible focus border plus a slight scale on large screens and
  TV (`focusHighlight`). Rows and grids use `focusRestorer`, so moving back into a row lands on the
  card you left. Cards, chips, rows,
  buttons, rail and drawer items are all reachable with D-pad / Tab and activated with
  Enter / centre (tested).
* **Initial focus:**
  * Home: the hero's Resume/Play;
  * Details: the primary button;
  * Player: Play/Pause; a panel's selected option; "Play now" when an episode ends.
* **Player keys:** see [Player](#player).
* **Seeking:** the timeline scrubs locally and seeks once (release, centre or a pause).
* **Back:** the system or remote BACK key leaves screens. On TV the title bar has no back button,
  since the remote has one.
* **Card menu:** long press (touch), Menu key (remote/keyboard) or screen-reader actions.
* **Touch:** standard Material targets, with no hover-only or focus-only controls.

## Accessibility

* **Cards:** each card is one merged node with a full description, e.g. "Vikram, 2022, 40 percent
  watched". The card menu's entries are also exposed as custom accessibility actions.
* **Artwork** is decorative; badges, the watched check and progress are in the description.
* **Headings and announcements:** section headers are headings. Empty and error messages are
  live regions: polite for empty, assertive for errors.
* **Labelled controls:**
  * Play/Pause has a content description that changes with the state;
  * the timeline is a progress bar announcing "1:02 / 22:00", with a set-progress action;
  * player icons (seek, episodes, audio, subtitles, speed, quality) have labels; panel options are
    radio buttons;
  * source switches say "Use Tamil Movies as a source";
  * icons in navigation are decorative, with the text label beside them.
* **Text:** all text is in string resources (translatable). Nothing depends on colour alone.

## Tests (47)

* **`:ui:core` (8):**
  * breakpoints, error mapping, state helpers;
  * `StateContent` in every state;
  * card accessibility, Enter / D-pad-centre activation and D-pad focus movement;
  * chips;
  * card menu by long press, Menu key and screen-reader action;
  * title-derived placeholder artwork;
  * timeline: key scrubbing as one seek, centre to commit, tap, screen-reader set-progress.
* **`:ui:screens`:**
  * pure mappers (7): tiles and captions, details primary action, player state, player quality
    labels, account steps, source rows, browse query;
  * ViewModels with fake domain interfaces (5): Home sign-in → sources → hero and rows; Search
    debounce, top result and error; Account routing and error; browse filters reaching the domain
    query plus card actions reaching the repositories; artwork cache hits and expiring misses;
  * Compose screens (10): Home (blocking, hero, rows), browse count, skeleton and empty state,
    Search top result and count, Settings groups and version, Details, sign-in, sources, every
    layout's navigation, typed routes and "play next" replacing the player;
  * player (5): TV transport, panels and BACK; controls hiding while playing with remote seeks and
    media keys; touch double-tap, resume/start over and episode list; error retry/skip and the
    up-next countdown and cancel; up next near the end;
  * screenshots (12, Robolectric native graphics): phone home (dark), library (light), empty
    favorites, tablet home, TV home, TV settings, phone player, phone audio panel, phone player
    error, TV player buffering, TV episode list, TV up next. They are written to
    `ui/screens/build/screenshots/` for review.
* **Engine and library:** playback speed and stream format reported by the controller (Robolectric
  playback test); previous episode across seasons (library test).

## Not done yet

* **Visuals:** animated transitions between screens, backdrop (landscape) artwork, trailers.
* **Player:** scrub thumbnails, picture-in-picture, remembering the speed across episodes,
  subtitle styling.
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
