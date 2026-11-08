# External metadata (Phase 5)

```
Telegram file name ─► :media:filename parse ─► :library item (title, year, kind, languages, title confidence)
                                                    │
                       :metadata:sync  LibraryMetadataService ── MatchInput
                                                    │
                       :metadata:core  MetadataMatcher ── MetadataProviderManager ──► MetadataProvider (interface)
                                        (score, decide)   (order, fallback, cache,      ├── :metadata:tmdb      TmdbProvider
                                                           rate limits, circuit)        ├── :metadata:cinemeta  CinemetaProvider
                                                                │                       └── … any new provider: one class
                                                    Room: metadata_cache, metadata_match, item/season/episode_metadata
```

| Module | Contents |
|---|---|
| `:core:network` | Ktor client factory (OkHttp engine, timeouts, lenient JSON), `getJson` with error mapping, token-bucket `RateLimiter` |
| `:metadata:api` | `MetadataProvider`, models (`MetadataRef`, `MetadataCandidate`, `MovieMetadata`, `SeriesMetadata`, `SeasonMetadata`, `EpisodeMetadata`, `CastMember`), capabilities |
| `:metadata:core` | `MetadataProviderManager`, `ProviderHealth`, `MetadataCache` (+ in-memory), `TitleSimilarity`, `MatchScorer`, `MetadataMatcher` |
| `:metadata:tmdb`, `:metadata:cinemeta` | The two providers |
| `:metadata:sync` | `RoomMetadataCache`, `LibraryMetadataService`, `MetadataRepository`, use cases, sync trigger + periodic worker, Hilt wiring |

## Provider interface

```kotlin
interface MetadataProvider {
    val id: ProviderId                     // "tmdb", "cinemeta": stored with every match
    val displayName: String
    val capabilities: Set<ProviderCapability>  // SEARCH_MOVIES, SEARCH_SERIES, MOVIE_DETAILS, SERIES_DETAILS,
                                                // SEASON_DETAILS, EPISODE_DETAILS, FIND_BY_IMDB_ID, CAST, IMAGES
    val isConfigured: Boolean              // false → skipped (e.g. no TMDB key)
    suspend fun search(query: MetadataQuery): AppResult<List<MetadataCandidate>>   // title, year?, type?, language?
    suspend fun movie(id, language): AppResult<MovieMetadata>
    suspend fun series(id, language): AppResult<SeriesMetadata>
    suspend fun season(seriesId, n, language): AppResult<SeasonMetadata>
    suspend fun episode(seriesId, s, e, language): AppResult<EpisodeMetadata>
    suspend fun findByImdbId(imdbId, type): AppResult<MetadataRef>
}
```

Provider contract:
- **Providers only translate HTTP to models.** They do no caching, retries or fallback.
- **Errors are values:** `NotFound`, `RateLimited(retryAfter)`, `NotConfigured` (missing or rejected key), and `Network` / `Timeout` / `Remote(status)`.

What each model carries: title, original title, overview, poster and backdrop URLs, genres, release / first-air date, year, original and spoken languages, runtime, rating, cast (name, character, photo), directors, seasons, and episodes (title, overview, air date, still image).

**Choosing providers is configuration, not code.** `metadata.providers=tmdb,cinemeta` sets the order. Adding a provider means writing one class and adding one map entry in `MetadataModule`.

| Provider | Key | Notes |
|---|---|---|
| TMDB | `tmdb.apiKey` (v3) or `tmdb.readToken` (v4 bearer) | Movies, TV, seasons, episodes, cast, images, `/find` by IMDb id. Client-side limit 20 req/s |
| Cinemeta | none | Stremio metadata add-on, IMDb ids. Seasons/episodes come from the series' `videos`. Only catalog and meta resources are used, never streams. Limit 5 req/s |

## Matching algorithm

`"Doraemon.S02E15.Tamil.720p"`:
1. The parser gives title **Doraemon**, season 2, episode 15, Tamil, 720p. The library holds it as SERIES `Doraemon` → season 2 → episode 15, with language `ta`.
2. The matcher builds `MatchInput(titles = [Doraemon (+ "aka" titles)], year = null, type = SERIES, languages = [ta], titleConfidence = 0.90)`.
3. **Queries:**
   - title + year (when there is a year);
   - title alone;
   - each alternative title.

   They are sent per provider, in configured order, through the manager (so they are cached and rate-limited). The search stops as soon as one candidate is acceptable.
