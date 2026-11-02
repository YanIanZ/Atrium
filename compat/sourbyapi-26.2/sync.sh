#!/usr/bin/env bash
# Regenerates sources.lock (commit + SHA-256 of every SourbyCraft API source file) for a git ref of a local
# SourbyCraft clone. Read-only on the clone: only `git rev-parse`, `git ls-tree` and `git show`; no checkout or build.
# The sources themselves are never stored in this repository; the build downloads and verifies them.
#
#   compat/sourbyapi-26.2/sync.sh /path/to/SourbyCraft [ref]
#
# Default ref: origin/26.2. Run `git -C <clone> fetch origin` first, then `./gradlew build`.
set -euo pipefail
repo="${1:?usage: sync.sh /path/to/SourbyCraft [ref]}"
ref="${2:-origin/26.2}"
here="$(cd "$(dirname "$0")" && pwd)"
api="sourbyapi/src/main/java/dev/iyanz/sourbycraft/api"
commit="$(git -C "$repo" rev-parse "$ref^{commit}")"
lock="$here/sources.lock"
{
  echo "# SourbyCraft API sources fetched at build time (never committed: GPL-3.0-only, see README.md)."
  echo "# Format: <sha256>  <path under sourbyapi/src/main/java/>. Regenerate with ./sync.sh."
  echo "commit $commit"
  git -C "$repo" ls-tree -r --name-only "$commit" -- "$api" | sort | while IFS= read -r path; do
    rel="${path#sourbyapi/src/main/java/}"
    hash="$(git -C "$repo" show "$commit:$path" | shasum -a 256 | cut -d' ' -f1)"
    echo "$hash  $rel"
  done
} > "$lock.tmp"
mv "$lock.tmp" "$lock"
echo "Locked $(grep -c '\.java$' "$lock") files of $ref = $commit"
