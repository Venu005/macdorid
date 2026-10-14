#!/usr/bin/env bash
# Starts the Swift dev server, runs the Kotlin client against it, and checks the server saw the phone.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG="$(mktemp)"

(cd "$ROOT/mac/MacDroidCore" && swift build --product macdroid-devserver >/dev/null)
WORK="$(mktemp -d)"
head -c 25165824 /dev/urandom > "$WORK/mac-to-phone.bin"
head -c 25165824 /dev/urandom > "$WORK/phone-to-mac.bin"
mkdir -p "$WORK/mac-received" "$WORK/phone-received"

"$ROOT/mac/MacDroidCore/.build/debug/macdroid-devserver" --host 127.0.0.1 --port 47999 \
  --receive-dir "$WORK/mac-received" --send-on-connect "$WORK/mac-to-phone.bin" >"$LOG" 2>&1 &
SERVER_PID=$!
trap 'kill $SERVER_PID 2>/dev/null || true; rm -rf "$LOG" "$WORK"' EXIT

for _ in $(seq 1 50); do grep -q '^LISTENING' "$LOG" && break; sleep 0.2; done
URI="$(grep '^PAIRING_URI' "$LOG" | cut -d' ' -f2)"
[ -n "$URI" ] || { echo "dev server did not start:"; cat "$LOG"; exit 1; }

(cd "$ROOT/android" && MACDROID_DEV_URI="$URI" MACDROID_PHONE_FILE="$WORK/phone-to-mac.bin" MACDROID_PHONE_RECEIVE_DIR="$WORK/phone-received" \
  ./gradlew -q :core:test --tests 'dev.macdroid.core.CrossPlatformTest' --rerun)

grep -q 'STATUS connected device=MacDroid Test Phone' "$LOG" || { echo "server never saw the phone"; cat "$LOG"; exit 1; }
grep -q 'battery=77' "$LOG" || { echo "server never got the battery update"; cat "$LOG"; exit 1; }
grep -q 'RECEIVED phone-to-mac.bin size=25165824' "$LOG" || { echo "Mac didn't receive the phone's file"; cat "$LOG"; exit 1; }
grep -q 'SENT mac-to-phone.bin' "$LOG" || { echo "the phone never confirmed the Mac's file"; cat "$LOG"; exit 1; }
grep -q 'NOTIFICATION apps count=1 access=true' "$LOG" || { echo "the Mac never got the phone's app list"; cat "$LOG"; exit 1; }
grep -q 'NOTIFICATION posted key=k1 title=Mom banner=true' "$LOG" || { echo "the Mac never got the notification"; cat "$LOG"; exit 1; }
grep -q 'NOTIFICATION replied key=k1' "$LOG" || { echo "the Mac never replied"; cat "$LOG"; exit 1; }
grep -q 'NOTIFICATION removed key=k1' "$LOG" || { echo "the Mac never saw the removal"; cat "$LOG"; exit 1; }
grep -q 'CLIPBOARD applied text=hello from phone' "$LOG" || { echo "the Mac never applied the phone's clipboard"; cat "$LOG"; exit 1; }
grep -q 'CLIPBOARD image size=3000' "$LOG" || { echo "the Mac never received the clipboard image"; cat "$LOG"; exit 1; }
grep -q 'CLIPBOARD applied image bytes=3000' "$LOG" || { echo "the Mac never applied the clipboard image"; cat "$LOG"; exit 1; }
grep -q 'RING state ringing=true' "$LOG" || { echo "the Mac never saw the phone ringing"; cat "$LOG"; exit 1; }
grep -q 'LINK open https://example.com/phone' "$LOG" || { echo "the Mac never got the phone's link"; cat "$LOG"; exit 1; }
grep -q 'MEDIA state title=Song playing=true art=0123456789abcdef' "$LOG" || { echo "the Mac never got the media state"; cat "$LOG"; exit 1; }
grep -q 'PHOTOS page count=2 access=full' "$LOG" || { echo "the Mac never got a photos page"; cat "$LOG"; exit 1; }
grep -q 'PHOTOS thumb id=2' "$LOG" || { echo "the Mac never got a thumbnail"; cat "$LOG"; exit 1; }
grep -q 'PHOTOS saved IMG_2.jpg size=4000' "$LOG" || { echo "the Mac never saved the photo"; cat "$LOG"; exit 1; }
grep -q 'PHOTOS gone id=424242' "$LOG" || { echo "the Mac never heard a photo was gone"; cat "$LOG"; exit 1; }
cmp "$WORK/phone-to-mac.bin" "$WORK/mac-received/phone-to-mac.bin" || { echo "phone → Mac file differs"; exit 1; }
cmp "$WORK/mac-to-phone.bin" "$WORK/phone-received/mac-to-phone.bin" || { echo "Mac → phone file differs"; exit 1; }
echo "cross-platform test passed"
