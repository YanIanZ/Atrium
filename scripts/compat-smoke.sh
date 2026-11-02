#!/usr/bin/env bash
# Compatibility and stability harness for Atrium + Atrium Vault.
#
# Builds an isolated server with a pinned set of popular plugins (scripts/compat-plugins.lock,
# every artifact checked against its official sha512/sha256) and checks that Atrium loads,
# reloads and documents itself without errors or command collisions.
#
# Usage:
#   scripts/compat-smoke.sh [options]
#     --target paper|sourby|all  server flavour (default: paper)
#     --all                      both targets
#     --stability                additionally run the stability suite (10x reload, module toggles,
#                                3 restarts on one data folder, corrupted module config, missing lang key)
#     --stability-only           run only the stability suite against the existing run/compat-paper
#                                (implies --no-build)
#     --paper-version <ver>      Paper version for the paper target (default: 26.1.2)
#     --no-build                 reuse the jars in atrium-plugin/build and atrium-vault/build
#
# Paper target: latest stable Paper build for the requested version from fill.papermc.io (sha256 checked).
# Sourby target: pinned official SourbyCraft release jar from GitHub (sha256 checked).
# A plugin is skipped on the Sourby target when its jar does not declare folia-supported: true.
#
# Exit codes: 0 = pass, 1 = Atrium/Vault errors, failed modules, reload failures or a stability check failure,
#             2 = harness problem (download, checksum, missing build).
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="$ROOT/run/compat-cache"
LOCK="$ROOT/scripts/compat-plugins.lock"
PAPER_VERSION="26.1.2"
SOURBY_TAG="v26.2-r50"
SOURBY_FILE="SourbyCraft-26.2-REL.jar"
SOURBY_SHA256="7137f22cb9c723859a17c15fb2db0ae50127872525fa80d71d59687fdf15197f"
SOURBY_URL="https://github.com/YanIanZ/SourbyCraft/releases/download/$SOURBY_TAG/$SOURBY_FILE"
TARGETS="paper"
STABILITY=0
STABILITY_ONLY=0
BUILD=1

while [ $# -gt 0 ]; do
  case "$1" in
    --target) TARGETS="$2"; shift 2 ;;
    --all) TARGETS="paper sourby"; shift ;;
    --stability) STABILITY=1; shift ;;
    --stability-only) STABILITY=1; STABILITY_ONLY=1; BUILD=0; shift ;;
    --paper-version) PAPER_VERSION="$2"; shift 2 ;;
    --no-build) BUILD=0; shift ;;
    -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
    *) echo "compat: unknown option $1" >&2; exit 2 ;;
  esac
done

mkdir -p "$CACHE"
FAILURES=0
# strict: every module must enable and Atrium must log no error.
# partial: a modules.yml batch was disabled on purpose (count check skipped).
# expected-failure: a broken config was planted on purpose (Atrium error check skipped).
RUN_MODE=strict
note() { echo "compat: $*"; }
fail() { echo "compat: FAIL: $*" >&2; FAILURES=$((FAILURES + 1)); }
die() { echo "compat: ERROR: $*" >&2; exit 2; }

# ---- checksums -------------------------------------------------------------------------------

digest_of() {
  local file="$1" alg="$2"
  if command -v "${alg}sum" >/dev/null 2>&1; then
    "${alg}sum" "$file" | awk '{print $1}'
  elif [ "$alg" = sha256 ]; then
    shasum -a 256 "$file" | awk '{print $1}'
  else
    shasum -a 512 "$file" | awk '{print $1}'
  fi
}

verify_digest() {
  local file="$1" spec="$2" alg expected actual
  alg="${spec%%:*}"
  expected="${spec#*:}"
  [ -f "$file" ] || return 1
  actual="$(digest_of "$file" "$alg")"
  [ "${actual:-}" = "$expected" ] || return 1
}

