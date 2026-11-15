# Streams from add-ons

Besides the user's own Telegram files, a title opened from a catalog can be watched through the
**stream add-ons the user installs** (Settings → Add-ons). YourStream ships none; it speaks the
public Stremio add-on protocol, which is also what Nuvio add-ons use.

## Add-ons page

| Tab | What it holds |
| --- | --- |
| **Stremio** | Cinemeta (the only built-in add-on since 0.11.4; switchable) and Stremio add-ons the user added. |
| **Nuvio** | Nuvio add-ons the user added. Same protocol as Stremio add-ons; kept apart because that is where users find them. |

Nuvio plugins (JavaScript scrapers for streaming websites, which usually have no rights to what
they show) are not supported and have no tab (removed in 0.11.2). A plugin-repository link (a
manifest with `scrapers`) is recognised and explained instead of failing obscurely.

Each add-on card shows what its manifest offers (streams, catalogs, details). Links may be
`https://…`, `stremio://…` or with or without `/manifest.json`; plain `http://` is accepted only
for add-ons on the user's own network (localhost, private ranges, Tailscale, `*.local`).

**Refresh** (top-bar button, or pull down; like Nuvio's): every add-on's manifest is fetched again
at once (`CatalogAddons.refresh`). Each card shows "Checking…" until its add-on answers. An add-on
that renamed itself in its manifest keeps the new name; the catalog manager is rebuilt so Home and
Search use the catalogs the add-on lists now. An add-on that does not answer is left unchanged.
The page then says how many add-ons answered.

## Asking for streams

`CatalogTitleViewModel` asks every enabled stream add-on in parallel. Each add-on gets the id
form it declares in its manifest (`resources[].idPrefixes` / `types`): an anime add-on keyed on
`kitsu:` gets the add-on's own id, most others the IMDb id (from the route or from the details'
`imdb_id`), with `:season:episode` for episodes. Add-ons that handle none of them are not asked.
A manifest without `idPrefixes` answers for any id (for `meta` as well).

**Grouped by add-on, like Nuvio.** Each add-on's streams appear under its name (with a count) as
soon as that add-on answers, instead of after the slowest one. A row of chips above them (All · n,
then each add-on · n, a spinner on those still asked) shows all add-ons or one; a refresh button
asks them again for the same movie or episode. Order: add-ons with direct (HTTP) streams, then
torrents, then those still loading, then those with nothing; within each, the order the add-ons
are listed in, so groups do not jump around as answers arrive. Add-ons that found nothing, or did
not answer, are named under the list. A newer request (another episode, a refresh) cancels the
older one: a slow answer for the previous episode can no longer replace the current list.

Stream entries keep what playback needs: `url` + `behaviorHints.proxyHeaders`, or `infoHash` +
`fileIdx` + `sources`, or a `magnet:` link (parsed into a hash and trackers, hex or base32),
plus `behaviorHints.filename` / `videoSize`.

## Movies in raw pieces (".mkv.001", ".002", …)

Telegram caps uploads (2 GB, 4 GB with Premium), so 4K movies are often cut into raw pieces with
7-Zip ("split to volumes"), HJSplit or `split`: `Film.2160p.mkv.001`, `.002`, … Only the first
piece starts with the container's header; joined in order the pieces are the original file byte
for byte. YourStream streams them as that one file, without downloading or joining first.

* **Library** (`SplitFiles`, `:library`): a first piece becomes one version holding every piece
  (`MediaVersion.parts`), found by name in its chat (`LibraryDao.splitParts`, exact prefix match)
  wherever the pieces were linked; later pieces are not versions of their own (none can play
  alone). A piece posted twice: the copy nearest the first piece's message is used. The version's
  size is the whole movie's; it is "On this device" only when every piece is. The title page
  shows "N pieces" (and any missing piece).
* **Resolving** (`LibraryMediaSourceResolver.resolveSplit`): every piece's message is looked up in
  one call (offline: the file ids saved at indexing). A gap in the numbering, a deleted piece or
  one Telegram cannot download is named in the error ("missing piece 002"), because playback would
  stop there. A missing *last* piece cannot be noticed: nothing says how many there were.
* **Playback** (`TelegramMediaUri` `tgfile://split/<id1>/<id2>/…?sizes=…`,
  `TelegramMediaDataSource`, `SplitLayout`): a byte position maps to (piece, offset); reading
  continues into the next piece at each boundary, and the next piece starts downloading 8 MB
  before the boundary (`TelegramFileStream.prefetch`). Media3 sees one file, so the duration,
  seeking (MKV cues / MP4 moov, wherever they are), tracks and resume work as for any file. The
  controller steers the download window, focus and statistics of the piece being read
  (`SplitReads`) and releases every piece when playback ends.
