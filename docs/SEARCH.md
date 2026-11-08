# Library search (Phase 10)

The library search runs entirely against the local database: no network, and it works offline.
It handles typos, transliterations, partial words, file names, chat names, and structured hints
in plain text ("tamil comedy", "ben 10 s2", "leo 2023 1080p"). It filters, sorts and pages, and
stays in the single-digit milliseconds for typed queries at 50,000 items.

```
 query text ─▶ QueryParser ─▶ words + soft filters (+ operators = hard filters)
                                   │
                    TermIndex.expand (library vocabulary: typos, transliterations)
                                   │
          ┌── words present ───────┴──────── no words ──┐
          ▼                                              ▼
 FTS4 groups  word* OR neighbours …  (AND)      SQL: each soft filter = condition OR word in text;
 + hard filters in SQL, ≤ 1 500 candidates      ORDER BY satisfied-count, sort; COUNT; LIMIT/OFFSET
          ▼
 Ranker (Kotlin): per-word best match × field weight, title bonuses, soft filters, priors
 → relaxed pass (≥ half the words) if nothing matched every word
          ▼
 sort, cache ranked list (8 queries), page slice, episode lookup ("s2e5")
```

| Piece | Module |
|---|---|
| `SearchText` (normalization), `Phonetic` (transliteration skeleton), `EditDistance` | `:search:core` |
| `TermIndex` (vocabulary, trigram + phonetic lookup, completions) | `:search:core` |
| `QueryParser`, models (`SearchRequest`, `SearchFilters`, `SoftFilter`, `SearchHit`, `SearchPage`), `Ranker` | `:search:core` |
| `SearchIndexer` (dirty queue → index rows, vocabulary), `LibrarySearchEngine` (`LibrarySearch`), Hilt `SearchModule` | `:search:engine` |
| DB v7: `search_doc`, `search_fts` (FTS4), `search_dirty`, `SearchTriggers`, `SearchDao` | `:core:database` |
| `LanguageCatalog` moved to `:core:language` (shared with track selection) | `:core:language` |

## 1. The index

**`search_doc`** has one denormalized row per library item, holding everything a search matches
or filters on:

| Kind | Columns |
|---|---|
| Text, normalized words (also indexed by `search_fts`, FTS4 unicode61) | `text_title` (title, normalized title, official title); `text_alt` (original title, library aliases/keys, joined compounds such as "spiderman", "ben10"); `text_files` (distinct words of the original file names, ≤ 300); `text_chats` (Telegram chat/channel names); `text_extra` (genres; language names in English and native script; provider) |
| Filters | year; `,ta,hi,`-style sets for languages (audio and original), genres, seasons, qualities (release source), chat ids, providers; max resolution; available count; hidden; rating; added time; size |

**Watched state is not indexed.** It is read live from `watch_state` and `library_item` (the
same rules as the library), so playback never touches the index.

**Freshness, by triggers.** Changes queue items in `search_dirty`:
- items, applied metadata, file links, languages and seasons;
- parsed release info;
- chat renames.

`SearchIndexer.flush()` rebuilds exactly those rows, in batches with one transaction each, before
every search. Results therefore never lag the library, and nothing has to remember to re-index.
The 6 → 7 migration queues every existing item. The index is rebuildable and holds no user data.

## 2. Normalization and matching

**`SearchText.normalize`** is used on both sides:
- lowercase, Latin accents folded (Pokémon → pokemon);
- `&` → "and", apostrophes removed;
- letters and digits split ("ben10" → "ben 10");
- all other characters become spaces;
- Indic and other scripts kept.

Stopwords ("the", "and", "of", …) are not required words: "tom jerry" = "Tom and Jerry".

| Matching | How |
|---|---|
| **Exact** | The word equals an indexed word (score 1.0) |
| **Prefix** | `word*` in FTS (0.75; 0.5 for 1–2 letters). Words under 4 letters and numbers prefix-match only titles: "ben" must not hit "bengali", "10" must not hit "1080" |
| **Token** | Every query word must match some word of the item (AND); order does not matter; extra words in the title are allowed |
| **Fuzzy** | `TermIndex` maps a query word to words that exist in the library. Trigram candidates are filtered by optimal-string-alignment distance: 0 edits up to 3 letters, 1 up to 6, 2 from 7 (0.7 for d = 1, 0.55 for d = 2). Expansions go into the FTS query, so retrieval stays index-based |
| **Transliteration** | `Phonetic.key` merges digraphs (th, dh, sh, ch, kh, bh, ph, zh → l …), collapses doubled letters and drops later vowels. Doraemon/Doremon, Shinchan/Sinchan, Chhota Bheem/Chota Beem, Kaththi/Kathi and Tamizh/Tamil collide on purpose (0.6). Words of ≥ 5 letters only |
| **Compounds** | Joined adjacent title words are indexed ("spiderman", "ben10"); split query compounds ("ben10") work via normalization |

