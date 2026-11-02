#!/usr/bin/env bash
# Boots a local test server with the freshly built Atrium jar, runs console commands, stops it.
# Usage: scripts/smoke.sh [run/folia|run/paper] ["console command" ...]
# Prints Atrium log lines plus every WARN/ERROR. Exit code 1 if Atrium logged an ERROR or the server
# did not finish starting within 180 s.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIR="$ROOT/${1:-run/folia}"; shift || true
# At most SMOKE_SLOTS (default 2) servers boot at once across concurrent runs, to keep memory in check.
SLOTS="${SMOKE_SLOTS:-2}"; SLOT=""
release_slot() { [ -n "$SLOT" ] && rm -rf "$SLOT"; }
trap release_slot EXIT
while [ -z "$SLOT" ]; do
  for i in $(seq 1 "$SLOTS"); do
    d="${TMPDIR:-/tmp}/atrium-smoke-slot-$i"
    if mkdir "$d" 2>/dev/null; then echo $$ > "$d/pid"; SLOT="$d"; break; fi
    owner="$(cat "$d/pid" 2>/dev/null)"
    if [ -n "$owner" ] && ! kill -0 "$owner" 2>/dev/null; then rm -rf "$d"; fi
  done
  [ -z "$SLOT" ] && { echo "smoke: waiting for a free server slot..."; sleep 5; }
done
JAR="$(ls "$ROOT"/atrium-plugin/build/libs/Atrium-*.jar | grep -v sources | head -1)"
SERVER_JAR="$(ls "$DIR"/*.jar | head -1)"
cp "$JAR" "$DIR/plugins/Atrium.jar"
rm -f "$DIR/plugins/Atrium-"*.jar
cd "$DIR"
LOG="$DIR/smoke.log"; FIFO="$DIR/.stdin"; rm -f "$FIFO" "$LOG"; mkfifo "$FIFO"
java -Xms1G -Xmx2G -jar "$SERVER_JAR" --nogui < "$FIFO" > "$LOG" 2>&1 &
PID=$!
exec 3>"$FIFO"
for _ in $(seq 1 180); do
  grep -q 'Done (' "$LOG" && break
  kill -0 $PID 2>/dev/null || break
  sleep 1
done
STARTED=0; grep -q 'Done (' "$LOG" && STARTED=1
for cmd in "$@"; do echo "$cmd" >&3; sleep 2; done
echo stop >&3
for _ in $(seq 1 60); do kill -0 $PID 2>/dev/null || break; sleep 1; done
kill $PID 2>/dev/null; exec 3>&-; rm -f "$FIFO"
grep -E 'Atrium|atrium|WARN|ERROR|Exception|Caused by|Done \(' "$LOG" | grep -v 'Unable to find default spawn' | head -200
if [ $STARTED -ne 1 ]; then echo "SMOKE: server did not finish starting"; exit 1; fi
if grep -E '(ERROR|Exception).*' "$LOG" | grep -qi atrium; then echo "SMOKE: Atrium errors found"; exit 1; fi
echo "SMOKE: OK"