# download <cache file name> <url> <digest spec>
download() {
  local name="$1" url="$2" digest="$3" out="$CACHE/$1"
  if verify_digest "$out" "$digest"; then
    return 0
  fi
  rm -f "$out" "$out.part"
  note "downloading $name"
  if ! curl -fsSL --retry 3 --retry-delay 2 --connect-timeout 20 -o "$out.part" "$url"; then
    rm -f "$out.part"
    return 1
  fi
  if ! verify_digest "$out.part" "$digest"; then
    echo "compat: checksum mismatch for $name ($url)" >&2
    rm -f "$out.part"
    return 1
  fi
  mv "$out.part" "$out"
}

# True when the plugin jar declares Folia support (Folia refuses plugins without it).
folia_supported() {
  local jar="$1"
  unzip -p "$jar" plugin.yml 2>/dev/null | grep -Eqi '^[[:space:]]*folia-supported:[[:space:]]*true' && return 0
  unzip -p "$jar" paper-plugin.yml 2>/dev/null | grep -Eqi '^[[:space:]]*folia-supported:[[:space:]]*true' && return 0
  return 1
}

# ---- build -----------------------------------------------------------------------------------

build_jars() {
  [ "$BUILD" = 1 ] || return 0
  note "building Atrium and Atrium Vault"
  if ! (cd "$ROOT" && ./gradlew :atrium-plugin:shadowJar :atrium-vault:shadowJar -x test --console=plain); then
    die "gradle build failed (another module may be mid-edit); run with --no-build to reuse existing jars"
  fi
}

build_jars

# A concurrent Gradle build in another worktree rewrites atrium-plugin/build/libs while smoke tests run.
# Take one verified snapshot and give every smoke.sh call the same file, so a half-written jar can never be
# picked up mid-suite (it would show up as "Provider ... not found" or an unexpected schema version).
snapshot_jars() {
  local src dest i
  src="$(plugin_jar)"
  [ -n "$src" ] || die "no Atrium jar to snapshot"
  dest="$CACHE/Atrium-under-test.jar"
  for i in 1 2 3 4 5; do
    cp "$src" "$dest" 2>/dev/null || true
    if unzip -tq "$dest" >/dev/null 2>&1 \
        && unzip -l "$dest" 2>/dev/null | grep -q 'module/rtp/RtpModule.class' \
        && unzip -l "$dest" 2>/dev/null | grep -q 'lang/core/en_US.yml'; then
      break
    fi
    note "Atrium jar is being rebuilt, retrying the snapshot ($i/5)"
    sleep 3
  done
  unzip -tq "$dest" >/dev/null 2>&1 || die "snapshot of $src is not a readable jar"
  ATRIUM_JAR="$dest"
  export ATRIUM_JAR
  note "testing $(basename "$src") ($(digest_of "$dest" sha256 | cut -c1-12))"

  src="$(vault_jar)"
  [ -n "$src" ] || die "no Atrium Vault jar to snapshot"
  VAULT_JAR="$CACHE/Vault-under-test.jar"
  cp "$src" "$VAULT_JAR"
  unzip -tq "$VAULT_JAR" >/dev/null 2>&1 || die "snapshot of $src is not a readable jar"
}

plugin_jar() {
  local version candidate
  version="$(sed -n 's/^version=//p' "$ROOT/gradle.properties")"
  candidate="$ROOT/atrium-plugin/build/libs/Atrium-$version.jar"
  if [ -f "$candidate" ]; then echo "$candidate"; return; fi
  ls -t "$ROOT"/atrium-plugin/build/libs/Atrium-*.jar 2>/dev/null | grep -v sources | head -1
}

vault_jar() {
  local candidate
  candidate="$(ls -S "$ROOT"/atrium-vault/build/libs/Vault-*.jar 2>/dev/null | grep -v sources | head -1)"
  echo "$candidate"
}

# ---- server setup ----------------------------------------------------------------------------

