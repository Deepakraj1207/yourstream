# YourStream

An open-source Android media player for your own Telegram media, with a real media library:
indexing, metadata matching, catalogs, smart playback, search and watch history.

Status: **Phase 5 done**: Telegram connection layer, media indexing, a standalone filename
parser, the local library (movies, series → seasons → episodes, versions, watch progress,
favorites, tracks) and provider-based external metadata (TMDB, Cinemeta) with confidence-scored
matching, all with plain debug screens. No playback yet.

- Architecture, roadmap and interfaces: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
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
