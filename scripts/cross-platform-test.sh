#!/usr/bin/env bash
# Starts the Swift dev server, runs the Kotlin client against it, and checks the server saw the phone.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG="$(mktemp)"

(cd "$ROOT/mac/MacDroidCore" && swift build --product macdroid-devserver >/dev/null)
"$ROOT/mac/MacDroidCore/.build/debug/macdroid-devserver" --host 127.0.0.1 --port 47999 >"$LOG" 2>&1 &
SERVER_PID=$!
trap 'kill $SERVER_PID 2>/dev/null || true; rm -f "$LOG"' EXIT

for _ in $(seq 1 50); do grep -q '^LISTENING' "$LOG" && break; sleep 0.2; done
URI="$(grep '^PAIRING_URI' "$LOG" | cut -d' ' -f2)"
[ -n "$URI" ] || { echo "dev server did not start:"; cat "$LOG"; exit 1; }

(cd "$ROOT/android" && MACDROID_DEV_URI="$URI" ./gradlew -q :core:test --tests 'dev.macdroid.core.CrossPlatformTest' --rerun)

grep -q 'STATUS connected device=MacDroid Test Phone' "$LOG" || { echo "server never saw the phone"; cat "$LOG"; exit 1; }
grep -q 'battery=77' "$LOG" || { echo "server never got the battery update"; cat "$LOG"; exit 1; }
echo "cross-platform test passed"
