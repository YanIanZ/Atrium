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
- **Modular.** 59 modules, each of which can be switched off in `modules.yml`. A disabled module registers no
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

## Migrating from CMI or EssentialsX

Run the importer from the console:

```text
/atrium-backup                         # snapshot the SQLite database first
/atrium-import cmi dry-run             # or: /atrium-import essentials dry-run
/atrium-import cmi run                 # import
/atrium-import status                  # progress of a running import
```

The importer brings over balances, homes, warps, nicknames, playtime and holograms. Options: `--only=<categories>`
(for example `--only=economy,homes`) and `--overwrite`. Imports are idempotent (an import ledger skips records that
were already imported) and all-or-nothing per player, and every run writes a report file. Data Atrium cannot store
yet (kits, mail, ignore lists, jail/mute/ban states and similar) is listed in the report instead of being dropped
silently.

## Optional integrations

Each integration is detected at runtime. Without the other plugin the feature falls back to a built-in
implementation or turns off cleanly.

| Plugin | Used for |
|---|---|
| Vault | Economy provider (Atrium registers itself) and chat metadata fallback |
| LuckPerms | Prefixes, suffixes and groups for chat, tab list and ranks |
| PlaceholderAPI | `%atrium_*%` placeholders, and placeholders in admin-written templates |
| DecentHolograms / FancyHolograms | Alternative hologram engines (`modules/holograms.yml` → `engine`) |
| Citizens / FancyNpcs | Alternative NPC engines (`modules/npcs.yml` → `engine`); the native engine uses Paper mannequins |
| WorldGuard / GriefPrevention / Lands / Towny | Protection checks for spawn, replace and edit tools |
| BlueMap / squaremap / dynmap | Warp, spawn and portal markers; vanished players are hidden |
| PacketEvents | Sidebar scoreboards on Folia |
| Floodgate (Geyser) | Dialogs shown as native Bedrock forms |
| Velocity / BungeeCord | `/server`, `/glist`, cross-server chat, staff chat, helpop and punishment sync over HMAC-authenticated plugin messages |

## Modules

| Area | Modules |
|---|---|
| Core gameplay | `player`, `playerplus`, `teleport`, `homes`, `warps`, `rtp`, `portals`, `elytra`, `seating` |
| Economy | `economy` (with Vault provider), `worth` (sell GUI), `kits`, `commandrules` (costs, cooldowns, warmups) |
| Social | `chat`, `mail`, `nick`, `messaging`, `chatbubbles`, `deathmessages`, `signs`, `skins`, `afk`, `seen`, `extras` |
| Staff | `staff` (staff mode, `/sudo`, `/commandspy`, `/spectate`), `moderation`, `vanish`, `inspect`, `inventories`, `search`, `performance`, `mobs`, `worldtools`, `worldrules`, `itemtools`, `recipes` |
| Presentation | `particles`, `effects`, `holograms`, `npcs`, `tablist`, `scoreboard`, `bossbar`, `customtext`, `dialogs`, `interactive`, `aliases`, `actions`, `armorstand` |
| Progression | `playtime`, `ranks` |
| Network and integrations | `sourbycraft`, `localeeditor`, `proxy`, `discord`, `placeholders`, `maps`, `integrations`, `importer` |

## Atrium Vault (Vault replacement)

`atrium-vault/` builds a Folia-supporting drop-in replacement for the [Vault](https://github.com/MilkBowl/Vault)
plugin, released as `Vault-<version>.jar`. Remove the old `Vault.jar` and put this one into `plugins/`. It is
optional: Atrium also works with the original Vault or without Vault.

- **Detected as Vault.** `plugin.yml` declares `name: Vault`, `main: net.milkbowl.vault.Vault`, `load: STARTUP` and
  `folia-supported: true`; the version has the form `1.7.3-atrium.N`. The VaultAPI 1.7.1 classes
  (`net.milkbowl.vault.economy`, `.permission`, `.chat`) are bundled unmodified and unrelocated, so plugins compiled
  against Vault link exactly as before.
- **GuardedEconomy** (priority Highest) forwards to the best other economy provider (Atrium, EssentialsX, ...) and
  rejects NaN, infinite or negative amounts, amounts above `guard.max-amount` and null names. Provider exceptions
  become `FAILURE` responses and are logged (rate-limited). Without a provider it reports `isEnabled() == false`.
  An optional asynchronous audit log records every withdraw and deposit with the calling plugin in
  `plugins/Vault/audit/`. Set `guard.enabled: false` for plain Vault behaviour.
- **SuperPerms fallback** permission provider (priority Lowest, no groups). LuckPerms, Atrium and other modern
  plugins register their own providers.
- `/vault-info` lists every Economy, Permission and Chat provider, the active one and the guard status.
  `/vault-convert <from> <to>` dry-runs a balance copy between two economy plugins; add `confirm` to apply it. The
  source economy is only read. Permission: `vault.admin` (default: op).

**License notice.** The bundled VaultAPI classes are copyright MilkBowl and contributors and licensed under the
GNU LGPL v3.0; the license, the GPL v3.0 text it incorporates and a notice are included in the jar's `META-INF/`.
Source: <https://github.com/MilkBowl/VaultAPI>. Everything else in the jar (`net.milkbowl.vault.Vault` and
`dev.yanianz.atrium.vault.*`) is original Atrium code, not derived from Vault. See [NOTICE](NOTICE).

## Releases

- Every push to `main` publishes a **pre-release** named `X.Y.Z-build.N`.
- A tag `vX.Y.Z` publishes a **stable release** with `Atrium-<version>.jar`, `Vault-<version>.jar` and
  `SHA256SUMS`. Verify a download with `sha256sum -c SHA256SUMS`.

Use stable releases on production servers. Changes are listed in [CHANGELOG.md](CHANGELOG.md).

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
