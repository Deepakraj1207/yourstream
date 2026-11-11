# Catalog providers (Phase 6)

Catalogs answer one question: **"which titles exist / are interesting?"** (trending, new OTT
releases, Tamil movies, Hindi-dubbed…). They are optional and replaceable. They never answer
"where can I watch it?" themselves: that is the user's own Telegram library, or the stream
add-ons the user installed, asked only when a title is opened (see [STREAMS.md](STREAMS.md)).

```
Catalog metadata  ≠  Media source  ≠  Playback
(this document)      (Telegram files,   (later phase)
                      the library)
```

- No catalog model has a URL, stream or file field (`CatalogItem`, `CatalogMetadata`,
  `CatalogPage`). Streams are a separate interface (`StreamProvider`, `AddonStream`), asked
  only for the opened title and only from add-ons that declare the `stream` resource for that
  type and id prefix. The `subtitles` resource is not used.
- A catalog title becomes watchable only when `LibraryAvailability` finds the same title in the
  library (IMDb id → TMDB id → normalized title + kind + year). That links to library items,
  which link to Telegram files. The library never depends on catalogs; with zero providers
  configured, or all of them down, Telegram media works exactly as before.

## 1. Provider architecture

```
 :catalog:api       CatalogProvider · ExternalMetadataFallback · LocalCatalogSource
 (JVM, no deps)     LibraryAvailability · models · CatalogLanguages
        ▲
 :catalog:stremio   StremioCatalogProvider: generic Stremio add-on protocol client
 (JVM, Ktor)        (manifest, catalog, meta only) · StremioAddonDefinition · id parsing
        ▲
 :catalog:indian    IndianCatalogs: INDIA_STREAMS and INDIAN_REGIONAL definitions
 (JVM)              (URLs, language/tag mapping, item filters), create(ids)

 :catalog:core      CatalogManager: availability, fallback chains, caching, health
 (JVM)              CatalogCache (+ in-memory) · CatalogCachePolicy
        ▲
 :catalog:sync      RoomCatalogCache · RoomLocalCatalogSource · RoomLibraryAvailability
 (Android, Hilt)    MetadataCatalogFallback (Phase 5 TMDB/Cinemeta) · CatalogModule

 :app               reads catalog.providers / catalog.customManifests and builds the
                    provider list (the only place that names concrete add-ons).
                    Debug "Catalogs" tab.
```

`CatalogProvider` (in `:catalog:api`):

| Operation | Method | Notes |
|---|---|---|
| availability + self-description | `describe()` | Fetches the manifest. Success means available |
| get catalogs | `describe().catalogs` → `CatalogManager.catalogs(type)` | Each `CatalogDescriptor` carries type, languages, tags and extras (paging, search, genre) |
| discover titles | `page(catalog, skip, genre)` → `CatalogManager.discover(request)` | `DiscoverRequest(language, type, tag, cursor)` |
| search | `search(query, type)` | `InvalidState` when the provider has no search catalog |
| get metadata | `metadata(ref)` | `InvalidState` when the provider has no `meta` resource or the id prefix is not served |
| supported languages | `CatalogProviderInfo.languages` → `CatalogManager.languages()` | Union over providers, plus all Indian languages (local-only when no provider offers them) |
| genres / categories | `CatalogProviderInfo.genres`, catalog `tags` → `CatalogManager.genres(type)` | Declared genre options + genres seen on cached pages; tags: trending, ott, new, dubbed, recommended |

Providers only translate their wire format into these models. Caching, availability tracking,
rate limiting and fallback all live in `CatalogManager`, so a new provider is either:

- **a Stremio-compatible add-on**: a `StremioAddonDefinition` (id, name, attribution, manifest
  URLs + mirrors, functions mapping catalog ids to languages/tags, item filter). No code.
  User-supplied add-ons use `StremioAddonDefinition.custom(manifestUrl)` via
  `catalog.customManifests`.
- **anything else**: implement `CatalogProvider` (4 methods).

The Hilt module (`CatalogModule`) receives `CatalogProviders(list)` from the app; the core never
references `:catalog:indian`.

### Configuration (outside source control)

`secrets.properties` / `local.properties` / Gradle property / environment (same lookup as the
other secrets, see `secrets.properties.example`):

```properties
# order = fallback order; "none" = no providers (local library only)
catalog.providers=indiastreams,indian_regional
# extra Stremio-compatible add-ons (catalog/meta only), comma-separated
catalog.customManifests=
```

Neither provider needs an API key. TMDB/Cinemeta fallback uses the Phase 5 settings
(`tmdb.apiKey`, `metadata.providers`).

## 2. Supported languages

