# Local media assistant (Phase 11)

An optional natural-language front end for the library. It runs **entirely on the device** and
needs **no cloud AI API and no model**: requests are read by a deterministic parser and turned into
ordinary local database queries.

```
User query ─▶ IntentParser ─▶ Interpretation ─▶ Planner ─▶ QueryPlan ─▶ LocalMediaAssistant ─▶ results + message
              (deterministic)   (intent, entities)          (pure)       (Room / search engine)
                    ▲
                    └─ optional IntentModel (on-device LLM), consulted only when the parser is unsure
```

| Module | Kind | Contents |
|---|---|---|
| `:assistant:core` | JVM | `IntentParser`, `Planner`, models (`Intent`, `Entities`, `Interpretation`, `QueryPlan`), `IntentModel` + `AssistantInterpreter` |
| `:assistant:engine` | Android | `LocalMediaAssistant` (`MediaAssistant`), `AssistantDao` queries (DB v7, no schema change), Hilt module |

## Example

`"unwatched Tamil movies"` →

```
intent   SEARCH
entities kinds={MOVIE}, languages={ta}, watch=UNWATCHED
plan     Search(SearchRequest(text="", filters(kinds=MOVIE, languages=ta, watch=UNWATCHED), sort=RECENTLY_ADDED))
message  "3 unwatched Tamil movies."
```

| Request | Intent | Entities / plan |
|---|---|---|
| show Tamil movies | SEARCH | kind MOVIE, language ta |
| find Doraemon episodes | EPISODES | series "doraemon" → every episode, in order |
| continue watching Ben 10 | CONTINUE_WATCHING | title "ben 10" → resume point, else the next episode |
| show unwatched comedy movies | SEARCH | kind MOVIE, genre comedy, unwatched |
| find movies from 2005 | SEARCH | kind MOVIE, year 2005–2005 |
| show Telugu movies I haven't watched | SEARCH | kind MOVIE, language te, unwatched |
| find season 2 episode 10 | EPISODES | S2E10 of every series that has it |
| what should I watch next? | RECOMMEND | in progress → next episodes → unwatched picks |
| play ben 10 season 2 | PLAY | first episode of season 2 |

## Intent detection

Intent cues are matched first, as whole phrases, and removed from the sentence:

* **RECOMMEND**: "what should I watch (next)", "recommend", "suggest", "surprise me", "I am bored", …
* **CONTINUE_WATCHING**: "continue (watching)", "keep watching", "resume", "where I left off", …
* **PLAY**: the request starts with "play", "put on" or "start watching".

Without a cue the entities decide. "episodes", or a season/episode on something that isn't a
movie, means **EPISODES**. Anything else is **SEARCH**.

## Entity extraction

The input is normalized first:

* lower case, with accents folded;
* contractions expanded ("haven't" → "have not");
* "season two" / "second season" → "season 2";
* "2010-2015" → "2010 to 2015".

Letters and digits are kept together ("s2e10", "1080p", "90s").

Recognised phrases are then *consumed* in a fixed order, longest first, so a word never feeds two
entities:

1. **Season / episode**: `s2e10`, `2x10`, `season 2`, `s2`, `episode 10`, `ep 10`, `e10`.
2. **Years**:
   * `between 2010 and 2015`, `from 2001 to 2003`, `2010-2015`;
   * `after 2020` (2021–), `since 2020`, `before 2000`;
   * `90s`, `the 2000s`;
   * `this year`, `last year`;
   * a single year.
3. **Watched state**:
   * unwatched: "I haven't watched/seen", "never watched", "not watched yet", "unwatched", "unseen";
   * in progress: "didn't finish", "half watched", "in progress";
   * watched: "already watched", "I've seen", "finished".
4. **Sort, limit, resolution**:
   * sort: "latest", "top rated", "oldest", "alphabetically", "biggest";
   * limit: "top 5", "10 movies";
   * resolution: "4k", "1080p", "in hd".
5. **Media type**: "movies", "films", "series", "tv shows", "web series", "episodes", "videos".
   Command verbs are stripped before this ("show me…"), so "show" is not read as a TV show.
6. **Language**: full language names in any script, through the shared `LanguageCatalog`.
   Short words are never languages, so "ben" in "ben 10" is not Bengali.
7. **Genre**: the genre lexicon plus synonyms:
   * "comedies", "funny" → comedy;
   * "cartoons" → animation;
   * "sci-fi" → science fiction;
   * "scary" → horror.