4. Every candidate is scored (below). Candidates are pooled across queries, and an ambiguity check runs among each provider's answers.
5. **Decision:**
   - ≥ 0.80 → **ACCEPTED**: details are fetched and stored. For series, metadata is also fetched for each numbered season the library has, and its episodes are filled in by number.
   - ≥ 0.55 → **NEEDS_REVIEW**: candidates are stored, **nothing is applied**.
   - Below that → **NO_MATCH**.
   - Every provider failed → **FAILED**, retried later (never recorded as "no match").
6. In the example, TMDB returns "Doraemon" (2005, popularity 90) and "Doraemon" (1979, popularity 5). The 2005 series is far more popular (×3 or more), so it is not ambiguous and is accepted at about 0.83.

**Title similarity** (`TitleSimilarity`):
- **Normalisation:** lower-case; Latin accents folded; `&` → and; apostrophes removed; punctuation becomes spaces; number words and Roman numerals become digits; a leading article is dropped.
- **Measures, best one wins:**
  - exact match → 1.0;
  - same letters with spacing ignored → 0.98 (`K.G.F` = `KGF`);
  - squared character edit ratio (typos stay high; short different words like Doraemon/Pokemon drop);
  - token Jaccard;
  - containment, with a bonus when the shorter title is the start of the longer one (`Dune` ⊂ `Dune: Part One`).

## Confidence system

| Evidence | Weight | Values |
|---|---|---|
| title | 0.60 | best similarity over our titles × the candidate's titles (title, original, alternatives) |
| year | 0.20 | movie: same 1.0, ±1 0.75, ±2 0.3, else 0 · series: started by our year 0.7, later 0.2 · unknown 0.5 |
| type | 0.07 | movie/series as expected |
| language | 0.08 | candidate's original language is among our files' languages 1.0, else 0.4 (dubs are normal), unknown 0.5 |
| rank | 0.05 | provider's own order: 1st 1.0, 2nd 0.7, rest 0.4 |

How the score is adjusted:
- **Input reliability:** the score is multiplied by `0.75 + 0.25 × parser title confidence`, so a shaky parse needs stronger evidence.
- **Hard caps:**
  - title similarity < 0.6 → score ≤ 0.5 (can never be accepted);
  - movie years more than 2 apart → ≤ 0.55.
- **Ambiguity:** every candidate within 0.05 of the best, unless clearly less popular (×3) or the same IMDb title, loses 0.12. All of them are penalised, so their order is kept. Penalising only the leader would let the runner-up be accepted, which a test caught.
- **Thresholds:** accept 0.80, review 0.55 (`MatchThresholds`).

Each candidate keeps its `ScoreBreakdown` (title / year / type / language / rank / reliability / penalty / cap), which is stored and shown in the debug review list.

User decisions:
- **MANUAL** (confirm a candidate) and **REJECTED** (no metadata) lock the item and are never revisited automatically.
- **Edits re-match:** if the item's title, year or kind changes (user edit, better parse, merge), the stored input signature no longer matches and the item is re-matched.

## What is stored (Room v4, additive migration 3 → 4)

| Table | Contents |
|---|---|
| `metadata_match` (per item) | status, **provider id**, **external id**, type, IMDb id, **confidence**, top candidates with breakdowns, input signature, attempts, matched_at, **updated_at**, next_attempt_at, last_error |
| `item_metadata` (per item) | provider, external id, IMDb id, title, original title, overview, poster, backdrop, genres, release date, year, original language, runtime, rating, cast (JSON), directors, series status, **fetched_at** |
| `season_metadata`, `episode_metadata` | name/title, overview, poster/still, air date, runtime, rating, fetched_at |
| `metadata_cache` | provider, request key, payload (NULL = "not found"), fetched_at, expires_at |

Effects on the library:
- Library list rows now carry `posterUrl` and `metadataTitle`.
- The official and original titles are added to the item's search text, so searching "kgf chapter 2" finds a file named `K.G.F.2`.

## Caching

- **Every provider call goes through the manager's cache, keyed by provider and request:**
  - `search:<type>:<normalised title>:<year>:<lang>`
  - `movie:<id>:<lang>`
  - `series:…`, `season:…`, `episode:…`
  - `find:<imdb>`
