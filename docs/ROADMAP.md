# Roadmap

Atrium was built in phases. Phases 0 to 9 are complete; the remaining work is listed under
[Open items](#open-items). Every phase was verified with `./gradlew build` (unit tests) and console boot smoke
tests on fresh Folia and Paper servers.

## History

### Phase 0: foundation
Gradle (Kotlin DSL) multi-project build, Java 25, Paper/Folia 26.1.2 API, Paper plugin loader with runtime
libraries, Folia scheduler, Brigadier commands, Configurate configs, MiniMessage i18n (`en_US`, `id_ID`), HikariCP
storage (SQLite/MySQL/MariaDB) with versioned migrations, player data loaded before login, module system with
automatic cleanup, `/atrium version|modules|reload`. Reference module: `player`.

### Phase 1: core gameplay
| Module | Scope |
|---|---|
| `teleport` | `TeleportService`, `/tp`, `/tphere`, `/tpall`, `/tpa`, `/tpahere`, `/tpaccept`, `/tpdeny`, `/tpacancel`, `/tptoggle`, `/back`, `/top`, `/spawn`, `/setspawn`; warmups, cooldowns, safe landing |
| `homes` | `/home`, `/sethome`, `/delhome`, `/homes`; limits by permission; offline admin access |
| `warps` | `/warp`, `/setwarp`, `/delwarp`, `/warps`; per-warp permissions, categories |
| `economy` | `EconomyService`, `/balance`, `/pay`, `/eco`, `/baltop`; Vault provider; transaction log |
| `moderation` | Bans, temporary and IP bans, kicks, mutes, warnings with escalation, jail, freeze, history |
| `player` | Fly, heal, feed, god, game mode, speed, hat, repair, ender chest, invsee, virtual workstations, clear and more |
| `chat` | Formats and groups, `/msg`, `/reply`, `/ignore`, `/socialspy`, staff chat, filter, mentions, broadcast |

### Phase 2: player systems
`rtp` (asynchronous safe search inside the world border), `worth` (`/worth`, `/sell` with a sell GUI; items are
removed before payment), `kits` (cooldowns, first-join kit, preview GUI), `vanish`, `seen` (`/seen`, `/whois`, alt
check), `afk`, `mail`, `nick`, `playtime` (rewards, `/playtimetop`), `ranks` (`/rankup` with playtime and money
requirements).

### Phase 3: world and presentation
`actions` (admin-defined action lines run safely), `aliases` (custom commands), `interactive` (commands bound to
blocks and entities), `tablist`, `scoreboard`, `bossbar`, `holograms` (display entities, per-viewer pages),
`deathmessages`, `chatbubbles`, `signs`, `portals`, `elytra`, `armorstand` (editor GUI and poses), `mobs`,
`worldtools`, `dialogs` (Paper Dialog API with server-side input validation), `skins`.

### Phase 4: network and integrations
`proxy` (HMAC-authenticated Velocity/BungeeCord messaging, `/server`, `/glist`), `placeholders` (PlaceholderAPI),
`discord` (webhook relay, mention-safe, rate-limited), `maps` (BlueMap, squaremap, dynmap; vanished players hidden),
`importer` (CMI and EssentialsX, idempotent with an import ledger, per-player rollback, `/atrium-backup`),
`customtext`, `search`, `extras` (read-only shulker preview), shared ignore and flight services, `/back` history,
warp GUI and per-player warp limits.

### Phase 5: completion
`messaging`, `inspect`, `itemtools`, `inventories` (undoable inventory snapshots), `playerplus` (timed flight,
flight charge, cuff), kit editor, moderation lists and cross-server punishment sync, chat colours, emoji and
cross-server chat, `performance` (`/lag`, ground item cleanup, entity limits), sidebar on Folia through PacketEvents.

### Phase 6: remaining features
`seating` (`/sit`, `/lay`, `/crawl`), `commandrules` (per-command cost, cooldown, warmup and world rules that cannot
be bypassed with aliases), `recipes` (custom recipes, enchantment limits, anvil rules), `worldrules` (per-world
rules, `/gamerule`, `/compass`, armour effects, `/blockcycle`), editors (`/editplaytime`, `/editwarp`,
`/editctext`, `/colorpicker`, `/colorlimits`).

### Phase 7: integrations
Hologram engines (native, DecentHolograms, FancyHolograms; Atrium stays the source of truth), `npcs` (native Paper
mannequins, Citizens, FancyNpcs; click actions, cooldowns, costs), `integrations` (protection checks for
WorldGuard, GriefPrevention, Lands and Towny; metadata from LuckPerms or Vault), Bedrock dialogs through Floodgate
forms, moderation editors (`/editwarnings`, `/jailedit`, clickable `/history`).

### Phase 8: UI and UX
Theme tags (`<primary>`, `<muted>`, `<success>`, ...) configured in `config.yml` → `theme`; a shared menu toolkit
(input slots, shift-insert into input slots only, click debounce); `/atrium help` and `/atrium menu`;
`/atrium docs` ([COMMANDS.md](COMMANDS.md), [PERMISSIONS.md](PERMISSIONS.md)). Every module was reworked for
themed lang files (`en_US` and `id_ID`), command descriptions, usage lines and tab completion, and every GUI moved to
the menu toolkit. Every module received an independent code review; the defects found (item duplication, money
creation, permission bypasses, Folia thread violations, races) were fixed with regression tests.

### Phase 9: final features, Atrium Vault, SourbyCraft
`/editlocale` (`localeeditor`), `/clearall`, `particles` (styles, wings, trails, presets, TPS throttle), `effects`
(event effects, damage-free fireworks, fixed effects active only near players), `staff` (staff mode with a
crash-safe restore, `/sudo`, `/commandspy`, `/spectate`), offline `/invsee` and `/endersee`,
**Atrium Vault** (a Folia-ready drop-in Vault plugin with a guarded economy and audit log) and **SourbyCraft**
support (profile-scoped data for MultiProfile, on-demand AWF world loading, region metrics in `/lag`; see
[SOURBYCRAFT.md](SOURBYCRAFT.md)).

State at the end of phase 9: 59 modules, 241 commands and 799 permissions on a default configuration; boot smoke
tests pass on Folia and Paper with Atrium and Atrium Vault installed.

## Open items

- **Real-client testing.** The phases above are verified by unit tests and console boot smoke tests. GUIs, NPCs,
  holograms, seating and crawling, Bedrock forms and cross-server sync still need in-game tests with real clients.
- **Live third-party plugin testing.** The hooks for DecentHolograms, FancyHolograms, Citizens, FancyNpcs,
  WorldGuard, GriefPrevention, Lands, Towny, LuckPerms, dynmap, squaremap, PacketEvents and Floodgate compile against
  the real APIs and are unit-tested, but have not yet been exercised against the running plugins.
- **SourbyCraft profile switching.** Boot is verified on the official SourbyCraft 26.2 release and on newer builds
  with the extended API; switching profiles with real players is still untested.
- **First stable release** once the testing above is done. Until then, builds are published as pre-releases.

## Known limits

- Skins and NPC skins need an online-mode server (Atrium does not fetch arbitrary skin URLs).
- `/lightfix` and chunk preview are not implemented: Folia has no safe API for them.
- `commandrules` cannot refund a command that runs but fails internally; use `charge-after` for such commands.
- With SourbyCraft profile purses on the `vault` backend, a profile switch waits for Atrium's database write;
  `economy.backend: native` avoids that (see [SOURBYCRAFT.md](SOURBYCRAFT.md)).
- An offline edit of profile-scoped data (for example deleting a home of an offline player) is overwritten by that
  player's profile snapshot at their next login on SourbyCraft MultiProfile servers.
