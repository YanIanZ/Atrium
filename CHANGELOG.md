# Changelog

All notable changes to Atrium are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). Every push to `main` is released as the next patch
version; patch releases with no notable change are not listed separately.

## [Unreleased]

## [1.0.0] - 2026-11-03

Initial feature set.

### Platform
- Paper and Folia 26.1.2+ on Java 25; Folia-native scheduling (no `BukkitScheduler`, no NMS, no reflection).
- 59 modules, each switchable in `modules.yml`, with automatic cleanup of commands, listeners, tasks and
  permissions.
- Storage on SQLite (default) or MySQL/MariaDB through HikariCP, with versioned migrations and transactional writes.
- MiniMessage texts in `en_US` and `id_ID`, per-player client locale, in-game lang editor (`/editlocale`).
- Theme tags (`<primary>`, `<muted>`, `<success>`, ...) and GUI defaults configured once in `config.yml` → `theme`.
- Shared menu toolkit: clicks cancelled unless a slot is an input slot, click debounce, menus closed on quit,
  world change and module disable.
- `/atrium help`, `/atrium menu`, `/atrium modules`, `/atrium reload`, `/atrium docs` (generates command and
  permission references).
- Public API (`atrium-api`) with services for economy, teleports, homes, warps, moderation, chat, vanish, AFK,
  playtime, ranks, worth, actions, holograms, nicknames, flight and proxy messaging.

### Gameplay
- Teleports (`/tp`, `/tpa` family, `/back` with history, `/top`, `/spawn`) with warmups, cooldowns and safe landing.
- Homes and warps with permission-based limits, categories and GUIs; random teleport inside the world border.
- Player utilities: fly, heal, feed, god, game mode, speed, repair, virtual workstations, online and offline
  `/invsee` and `/endersee`, timed flight, seating (`/sit`, `/lay`, `/crawl`), elytra boost, portals.

### Economy
- Exact-decimal economy with transaction log, `/pay`, `/eco`, `/baltop`, and a Vault provider.
- `/worth` and `/sell` with a sell GUI (items are removed before payment), kits with cooldowns and editor.
- `commandrules`: per-command costs, cooldowns, warmups and world rules that aliases cannot bypass.

### Chat and social
- Chat formats and groups, private messages, ignore, social spy, staff chat, filter, mentions, emoji, colours and
  cross-server chat.
- Mail, nicknames, AFK, `/seen` and `/whois`, chat bubbles, death messages, sign editing, skins, playtime and rank-ups.

### Staff and moderation
- Bans (temporary and IP), mutes, warnings with escalation, jail, freeze, history and editors; cross-server
  punishment sync.
- Staff mode with crash-safe restore, `/sudo`, `/commandspy`, `/spectate`, vanish, inspection tools, undoable
  inventory snapshots, search, `/lag` and entity limits, mob and world tools, per-world rules, custom recipes.

### Presentation
- Holograms, NPCs (Paper mannequins), tab list, sidebar (PacketEvents on Folia), boss bars, custom text pages,
  dialogs (Paper Dialog API), interactive blocks and entities, custom command aliases, admin action lines, particles
  and effects, armour stand editor.
- Selectable tab list engine (`engine: auto|native|tab`) and sidebar backend (`backend: auto|bukkit|packets|tab`):
  with the TAB plugin, Atrium still renders headers, footers, list names, sorting and sidebars per player and hands
  them to TAB's API instead of sending its own packets. Parts whose TAB feature is disabled are left to TAB; Atrium
  falls back to its own engines when TAB is missing, and removes everything it created in TAB on reload or disable.
  `/tablist engine` and `/sidebar engine` show the active engine and what it supports.

### Integrations
- Optional hooks for Vault, LuckPerms, PlaceholderAPI, DecentHolograms, FancyHolograms, Citizens, FancyNpcs,
  WorldGuard, GriefPrevention, Lands, Towny, BlueMap, squaremap, dynmap, PacketEvents, TAB and Floodgate.
- Velocity/BungeeCord messaging with HMAC authentication (`/server`, `/glist`, cross-server chat and staff features).
- Discord webhook relay (mention-safe, rate-limited).
- SourbyCraft support: profile-scoped data for MultiProfile, on-demand AWF world loading, region metrics.

### Migration
- `/atrium-import cmi|essentials` with dry run, idempotent import ledger, per-player rollback and a report file;
  `/atrium-backup` for the SQLite database.
- The CMI and EssentialsX imports also bring over spawn and first-join spawn, kits (items with name, lore and
  enchantments; unsupported item data is reported), jails, active bans, temp bans, IP bans, mutes and jail
  sentences, EssentialsX mail and the last/logout location as a `/back` entry. Warps keep yaw/pitch, category,
  icon, owner and creation time, and worlds stored by UUID resolve to their name.
- `/atrium-import vanilla` imports `banned-players.json` and `banned-ips.json`.
- `/atrium-import economy <plugin>` imports balances from any other Vault economy provider (RoyaleEconomy,
  XConomy, EssentialsX, CMI, TheNewEconomy, ...), with the provider's decimal precision and a per-plugin ledger.
- `/atrium-import sources` lists the detected sources and what each can import; the report shows imported,
  skipped and failed counts with their reasons for every category.
- API: `SpawnAdminService`, `BackHistoryAdminService`, `WarpImportService`, `KitAdminService`,
  `ModerationImportService` and `MailAdminService` for import tools.

### Atrium Vault
- Folia-ready drop-in replacement for the Vault plugin (bundles VaultAPI 1.7.1 unmodified, LGPL-3.0) with a
  guarded economy wrapper, optional audit log, SuperPerms fallback, `/vault-info` and `/vault-convert`.

[Unreleased]: https://github.com/YanIanZ/Atrium/compare/v1.0.0...main
[1.0.0]: https://github.com/YanIanZ/Atrium/releases/tag/v1.0.0