* **Downloads** (`VideoDownloads`): every piece is downloaded and kept; progress counts all pieces;
  cancel and remove act on every piece.
* Not joined: archive pieces (`.7z.001`, `.part1.rar`), which would have to be unpacked, and
  files on the device.

Tests: `SplitFilesTest` (real indexer, database and library), `SplitPlaybackTest` (real
ExoPlayer over the simulated Telegram: an MKV in 3 pieces plays to the end with its real duration
and size; an MP4 in 4 pieces seeks into a later piece; the data source returns the original bytes
from any position, including exactly on a boundary), `LibraryMediaSourceResolverTest` and
`VideoDownloadsTest` (split cases).

## Web streams (`WebStreams`, `:playback:media3`)

- One OkHttp client: HTTP/2, connection reuse across HLS/DASH segments, redirects followed
  (http → https too, as debrid links do).
- A 512 MB LRU disk cache of played bytes: seeking back and replays do not download again.
- Starts after 1.5 s of buffered media (3 s after a stall), buffers up to 2 minutes within
  Media3's default memory bounds.
- `network_security_config.xml` permits cleartext for media (self-hosted / LAN streams), with
  the system's CAs only.

## Torrent streams (`:playback:torrent`)

Built on libtorrent 2.0 (libtorrent4j, MIT). **Off by default** (since 0.11): turning it on in
Settings → Playback → Streaming, or tapping a torrent result while it is off, first explains that
peers see the device's IP address (`TorrentConsentDialog`); turning it off never asks.

1. **Session**: started with the first torrent stream, stopped 2 minutes after the last one;
   DHT, LSD, UPnP on; seeds are not disconnected when every wanted piece is present. Storage
   uses plain file I/O, not memory-mapped files: no address-space limit on 32-bit devices, a
   full disk is an error rather than a SIGBUS, and libtorrent installs no signal handlers.
2. **Magnet**: the info hash, the add-on's trackers, then a short list of public trackers.
3. **File list**: up to 90 s for peers to send it. The file played is the add-on's `fileIdx`,
   else its file name, else the largest video (never an archive).
4. **Download plan** (`TorrentEngine.steer`, every 0.5 s): only pieces playback needs are
   wanted: the read-ahead window from the player's read position (file size / 60, 32–256 MB:
   about two minutes of a two-hour film), the file's last 4 MB (MKV cues / MP4 moov) and any
   piece a reader is blocked on. The next missing pieces get staggered deadlines so libtorrent
   fetches them in order from the fastest peers.
5. **Reading** (`TorrentDataSource`): Media3 reads `torrent://<hash>/<file name>`; a read waits
   until its piece has passed the hash check (plus a short settle time for the disk write), and
   moves that piece to the front. Cancelled loads interrupt the wait at once.
6. **Cleanup**: data lives in `cache/torrents/<hash>` and is deleted when the player closes
   (leftovers from a crash are deleted when the next session starts). At least 256 MB free and
   twice the read-ahead are required before a stream starts.

The played file gets a normal file priority (pieces of priority-0 files would go to a hidden
part file); which of its pieces download is steered piece by piece.

The player shows peers, download speed and how much of the file is on the device.

**Tested end to end** (`TorrentEngineLocalTest`): a libtorrent session on localhost seeds a 48 MB
file; the engine gets the file list from it, reads the file's end, then the whole file through
`TorrentDataSource` past the first read-ahead window, byte for byte, and cleans up (about 3 s). While a
torrent plays, the device downloads from and uploads to other peers, who can see its IP address;
the setting's description says so.

## Decoders

Both players (Telegram files and add-on streams) use `playerRenderers()`: the device's decoders
first, then FFmpeg's audio decoders (Jellyfin's build of the Media3 FFmpeg extension) for AC-3,
E-AC-3, DTS, TrueHD and others the device lacks; a decoder that fails to start falls back to the
next one. The extension (built for Media3 1.9) was checked against Media3 1.11: every Media3
member it references exists and it implements every abstract method; its native code only calls
back into its own class.

## APK size

Native libraries (TDLib, libtorrent, FFmpeg) are compressed in the APK. Releases contain one
APK per CPU type plus a universal one; the in-app updater downloads the one for the device
(`GitHubAppUpdates.pickApk`), about 40 MB instead of about 90 MB.
