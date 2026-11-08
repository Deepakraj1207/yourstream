# YourStream

An open-source Android media player for your own Telegram media, with a real media library:
indexing, metadata matching, catalogs, smart playback, search and watch history.

Status: **Phase 3 done**: Telegram connection layer, media discovery and indexing, and a
standalone filename parser (titles, episodes, languages, quality… with per-field confidence),
all with plain debug screens. No playback yet.

- Architecture, roadmap and interfaces: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
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
