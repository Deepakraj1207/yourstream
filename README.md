# YourStream

An open-source Android media player for your own Telegram media, with a real media library:
indexing, metadata matching, catalogs, smart playback, search and watch history.

Status: **Phase 7 done**: Telegram connection layer, media indexing, a standalone filename
parser, the local library (movies, series → seasons → episodes, versions, watch progress,
favorites, tracks), provider-based external metadata (TMDB, Cinemeta) with confidence-scored
matching, and optional Indian-language catalog providers (IndiaStreams, Indian Regional
Catalog) for discovery, and Telegram media playback with Media3 that streams while downloading
(seeking jumps the download to the new position), all with plain debug screens and a minimal
test player. Catalogs are information only; what you can watch is always your own Telegram media.

- Architecture, roadmap and interfaces: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
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