8. **Filler** (show, me, find, please, the, …) is dropped. Connectors are kept *inside* a title
   ("tom and jerry") but not at its ends.

Whatever remains is the **title**. It goes to the Phase 10 search engine, so typos and
transliterations still work: "doremon episodes" finds Doraemon.

Every recognised entity also leaves a human-readable note ("telugu → language Telugu",
"\"I have not watched\" → unwatched"), which the debug screen shows.

### Confidence

| Reading | Confidence |
|---|---|
| explicit intent cue | 0.9 |
| ≥ 2 entities | 0.85 |
| 1 entity | 0.75 |
| only leftover words (a plain title search) | 0.6 |
| nothing | 0.3 |

## Query plans and execution

`Planner` is a pure function from interpretation to plan:

* **Search**: a `SearchRequest`. The title words become the text and the entities become *hard*
  filters (watch state included). With no title, results sort newest first.
* **Episodes**: the series is resolved through the search engine (kind = SERIES, best hit).
  Episodes come from SQL with optional season, episode number (or absolute number), watch state
  and audio language, in season/episode order. If no series matches, the request falls back to a
  normal search and says so.
* **ContinueWatching**:
  * with a title: the in-progress unit (resume), otherwise the next episode of a series, otherwise
    "start from the beginning";
  * without a title: every in-progress unit, then started series with a next episode.

  The *next episode* is the one in progress, else the first unfinished one after the most recently
  finished episode, else the first unfinished one.
* **Recommend**:
  1. With no constraints in the request, the first picks are unfinished things: two in progress,
     then two next episodes.
  2. The rest are unwatched, available, non-hidden titles (filtered by any language, genre or type
     the user asked for). They are ranked by a **watch profile** built from the last 200 watched
     units: the shares of language (×2), genre (×1.5) and type (×0.5), plus rating/10. Recent
     viewing weighs more.
  3. Each pick carries a reason ("Telugu · action · ★ 7.6").
* **Play**: resolves one playable target, either a movie or an episode. For a series this is
  S/E as asked, the first episode of a season, or the next episode.

The message is built deterministically from the entities, for example "5 Tamil movies.",
"13 episodes of Doraemon.", "Next: Ben 10 S01E03." or "No unwatched comedy movies in your
library.".

## Optional on-device model

`IntentModel` is a single suspend function. It gets the text and the deterministic reading, and
returns JSON:

```json
{"intent":"search","title":null,"mediaType":"movie","languages":["telugu"],"genres":["comedy"],
 "yearFrom":2005,"yearTo":2010,"season":null,"episode":null,"watched":false,"inProgress":null,"sort":null,"limit":null}
```

**No model ships with the app and nothing requires one.** Hilt binds it with `@BindsOptionalOf`;
a build that provides an `IntentModel` gets it used automatically.

`AssistantInterpreter` rules:

1. **Deterministic first.** The model is consulted only when the parser's confidence is below
   0.8, i.e. bare words with at most one recognised entity.
2. **Bounded.** The call has a timeout (1.5 s by default). A timeout, an exception, no answer or
   invalid JSON all keep the deterministic reading, with a note saying why.
3. **Validated.**
   * JSON is extracted from prose and unknown keys are ignored.
   * Intents, kinds and sorts must be known values.
   * Languages are normalized through `LanguageCatalog` (unknown ones dropped).
   * Genres must be known genres.
   * Years must be in 1880–2100, seasons in 1–99, limits in 1–500.
4. **Merged, parser wins.** Every entity the parser recognised is kept, and the model only fills
   gaps. Leftover words are the model's to replace only when they were the parser's sole guess
   (a title-only reading).
5. The result is marked `source = MODEL`.

## Debug screen

The **Assistant** tab (Stage 1, unstyled) has:

* a text field and one-tap examples;
* the message, intent, confidence, source and timing;
* entities, the plan and the "read as" notes;
* the results, with open (library detail) and ▶ play.

## Tests

* `IntentParserTest` (7): every spec example, plus phrasings of watched state, year ranges and
  decades, season/episode forms, genres/languages/types, sort/limit/resolution, intents and titles.
* `PlannerAndModelTest` (5): plans; no model; a model filling gaps while the parser wins; a
  confident parse skipping the model; broken models (prose, bad JSON, null, crash, timeout) falling
  back.
* `LocalMediaAssistantTest` (11, Robolectric + in-memory Room): every spec example end to end on a
  fixture library, plus unwatched episodes, play resolution, the unknown-series fallback and the
  optional model wiring.
