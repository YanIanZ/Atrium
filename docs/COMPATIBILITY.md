# Compatibility

Atrium is tested against real Paper and SourbyCraft servers with the plugins most servers run.
`scripts/compat-smoke.sh` builds those servers, installs pinned official builds, boots them and fails on
Atrium/Vault errors, modules that do not enable, failed reloads and command-registration collisions.

```bash
scripts/compat-smoke.sh --target paper     # Paper target (also what CI runs)
scripts/compat-smoke.sh --all              # Paper + SourbyCraft
scripts/compat-smoke.sh --target paper --stability
scripts/compat-smoke.sh --stability-only   # stability suite against the existing run/compat-paper
```

Artifacts live in `run/compat-paper/report.txt`, `command-report.txt`, `smoke.log` and `*.out`.
Plugin versions and checksums are pinned in `scripts/compat-plugins.lock`; every jar is checked before it is
installed. The Paper runtime comes from `fill.papermc.io` (sha256 checked, latest build of the requested
version). The SourbyCraft target uses the pinned `YanIanZ/SourbyCraft` release asset (sha256 checked).

## Tested matrix

Tested on 2026-11-03 with Atrium `1.0.0`, Atrium Vault `1.7.3-atrium.2`,
Paper `26.1.2` build 74 and SourbyCraft `26.2-r50` (Folia).

| Plugin | Version | Paper 26.1.2 | SourbyCraft 26.2 | Notes |
|---|---|---|---|---|
| Atrium | 1.0.0 | pass | pass | 60/60 modules enabled on both |
| Atrium Vault | 1.7.3-atrium.2 | pass | pass | economy provider registered; guard active |
| LuckPerms | 5.5.71 | pass | pass | metadata provider active (`/integrations`) |
| PlaceholderAPI | 2.12.3 | pass | pass | `/papi` responds; no placeholder errors |
| WorldEdit | 7.4.6 | pass | pass | commands coexist |
| WorldGuard | 7.0.18 | pass | pass | protection hook active |
| DecentHolograms | 2.10.2 | pass | pass | display hook detected |
| PacketEvents | 2.14.0 | pass | pass | packet hook detected |
| ProtocolLib | 5.4.0 | pass | pass | warns “has not yet been tested” for 26.x; no failure |
| TAB | 6.2.1 | pass | not installed | paper-only target; Atrium scoreboard detects TAB |
| Multiverse-Core | 5.8.2 | pass | skipped | jar declares no `folia-supported: true` |
| EssentialsX | 2.22.0 | pass | skipped | command overlap by design; see below |

Skipped on purpose:

- **Geyser + Floodgate** – optional and heavy; the harness does not boot a Bedrock stack.
- **EconomyShopGUI** – no public official release endpoint with a verifiable checksum.
- **TAB on SourbyCraft** – the 6.2.1 Bukkit adapter is pinned for the Paper target only. Atrium's own tablist
  and scoreboard modules are exercised there without TAB.
- **Multiverse-Core / EssentialsX on SourbyCraft** – neither jar declares `folia-supported: true`, so Folia
  refuses to load them. The harness skips them automatically instead of producing a false failure.

## Command overlap (EssentialsX, WorldEdit, ...)

`/atrium docs` listed 310 Atrium commands when this ran. EssentialsX 2.22.0 declares 153 commands and 89 of
them share a name with Atrium (for example `home`, `sethome`, `pay`, `kit`, `tpa`, `warp`, `fly`, `gamemode`,
`seen`, `vanish`, `tempban`, `mute`, `ptime`, `pweather`). Paper keeps the Brigadier command in that case,
which silently hides the EssentialsX command. On the test server, more than 100 names/aliases overlapped
once aliases were counted.

New core option (requires a restart):

```yaml
# plugins/Atrium/config.yml
# explicit skips always win
disabled-commands: []
# keep the other plugin's command whenever the name (or an Atrium alias) is already registered
prefer-other-plugin-commands: false
```