server_properties() {
  local port="$1"
  printf 'online-mode=false\nserver-port=%s\nlevel-type=minecraft\\:flat\ngenerate-structures=false\nspawn-protection=0\nview-distance=4\nsimulation-distance=4\nenable-query=false\nenable-rcon=false\nmax-players=20\n' "$port"
}

prepare_dir() {
  local target="$1" port="$2" server_jar="$3" source_dir="$4"
  local dir="$ROOT/run/compat-$target"
  rm -rf "$dir"
  mkdir -p "$dir/plugins"
  cp "$server_jar" "$dir/${5:-server}.jar"
  echo 'eula=true' > "$dir/eula.txt"
  server_properties "$port" > "$dir/server.properties"
  # Reuse the local library cache of an existing server when present; otherwise the server fetches it.
  if [ -n "$source_dir" ] && [ -d "$source_dir/libraries" ]; then
    cp -R "$source_dir/libraries" "$dir/" 2>/dev/null || true
  fi
  if [ -n "$source_dir" ] && [ -d "$source_dir/cache" ]; then
    cp -R "$source_dir/cache" "$dir/" 2>/dev/null || true
  fi
  if [ -n "$source_dir" ] && [ -d "$source_dir/versions" ]; then
    cp -R "$source_dir/versions" "$dir/" 2>/dev/null || true
  fi
  echo "$dir"
}

install_atrium() {
  local dir="$1"
  [ -n "${ATRIUM_JAR:-}" ] || die "Atrium jar was not snapshotted"
  [ -n "${VAULT_JAR:-}" ] || die "Atrium Vault jar was not snapshotted"
  cp "$ATRIUM_JAR" "$dir/plugins/Atrium.jar"
  cp "$VAULT_JAR" "$dir/plugins/$(basename "$VAULT_JAR" | sed 's/Vault-under-test/Vault/')"
}

# install_plugins <target> <dir> <inventory file>
install_plugins() {
  local target="$1" dir="$2" inventory="$3"
  : > "$inventory"
  while IFS='|' read -r id targets version file digest url; do
    case "$id" in ''|\#*) continue ;; esac
    if ! printf '%s' ",$targets," | grep -q ",$target,"; then
      continue
    fi
    if ! download "$file" "$url" "$digest"; then
      fail "cannot download $id $version from $url"
      echo "$id|$version|$file|download-failed" >> "$inventory"
      continue
    fi
    if [ "$target" = sourby ] && ! folia_supported "$CACHE/$file"; then
      note "$id $version skipped on sourby: jar does not declare folia-supported: true"
      echo "$id|$version|$file|skipped-no-folia" >> "$inventory"
      continue
    fi
    cp "$CACHE/$file" "$dir/plugins/$file"
    echo "$id|$version|$file|installed" >> "$inventory"
    note "installed $id $version"
  done < <(grep -v '^#' "$LOCK" | grep -v '^[[:space:]]*$')
}

# ---- assertions ------------------------------------------------------------------------------

enabled_counts() {
  local log="$1"
  sed -n 's/.*Enabled \([0-9]*\)\/\([0-9]*\) modules.*/\1 \2/p' "$log" | tail -1
}

failed_modules() {
  sed -n 's/.*Module \([a-z0-9_-]*\) failed to enable.*/\1/p' "$1"
}

