#!/usr/bin/env bash
# Regenerates the macOS AppIcon PNGs from design/icon/macdroid-icon-mac.svg.
# Android uses vector drawables (android/app/src/main/res/drawable/ic_launcher_*.xml), so it needs no rendering.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/design/icon/macdroid-icon-mac.svg"
OUT="$ROOT/mac/MacDroid/Assets.xcassets/AppIcon.appiconset"
mkdir -p "$OUT"
BIN="$(mktemp -d)/render-icon"
swiftc -O "$ROOT/scripts/render-icon.swift" -o "$BIN"
images=""
for size in 16 32 128 256 512; do
  for scale in 1 2; do
    px=$((size * scale))
    suffix=""
    if [ "$scale" = 2 ]; then suffix="@2x"; fi
    name="icon_${size}x${size}${suffix}.png"
    "$BIN" "$SRC" "$OUT/$name" "$px"
    images="$images{\"idiom\":\"mac\",\"size\":\"${size}x${size}\",\"scale\":\"${scale}x\",\"filename\":\"$name\"},"
  done
done
printf '{"images":[%s],"info":{"version":1,"author":"xcode"}}\n' "${images%,}" > "$OUT/Contents.json"
printf '{"info":{"version":1,"author":"xcode"}}\n' > "$ROOT/mac/MacDroid/Assets.xcassets/Contents.json"
echo "Rendered $(ls "$OUT"/*.png | wc -l | tr -d ' ') PNGs into $OUT"
