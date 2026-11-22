#!/usr/bin/env bash
# Puts VLC's player libraries (libvlc, libvlccore and the plugins: VLC 3, 64-bit, GPL/LGPL) into
# desktop/appResources/windows/vlc, from where the Windows installer ships them and the app plays
# videos without a separate VLC install. Run on the Windows build machine (CI); the folder is not
# committed.
#
# The zip comes from VideoLAN's own server and is checked against the SHA-256 VideoLAN publishes
# next to it. Any failure stops the script with exit code 1; the caller decides whether to build
# without VLC (the app then asks the user to install VLC).
set -euo pipefail

BASE="https://download.videolan.org/pub/videolan/vlc/last/win64/"
OUT="desktop/appResources/windows/vlc"
WORK="${RUNNER_TEMP:-/tmp}/vlc-download"

rm -rf "$WORK" "$OUT"
mkdir -p "$WORK" "$OUT"

ZIP=$(curl -fsSL "$BASE" | grep -oE 'vlc-[0-9][0-9.]*-win64\.zip' | sort -uV | tail -1)
if [ -z "$ZIP" ]; then echo "No VLC win64 zip listed at $BASE"; exit 1; fi
echo "VLC: $ZIP"
curl -fsSL -o "$WORK/$ZIP" "$BASE$ZIP"
curl -fsSL -o "$WORK/$ZIP.sha256" "$BASE$ZIP.sha256"
EXPECTED=$(grep -oE '[0-9a-f]{64}' "$WORK/$ZIP.sha256" | head -1)
# From stdin and only the hex digits: Git Bash's sha256sum prefixes "\" for Windows paths.
ACTUAL=$(sha256sum < "$WORK/$ZIP" | grep -oE '[0-9a-f]{64}' | head -1)
if [ -z "$EXPECTED" ] || [ "$EXPECTED" != "$ACTUAL" ]; then
  echo "Checksum mismatch for $ZIP: expected '$EXPECTED', got '$ACTUAL'"; exit 1
fi
echo "Checksum OK: $ACTUAL"

unzip -q "$WORK/$ZIP" -d "$WORK/x"
SRC=$(ls -d "$WORK"/x/vlc-*/ | head -1)
cp "$SRC/libvlc.dll" "$SRC/libvlccore.dll" "$OUT/"
cp -r "$SRC/plugins" "$OUT/"
BEFORE=$(du -sm "$OUT" | cut -f1)
# Not needed to play videos inside YourStream (they make up a large part of the download):
#  gui            VLC's own window (Qt, skins)
#  lua            VLC's scripting (playlist parsers, extensions)
#  visualization  music visualisers
#  services_discovery  network/UPnP/podcast browsing
#  stream_out, access_output, mux  writing or re-streaming videos
#  video encoders (x264, x265), MP2/MP3 encoders (twolame, shine)
# Kept: every demuxer and decoder, HTTP and file input, audio/video output, subtitles, filters.
for d in gui lua visualization services_discovery stream_out access_output mux; do rm -rf "$OUT/plugins/$d"; done
rm -f "$OUT"/plugins/codec/libx264_plugin.dll "$OUT"/plugins/codec/libx26410b_plugin.dll "$OUT"/plugins/codec/libx265_plugin.dll \
      "$OUT"/plugins/codec/libtwolame_plugin.dll "$OUT"/plugins/codec/libshine_plugin.dll
echo "VLC plugins trimmed: ${BEFORE} MB -> $(du -sm "$OUT" | cut -f1) MB"
# VLC's licence and authors travel with it.
for f in COPYING.txt AUTHORS.txt README.txt; do [ -f "$SRC/$f" ] && cp "$SRC/$f" "$OUT/VLC-$f"; done
echo "${ZIP%-win64.zip}" > "$OUT/VERSION.txt"
du -sh "$OUT"