- **Freshness (TTLs):** search 7 days; movie/series 30 days; season/episode 14 days.
- **Not-found answers are cached** for 2 days, so missing titles are not re-requested on every sync.
- **Persistent:** `RoomMetadataCache` stores entries in SQLite, so restarts don't re-fetch (tested).
- **Request de-duplication:** identical concurrent requests share one network call (tested with 20 parallel searches → 1 call).
- **Stale fallback:** when a fresh fetch fails, an expired entry is returned instead of an error. The periodic worker purges entries only a week after they expire, keeping a buffer for outages.
- **Matching runs only when needed:** for new items, changed inputs, or a due retry. Accepted items are never re-searched; their details are refreshed after 30 days, and the cache usually answers that refresh.

## API failure handling

| Situation | Handling |
|---|---|
| 429 Too Many Requests | `RateLimited(Retry-After)`. The provider cools down for that long and the manager uses the next provider meanwhile. Client-side token buckets keep this rare |
| 3+ consecutive network / timeout / 5xx errors | circuit opens for 30 s, doubling up to 15 min; any success closes it |
| 401/403 (bad key) | `NotConfigured`: provider disabled for an hour; others continue |
| 404 | `NotFound`: cached as a negative answer; not a provider failure |
| malformed JSON | `Unknown` error for that call; counts as a failure |
| every provider failing | stale cache if any, else the item is marked `FAILED` and retried after 15 min, doubling to 1 day |
| nothing plausible | `NO_MATCH`, retried after 7 days, doubling to 60 days (catalogues grow) |
| details provider down | another provider is asked via the IMDb id (`findByImdbId`) |

Nothing throws for expected failures: errors are `AppResult.Err` all the way up.

## Configuration and keys

The values live in `secrets.properties` or `local.properties` (both git-ignored), or in environment variables for CI. They are compiled into BuildConfig in `:app` only.

```properties
tmdb.apiKey=…                 # or TMDB_API_KEY   (themoviedb.org → Settings → API)
tmdb.readToken=…              # or TMDB_READ_TOKEN (alternative to apiKey)
metadata.providers=tmdb,cinemeta
metadata.language=en
```

- **Without a TMDB key**, TMDB reports itself unconfigured and Cinemeta is used alone.
- **Keys are never logged** (`TmdbConfig.toString()` prints only set/missing).
- **Before publishing a build:** TMDB's terms require the notice "This product uses the TMDB API but is not endorsed or certified by TMDB" plus their logo in the app's About screen. That belongs to the Stage 2 UI.

## Tests

| Suite | What it covers |
|---|---|
| `TitleSimilarityTest` | normalisation equivalences, dotted acronyms, typos, partial titles, different titles, non-Latin scripts |
| `MatchingTest` (11) | the brief's Doraemon example; exact title + year accepted; same-title ambiguity → review (both penalised); year disambiguates; low confidence never accepted; unreliable parse lowers confidence; language preference; alternative titles; weak provider → next provider; nothing found; outage → FAILED |
| `ProviderManagerTest` (13) | fallback on error and on empty results; unconfigured/incapable skipped; caching incl. not-found; expiry; stale-on-error; 429 cool-down and recovery; circuit breaker; bad key; details via IMDb id; concurrent de-duplication; total failure |
| `TmdbProviderTest` (8), `CinemetaProviderTest` (3) | Ktor MockEngine: request parameters, auth (key vs bearer, nothing sent without a key), JSON mapping, images, credits, seasons/episodes, 429/401/404/5xx/bad JSON mapping |
| `LibraryMetadataServiceTest` (9) | Robolectric + Room: accept + store; series season/episode metadata; review → confirm (MANUAL, locked); reject; outage retry; no-match backoff; Room cache across restarts; title edit → re-match; no providers → no work |
| `MigrationTest` | v3 → v4 keeps library data and favorites, and queues existing items for matching |

## Debug UI

In the **Library** tab:
- a "match metadata" button, per-status counts, and provider health;
- on an item page:
  - match status, provider:id and confidence, plus the applied metadata (title, year, genres, language, runtime, rating, IMDb id, overview, poster/backdrop URLs, cast);
  - the candidate list with score breakdowns and a **Use this** button each;
  - **Rematch**, **Reject**, and a "search metadata as…" box.