**Field weights:** title 1.0, alternative/official title 0.85, chat name 0.5,
genre/language/provider words 0.45, file names 0.4.

## 3. Reading the query

`QueryParser` turns words into **soft filters**. Satisfying one ranks an item higher (+2; +3
for season/episode). Not satisfying it costs −1. A title containing the word still matches
("Tamil Padam", "2012").

| Words | Soft filter |
|---|---|
| `s2`, `season 2`, `s02e05`, `2x05`, `e12`, `episode 12` | season / episode; with both, the episode id is resolved for the result |
| `1900`–`2099` | year (the word also stays a title word) |
| `2160p`/`4k`/`uhd`, `1080p`/`fhd`, `720p`/`hd`, `480p`/`sd` | minimum resolution |
| `web-dl`, `webrip`, `bluray`/`bdrip`, `hdrip`, `dvdrip`, `hdtv`, `cam` … | release quality |
| Language names, English or native ("tamil", "हिन्दी"); full names only, since 3-letter codes collide with title words ("ben", "tom") | language |
| Genres that exist in the library, plus synonyms (cartoon → animation, funny → comedy, kids → family …) | genre |
| `movie(s)`, `film`, `series`, `show`, `tv` | kind |
| `unwatched`/`new`, `watched`/`seen`, `continue`/`resume` | watch state |
| `tmdb`, `cinemeta`, `unmatched` | metadata provider |

**Operators are hard filters:** `lang:ta`, `genre:comedy`, `year:2020`, `year:2018-2023`,
`season:2`, `res:1080`, `quality:web-dl`, `kind:series`, `provider:tmdb`, `watched:no|yes|partial`,
`in:<chat words>`. The same filters exist as `SearchFilters` for UI controls: kinds, languages,
genres, year range, season, minimum resolution, qualities, chat ids, providers (`none` =
unmatched), watch state, include hidden/unavailable. Hidden and unavailable items are excluded
by default.

## 4. Ranking

```
score = 10 · coverage · mean(best word match × field weight)
      + 5  if the title equals the query words      (else +2 if it starts with them)
      + 1.5 · (matched title words / title words)   (shorter, fully matched titles first)
      + 2 per satisfied soft filter (3 for season/episode), −1 per unsatisfied one
        (+1 instead when the filter word is in the title)
      + 0.3 files available + 0.2 matched metadata + 0.03 · rating
```

- **Coverage** must be 1: every word matches. If no item matches every word, a relaxed pass
  accepts items matching at least half of them.
- **Without words**, relevance is the number of satisfied soft filters, then most recently
  added.
- **Sorts:** relevance, title, year, recently added, recently watched, rating, size, each
  reversible.

## 5. Pagination

- **Text queries:** the ranked list (≤ 1 500 candidates) is cached per request, keyed by
  text + filters + sort + index version (LRU of 8). Pages are slices: < 1 ms.
- **Filter-only queries:** paged in SQL (`COUNT` + `LIMIT/OFFSET`, stable order with an id
  tie-breaker). The cost does not depend on page depth.
- Every `SearchPage` carries the total, the next offset, how the query was read (e.g.
  `"doremon" ≈ doraemon`, `tamil = Tamil (language)`, `season 2`) and the elapsed time.

## 6. Benchmark

```bash
./gradlew :search:engine:testDebugUnitTest --tests '*SearchBenchmarkTest*' -Psearch.benchmark
```

The default test run uses 2 000 items as a quick check. The report is written to
`search/engine/build/reports/search-benchmark.md`.

**Synthetic library:**
- random 1–3-word titles plus seeded real ones (Doraemon, Tom and Jerry, Ben 10, Chhota Bheem,
  Shinchan, Motu Patlu, Spider-Man, Vikram);
- 60% with metadata (1–3 genres, rating);
- 1–2 audio languages each;
- series with 1–5 seasons;
- 1–5 files each, with realistic release names, resolutions, qualities and 20 chats;
- 10% with watch progress.

Measured on Robolectric (JVM + native SQLite, in-memory), so phone numbers will differ. Each
query ran 25 times with the result cache defeated (full search every time). The relevance check
at scale passes: "doremon" → Doraemon, "tom jerry" → Tom and Jerry, "chota beem" → Chhota
Bheem, and "ben 10 s2" has Ben 10 in the top 3.


## 20,000 items, 43,069 files

