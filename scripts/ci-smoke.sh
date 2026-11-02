#!/usr/bin/env bash
# CI boot smoke test: downloads the latest stable Paper build for the target version (checksum verified), installs the
# freshly built Atrium jar plus Atrium Vault, boots the server, runs console commands and fails on any Atrium error.
# Usage: scripts/ci-smoke.sh [paper-version]   (default 26.1.2)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="${1:-26.1.2}"
DIR="$ROOT/run/ci"
rm -rf "$DIR"; mkdir -p "$DIR/plugins"
meta="$(curl -fsSL "https://fill.papermc.io/v3/projects/paper/versions/$VERSION/builds/latest")"
url="$(printf '%s' "$meta" | python3 -c 'import json,sys; d=json.load(sys.stdin)["downloads"]["server:default"]; print(d["url"])')"
sha="$(printf '%s' "$meta" | python3 -c 'import json,sys; d=json.load(sys.stdin)["downloads"]["server:default"]; print(d["checksums"]["sha256"])')"
curl -fsSL "$url" -o "$DIR/paper.jar"
echo "$sha  $DIR/paper.jar" | (command -v sha256sum >/dev/null && sha256sum -c - || shasum -a 256 -c -)
echo "eula=true" > "$DIR/eula.txt"
printf 'online-mode=false\nserver-port=25565\nlevel-type=minecraft\\:flat\ngenerate-structures=false\nspawn-protection=0\n' > "$DIR/server.properties"
find "$ROOT/atrium-vault/build/libs" -name 'Vault-*.jar' ! -name '*-sources.jar' -exec cp {} "$DIR/plugins/" \;
"$ROOT/scripts/smoke.sh" run/ci "atrium version" "atrium modules" "vault-info" "atrium docs" "atrium help"
grep -q 'Enabled [0-9]*/[0-9]* modules' "$DIR/smoke.log"
modules="$(sed -n 's/.*Enabled \([0-9]*\)\/\([0-9]*\) modules.*/\1 \2/p' "$DIR/smoke.log" | tail -1)"
read -r enabled total <<< "$modules"
if [ "$enabled" != "$total" ]; then echo "SMOKE: only $enabled/$total modules enabled"; exit 1; fi
echo "CI smoke: $enabled/$total modules enabled"