check_log() {
  local target="$1" label="$2" log="$3" dir="$4"
  local counts enabled total bad reload_conflict conflicts
  [ -f "$log" ] || { fail "$target/$label: no smoke.log"; return; }
  counts="$(enabled_counts "$log")"
  if [ -z "$counts" ]; then
    fail "$target/$label: Atrium never reported its module count"
    return
  fi
  read -r enabled total <<< "$counts"
  if [ "$RUN_MODE" = strict ] && [ "$enabled" != "$total" ]; then
    fail "$target/$label: only $enabled/$total modules enabled ($(failed_modules "$log" | tr '\n' ' '))"
  elif [ "$RUN_MODE" = partial ]; then
    note "$target/$label: $enabled/$total modules enabled (partial run)"
  else
    note "$target/$label: $enabled/$total modules enabled"
  fi

  if [ "$RUN_MODE" != expected-failure ]; then
    bad="$(grep -nE '(^|[^A-Za-z])(ERROR|Exception|Caused by)([^A-Za-z]|$)' "$log" \
      | grep -iE 'atrium|vault' | grep -vi 'Atrium-.*sources' || true)"
    if [ -n "$bad" ]; then
      fail "$target/$label: Atrium/Vault error lines:"
      printf '%s\n' "$bad" | head -30
      printf '%s\n' "$bad" >> "$dir/atrium-errors.txt"
    fi
  fi

  if grep -qE 'Module [a-z0-9_-]+ failed to reload|Reloaded, but these modules failed|Reload failed' "$log"; then
    reload_conflict="$(grep -nE 'failed to reload|Reloaded, but these modules failed|Reload failed' "$log" | head -10)"
    fail "$target/$label: reload reported a failure:"
    printf '%s\n' "$reload_conflict"
  fi

  conflicts="$(grep -nEi 'Failed to register command|already registered|Ambiguous command|conflicts with' "$log" | head -20 || true)"
  if [ -n "$conflicts" ]; then
    note "$target/$label: command registration conflicts:"
    printf '%s\n' "$conflicts"
    printf '%s\n' "$conflicts" >> "$dir/command-conflicts.txt"
  fi

  local done_line
  done_line="$(grep -o 'Done ([0-9.]*s)' "$log" | tail -1)"
  [ -n "$done_line" ] && note "$target/$label: startup $done_line"
}

# run_smoke <target> <label> [commands...]
run_smoke() {
  local target="$1" label="$2"; shift 2
  local dir="$ROOT/run/compat-$target"
  local out="$dir/$label.out"
  note "booting $target ($label)"
  if ! "$ROOT/scripts/smoke.sh" "run/compat-$target" "$@" > "$out" 2>&1; then
    if [ "$RUN_MODE" != expected-failure ]; then
      fail "$target/$label: scripts/smoke.sh exited with an error (see $out)"
    fi
  fi
  tail -3 "$out" | sed 's/^/    /'
  check_log "$target" "$label" "$dir/smoke.log" "$dir"
}

# Atrium command names come from the docs written by /atrium docs; plugin command names from the jars.
report_conflicts() {
  local target="$1" dir="$2" docs="$dir/plugins/Atrium/docs/COMMANDS.md"
  local report="$dir/command-report.txt"
  {
    echo "# Command overlap: Atrium vs installed plugins ($target)"
    if [ ! -f "$docs" ]; then
      echo "no Atrium docs (run /atrium docs first)"
      return
    fi
    python3 - "$docs" "$dir/plugins" <<'PY'
import re, sys, zipfile, pathlib
docs, plugdir = sys.argv[1], pathlib.Path(sys.argv[2])
text = pathlib.Path(docs).read_text(encoding='utf-8', errors='replace')
atrium = set()
for line in text.splitlines():
    m = re.match(r'\|\s*`/([A-Za-z0-9_-]+)', line)
    if m:
        atrium.add(m.group(1).lower())
all_cmds = {}
for jar in sorted(plugdir.glob('*.jar')):
    if jar.name.startswith(('Atrium', 'Vault')):
        continue
    names = set()
    try:
        with zipfile.ZipFile(jar) as z:
            for entry in ('plugin.yml', 'paper-plugin.yml'):
                try:
                    raw = z.read(entry).decode('utf-8', 'replace')
                except KeyError:
                    continue
                in_cmds = False
                for line in raw.splitlines():
                    if re.match(r'^commands:\s*$', line):
                        in_cmds = True
                        continue
                    if in_cmds:
                        if line and not line.startswith((' ', '\t')):
                            in_cmds = False
                        else:
                            m = re.match(r'^  ([A-Za-z0-9_-]+):\s*$', line)
                            if m:
                                names.add(m.group(1).lower())
    except zipfile.BadZipFile:
        continue
    if names:
        all_cmds[jar.name] = names
print(f"Atrium commands: {len(atrium)}")
for jar, names in all_cmds.items():
    overlap = sorted(atrium & names)
    print(f"{jar}: {len(names)} commands" + (f"; overlaps Atrium: {', '.join(overlap)}" if overlap else ""))
PY
  } > "$report" 2>&1
  sed 's/^/  /' "$report"
  if grep -q 'overlaps Atrium' "$report"; then
    note "command overlaps found, see $report"
  fi
}

