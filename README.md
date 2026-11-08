# YourStream

An open-source Android media player for your own Telegram media, with a real media library:
indexing, metadata matching, catalogs, smart playback, search and watch history.

Status: **Phase 1 done**: Telegram connection layer (TDLib, login, session, chats) with a
plain debug screen. No media features yet.

- Architecture, roadmap and interfaces: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- Telegram API credentials, configuration and test steps: [docs/TELEGRAM_SETUP.md](docs/TELEGRAM_SETUP.md)

Quick start:

```bash
cp secrets.properties.example secrets.properties   # then fill in your api_id / api_hash
./gradlew test assembleDebug
```

Development happens in two stages: **Stage 1** builds all functionality behind plain debug
screens; **Stage 2** builds the final UI/UX.
