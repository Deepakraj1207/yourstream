# Media filename parser (Phase 3)

Module `:media:filename`, package `dev.yourstream.media.filename`. Pure Kotlin with **no
dependencies** besides the standard library. It knows nothing about Telegram, Room or Android,
so it can be improved, tested and versioned on its own.

```kotlin
val m = MediaFilenameParser.parse(
    "Doraemon.S03E12.Tamil.720p.mkv",
    ParseHints(caption = message.caption, chatTitle = chat.title), // optional
)
m.title       // Field(value="Doraemon", confidence=0.90, raw="Doraemon", source=FILE_NAME)
m.season      // Field(3, 0.98, "S03E12")
m.episode     // Field(12, 0.98, "S03E12")
m.languages   // [Field(Language("ta","Tamil"), 0.85, "Tamil")]
m.resolution  // Field(720, 0.98, "720p")
m.original    // "Doraemon.S03E12.Tamil.720p.mkv": never modified
```

## Architecture

```
original name ──► Preprocessor ──► Scan + Detectors ──► Analyzer (resolver) ──► MediaFilenameParser
                  · extension,       · ordered rules,     · pick the year         · caption / chat-title
                    split part         each claims the      among candidates        fallback for generic
                  · links, @handles,   characters it      · find the title end      names
                    sites, sizes,      matched (no text   · language inside the   · merge sources
                    emoji removed      read twice)          title? keep/drop      · kind + overall
                  · [Group] / [CRC]  · rule failures are  · trailing number =       confidence
                  · separators → ' '   isolated             absolute episode?     · never throws
                                                          · title normaliser
```

| File | Responsibility |
|---|---|
| `MediaFilenameParser` | Public entry point, fallbacks, merging, kind, overall score, error safety |
| `FilenameMetadata` | Result model: `Field<T>(value, confidence, raw, source)` per field |
| `FileExtensions` | Extension tables; split suffixes (`.mkv.001`, `.part2.rar`) |
| `internal/Preprocessor` | Cleans the name into scannable text; records parenthesised years and bracket groups |
| `internal/Scan` | Text + claimed-character map + findings |
| `internal/Detectors` | ~20 ordered rules: episodes, dates, resolution, source, service, codecs, HDR, audio, subtitles, dub, languages, editions, flags, parts, years, group |
| `internal/Analyzer` | Resolves findings into fields and scores them |
| `internal/TitleNormalizer` | Display title (casing, junk words) and matching key (folded, lower-case) |
| `internal/Vocabulary` | 36 languages (names, abbreviations, native scripts, ISO codes), streaming services |

Integration:
- `:media:parser`'s `ParsedMediaMapper` converts `FilenameMetadata` into the index's `ParsedMedia`.
- `media.file_name` stores the Telegram name **byte for byte** (even when empty). The metadata goes to `media_parsed`, with per-field confidences in `field_confidence`.
- When `MediaFilenameParser.VERSION` increases, `ReparseWorker` recomputes `media_parsed` from the stored names. No Telegram access is needed.

## Supported patterns

| Field | Recognised forms (case-insensitive) | Confidence |
|---|---|---|
| Season + episode | `S01E05`, `s1e5`, `S01 E05`, `S01.E05`, `S1 EP5`, `S01E05E06`, `S01E05-E07`, `S01E05-07`, `1x05`, `1x05-06`, `Season 1 Episode 5`, `Season.1.Ep.5` | 0.98 / 0.95 / 0.88 |
| Season only / packs | `S02`, `Season 3`, `Seasons 1-3`, `S01-S10`, `Complete` | 0.8–0.9 |
| Episode only | `Episode 45`, `Ep 45`, `EP125`, `E05`, `Episodes 1-10` | 0.85 / 0.75 |
| Absolute episode | anime `Title - 12`, `Title 125 Tamil`, `Title Tamil 125`, `[Group] Title - 1085 [720p]` | 0.8 / 0.65 / 0.6 / ≤0.5 (with a warning) |
| Air date | `2024.03.15`, `2024-03-15` | 0.9 |
| Year | 1920 to next year; `(2024)` strongest; the **last** candidate wins, so `1917`, `2012 (2009)`, `Blade Runner 2049 (2017)`, `2001 A Space Odyssey 1968` work | 0.95 / 0.9 / 0.8 / 0.75 |
| Resolution | `480p…4320p`, `1080i`, `4K`, `8K`, `UHD`, `FHD`, `QHD`, `1920x1080`; bare `1080` only among other tech words; `HD`/`SD` weakest | 0.98 → 0.4 |
| Quality / source | Remux, WEB-DL (`WEBDL`, `WEB-HD`), WEBRip, WEB, BluRay (`BDRip`, `BRRip`), HDRip, PreDVD, DVDScr, DVDRip, DVD, HDTV, TVRip, HDCAM, CAM, HDTS/TS, Telecine | 0.95 → 0.5 |
| Quality tags | HQ, PROPER, REPACK, REAL, INTERNAL, LIMITED, HYBRID, UNTOUCHED, ORG/Original audio, Clean/Line audio | 0.9 |
| Streaming service | Netflix, Amazon/AMZN, Hotstar, DSNP, HMAX, ATVP, Hulu, ZEE5, SonyLIV, JioCinema, SunNXT, Aha, MX Player, Voot, ALTBalaji, Hoichoi, Crunchyroll, YouTube; `NF`/`HS`/`JC`/`iT` only right before WEB | 0.85 / 0.75 |
| Video codec | x265/H.265/HEVC, x264/H.264/AVC, AV1, VP9, XviD, DivX, MPEG-2, VC-1; bit depth 8/10/12bit; HDR10+, HDR10, HDR, DV/DoVi, HLG, SDR | 0.95 / 0.9 / 0.85 |
| Audio | DD+/DDP/E-AC3, DD/AC3, TrueHD, Atmos, DTS, DTS-HD (MA), DTS:X, AAC (HE-AAC), FLAC, Opus, MP3, LPCM; channels 2.0/5.1/7.1, `6CH`; bitrate `192Kbps`; Dual/Multi Audio | 0.9 |
| Languages | 36 languages: full names anywhere after the title; native scripts (தமிழ், हिंदी, తెలుగు, മലയാളം, ಕನ್ನಡ, বাংলা …); abbreviations only in groups (`Tam+Tel+Hin`, `Tam-Tel`, `[Tam + Tel]`) or before `Dub`/`Audio`; "Dubbed" flag | 0.9 / 0.85 / 0.75 |
| Subtitles | ESub/ESubs, Eng Subs, `<Language> Sub(s/titles/bed)`, MSubs/Multi Subs, Hardsub/Hardcoded/HC, Softsub, NoSubs; subtitle files: `.en.srt`, `.English.forced.srt`, SDH/CC | 0.85 → 0.6 |
| Edition | Director's Cut, Extended, Theatrical, Uncut, Unrated, Remastered, IMAX, Criterion, Special/Anniversary Edition, Final Cut, Open Matte | 0.85 |
| Flags | Sample, Trailer/Teaser, 3D/HSBS/HOU, Complete, Proper, Repack | 0.85 |
| Parts | `.001`, `.part2.rar`, `CD2`, `Disc 1`; `Part 2` only at the very end (so "Dune Part Two" stays a title) | 0.98 / 0.9 / 0.6 |
| Release group | leading `[Group]`, trailing `-GROUP`, trailing `[GROUP]` | 0.9 / 0.8 / 0.6 |
| Alternative title | `Title aka Other Title` | title − 0.1 |
| Noise removed | URLs, `t.me/…`, `@handles`, `www.site.tld`, `Site.xyz`-style domains, sizes (`1.4GB`), CRC `[ABCD1234]`, emoji, "Join us…" | — |
| Fallbacks | generic names (`video_2024…`, `VID_…_WA0003`, `1.mkv`) use the caption's first meaningful line, then the chat title (with "Episodes/Movies/HD/Tamil…" removed) | caption ×0.9; chat 0.35 |

