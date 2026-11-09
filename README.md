# YourStream

An open-source Android media player for your own Telegram media, with a real media library:
indexing, metadata matching, catalogs, smart playback, search and watch history.

Status: **Phase 10 done**: Telegram connection layer, media indexing, a standalone filename
parser, the local library (movies, series → seasons → episodes, versions, watch progress,
favorites, tracks), provider-based external metadata (TMDB, Cinemeta) with confidence-scored
matching, and optional Indian-language catalog providers (IndiaStreams, Indian Regional
Catalog) for discovery, and Telegram media playback with Media3 that streams while downloading
(seeking jumps the download to the new position) with network-aware adaptive buffering, smart
seeking and next-episode prefetch under strict download limits, and preference-based audio/subtitle
selection remembered per movie and series, and a fast offline library search (fuzzy, transliteration-aware,
filters, sorting, paging) with an offline natural-language assistant ("show Telugu movies I haven't
watched", "what should I watch next?") that needs no AI service, and watch intelligence (continue watching, automatic next episode, recently
watched/added, favorites, abandoned content), all with plain debug screens and a minimal
test player. Catalogs are information only; what you can watch is always your own Telegram media.

- Architecture, roadmap and interfaces: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- UI skeleton (navigation, screen contracts, states, layouts, TV/remote, accessibility): [docs/UI.md](docs/UI.md)
- Functional audit (checklist, bugs, performance, recommendations): [docs/AUDIT.md](docs/AUDIT.md) · interfaces: [docs/API.md](docs/API.md)
- Robustness and diagnostics (failure handling, database safety, crash/safe mode, recovery, diagnostics screen): [docs/ROBUSTNESS.md](docs/ROBUSTNESS.md)
- Watch intelligence (progress, next-episode rules, continue watching, abandonment): [docs/WATCH.md](docs/WATCH.md)
- Local assistant (intents, entities, query plans, optional on-device model): [docs/ASSISTANT.md](docs/ASSISTANT.md)
- Library search (matching, query interpretation, ranking, benchmark): [docs/SEARCH.md](docs/SEARCH.md)
- Audio & subtitle tracks (detection, preferences, fallback, memory, external subtitles): [docs/TRACKS.md](docs/TRACKS.md)
- Adaptive streaming (algorithms, trade-offs, benchmarks): [docs/ADAPTIVE_STREAMING.md](docs/ADAPTIVE_STREAMING.md)
- Playback engine (Media3, Telegram streaming, seeking, buffering, errors, metrics): [docs/PLAYBACK.md](docs/PLAYBACK.md)
- Catalog providers (architecture, languages, add-on integration, fallback, caching): [docs/CATALOGS.md](docs/CATALOGS.md)
- External metadata (providers, matching, caching, failures): [docs/METADATA.md](docs/METADATA.md)
- Library (relationships, indexes, queries, migrations): [docs/LIBRARY.md](docs/LIBRARY.md)
- Filename parser (patterns, confidence, limitations): [docs/PARSER.md](docs/PARSER.md)
- Media indexing design (schema, algorithm, duplicates, performance): [docs/INDEXING.md](docs/INDEXING.md)
- Telegram API credentials, configuration and test steps: [docs/TELEGRAM_SETUP.md](docs/TELEGRAM_SETUP.md)

Quick start:

```bash
cp secrets.properties.example secrets.properties   # then fill in your api_id / api_hash
./gradlew test assembleDebug
```

Development happens in two stages: **Stage 1** builds all functionality behind plain debug
screens; **Stage 2** builds the final UI/UX.