# ---- targets ---------------------------------------------------------------------------------

paper_server() {
  local meta url sha build
  meta="$(curl -fsSL "https://fill.papermc.io/v3/projects/paper/versions/$PAPER_VERSION/builds/latest")" \
    || die "cannot read fill.papermc.io metadata for Paper $PAPER_VERSION"
  url="$(printf '%s' "$meta" | python3 -c 'import json,sys; d=json.load(sys.stdin)["downloads"]["server:default"]; print(d["url"])')"
  sha="$(printf '%s' "$meta" | python3 -c 'import json,sys; d=json.load(sys.stdin)["downloads"]["server:default"]; print(d["checksums"]["sha256"])')"
  build="$(printf '%s' "$meta" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')"
  download "paper-$PAPER_VERSION-build-$build.jar" "$url" "sha256:$sha" \
    || die "cannot download Paper $PAPER_VERSION build $build"
  echo "Paper $PAPER_VERSION build $build|$CACHE/paper-$PAPER_VERSION-build-$build.jar"
}

run_paper() {
  local target=paper dir server source info jar
  info="$(paper_server)"; jar="${info##*|}"
  note "server: ${info%%|*}"
  source="$ROOT/run/paper"
  dir="$(prepare_dir paper 25750 "$jar" "$source" paper)"
  install_atrium "$dir"
  install_plugins paper "$dir" "$dir/plugin-inventory.txt"

  local cmds=("atrium version" "atrium modules" "plugins" "integrations" "vault-info" "atrium reload" "atrium reload" "atrium reload" "atrium docs" "atrium help")
  grep -q '^papi|' "$dir/plugin-inventory.txt" && cmds+=("papi" "papi list")
  grep -q '^luckperms|' "$dir/plugin-inventory.txt" && cmds+=("lp info")
  run_smoke paper main "${cmds[@]}"
  exercise_command_precedence "$dir"
  report_conflicts paper "$dir"
  write_report paper "$dir" "${info%%|*}"
}

# EssentialsX is installed on purpose: prove that core can hand duplicate command names back to it.
exercise_command_precedence() {
  local dir="$1" config="$dir/plugins/Atrium/config.yml" log skipped
  grep -q '^essentialsx|.*|installed$' "$dir/plugin-inventory.txt" || return 0
  [ -f "$config" ] || return 0
  cp "$config" "$dir/config.yml.prefer-bak"
  printf '\nprefer-other-plugin-commands: true\n' >> "$config"
  note "paper: checking command precedence with prefer-other-plugin-commands"
  run_smoke paper coexist "atrium modules" "pay" "home" "kit" "vanish"
  skipped="$(grep -o 'Skipped [0-9]* Atrium commands' "$dir/smoke.log" | tail -1)"
  if [ -z "$skipped" ]; then
    fail "paper/coexist: Atrium did not report skipped conflicting commands"
  else
    note "paper/coexist: $skipped (EssentialsX keeps them)"
  fi
  mv "$dir/config.yml.prefer-bak" "$config"
}

