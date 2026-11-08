# Audio and subtitle tracks (Phase 9)

YourStream picks the audio and subtitle tracks the user wants, from their own preference list.
It remembers what they choose per movie and per series, and it does not trust file metadata
blindly. All of it is UI-independent: `:playback:tracks` is pure Kotlin. The Media3 glue in
`:playback:media3` only maps tracks in and applies the decision.

```
 Media3 Tracks ──▶ Media3Tracks.candidates ──▶ TrackCandidate[] (+ label hints: commentary, forced, SDH)
 External subtitle files ─▶ ExternalSubtitleLoader ─▶ SideloadedSubtitle (UTF-8 copy, content language)
 Library ─▶ TrackContextProvider ─▶ FileContext (file name, languages in the name, original language)
                                    │
                                    ▼
                     LanguageDetector.analyze ─▶ AnalyzedTrack[] (language + confidence + source)
                                    │
 TrackPreferenceStore (Room v6) ────┼─▶ LanguagePreferences (global) + RememberedChoice (movie / series)
                                    ▼
                     TrackSelector.select ─▶ TrackSelection (audio, subtitle, rule, reason)
                                    │
                                    ▼
                     Media3Tracks.apply (TrackSelectionOverride / text disabled)
 user picks a track ─▶ TrackSession.userSelected… ─▶ remembered for the movie, or the whole series
```

| Piece | Module |
|---|---|
| `LanguageCatalog`: normalization of tags, names, native names, abbreviations | `:playback:tracks` |
| `LanguageDetector`, `SubtitleContent` (script / English detection), `withLabelHints` | `:playback:tracks` |
| `LanguagePreferences`, `TrackSelector`, `TrackSignature`, `RememberedChoice`, `TrackSession`, `TrackPreferenceStore` | `:playback:tracks` |
| `Media3Tracks`, `RoomTrackPreferenceStore`, `ExternalSubtitleLoader`, `TrackContextProvider`, `TrackSupport` | `:playback:media3` |
| `TelegramPlaybackController.tracks`, `selectAudio(id)`, `selectSubtitle(id \| null)` | `:playback:media3` |
| DB v6: `language_settings` table; `track_preference` + `audio_signature`, `subtitle_signature`, `scope` | `:core:database` |

## 1. Language normalization

`LanguageCatalog.normalizeCode` maps every spelling to one code: ISO 639-1 where one exists,
else 639-2/3 (e.g. `bho`). It covers:

- **Metadata codes:** `ta`, `tam`, `TAM`, `ta-IN`, `ta_IN`, legacy codes (`iw`→`he`), B/T pairs
  (`fre`/`fra`), and macrolanguage members (`yue`/`cmn`→`zh`, `nob`/`nno`→`no`, `fil`→`tl`).
- **Names:** English names, other European spellings ("Español"), and native script ("தமிழ்",
  "हिन्दी", "తెలుగు", …) for the Indian languages and the common world languages.
- **Unknown values:** `und`, `mul`, `zxx`, `mis`, empty and garbage → null. A well-formed but
  unknown code is kept as is.

`findInText` finds languages in labels and file names, in order of appearance ("Tam+Tel+Hin" →
ta, te, hi):
- Full and native names count anywhere.
- 3-letter abbreviations count only in short labels, in upper case, or when they are not
  ordinary words. This avoids "may", "per" and "dan" in prose.
- 2-letter codes count only as subtitle file-name suffixes.

## 2. Language detection: metadata is evidence, not truth

For each track the detector gathers evidence and keeps the strongest:

