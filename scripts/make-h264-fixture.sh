#!/usr/bin/env bash
# Writes protocol/fixtures/h264/sample.h264: 10 frames, 320x240 then 240x320 (a "rotation"), no B-frames, one slice per frame,
# IDR at frames 0 and 5 of each half. Used by Swift and Kotlin tests and the cross-platform test.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/protocol/fixtures/h264"
mkdir -p "$OUT"
TMP="$(mktemp -d)"
for size in 320x240 240x320; do
  ffmpeg -loglevel error -y -f lavfi -i "testsrc=size=$size:rate=30" -frames:v 10 -pix_fmt yuv420p \
    -c:v libx264 -profile:v high -bf 0 -g 5 -keyint_min 5 -sc_threshold 0 -x264-params "slices=1:aud=0" \
    -f h264 "$TMP/$size.h264"
done
# Like the phone's MediaCodec: parameter sets once per encoder start (x264 repeats them before every IDR).
for size in 320x240 240x320; do
  python3 - "$TMP/$size.h264" <<'PY'
import sys
path = sys.argv[1]
data = open(path, "rb").read()
starts = [i for i in range(len(data) - 3) if data[i:i + 4] == b"\0\0\0\1" or (data[i:i + 3] == b"\0\0\1" and (i == 0 or data[i - 1] != 0))]
nals = [data[s:(starts[n + 1] if n + 1 < len(starts) else len(data))] for n, s in enumerate(starts)]
seen, out = set(), b""
for nal in nals:
    kind = nal[(3 if nal[2] == 1 else 4)] & 0x1F
    if kind in (7, 8):
        if kind in seen: continue
        seen.add(kind)
    out += nal
open(path, "wb").write(out)
PY
done
cat "$TMP/320x240.h264" "$TMP/240x320.h264" > "$OUT/sample.h264"
rm -rf "$TMP"
echo "wrote $OUT/sample.h264 ($(wc -c < "$OUT/sample.h264") bytes)"