run_sourby() {
  local target=sourby dir source
  download "$SOURBY_FILE" "$SOURBY_URL" "sha256:$SOURBY_SHA256" || die "cannot download SourbyCraft $SOURBY_TAG"
  note "server: SourbyCraft $SOURBY_TAG"
  source="$ROOT/run/sourbycraft-r50"
  dir="$(prepare_dir sourby 25751 "$CACHE/$SOURBY_FILE" "$source" "$SOURBY_FILE")"
  install_atrium "$dir"
  install_plugins sourby "$dir" "$dir/plugin-inventory.txt"

  local cmds=("atrium version" "atrium modules" "plugins" "integrations" "vault-info" "atrium reload" "atrium reload" "atrium reload" "atrium docs" "atrium help")
  grep -q '^papi|' "$dir/plugin-inventory.txt" && cmds+=("papi" "papi list")
  grep -q '^luckperms|' "$dir/plugin-inventory.txt" && cmds+=("lp info")
  run_smoke sourby main "${cmds[@]}"
  report_conflicts sourby "$dir"
  write_report sourby "$dir" "SourbyCraft $SOURBY_TAG"
}

write_report() {
  local target="$1" dir="$2" server="$3"
  {
    echo "Atrium compatibility report"
    echo "target: $target"
    echo "server: $server"
    echo "date: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo
    echo "plugins:"
    sed 's/^/  /' "$dir/plugin-inventory.txt"
    echo
    echo "startup:"
    grep -E 'Atrium .* enabled|Enabled [0-9]+/[0-9]+ modules|Done \(' "$dir/smoke.log" | tail -8 | sed 's/^/  /'
    if [ -s "$dir/atrium-errors.txt" ]; then
      echo
      echo "atrium errors:"
      sed 's/^/  /' "$dir/atrium-errors.txt"
    fi
    if [ -s "$dir/command-conflicts.txt" ]; then
      echo
      echo "registration conflicts:"
      sed 's/^/  /' "$dir/command-conflicts.txt"
    fi
  } > "$dir/report.txt"
}

# ---- stability -------------------------------------------------------------------------------

stability_paper() {
  local dir="$ROOT/run/compat-paper"
  if [ "$STABILITY_ONLY" = 1 ] && [ -d "$dir" ]; then
    # Start from a clean Atrium data folder: the suite must not inherit a database written by another build.
    rm -rf "$dir/plugins/Atrium" "$dir/world" "$dir/world_nether" "$dir/world_the_end"
    note "stability: reset the Atrium data folder"
  fi
  if [ ! -f "$dir/plugins/Atrium/modules.yml" ]; then
    note "stability: modules.yml does not exist yet; the first boot creates it"
  fi

  note "stability: 10x /atrium reload"
  local reloads=()
  local i
  for i in $(seq 1 10); do reloads+=("atrium reload"); done
  run_smoke paper reload10 "atrium modules" "${reloads[@]}" "atrium modules"

  note "stability: module toggles through modules.yml in two batches"
  local all_modules batch
  all_modules="$(sed -n 's/^\([a-z0-9_-]*\):.*/\1/p' "$dir/plugins/Atrium/modules.yml" | tr '\n' ' ')"
  local count=0 total=0
  for _ in $all_modules; do total=$((total + 1)); done
  local half=$(((total + 1) / 2))
  batch=""
  for m in $all_modules; do
    batch="$batch $m"
    count=$((count + 1))
    if [ "$count" = "$half" ]; then
      break
    fi
  done
  toggle_batch paper "off1" $batch
  restore_toggles "$dir" $all_modules
  run_smoke paper "on1" "atrium modules"
  batch=""
  count=0
  for m in $all_modules; do
    count=$((count + 1))
    [ "$count" -gt "$half" ] && batch="$batch $m"
  done
  toggle_batch paper "off2" $batch
  restore_toggles "$dir" $all_modules
  run_smoke paper "on2" "atrium modules"

  note "stability: 3 start/stop cycles on one data folder"
  local n
  for n in 1 2 3; do
    run_smoke paper "cycle$n" "atrium modules"
  done
  if grep -qiE 'migrat.*(fail|error)|table .* already exists|duplicate column' "$dir/smoke.log"; then
    fail "stability: migration error on a repeated start"
  fi

  note "stability: corrupted module config must disable only that module"
  cp "$dir/plugins/Atrium/modules/homes.yml" "$dir/homes.yml.bak" 2>/dev/null || true
  printf 'this: [is: not: valid: yaml\n  - nope\n' > "$dir/plugins/Atrium/modules/homes.yml"
  RUN_MODE=expected-failure
  run_smoke paper corrupt "atrium modules"
  RUN_MODE=strict
  if ! grep -q 'Module homes failed to enable' "$dir/smoke.log"; then
    fail "stability: a corrupted homes.yml did not disable just that module"
  fi
  if [ -f "$dir/homes.yml.bak" ]; then mv "$dir/homes.yml.bak" "$dir/plugins/Atrium/modules/homes.yml"; else rm -f "$dir/plugins/Atrium/modules/homes.yml"; fi

  note "stability: missing lang key falls back without exceptions"
  cp "$dir/plugins/Atrium/config.yml" "$dir/config.yml.bak"
  mkdir -p "$dir/plugins/Atrium/lang/zz_ZZ"
  printf 'prefix: "<gray>[Atrium] "\n' > "$dir/plugins/Atrium/lang/zz_ZZ/core.yml"
  sed -i.bak 's/^default-locale:.*/default-locale: zz_ZZ/' "$dir/plugins/Atrium/config.yml"
  run_smoke paper langfallback "atrium version" "atrium modules"
  if grep -qE 'Exception.*(message|lang|key)|Missing message key' "$dir/smoke.log"; then
    fail "stability: a missing lang key produced an exception"
  fi
  mv "$dir/config.yml.bak" "$dir/plugins/Atrium/config.yml"
  rm -f "$dir/plugins/Atrium/config.yml.bak"
  rm -rf "$dir/plugins/Atrium/lang/zz_ZZ"
}