`CatalogLanguages.ALL` (ISO codes as used by the library's audio-language detection):

| Code | Language | Native | Indian Regional Catalog (movies + series) | IndiaStreams |
|---|---|---|---|---|
| hi | Hindi | हिन्दी | ✔ (+ Hindi Dubbed catalog) | mixed lists |
| ta | Tamil | தமிழ் | ✔ | mixed lists |
| te | Telugu | తెలుగు | ✔ | mixed lists |
| ml | Malayalam | മലയാളം | ✔ | mixed lists |
| kn | Kannada | ಕನ್ನಡ | ✔ | mixed lists |
| bn | Bengali | বাংলা | ✔ | mixed lists |
| mr | Marathi | मराठी | ✔ | mixed lists |
| gu | Gujarati | ગુજરાતી | ✔ | mixed lists |
| pa | Punjabi | ਪੰਜਾਬੀ | ✔ | mixed lists |
| or | Odia | ଓଡ଼ିଆ | — (local library only) | — |
| as | Assamese | অসমীয়া | — (local library only) | — |
| ur | Urdu | اردو | — (local library only) | — |
| bho | Bhojpuri | भोजपुरी | — (local library only) | — |
| en | English | English | — | mixed lists |

A language with no provider still works as a filter over the local library (matched against
the files' audio languages and the applied metadata's original language). Unknown codes from a
future manifest are kept and shown by their code.

## 3. Manifest / API integration

Both integrations use the **public Stremio add-on protocol**: a JSON manifest plus
`/catalog/{type}/{id}[/{extra}].json` and `/meta/{type}/{id}.json`. No HTML is scraped.
Requests are rate limited (4/s per provider), use a `YourStream/<version>` user agent, and go
through the shared Ktor client with timeouts.

### IndiaStreams: `https://indiastreams.rdata.in/manifest.json`

Verified live (id `in.rdata.indiastreams`, v1.0.2):

- `resources: ["catalog"]` only: **no meta, no search, no streams**.
- 8 catalogs (movie + series): trending, Netflix/Prime, Hotstar/Zee5, recommended, and others.
  The lists mix languages. They are tagged `trending` / `ott` / `recommended`, with no language.
- Ids are IMDb `tt…` ids. `releaseInfo` is a JSON **number** (the parser accepts numbers and
  strings). `imdbRating` is a number; any primitive is accepted.
- `skip` is not supported (the add-on returns an empty body), so only the first page is
  fetched. `page(skip > 0)` returns an empty page without a request. The lists refresh about
  every 4 h, which sets the page TTL.

### Indian Regional Catalog: `https://indian-regional-catalog.vercel.app/manifest.json`

Listed at stremio-addons.net. Verified live (id `com.stremio.indianStreamCatalog`, v1.0.6):

- `resources: catalog, meta (idPrefixes tt, tmdb:, ir), stream`. The **stream resource is
  never used**.
- One catalog per language: movies `te hi ta ml kn bn mr gu pa`, series `<code>_series`, plus
  `hindi_dubbed` (tag `dubbed`, language hi) and `ott_releases` (tags `ott`, `new`). All
  catalogs support `skip` with pages of 20.
- Ids are `tt…` or composite `tmdb:<id>||Title||Year||type||poster||urlencoded-description`.
  `StremioIds.parse` extracts tmdb id, title, year, poster and description, so a listing is
  useful even without a meta call.
- `meta` works for composite ids. For `tt` ids it returns `[]`, which is mapped to `NotFound`
  (cached as a negative entry) so the chain moves on to TMDB/Cinemeta.
- An identical second instance
  (`https://83e20802dcf1-indian-streams.baby-beamup.club/manifest.json`) is configured as a
  **mirror**. The client fails over to it on transient errors (network, timeout, 5xx), never on
  not-found.
- The add-on injects promo entries (`ir:promo-unlock`, "🎬 New Hindi Dubbed Catalog!") into
  its lists. The definition's `includeItem` drops them. Paging still counts them, so no title
  is skipped.

### Tolerant parsing

Manifest `resources` may be strings or objects. Catalog extras use both `extra` and the legacy
`extraSupported`/`genres` forms. Unknown fields are ignored. Malformed entries are dropped one
by one; one bad item never fails a page.

## 4. Failure handling and fallback chains

### Availability detection

`refreshAvailability(force)` fetches each manifest (cached for 24 h unless forced):

| State | Meaning |
|---|---|
| `AVAILABLE` | Manifest fetched (live or fresh cache) |
| `STALE` | Live check failed, but a cached manifest exists. Catalogs are served from cache, flagged stale |
| `PAUSED` | Rate limited or circuit open (shared `ServiceHealth` from `:core:network`: back-off after repeated failures, honours `Retry-After`). The provider is skipped until the pause ends |
| `UNAVAILABLE` | Never reached / no manifest |
| `UNKNOWN` | Not checked yet |

Errors are values (`AppResult`): `Network`, `Timeout`, `RateLimited`, `Remote(status)`,
`NotFound`, `InvalidState` ("not supported"). `InvalidState` and `NotFound` never count as a
provider failure.

### Chains (each step only when the previous one fails or has nothing)

```
discover   provider A matching catalog ─▶ provider B matching catalog ─▶ local library ─▶ empty (Fallback)
search     providers with search ─▶ TMDB/Cinemeta search ─▶ local library FTS ─▶ empty
metadata   owning provider (if it serves meta for that id prefix)
             ─▶ other catalog providers by the same IMDb id
             ─▶ TMDB/Cinemeta (tmdb id → IMDb id; title search only if exactly one
                candidate has a near-identical title and the same year)
             ─▶ local library metadata
             ─▶ the listing itself (partial = true)
```

Every answer is `Sourced(value, source, fromCache, stale)`, where `source` is
`Provider(id) | ExternalMetadata(provider) | Local | Fallback`. `DiscoverResult.skipped` records
why earlier steps were skipped. The debug tab shows both. Library items (`provider = local`)
resolve their metadata locally first, with no network call.

The metadata fallback never guesses. Without ids, a title search is used only when exactly one
candidate has a near-identical title (similarity ≥ 0.95) and the same year. Anything else
returns `NotFound` and the partial listing is shown.

## 5. Caching

| What | Key | TTL | Notes |
|---|---|---|---|
| manifest | `manifest` | 24 h | Also the offline source of catalogs/languages (STALE) |
| catalog page | `page:{type}:{catalog}:{skip}` | 4 h | IndiaStreams refreshes ~4 h |
| metadata | `meta:{type}:{id}` | 7 d | |
| search | `search:{type}:{query}` | 24 h | |
| not found | same key, `payload = NULL` | 24 h | Negative caching (e.g. `[]` meta for tt ids) |

- Persisted in Room, table `catalog_cache(provider, cache_key, payload, fetched_at,
  expires_at)` (DB **v5**, auto-migration 4→5 that only adds the table; covered by
  `MigrationTest.v4ToV5…`). Catalog rows never reference library rows.
- **Stale-while-error**: an expired entry is still served (flagged `stale`) when the provider
  fails or is paused, so catalogs open offline after a restart.
- `purgeCache()` deletes entries that expired more than 30 days ago. It runs when the debug
  catalog screen opens.
- TMDB/Cinemeta answers are cached by the Phase 5 metadata cache, not here.

## 6. Limitations

- **Catalogs are information only.** Nothing from a catalog listing is played, downloaded or
  redistributed by this app. Built-in add-ons are used for catalogs and details only; streams
  come only from add-ons the user adds themselves (see [STREAMS.md](STREAMS.md)).
- **Terms.** Neither add-on publishes formal terms of use or a licence for its data. We use only
  their public, documented add-on endpoints, at a low request rate, with heavy caching and
  attribution shown next to their results. Posters and descriptions belong to their owners
  (TMDB, IMDb/RPDB image hosts, the add-on authors) and are only displayed, never re-hosted.
  If a provider asks us to stop, removing it from `catalog.providers` (or from
  `IndianCatalogs.KNOWN`) is enough.
- **Third-party, unofficial services.** Both are community add-ons hosted on free tiers
  (Vercel, BeamUp). Expect outages, rate limits and format changes. The app degrades to cached,
  then local results.
- IndiaStreams has no metadata, no search and no paging, and its lists are not separated by
  language. Language browsing therefore comes from the Indian Regional Catalog.
- Indian Regional Catalog has no search catalogs, so search falls through to TMDB/Cinemeta and
  then to the library.
- Odia, Assamese, Urdu and Bhojpuri have no provider catalog; they only filter the library.
- Library presence by title (`TITLE_YEAR`) is a heuristic. It is shown as such, and is reported
  only when exactly one library item fits.
- TMDB and Cinemeta are used here as metadata fallback, not as catalog providers (trending /
  discover lists from them were not part of this phase). They can be added later as further
  `CatalogProvider`s without touching the core.
- Genre filtering inside a catalog is supported by the client (`genre` extra), but neither
  current add-on declares genre options. Genres are therefore collected from cached pages.
- Network hosts must be reachable. In a restricted environment, the provider hosts above must
  be allowed.

## 7. Tests (mock provider responses, no network)

- `:catalog:indian` `IndianCatalogProvidersTest` (Ktor `MockEngine`, fixtures recorded from
  the real manifests): manifest parsing, numeric fields, no skip requests for IndiaStreams,
  InvalidState for unsupported operations, per-language catalogs, composite ids, paging
  (including dropped promo entries), meta for composite ids, `[]` → NotFound, mirror failover,
  streams never requested, custom definitions.
- `:catalog:core` `CatalogManagerTest`: availability (live, stale, paused), languages, discover
  paging, provider A → B fallback, all providers failing → local → fallback, caching and
  stale-on-error, rate-limit pause, tag/type filters, the 5-step metadata chain, search
  fallback, library presence.
- `:catalog:sync` `CatalogSyncTest` (Robolectric, in-memory Room): Room cache round trip and
  purge, offline restart served from the persisted cache, local source (language via audio or
  original language, type, paging, FTS search, hidden/unavailable excluded), presence by
  IMDb/TMDB/title (with year, ambiguity and kind checks), TMDB fallback by id and unambiguous
  title only, the end-to-end chain.
- `:core:database` `MigrationTest.v4ToV5…`: the migration keeps library, metadata and metadata
  cache data.