| Evidence | Confidence | Notes |
|---|---|---|
| CONTENT: subtitle text | up to 0.98 | Unambiguous scripts: Tamil, Telugu, Kannada, Malayalam, Gujarati, Gurmukhi, Odia, Sinhala, Hangul, Kana, Thai, Greek, Hebrew. Shared scripts give a weaker guess: Devanagari → hi 0.65, Bengali → bn 0.7, Arabic, Cyrillic. Latin → English only if ≥ 18% are common English words. Romanized "Tanglish" stays unknown. Wins over contradicting evidence at ≥ 0.8 |
| LABEL: language named in the track title | 0.9 | "Tamil 5.1", "Hindi [Dubbed]" |
| METADATA: container tag | 0.8 | Drops to 0.25 when every track has the same tag but labels or the file name show several languages (a muxer default such as "eng" on all tracks). Drops to 0.3 when it contradicts the label |
| FILE_NAME: external subtitle name | 0.7 | Suffix before the extension, skipping `forced`/`sdh`/`cc` ("x.ta.srt", "x.eng.forced.srt"), else one language named in the name |
| FILE_NAME_ORDER | 0.5–0.6 | Untrusted or unknown audio tracks get the video name's languages *in order*, only when the counts match exactly |
| ORIGINAL_LANGUAGE | 0.4 | A single untagged audio track in a file whose name names no language gets the title's original language (TMDB) |

**Flags.** They are also taken from labels: commentary, audio description, forced/signs, and
SDH/CC. Container flags are often missing.

**Audio content is not analysed** (that would need speech recognition). Audio relies on
labels, tags and the file name.

## 3. Preferences and fallback

`LanguagePreferences`:

| Setting | Meaning |
|---|---|
| `audio` | Preferred audio languages in order, e.g. Tamil, English, Hindi |
| `subtitles` | Preferred subtitle languages in order, e.g. Tamil, English |
| `fallbackLanguage` | Tried after the lists |
| `subtitleMode` | `OFF` / `FOREIGN_ONLY` (default) / `ALWAYS` |
| `avoidCommentary` | Skip commentary and audio-description tracks |
| `preferSdh` | Prefer SDH subtitles |

Settings are stored in `language_settings`. The defaults (English) apply until the user saves
their own.

**Audio**, first match wins:
1. remembered pick for this movie or series (§4);
2. preferred languages in order: Tamil available → Tamil; else English; else Hindi;
3. fallback language;
4. the file's default track, when the file marks one;
5. the first available track.

Commentary and audio-description tracks are skipped while anything else exists. Unsupported
tracks (no decoder) are never chosen. Among several tracks of one language the order is: a
confident detection (≥ 0.5), then the file's default, then file order.

**Subtitles:**
1. Remembered pick, including "off".
2. Mode `OFF` → none.
3. Mode `FOREIGN_ONLY` with *understood* audio (its language is in the audio preference list)
   → only *forced* subtitles in that language (signs, foreign-language lines), else none.
4. Otherwise (`ALWAYS`, or foreign or unknown audio) → preferred subtitle languages, then the
   fallback language. Among matches the order is SDH per preference, then default, then
   embedded before Telegram files before local files.
5. Nothing matches → **no subtitles**. A subtitle in a language the user did not ask for is
   never shown automatically.

Every decision carries a rule and a reason (e.g. `PREFERRED_LANGUAGE: "Tamil is preference
#1"`). The test player shows them.

## 4. Remembered selection: per movie and per series

When the user picks a track (`selectAudio`, `selectSubtitle`, or `null` = off), the choice is
stored in `track_preference`, keyed by the library item:

- **an episode stores it on its series item** (`scope = SERIES`): it applies to every episode;
- **a movie or video stores it on itself** (`scope = MOVIE`).

Track ids and indexes change between files, so the choice is stored as a
**`TrackSignature`**: language | normalized label | channels | commentary | descriptive |
forced | SDH.

- In the next file the same language is required. The label, channel layout and flags break
  ties: "Hindi Dubbed 5.1" over "Hindi commentary".
- A pick whose language was unknown is found again by its label.
- If the remembered language is absent in a file, the global preferences apply to that file;
  the memory itself is kept.

**Compatibility.** Rows from Phase 4 (languages only, `subtitles_enabled`) are read as language
signatures and "off". A language later changed through the library API overrides an older
signature. The migration 5 → 6 only adds a table and nullable columns; it is covered by
`MigrationTest.v5ToV6…`.