- insert (fixture): 10687 ms; **index build: 3178 ms** for 20000 items (6292 items/s)
- vocabulary: 7,166 words, loaded in 164 ms, ~5 MB heap
- result pages 2–5 of a cached query: median 0.94 ms

| Query | p50 | p95 | max | results |
|---|---|---|---|---|
| exact title | 2.5 | 3.7 | 4.5 | 7 |
| prefix (typing) | 1.6 | 1.8 | 2.4 | 1 |
| typo | 2.8 | 5.7 | 6.7 | 7 |
| transliteration | 1.9 | 2.8 | 5.4 | 1 |
| two words | 2.1 | 2.6 | 3.0 | 1 |
| title + season | 2.8 | 3.8 | 5.2 | 1 |
| common words | 3.6 | 4.0 | 6.7 | 22 |
| file-name words | 31.7 | 37.9 | 45.7 | 1500 |
| filters only (soft) | 32.7 | 39.2 | 46.3 | 1470 |
| hard filters + sort | 12.3 | 13.7 | 15.3 | 220 |
| operators | 7.0 | 7.5 | 9.6 | 48 |
| no match | 1.5 | 2.0 | 2.1 | 0 |

## 50,000 items, 108,661 files

- insert (fixture): 23669 ms; **index build: 6444 ms** for 50000 items (7758 items/s)
- vocabulary: 7,712 words, loaded in 307 ms, ~6 MB heap
- result pages 2–5 of a cached query: median 0.61 ms

| Query | p50 | p95 | max | results |
|---|---|---|---|---|
| exact title | 2.2 | 4.0 | 5.6 | 14 |
| prefix (typing) | 1.5 | 1.7 | 1.8 | 1 |
| typo | 2.1 | 2.5 | 3.1 | 14 |
| transliteration | 1.8 | 2.2 | 2.7 | 1 |
| two words | 1.9 | 2.2 | 2.6 | 1 |
| title + season | 5.1 | 5.3 | 6.3 | 1 |
| common words | 6.7 | 7.5 | 11.6 | 60 |
| file-name words | 28.9 | 39.5 | 45.7 | 1500 |
| filters only (soft) | 74.0 | 105.6 | 118.1 | 3728 |
| hard filters + sort | 28.9 | 33.9 | 44.5 | 570 |
| operators | 18.6 | 22.3 | 24.8 | 136 |
| no match | 1.3 | 1.7 | 3.5 | 0 |


**Reading the numbers:**
- **Typed title queries** (exact, prefix, typo, transliteration, two words, title + season) take
  1.5–5 ms p50 at 20k *and* 50k items. They are index lookups plus ranking of a few candidates,
  so cost follows the number of *matches*, not the library size.
- **Broad queries** that match thousands of items rank at most 1 500 candidates, staying
  around 30–45 ms. Examples: file-name words like "tam hin 1080p", or filters only ("tamil
  comedy" = 3 728 matches at 50k: 74 ms p50, 106 ms p95). That is the most expensive kind:
  every match is counted and ordered in SQL.
- **Index build:** about 6 000–7 800 items/s, a one-time cost per change. Incremental updates
  touch only dirty items.
- **Vocabulary:** about 7 700 distinct words (6 MB) for 50k synthetic items. Real libraries have
  richer vocabularies. Lookup cost depends on trigram fan-out, not on item count.

## 7. Trade-offs and limitations

- **Denormalized rows** make queries a single table plus FTS, at the price of rebuilding a row
  when any of its inputs change. Triggers keep this exact; watch state is read live instead of
  rebuilt.
- **Set columns** use `LIKE '%,x,%'`, which scans the matching rows. Fine to 50k (see the
  filter-only numbers). Join tables with indexes would be the next step for much larger
  libraries.
- **The candidate cap** (1 500) bounds the work for very broad text queries. Items beyond the
  cap in FTS order are not ranked; narrowing the query or adding filters finds them.
- **Soft filters can surprise.** "tamil comedy" also shows items that are only Tamil or only
  comedy (ranked after those that are both). Use operators (`lang:ta genre:comedy`) for strict
  filtering.
- **No speech or content matching.** Search covers titles, metadata, file names and chat names
  as indexed. Captions and episode titles are not indexed yet.
- **Transliteration keys are heuristics** for romanized Indian titles. Unusual spellings beyond
  2 edits, or a different skeleton, are not found.
- **Ranking weights** were tuned on the test library and the spec examples, not on real usage
  data.
- **The library's own `SearchMedia`** (Phase 4 FTS) still exists. `LibrarySearch` is the new
  engine used by the debug Search tab; Stage 2 can switch the library UI over.
