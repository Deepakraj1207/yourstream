# Watch intelligence (Phase 12)

The app knows **Series → Season → Episode → Watch progress**, and uses it to decide what to
show and what comes next. Everything is local: it is computed from the library database on
every change.

| Module | Kind | Contents |
|---|---|---|
| `:watch:core` | JVM, pure | `NextEpisodePolicy`, `WatchPolicies` (item status, completion, abandonment), `ContinueWatchingOrder`, models |
| `:watch:engine` | Android | `WatchIntelligence` / `RoomWatchIntelligence` (live lists, item facts), `WatchIntelligenceDao` |
| `:library` (existing) | | `WatchHistoryRepository.updateProgress` records progress; `CompletionPolicy` decides completion |

## What is tracked

| Fact | Where | Notes |
|---|---|---|
| Playback position | `watch_state.position_ms` per unit (movie: `episode_key = 0`, episode: its id) | Peeks under 30 s are not resume points; finished units reset to 0 |
| Completion percentage | derived: `position / duration`, 100 when completed | Unfinished is capped at 99 %. A series is (completed + partial fractions) / available regular episodes |
| Last watched time | `watch_state.last_watched_at`; per item = max over its units | |
| Watch count | `watch_state.play_count` (times finished); series = sum over episodes | Viewing sessions are counted separately (`watch_history`, a new session after a 30 min gap) |
| Completed episodes | `completed = 1` | Finished at ≥ 90 %, or within the last 3 min of anything over 20 min |
| Partially watched episodes | `completed = 0 AND position_ms > 0` | |
| Favorite content | `favorite` | |
| Abandoned content | derived, plus `watch_dismissal` (v8, user data) | See below |

DB v8 adds only `watch_dismissal (item_id, dismissed_at)`, as an additive auto-migration.
Existing progress, history and favorites are untouched; `MigrationTest` checks this.

## Next episode

`NextEpisodePolicy.decide(episodes)`.

**Watch order:**

1. regular seasons, ascending;
2. season-less ("absolute") numbering;
3. specials last.

**The anchor** is the most recently watched regular episode. When several share a time (bulk
"mark watched"), the later one is the anchor. Old partial episodes don't steer.

**Rules, in order:**

1. **The anchor is unfinished:** resume it.
   S01E05 = 100 %, S01E06 = 35 % → **S01E06** (resume at 35 %).
2. **The anchor is finished:** take the first *unfinished, available* episode after it.
   S01E06 = 100 % → **S01E07** (from the start).
   * If that episode has a position, it is resumed.
   * Unavailable episodes are skipped and reported ("S01E07 missing → S01E08").
   * Season finale → next season premiere.
   * It is `NEW_EPISODE` when the file arrived after the user watched the anchor (caught up,
     then a new episode was added).
3. **A re-watch is under way** (the anchor was finished ≥ 2 times and everything after it is
   done): the next episode not seen since (`REWATCH`).
4. **Otherwise:** the first unfinished episode anywhere (`EARLIER_GAP`), or `SERIES_FINISHED`.

**Never watched:** the first available episode (`NOT_STARTED`).

**Specials:** a special in progress that was watched last is resumed. Finished specials never
move the pointer.

**Examples** (tested):

| History | Next |
|---|---|
| E01–E05 done, E06 35 % | resume E06 |
| E01–E06 done | E07 |
| E03 at 40 % long ago, then E04–E06 done | E07 (stale partial ignored) |
| E01–E05 done, then re-watched E02 | E06 |
| E06 half watched, then re-watched E05 | resume E06 |
| finished series, re-watching: E01 done again | E02 (REWATCH) |
| finished series, E04 added later | E04 (NEW_EPISODE) |
| E01, E03, E04 done | E02 (EARLIER_GAP) |

## Lists

All lists are `Flow`s. They re-emit when watch state, history, favorites, dismissals, items or
episodes change. Hidden items never appear.

* **Continue Watching**:
  * contents:
    * movies and videos in progress;
    * plus, for every started series, its next episode (resume or start).
  * excluded: finished items and abandoned content.
  * order: most recent activity first. A new episode counts from its arrival, so a series
    you'd caught up on rises to the top when a new episode lands. At equal activity, a resume
    ranks above a fresh start.
* **Next Episode**: `nextEpisode(seriesId)` gives the decision with reason, anchor and skipped
  episodes. It is also in every series `WatchSummary`.
* **Recently Watched**: one row per item, newest first. It carries the last unit (episode),
  that unit's percentage and the item summary.
* **Recently Added**: newest arrivals first (`latest_added_at`, so new files or episodes
  refresh an item), optionally by kind. Started series show the number of unwatched episodes
  that arrived after the user last watched.
* **Favorites**: favorite items with their watch summary, including the next episode.
* **Abandoned**: started, unfinished items in any of these states:
  * **DISMISSED**: "Remove from Continue Watching", until it is played again;
  * **INACTIVE**: idle for 30 days (movie) / 60 days (series);
  * **DROPPED_EARLY**: idle for 14 days after stopping early (under 15 % of a movie, or at most
    one episode of a longer series).

  Finished items are never abandoned. `restore` clears a dismissal. Thresholds live in
  `WatchPolicy`.

`summary(itemId)` returns every fact about one item:
* status (UNWATCHED, IN_PROGRESS, COMPLETED, ABANDONED) and percentage;
* position and duration, last watched, watch count, sessions;
* completed, partial and total episodes;
* favorite, abandonment and next episode.

`episodes(seriesId)` gives per-episode progress in watch order.

## Cost

* Each list computation runs one item query plus one episode query per 500 started series.
* Continue Watching and Abandoned consider the 500 most recently watched items.
* Next-episode decisions are O(episodes) per series and pure.

## Debug screen

The **Watch** tab (Stage 1, unstyled):

* **Lists:**
  * Continue (▶ play, **+25 %** simulates the player, ✕ dismiss);
  * Recently watched, Recently added, Favorites;
  * Abandoned (restore).
* **Detail view**, opened by tapping an item:
  * all tracked facts and the next-episode decision with its reason;
  * per-episode progress with ▶, +25 % and ✓ / unwatch;
  * favorite and dismiss toggles.

## Tests

* `NextEpisodePolicyTest` (10): the spec example, plus:
  * season boundaries and finishing;
  * stale partials, going back, re-watching;
  * new episodes, missing files, gaps and out-of-order viewing;
  * specials, absolute numbering, bulk marks;
  * labels and percentages.
* `WatchPoliciesTest` (3): movie and series status, counts, abandonment kinds, list ordering.
* `WatchIntelligenceTest` (7, Robolectric + Room, driven through the real
  `RoomWatchHistoryRepository`):
  * spec example S01E06 → S01E07;
  * mixed Continue Watching;
  * a new episode rising to the top;
  * dismiss and abandonment over time;
  * every tracked fact;
  * recents and favorites;
  * live flows.
* `MigrationTest.v7ToV8…`: progress and favorites survive the migration.
* The assistant (Phase 11) now uses the same `NextEpisodePolicy`.
