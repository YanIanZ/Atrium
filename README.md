# Atrium

Modular, Folia-native server essentials for Paper and Folia 26.1.2+, written from scratch in Java 25.

Atrium covers the everyday toolkit of a survival or network server (teleports, homes, warps, economy, chat,
moderation, kits, holograms, NPCs, tab list, scoreboards and more) in one plugin made of independent modules.
Its scope is comparable to CMI; it is a separate, independent implementation (see [Disclaimer](#disclaimer)).

## Features

- **Folia-native.** Every entity, block and world access runs on the thread that owns it. No `BukkitScheduler`,
  no NMS, no reflection into server internals. The same jar runs on Paper.
- **Safe by design.** GUIs cancel every click unless a slot is declared as an input slot, sales remove items before
  paying, player-typed text is never parsed as MiniMessage or PlaceholderAPI, proxy messages are HMAC-authenticated,
  and numeric permissions (`atrium.homes.5`) are evaluated live, never cached.
- **Transactional storage.** SQLite (default) or MySQL/MariaDB through HikariCP, with versioned migrations. A failed
  write is never reported as success.
- **One theme.** Every message uses theme tags (`<primary>`, `<muted>`, `<success>`, ...) that you restyle in one
  place. Every GUI uses the same menu toolkit.
- **Modular.** 61 modules, each of which can be switched off in `modules.yml`. A disabled module registers no
  commands, listeners, tasks or permissions.
- **Bilingual out of the box.** English (`en_US`) and Indonesian (`id_ID`) texts are included; add a folder for
  any other locale. Players see their client language when a translation exists.

> Catatan: Atrium menyertakan teks bawaan dalam bahasa Inggris dan bahasa Indonesia (`id_ID`).

## Requirements

| | |
|---|---|
| Server | Paper or Folia **26.1.2** or newer. SourbyCraft is supported (see [docs/SOURBYCRAFT.md](docs/SOURBYCRAFT.md)). |
| Java | **25** |
| Database | SQLite (built in, default) or MySQL / MariaDB |

## Install

1. Download `Atrium-<version>.jar` from the [releases page](https://github.com/YanIanZ/Atrium/releases) and put it
   into `plugins/`.
2. Start the server. Paper's plugin loader downloads HikariCP, Caffeine, Configurate and the JDBC drivers from
   Maven Central on the first start, so that start needs internet access.
3. Edit the files in `plugins/Atrium/`, then run `/atrium reload` (or restart for the settings that say so).

## Configuration

| File | Purpose |
|---|---|
| `config.yml` | Default locale, database (SQLite/MySQL/MariaDB), save intervals, disabled commands, and the `theme` section (colours, prefix, GUI filler, sounds and click debounce). |
| `modules.yml` | One `true`/`false` per module. Read at startup. |
| `modules/<module>.yml` | Settings of one module. Every option is commented. |
| `lang/<locale>/<module>.yml` | MiniMessage texts. Missing keys are added automatically on load. |

The full key reference is in [docs/CONFIGURATION.md](docs/CONFIGURATION.md).

### Admin commands

| Command | What it does |
|---|---|
| `/atrium help [page\|module]` | Lists every command you may use, filtered by your permissions. |
| `/atrium menu` | The same list as a GUI. |
| `/atrium modules` | Shows which modules are enabled. |
| `/atrium reload` | Reloads `config.yml`, the theme, lang files and module settings. |
| `/atrium docs` | Writes `COMMANDS.md` and `PERMISSIONS.md` for your exact configuration into `plugins/Atrium/docs/`. |

The reference generated from the default configuration is in [docs/COMMANDS.md](docs/COMMANDS.md) and
[docs/PERMISSIONS.md](docs/PERMISSIONS.md).

## Migrating from CMI, EssentialsX or another economy plugin

Run the importer from the console, with the old plugin's data folder still in `plugins/`:

```text
/atrium-backup                                # snapshot the SQLite database first
/atrium-import sources                        # what was found and what each source can import
/atrium-import cmi dry-run                    # or: essentials | vanilla, counts only, nothing is written
/atrium-import cmi run                        # import
/atrium-import economy XConomy dry-run        # balances from any other Vault economy plugin
/atrium-import economy XConomy run
/atrium-import status                         # progress of a running import
```

| Source | Command | Imports |
|---|---|---|
| CMI | `/atrium-import cmi` | balances, homes, warps, nicknames, playtime, holograms, spawn and first-join spawn, kits, jails, mutes, last location (`/back`) |
| EssentialsX | `/atrium-import essentials` | balances, homes, warps, nicknames, spawn and first-join spawn, kits, jails, mutes, jail sentences, bans, mail, logout location (`/back`) |
| Vanilla ban lists | `/atrium-import vanilla` | `banned-players.json` and `banned-ips.json` (bans, temp bans, IP bans); also read by the CMI and EssentialsX imports |
| Vault economy | `/atrium-import economy <plugin>` | balances from any other plugin registered as a Vault economy (RoyaleEconomy, XConomy, EssentialsX, CMI, TheNewEconomy, ...) |

Options: `--only=<categories>` (for example `--only=warps,spawn,kits`) and `--overwrite`. Imports are idempotent:
an import ledger remembers what was imported per source, and spawn points, warps, kits and jails that already
exist are skipped unless `--overwrite` is given. Player data is all-or-nothing per player, and every run writes a
report file listing, per category, what was imported, skipped or failed and why. Data Atrium cannot store (ignore
lists, per-group spawns, kit commands and similar) is listed in the report instead of being dropped silently.
The full per-source matrix is in [docs/MIGRATION.md](docs/MIGRATION.md).

## Optional integrations

Each integration is detected at runtime. Without the other plugin the feature falls back to a built-in
implementation or turns off cleanly.

| Plugin | Used for |
|---|---|
| Vault / VaultUnlocked | Economy provider for the v1 and v2 APIs (Atrium registers itself) and chat metadata fallback |
| LuckPerms | Prefixes, suffixes and groups for chat, tab list and ranks |
| PlaceholderAPI | `%atrium_*%` placeholders, and placeholders in admin-written templates |
| DecentHolograms / FancyHolograms | Alternative hologram engines (`modules/holograms.yml` → `engine`) |
| Citizens / FancyNpcs | Alternative NPC engines (`modules/npcs.yml` → `engine`); the native engine uses Paper mannequins |
| WorldGuard / GriefPrevention / Lands / Towny | Protection checks for spawn, replace and edit tools |
| BlueMap / squaremap / dynmap | Warp, spawn and portal markers; vanished players are hidden |
| PacketEvents | Sidebar scoreboards on Folia |
| TAB (by NEZNAMY) | Alternative tab list and sidebar engine: Atrium renders, TAB displays (`modules/tablist.yml` → `engine`, `modules/scoreboard.yml` → `backend`; `/tablist engine`, `/sidebar engine`) |
| Multiverse-Core 5 / Advanced Slime Paper | World providers of `/wtp`, `/worlds`, `/worldinfo`: list and load known-but-unloaded worlds, Multiverse aliases, spawns and access permissions, slime worlds from the configured loader (`modules/worlds.yml`); SourbyCraft AWF and profile worlds through the `sourbycraft` module |
| Floodgate (Geyser) | Dialogs shown as native Bedrock forms |
| Velocity / BungeeCord | `/server`, `/glist`, cross-server chat, staff chat, helpop and punishment sync over HMAC-authenticated plugin messages |

## Modules

| Area | Modules |
|---|---|
| Core gameplay | `player`, `playerplus`, `teleport`, `homes`, `warps`, `rtp`, `portals`, `elytra`, `seating`, `worlds` |
| Economy | `economy` (with Vault provider), `worth` (sell GUI), `kits`, `commandrules` (costs, cooldowns, warmups) |
| Social | `chat`, `mail`, `nick`, `messaging`, `chatbubbles`, `deathmessages`, `signs`, `skins`, `afk`, `seen`, `extras` |
| Staff | `staff` (staff mode, `/sudo`, `/commandspy`, `/spectate`), `moderation`, `vanish`, `inspect`, `inventories`, `search`, `performance`, `mobs`, `worldtools`, `worldrules`, `itemtools`, `recipes` |
| Presentation | `particles`, `effects`, `holograms`, `npcs`, `tablist`, `scoreboard`, `bossbar`, `customtext`, `dialogs`, `interactive`, `aliases`, `actions`, `armorstand` |
| Progression | `playtime`, `ranks` |
| Network and integrations | `sourbycraft`, `localeeditor`, `proxy`, `discord`, `placeholders`, `maps`, `integrations`, `importer` |

## Atrium Vault (Vault replacement)

`atrium-vault/` builds a Folia-supporting drop-in replacement for the [Vault](https://github.com/MilkBowl/Vault)
plugin and for [VaultUnlocked](https://github.com/TheNewEconomy/VaultUnlocked) (the modern Vault fork with the v2 API),
released as `Vault-<version>.jar`. Remove the old `Vault.jar` (or VaultUnlocked) and put this one into `plugins/`. It is
optional: Atrium also works with the original Vault, with VaultUnlocked or without Vault.

- **Detected as Vault and as VaultUnlocked.** `plugin.yml` declares `name: Vault`, `main: net.milkbowl.vault.Vault`,
  `provides: [VaultUnlocked]`, `load: STARTUP` and `folia-supported: true`. Both APIs are bundled unmodified and
  unrelocated, so plugins compiled against either link exactly as before:
  - VaultAPI 1.7.1 (`net.milkbowl.vault.economy`, `.permission`, `.chat`) — the legacy **v1** API;
  - VaultUnlockedAPI 2.20 (`net.milkbowl.vault2.*`: `economy.Economy`, `permission.PermissionUnlocked`,
    `chat.ChatUnlocked`, ...) — the **v2** API with `BigDecimal` amounts, UUID accounts, currencies and worlds.
- **Version `2.20.0-atrium.N`.** VaultUnlocked itself is also called `Vault` and reports `2.20.x`; plugins that support
  both APIs pick v2 when the `Vault` version does not start with `1` (iConomyUnlocked does exactly this), and v1
  plugins compare 2.20 as newer than 1.7. A `1.7.x` version would make such plugins fall back to v1.
- **GuardedEconomy** (v1) and **GuardedEconomyV2** (v2), both priority Highest, forward to the best other provider of
  their API. v1: rejects NaN, infinite or negative amounts, amounts above `guard.max-amount` and null names. v2: rejects
  null, negative, too precise (`guard.max-scale` decimal places, trailing zeros ignored; `guard.max-precision`
  digits) and too large (`guard.max-amount`) amounts and null arguments. Provider exceptions (v2 also
  `AbstractMethodError` from providers built against an older v2 API) become `FAILURE`/`NOT_IMPLEMENTED` responses and
  are logged (rate-limited); calls that block a tick thread are reported with the calling plugin. Without a provider
  a guard reports `isEnabled() == false`. The guards never delegate to themselves or to each other. An optional
  asynchronous audit log records every v1 withdraw/deposit and v2 withdraw/deposit/set/transfer (exact amounts, the
  calling plugin and the v2 `pluginName`) in `plugins/Vault/audit/`. Set `guard.enabled: false` for plain Vault
  behaviour. The v2 guard does not expose a provider's `async()` API: async users fall back to the guarded calls.
- **v1/v2 bridges** (`bridge.enabled`). If only a v1 economy is registered (EssentialsX, ...), it is also registered
  as a v2 provider (UUID → `OfflinePlayer`; balances rounded HALF_EVEN to the provider's `fractionalDigits`; an
  amount is passed only if it has no more decimals than the provider supports and is an exact double, else the call
  fails and nothing moves). If only a v2 economy is registered, it is also registered as a v1 provider (double
  amounts rounded HALF_EVEN to the provider's digits; the calling plugin is passed as `pluginName`; names resolved
  without blocking; no banks). When both APIs have a native provider no bridge is registered. Bridges use priority
  Lowest and appear as `BRIDGED: ...` in `/vault-info`.
- **SuperPerms fallback** permission provider (v1, priority Lowest, no groups). LuckPerms, Atrium and other modern
  plugins register their own providers.
- `/vault-info` lists every v1 and v2 Economy, Permission and Chat provider, the active one, the bridge state and both
  guards. `/vault-convert <from>[:v1|:v2] <to>[:v1|:v2]` dry-runs a balance copy between two economy plugins (without
  a suffix the plugin's v1 provider is used, else its v2 one); add `confirm` to apply it. v1 to v1 works as before;
  anything with a v2 side is computed in exact decimal (exact end to end between two v2 providers). The source
  economy is only read. Permission: `vault.admin` (default: op).

**License notice.** The bundled VaultAPI classes are copyright MilkBowl and contributors, the bundled
VaultUnlockedAPI classes copyright The New Economy and the Vault contributors; both are licensed under the GNU LGPL
v3.0. The licenses, the GPL v3.0 text they incorporate and a notice for each are included in the jar's `META-INF/`.
Sources: <https://github.com/MilkBowl/VaultAPI>, <https://github.com/TheNewEconomy/VaultUnlockedAPI>. Everything else
in the jar (`net.milkbowl.vault.Vault` and `dev.yanianz.atrium.vault.*`) is original Atrium code, not derived from Vault
or VaultUnlocked. See [NOTICE](NOTICE).

## Releases

Versions are plain semantic versions (`MAJOR.MINOR.PATCH`, e.g. `1.0.3`).

- Every push to `main` publishes the next patch release of the line in `gradle.properties` (`1.0.0`, `1.0.1`, ...).
- A new feature line starts by raising `version` in `gradle.properties` (e.g. `1.1.0`); a tag `vX.Y.Z` publishes exactly
  that version.
- Every release contains `Atrium-<version>.jar`, `Vault-<version>.jar` (Atrium Vault) and `SHA256SUMS`. Verify a
  download with `sha256sum -c SHA256SUMS`.

Changes are listed in [CHANGELOG.md](CHANGELOG.md). `/updates` (performance module) tells operators about newer releases.

## Build from source

The source is published for transparency and review; see [LICENSE](LICENSE) for what you may do with it.

```sh
./gradlew build                  # every module + tests -> atrium-plugin/build/libs/Atrium-<version>.jar
./gradlew :atrium-vault:build    # -> atrium-vault/build/libs/Vault-<version>.jar
```

The `compat/sourbyapi-26.2` subproject downloads the SourbyCraft API sources at build time (pinned commit, every
file checked against a SHA-256 in `sources.lock`). For an offline build, point it at a local SourbyCraft clone that
contains the pinned commit:

```sh
./gradlew build -PsourbycraftRepo=/path/to/SourbyCraft
```

Local test servers and smoke tests are described in [CONTRIBUTING.md](CONTRIBUTING.md); the architecture rules
every module follows are in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

## Documentation

See the [documentation index](docs/README.md): configuration, commands, permissions, SourbyCraft support and the
roadmap.

## Support and security

- Bugs and feature requests: [GitHub issues](https://github.com/YanIanZ/Atrium/issues).
- Security vulnerabilities: report privately, see [SECURITY.md](SECURITY.md).

## Disclaimer

Atrium is an independent project. It is not affiliated with, endorsed by or derived from CMI or Zrips, EssentialsX,
Vault or any other plugin mentioned here. Minecraft is a trademark of Mojang AB; CMI, EssentialsX, Vault and the
other names are trademarks or names of their respective owners.

## License

Proprietary. See [LICENSE](LICENSE). Third-party components and their licenses are listed in [NOTICE](NOTICE).
