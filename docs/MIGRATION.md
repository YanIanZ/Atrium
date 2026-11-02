# Migrating to Atrium

Atrium's `importer` module copies data from CMI, EssentialsX, PlayerVaultsX, the server's vanilla ban lists and
any other Vault economy plugin into Atrium. It reads the old plugin's files (or CMI's database) directly, so the
old plugin does not have to be running, with two exceptions: the Vault economy import reads balances through the
old economy plugin, so that plugin and Vault must be installed and enabled, and PlayerVaultsX vaults that are
stored with Java object serialization are refused (see below).

## Steps

1. Keep the old plugin's data folder in `plugins/` (or point `modules/importer.yml` → `cmiFolder`,
   `essentialsFolder`, `essentialsSpawnFolder` at a copy). Load every world the data refers to.
2. `/atrium-backup` to snapshot Atrium's SQLite database.
3. `/atrium-import sources` to see what was found and what each source can import.
4. `/atrium-import <source> dry-run` and read the report. A dry run counts exactly what a run would do and writes
   nothing.
5. `/atrium-import <source> run`. Players who are online while their data is imported are skipped; run the
   import again after they log out.
6. Run it again whenever you like: a repeated run only imports what is new.

Run imports from the console. One import runs at a time; `/atrium-import status` shows progress and
`/atrium-import cancel` stops it.

## Commands

| Command | What it does |
|---|---|
| `/atrium-import sources` | Lists every detected source and what it can import, plus every Vault economy provider. |
| `/atrium-import cmi [dry-run\|run] [--only=...] [--overwrite]` | CMI files and database. |
| `/atrium-import essentials [dry-run\|run] [--only=...] [--overwrite]` | EssentialsX (and EssentialsSpawn) files. |
| `/atrium-import pvx [dry-run\|run] [--only=vaults] [--overwrite]` | PlayerVaultsX vaults. |
| `/atrium-import vanilla [dry-run\|run] [--overwrite]` | `banned-players.json` and `banned-ips.json`. |
| `/atrium-import economy <plugin> [dry-run\|run] [--overwrite]` | Balances from another Vault economy provider. |
| `/atrium-import status` / `cancel` | Progress of the running import / stop it. |

The default mode is `dry-run`. `--only=<a,b,...>` limits the run to some categories (tab completion lists the
categories of each source). `--overwrite` replaces data that already exists in Atrium and re-imports records the
ledger already marked.

## Per-source matrix

✔ imported · ◐ imported with limits (see notes) · ✘ not imported (counted and explained in the report) · – the
source has no such data

| Category (`--only=`) | CMI | EssentialsX | PlayerVaultsX | Vanilla | Vault economy |
|---|---|---|---|---|---|
| `economy` | ✔ database | ✔ `userdata/*.yml` | – | – | ✔ any provider |
| `homes` | ✔ database | ✔ `userdata/*.yml` | – | – | – |
| `warps` | ✔ `Saves/Warps.yml` | ✔ `warps/*.yml` | – | – | – |
| `nicks` | ✔ database | ✔ `userdata/*.yml` | – | – | – |
| `playtime` | ✔ database | – | – | – | – |
| `holograms` | ✔ `Saves/Holograms.yml` | – | – | – | – |
| `spawn` | ✔ `config.yml` (main and first-join spawn) | ◐ `Essentials/spawn.yml` | – | – | – |
| `kits` | ◐ `Kits/*.yml` | ◐ `kits.yml` | – | – | – |
| `jails` | ◐ `Saves/Jails.yml` | ◐ `jail.yml` / `jails.yml` | – | – | – |
| `punishments` | ◐ mutes + vanilla ban lists | ✔ mutes, jail sentences, bans + vanilla ban lists | – | ✔ bans, temp bans, IP bans | – |
| `locations` | ✔ last location → `/back` | ✔ logout location → `/back` | – | – | – |
| `mail` | – | ✔ `userdata/*.yml` | – | – | – |
| `ignore` | ✔ database (UUIDs separated by `;`) | ✔ `userdata/*.yml` | – | – | – |
| `vaults` | – | – | ✔ `newvaults/*.yml` (gzip item NBT) | – | – |
| `history` | – | ✘ login/logout times | – | – | – |

### Notes per category

- **Economy.** Balances become exact decimals. Players already imported from the same source are skipped
  (ledger), and a balance is only written when the player has no Atrium economy account yet. Players who joined
  after Atrium was installed already have one: use `--overwrite` to replace their Atrium balance with the old one.
- **Warps.** Location with yaw and pitch, category, owner, creation time and menu icon are kept. A world stored as
  a UUID is resolved to the loaded world's name. CMI's per-warp `ReqPerm` and EssentialsX's `per-warp-permission`
  map to Atrium's single switch `requirePerWarpPermission` in `modules/warps.yml` (the report says how many warps
  needed a permission); grant `atrium.warp.<name>` or `atrium.warps.all`. Invalid names are made valid and the
  rename is reported. CMI's `HideFromList`, page and slot layout are not carried over.
- **Spawn.** CMI `Spawn.Main` becomes the default spawn and `Spawn.FirstSpawn` the first-join spawn.
  EssentialsSpawn's `default` group becomes the default spawn and the group named in `newbies.spawnpoint` the
  first-join spawn. Other EssentialsSpawn groups are reported with their location (Atrium has no per-group spawns;
  use a per-world spawn or a warp). An existing Atrium spawn is kept unless `--overwrite`.