Title normalisation:
- separators become spaces, and junk at the edges ("Full Movie", "Watch Online") is dropped;
- ALL-CAPS and all-lower titles get title case; mixed-case and short acronyms (RRR, KGF) are kept as written;
- `normalizedTitle` is the matching key: lower-case, Latin accents folded, `&` → "and", apostrophes and punctuation dropped.

Overall confidence = 0.4 × title + 0.3 × best identity field (year/episode/…) + 0.2 × best technical field + 0.1 × (known extension).

## Limitations

- Rule-based, English-centric vocabulary. Titles written only in a native script parse, but are not transliterated, so matching across scripts belongs to Phase 10 (search).
- **Numbers are inherently ambiguous.** "Ben.10.mkv" becomes title "Ben", episode 10 at 0.40 with a warning; "Ocean's 11" with no year has the same problem. When a year or SxxEyy is present, numbers before it stay in the title.
- **A language word inside a title** is kept in the title unless only languages, dub words or an episode number follow it ("The English Patient" stays intact). A title ending in a language word followed by a year ("Leo Tamil 2023") loses that word to the language field. That is usually right, and wrong for a title like "Hindi Medium 2017".
- Two-letter ISO codes are only trusted in subtitle-file suffixes. Short codes like `Tam` are only trusted in groups or before Dub/Audio, so a lone "Mal" is not read as Malayalam.
- The episode title in "Show S08E06 The Iron Throne" is not extracted, and episode ranges are capped at what the name states (no inference).
- Only the caption's first meaningful line is used. Multi-line captions with several titles are not split.
- No external lookups: real codec, duration and resolution come from Telegram or playback (Phase 8); real titles from metadata providers (Phase 5).

## Tests

| Suite | What it covers |
|---|---|
| `CorpusTest` | 103 real-world-style names: the 5 examples from the brief, movies, Indian multi-language releases, anime, kids' shows, packs, subtitles, noise, generic names with caption/chat hints. Each states only the fields it checks; all failures are reported together |
| `ConfidenceAndRobustnessTest` | Confidence ordering per evidence type; per-field map; fallback sources marked and discounted; original name preserved for odd inputs; 3,000 random fuzz inputs (never throws, all scores in 0..1); 10,000-word input bounded; empty/symbol-only input; casing rules; normalised keys; subtitle-file metadata; caption text parsing |
| `:media:parser` `MediaClassifierAndTextTest` | Classification through the shared extension table |
| `:core:database` `MigrationTest` | Schema v1 → v2 with real rows: the original name is kept, defaults are applied, and old rows are flagged for re-parse |
| `:media:indexer` `ParsedMetadataTest` | End to end: original + metadata stored; empty Telegram names stay empty; chat-title hint; re-parse from stored names without Telegram, refreshing search; 450-row batched re-parse |

Run them:

```bash
./gradlew :media:filename:test
```

Run all tests:

```bash
./gradlew test
```

Debug app: the **Index & search** tab has a *Parser playground* (type any name to see every field and confidence), and the record view shows each parsed field with its confidence.
