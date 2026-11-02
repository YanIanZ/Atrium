#!/usr/bin/env bash
# Creates an isolated test server directory run/<name> (own world, own port) from the Folia jar in run/folia.
# Usage: scripts/new-server.sh <name> <port>     e.g. scripts/new-server.sh economy 25611
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NAME="$1"; PORT="$2"; DIR="$ROOT/run/$NAME"
mkdir -p "$DIR/plugins"
cp "$ROOT/run/folia/folia.jar" "$DIR/folia.jar"
echo 'eula=true' > "$DIR/eula.txt"
printf 'online-mode=false\nserver-port=%s\nlevel-type=minecraft\\:flat\nspawn-protection=0\nview-distance=4\nsimulation-distance=4\nenable-query=false\nenable-rcon=false\n' "$PORT" > "$DIR/server.properties"
[ -d "$ROOT/run/folia/libraries" ] && cp -R "$ROOT/run/folia/libraries" "$DIR/" 2>/dev/null || true
[ -d "$ROOT/run/folia/cache" ] && cp -R "$ROOT/run/folia/cache" "$DIR/" 2>/dev/null || true
[ -d "$ROOT/run/folia/versions" ] && cp -R "$ROOT/run/folia/versions" "$DIR/" 2>/dev/null || true
echo "Created $DIR (port $PORT). Run: scripts/smoke.sh run/$NAME \"command\" ..."