- **Kits.** Items keep their slot, amount, display name, lore and enchantments; CMI's and EssentialsX's serialized
  items are imported whole. Item data that cannot be carried over (legacy data values, colours, potion and book
  data, custom model data from item maps, ...) is listed per item. Cooldown, cost (`MoneyCost`, `command-costs`),
  maximum uses and one-time kits (EssentialsX `delay: -1`) are kept; CMI's `Newbie.Kit` becomes a first-join kit.
  Kit commands, money lines, `ExpCost`, conditions and groups are reported. `essentials.kits.<name>` and
  `cmi.kit.<name>` become `atrium.kit.<name>`.
- **Jails.** An Atrium jail is a spawn point with a radius. CMI: the first cell is the spawn point, the radius
  covers the whole jail area, `safeLoc` becomes the release point and extra cells are reported. EssentialsX jails
  are points and get the moderation module's default radius. Jails are imported before jail sentences.
- **Punishments.** Only active records are imported; expired ones are skipped. A player who already has an active
  punishment of the same kind in Atrium is skipped. Imported punishments send no announcement and kick nobody;
  they apply on the next login or chat message. Each record is marked in the ledger under its source (`cmi`,
  `essentials` or `vanilla`), so a punishment lifted in Atrium is not re-applied by a later run. A jail sentence
  whose jail exists neither in Atrium nor in the source is reported as invalid.
- **Locations.** The last (CMI) or logout (EssentialsX) location becomes the newest `/back` entry of that player.
- **Mail.** Expired mail is skipped; identical mail (same sender, text and time) is never stored twice. Imported
  mail never expires and ignores the mailbox limit.
- **Ignore lists.** Entries are added to the player's existing list, never removed, through the chat module's
  `IgnoreAdminService`; the import is therefore idempotent and safe to re-run. EssentialsX entries are UUIDs
  (older files: names) and CMI stores UUIDs separated by `;`; a name is resolved against accounts Atrium knows,
  and a name it does not know is reported as invalid. The player's own entry is dropped (`/ignore` refuses
  self-ignores). The write is confirmed by a save before it is counted, so an ignore list is never reported as
  imported when its save failed. Online players can be imported here: the write is one atomic component update,
  like `/ignore` itself, and cannot lose an entry the player adds at the same time.
- **History** is counted in the report only: Atrium derives first-join and last-seen from its own sessions.

## PlayerVaultsX vaults

`/atrium-import pvx` reads `plugins/PlayerVaults/newvaults/<uuid>.yml` (configurable with `pvxFolder`) and
writes the contents into the inventories module's `/pv` vaults through `VaultImportService`.

PlayerVaultsX 4.4 stores one base64 value per vault (`vault1`, `vault2`, ...). The payload is a slot count
followed by, per slot, a length and that many item bytes; the item bytes are gzip-compressed item NBT. That is
the same item format as Paper's `ItemStack#serializeAsBytes`, so it is read with the public Paper API and no
object stream. Every vault of one player is written in a single transaction: if one of their vaults cannot be
read, none of that player's vaults is written.

Two shapes are refused on purpose:

- **Java object serialization** (`BukkitObjectOutputStream`, payload magic `AC ED`). Old PlayerVaults and old
  PlayerVaultsX builds stored vaults this way. Reading it means deserializing classes chosen by the file, which
  can execute code, so Atrium does not do it. The report marks those players `unsupported` with that reason.
  Alternative with PlayerVaultsX installed and enabled: open each vault with `/pv <player> <n>` and copy the
  items into the matching `/pv` vault, or ask the plugin author for an NBT export. Atrium does not call the
  PlayerVaultsX API: the plugin has no documented API for reading another player's vaults, so a live import
  would depend on its internals.
- **YAML item maps** (`==: org.bukkit.inventory.ItemStack`). This is not the base64 format PlayerVaultsX
  writes; the file is reported as invalid instead of guessed.

## Vault economy import

`/atrium-import economy <plugin>` works with any economy plugin registered as a Vault provider: RoyaleEconomy,
XConomy, EssentialsX Economy, CMI Economy, TheNewEconomy and others. Vault (or Atrium Vault) and the old economy
plugin must both be installed. Atrium's own provider and the Atrium Vault guard are never used as a source; they
are listed as "not importable" by `/atrium-import sources`.

- Players checked: every offline player the server knows plus every account Atrium knows.
- Balances are read on the global region thread in batches (`batchSize` in `modules/importer.yml`), because many
  economy plugins are not thread-safe, and written through Atrium's economy in single transactions.
- Amounts are rounded to the provider's own number of decimals (`fractionalDigits`). Negative, NaN and infinite
  balances are reported as invalid; a player without an account in the old plugin is skipped.
- The ledger source is `vault:<plugin>`, so a second run skips players already imported from that plugin.
- Typical switch: install Atrium, keep the old economy plugin enabled for one start, run the import, then remove
  the old plugin.

## Reports

Every run writes `plugins/Atrium/import-<source>-<time>.log` (the console shows the path, click to copy in game).
For every category it lists found, imported, skipped, invalid, failed and unsupported counts, the reasons for every
skip, and every problem with the record it belongs to. The console shows the first problems per category
(`maxProblemsPerCategory`).

## Idempotency

| Data | How a repeated run avoids duplicates |
|---|---|
| Balances, homes, nicknames, playtime | Ledger per source, category and player |
| `/back` entry, mail | Ledger per source and player; identical mail is never stored twice |
| Ignore lists | Ledger per source and player; the write is additive, so a repeated run never removes or duplicates an entry |
| Personal vaults | Ledger per source and player; nothing is written when a vault of that player already exists unless `--overwrite` |
| Punishments | Ledger per source, kind and player (or IP address); active duplicates are skipped |
| Warps, spawn, kits, jails, holograms | Skipped when they already exist in Atrium, unless `--overwrite` |
