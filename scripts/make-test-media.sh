#!/usr/bin/env bash
# Generates the synthetic test videos used by the playback tests (ffmpeg test patterns + tones;
# no third-party content). Small clips are committed; the long one is generated on demand.
#   scripts/make-test-media.sh <out-dir>            small fixtures
#   scripts/make-test-media.sh <out-dir> long       + an 85-minute, ~150 MB CBR file
set -euo pipefail
out=${1:?out dir}; mkdir -p "$out"
ff() { ffmpeg -hide_banner -loglevel error -y "$@"; }
src() { echo "testsrc2=size=$1:rate=$2"; }
tone="sine=frequency=440:sample_rate=44100"

ff -f lavfi -i "$(src 640x360 25)" -f lavfi -i "$tone" -t 6 -c:v libx264 -preset veryfast -pix_fmt yuv420p -g 25 \
   -b:v 300k -c:a aac -b:a 64k -movflags +faststart "$out/small_360p_faststart.mp4"
# moov atom at the END: the player must jump to the end before it can start.
ff -f lavfi -i "$(src 1280x720 25)" -f lavfi -i "$tone" -t 3 -c:v libx264 -preset veryfast -pix_fmt yuv420p -g 25 \
   -b:v 600k -c:a aac -b:a 64k "$out/res_720p_moov_end.mp4"
ff -f lavfi -i "$(src 1920x1080 25)" -f lavfi -i "$tone" -t 2 -c:v libx264 -preset veryfast -pix_fmt yuv420p -g 25 \
   -b:v 900k -c:a aac -b:a 64k -movflags +faststart "$out/res_1080p.mp4"
ff -f lavfi -i "$(src 854x480 25)" -f lavfi -i "$tone" -t 3 -c:v libx264 -preset veryfast -pix_fmt yuv420p -g 25 \
   -b:v 400k -c:a aac -b:a 64k "$out/res_480p.mkv"

# Multi-track files for track selection (3 s, 3 audio tracks, 2 embedded subtitle tracks).
tmp=$(mktemp -d)
printf '1\n00:00:00,500 --> 00:00:02,500\nHello, this is the English subtitle and you can read it.\n' > "$tmp/en.srt"
printf '1\n00:00:00,500 --> 00:00:01,500\nவணக்கம், இது தமிழ் வசனம்\n\n2\n00:00:01,600 --> 00:00:02,800\nநான் உன்னை எப்போதும் மறக்க மாட்டேன், இது என் வீடு\n' > "$tmp/ta.srt"
multi() { # $1 out, $2..$4 audio language tags, $5..$7 audio titles
  ff -f lavfi -i "$(src 320x180 25)" -f lavfi -i "sine=frequency=300:sample_rate=44100" -f lavfi -i "sine=frequency=500:sample_rate=44100" \
     -f lavfi -i "sine=frequency=700:sample_rate=44100" -i "$tmp/en.srt" -i "$tmp/ta.srt" -t 3 \
     -map 0:v -map 1:a -map 2:a -map 3:a -map 4:s -map 5:s -c:v libx264 -preset veryfast -pix_fmt yuv420p -b:v 150k -c:a aac -b:a 48k -c:s srt \
     -metadata:s:a:0 language="$2" -metadata:s:a:0 title="$5" -metadata:s:a:1 language="$3" -metadata:s:a:1 title="$6" \
     -metadata:s:a:2 language="$4" -metadata:s:a:2 title="$7" -metadata:s:s:0 language=eng -metadata:s:s:1 language=tam \
     -disposition:a:0 default -disposition:a:1 0 -disposition:a:2 0 "$out/$1"
}
multi multi_audio_hin_tam_eng.mkv hin tam eng "Hindi" "Tamil" "English"
# Wrong metadata: every audio track tagged "eng"; only the titles are right.
multi multi_audio_wrong_tags.mkv eng eng eng "Hindi" "Tamil" "English"
# An external subtitle file as users share them (Tamil text, misleading "eng" in its name).
cp "$tmp/ta.srt" "$out/external_tamil_named_eng.srt"
rm -rf "$tmp"

if [[ "${2:-}" == "long" ]]; then
  # 85 min at 1 fps, constant 220 kbit/s (filler NALs) -> large file with a small sample table.
  ff -f lavfi -i "$(src 320x180 1)" -f lavfi -i "sine=frequency=330:sample_rate=22050" -t 5100 \
     -c:v libx264 -preset ultrafast -pix_fmt yuv420p -g 10 -b:v 220k -minrate 220k -maxrate 220k -bufsize 220k \
     -x264-params nal-hrd=cbr -c:a aac -b:a 24k -ac 1 -movflags +faststart "$out/long_85min.mp4"
fi