With `prefer-other-plugin-commands: true`, Atrium skips its own command or alias for every name another
plugin already registered (detected through the Bukkit command map) and reports the decision:

```
[Atrium] Registered 199 commands
[Atrium] Skipped 111 Atrium commands that another plugin also registers (commands.prefer-other-plugin-commands)
```

Without it, Atrium logs a single summary so the overlap is visible instead of silent:

```
[Atrium] ... Atrium command names are also registered by other plugins (afk, anvil, back, bal, balance, ...);
set commands.prefer-other-plugin-commands: true to keep theirs and skip Atrium's
```

The setting is deliberately opt-in: a network that wants Atrium's `/home`, `/warp` or `/pay` keeps them by
default, while a server migrating from EssentialsX can let EssentialsX win with one line. `disabled-commands`
still works for individual names and always takes precedence.

## Stability suite

`scripts/compat-smoke.sh --stability` (Paper target) passes:

| Check | Result |
|---|---|
| 10× `/atrium reload` | 60/60 modules before and after, no failed reload |
| `modules.yml` off in two batches | 29/60 enabled while off, 60/60 after restoring |
| 3 start/stop cycles on one data folder | 60/60 each boot, no migration or duplicate-column error |
| corrupted `modules/homes.yml` | 59/60: only `homes` failed, the rest kept running |
| missing lang key (`default-locale: zz_ZZ`) | 60/60, falls back to English, no exception |

The corrupted-config run logs `Module homes failed to enable` once and then keeps serving the other 59
modules, which is the intended isolation. `smoke.sh` reports that boot as an Atrium error, so the harness runs
that single check in `expected-failure` mode and asserts the isolation explicitly.

The suite snapshots the Atrium jar once and gives every boot the same verified file. Without that, a Gradle
build running in another worktree can rewrite `atrium-plugin/build/libs` mid-suite and produce false
`Provider dev.yanianz.atrium.module.rtp.RtpModule not found` or schema errors in the next boot.

## Known behaviour and gaps

- **`modules.yml` needs a restart.** Commands are registered once through Paper's lifecycle API, so
  `/atrium reload` cannot safely start or stop a module. Reload now compares `modules.yml` with the running
  state and warns which modules need a restart instead of silently ignoring the edit. The stability suite
  therefore toggles modules in batches with a server start, not with a reload.
- **Atrium warnings seen with plugins installed:** Atrium's scoreboard module reports
  `Sidebar backend 'tab' is not used: TAB's scoreboard feature is disabled in TAB's config; using bukkit`
  when TAB is present but its scoreboard feature is off. It is informational, but it is logged at WARN and
  is a candidate for INFO.
- **SourbyCraft startup is slower on first boot** (106 s vs 53 s on Paper) because Aurora, ViaVersion and
  ViaBackwards initialise; later boots on the same folder are faster. No Atrium error is involved.
- **ProtocolLib 5.4.0** logs `Version (MC: 26.1.2/26.2.0) has not yet been tested!` on both targets. This is
  ProtocolLib's own version gate, not an Atrium problem.
- **SourbyCraft auto-provisions ViaVersion/ViaBackwards** into `plugins/` on first start, with its own
  SHA-256 verification. They are outside the lock file because the server downloads them itself.
- **Data folder from a different build:** one early run mixed jars from concurrent build jobs on the same
  folder and moderation logged
  `Could not read the chat mute state; chat stays unmuted` /
  `SQLITE_ERROR ... (no such column: expires_at)` and continued with chat unmuted. It did not reproduce from a
  clean folder with one pinned jar, and Atrium degraded instead of disabling the module.

## Adding a plugin

1. Pick an official download (Modrinth API, Hangar API or a GitHub release) with a sha512/sha256.
2. Add one `id|targets|version|file|digest|url` line to `scripts/compat-plugins.lock`.
3. Run `scripts/compat-smoke.sh --all`. A plugin without `folia-supported: true` is skipped on SourbyCraft
   automatically; list it as “skipped” in the matrix above if it is not usable there.
