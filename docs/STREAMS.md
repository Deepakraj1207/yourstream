# Streams from add-ons

Besides the user's own Telegram files, a title opened from a catalog can be watched through the
**stream add-ons the user installs** (Settings → Add-ons). YourStream ships none; it speaks the
public Stremio add-on protocol, which is also what Nuvio add-ons use.

## Add-ons page

| Tab | What it holds |
| --- | --- |
| **Stremio** | Built-in catalog add-ons (Cinemeta, Indian catalogs) and Stremio add-ons the user added. |
| **Nuvio** | Nuvio add-ons the user added. Same protocol as Stremio add-ons; kept apart because that is where users find them. |
| **Nuvio plugins** | Not supported: they are JavaScript scrapers for streaming websites, which usually have no rights to what they show. A plugin-repository link (a manifest with `scrapers`) is recognised and explained instead of failing obscurely. |

Each add-on card shows what its manifest offers (streams, catalogs, details). Links may be
`https://…`, `stremio://…` or with or without `/manifest.json`; plain `http://` is accepted only
for add-ons on the user's own network (localhost, private ranges, Tailscale, `*.local`).

## Asking for streams

`CatalogTitleViewModel` asks every enabled stream add-on in parallel. Each add-on gets the id
form it declares in its manifest (`resources[].idPrefixes` / `types`): an anime add-on keyed on
`kitsu:` gets the add-on's own id, most others the IMDb id (from the route or from the details'
`imdb_id`), with `:season:episode` for episodes. Add-ons that handle none of them are not asked.
A manifest without `idPrefixes` answers for any id (for `meta` as well).

Stream entries keep what playback needs: `url` + `behaviorHints.proxyHeaders`, or `infoHash` +
`fileIdx` + `sources`, or a `magnet:` link (parsed into a hash and trackers, hex or base32),
plus `behaviorHints.filename` / `videoSize`.

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