toggle_batch() {
  local target="$1" label="$2"; shift 2
  local dir="$ROOT/run/compat-$target" m
  for m in "$@"; do
    sed -i.bak "s/^$m: .*/$m: false/" "$dir/plugins/Atrium/modules.yml"
  done
  RUN_MODE=partial
  run_smoke "$target" "$label" "atrium modules"
  RUN_MODE=strict
  local counts enabled total
  counts="$(enabled_counts "$dir/smoke.log")"
  if [ -z "$counts" ]; then
    fail "stability: batch $label did not reach a module count"
    return
  fi
  read -r enabled total <<< "$counts"
  if [ "${enabled:-0}" -ge "${total:-0}" ]; then
    fail "stability: disabling modules in modules.yml was not applied ($enabled/$total still enabled)"
  fi
  note "stability: batch $label left $enabled/$total modules enabled"
}

restore_toggles() {
  local dir="$1"; shift
  local m
  for m in "$@"; do
    sed -i.bak "s/^$m: .*/$m: true/" "$dir/plugins/Atrium/modules.yml" 2>/dev/null
  done
  rm -f "$dir/plugins/Atrium/modules.yml.bak"
}

# ---- main ------------------------------------------------------------------------------------

build_jars
snapshot_jars
if [ "$STABILITY_ONLY" = 0 ]; then
  for target in $TARGETS; do
    case "$target" in
      paper) run_paper ;;
      sourby) run_sourby ;;
      *) die "unknown target $target" ;;
    esac
  done
fi

if [ "$STABILITY" = 1 ]; then
  [ "$STABILITY_ONLY" = 1 ] && TARGETS="paper"
  case " $TARGETS " in
    *" paper "*) stability_paper ;;
    *) note "stability suite currently runs on the paper target only" ;;
  esac
fi

echo
if [ "$FAILURES" -ne 0 ]; then
  echo "COMPAT: FAILED ($FAILURES check(s), see run/compat-*/*.out and report.txt)"
  exit 1
fi
echo "COMPAT: OK"