## 5. External subtitles

The library already links subtitle files found in the user's indexed Telegram chats to their
movie or episode (Phase 4, `subtitle_track` with source EXTERNAL). Files added on the device are
LOCAL. Before playback, `ExternalSubtitleLoader` handles them:

1. **Format filter.** Only text formats Media3 renders are used: SRT, ASS/SSA, WebVTT,
   TTML/DFXP. Bitmap formats (VobSub `.sub/.idx`, PGS `.sup`) and files over 5 MB are skipped:
   *technically appropriate* only.
2. **Download.** Each file is downloaded whole from the user's Telegram (they are a few KB),
   with a 15 s limit per file. Playback starts without a file that is not ready in time.
3. **Re-encoding.** The text is re-encoded to UTF-8: BOM → UTF-8/UTF-16LE/BE, valid UTF-8 → as
   is, otherwise Windows-1252. The copy goes to the app cache.
4. **Content language.** The text is analysed (§2), so a Tamil file named `*.eng.srt` is
   treated as Tamil.
5. **Sideloading.** The file is added to the `MediaItem` as a `SubtitleConfiguration`.
   `TelegramMediaSourceFactory` merges it into the video (`MergingMediaSource` +
   `SubtitleExtractor`).

Embedded text tracks (MKV SRT/ASS, MP4 WebVTT, …) are parsed during extraction
(`DefaultExtractorsFactory.setSubtitleParserFactory`).

**Feedback to the library.** What the player detected is written back: probed audio tracks and
embedded subtitle tracks with their detected languages. This improves library language
filters.

## 6. Tests

**`:playback:tracks` (17, language matching):**
- normalization of all spellings and "unknown" values;
- text and name detection without false hits;
- trusted metadata;
- copied-default tags losing to labels;
- file-name order;
- mismatched counts left alone;
- a label contradicting its tag;
- the original-language fallback;
- subtitle file names;
- content winning over a wrong tag;
- script and English detection (also ASS), with romanized text unknown;
- the spec's fallback chain: Tamil → English → next preference → first or default;
- the fallback language;
- commentary and unsupported tracks;
- subtitle modes and forced subtitles;
- SDH preference;
- remembered picks across reordered files, "off", label-only matching;
- signature encoding;
- `TrackSession` memory per series and per movie.

**`:playback:media3` (Robolectric, real ExoPlayer):**
- `TrackSelectionPlaybackTest` (5), using real MKVs made by `scripts/make-test-media.sh`
  (audio hin (default) / tam / eng with titles, embedded eng/tam SRT; plus a variant with every
  audio track tagged "eng"):
  - the preferred language beats the file default;
  - wrong tags are overruled by titles;
  - the fallback chain, with subtitles for foreign audio;
  - a user choice remembered across episodes of a series but not for a movie;
  - an external Tamil SRT named "eng" detected as Tamil and selected.
- `TrackStorageTest` (3): settings and choices round trip in Room, Phase 4 rows still work,
  subtitle decoding and format filter.

**`:core:database`:** the v5 → v6 migration.

## 7. Limitations

- **No audio content detection.** An audio track with no label, no tag and no hint in the file
  name stays "unknown". The file's default or first track is then used.
- **Order-based assignment.** "Tam+Tel+Hin" → tracks 1..3 assumes uploaders list languages in
  track order. It is used only when the counts match, and with a lower confidence.
- **Shared scripts.** Devanagari, Bengali, Arabic and Cyrillic give a best guess (Hindi,
  Bengali, Arabic, Russian), so Marathi text may be read as Hindi. Romanized subtitles are not
  identified.
- **Bitmap subtitles.** Embedded PGS/VobSub play when Media3 supports them. External bitmap
  files are not sideloaded.
- **Late external subtitles.** Files not downloaded within 15 s are left out of that playback
  session.
- **No settings screen.** The preferences UI is the Stage-1 test player: comma-separated lists
  and a mode toggle. The engine is UI-independent; a real settings screen is Stage 2.
