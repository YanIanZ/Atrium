# Configuration

Everything lives in `plugins/Atrium/`. All files are created with defaults and comments on the first start, and
missing keys are added when Atrium loads a file, so updating Atrium never requires deleting a file.

```text
plugins/Atrium/
├── config.yml                 core settings and theme
├── modules.yml                module on/off switches
├── modules/<module>.yml       settings of one module
├── modules/<module>/          extra files of some modules (customtext pages, dialogs, recipes)
├── lang/<locale>/<module>.yml texts (MiniMessage)
├── atrium.db                  SQLite database (when database.type is SQLITE)
└── docs/                      output of /atrium docs
```

## What `/atrium reload` applies

| Applied by `/atrium reload` | Needs a restart |
|---|---|
| `default-locale`, `per-player-locale`, the whole `theme` section, every lang file, module settings that support reloading | `database.*`, `disabled-commands`, `modules.yml`, module settings whose comment says so |

## `config.yml`

| Key | Default | Meaning |
|---|---|---|
| `default-locale` | `en_US` | Locale for the console and for players whose client locale has no translation. |
| `per-player-locale` | `true` | Use each player's client language when a translation exists. |
| `database.type` | `SQLITE` | `SQLITE`, `MYSQL` or `MARIADB`. |
| `database.host` / `database.port` | `localhost` / `3306` | MySQL/MariaDB server. |
| `database.database` | `atrium` | Schema name. |
| `database.username` / `database.password` | `atrium` / empty | Credentials. |
| `database.table-prefix` | `atrium_` | Prefix for every table name. |
| `database.pool-size` | `8` | Connection pool size. |
| `database.parameters` | empty | Extra JDBC URL parameters (MariaDB Connector/J syntax). When empty, remote hosts use TLS with certificate verification (`sslMode=verify-full`) and `localhost` uses no TLS. |
| `user-save-interval-seconds` | `30` | Seconds between background saves of changed player data. |
| `user-load-timeout-seconds` | `10` | Seconds to wait for player data during login before the login is refused. |
| `offline-cache-minutes` | `10` | How long an offline player's data stays cached after it was last used. |
| `disabled-commands` | `[]` | Command names (without slash) that are not registered at all. |

### `theme`

The palette is exposed to every lang file and admin-written template as MiniMessage tags. Values are hex
(`#5FB3A1`) or named colours (`gray`, `dark_aqua`, ...). An invalid value logs a warning and falls back to the
default for that entry only.

| Key | Default | Tag | Used for |
|---|---|---|---|
| `theme.primary` | `#5FB3A1` | `<primary>` | Titles, command names, important values |
| `theme.secondary` | `#3B7F9A` | `<secondary>` | Sub-headings, secondary values |
| `theme.accent` | `#F2C46D` | `<accent>` | Small highlights: keys, counters, links |
| `theme.muted` | `gray` | `<muted>` | Explanations, separators, hints |
| `theme.success` | `#6BCB77` | `<success>` | Positive results |
| `theme.warning` | `#F5A65B` | `<warning>` | Warnings and partial results |
| `theme.error` | `#E5534B` | `<error>` | Errors and denials |
| `theme.highlight` | `white` | `<highlight>` | Inserted values (player names, amounts) inside a sentence |
| `theme.prefix` | empty | `<prefix>` | MiniMessage for the prefix (theme tags allowed). Empty = `prefix` from `lang/<locale>/core.yml`. |

`theme.gui` sets the defaults for every chest menu:

| Key | Default | Meaning |
|---|---|---|
| `theme.gui.filler` | `GRAY_STAINED_GLASS_PANE` | Material for empty slots. Empty = leave slots empty. |
| `theme.gui.open-sound` | `minecraft:ui.button.click` | Played when a menu opens. Empty = silent. |
| `theme.gui.click-sound` | `minecraft:ui.button.click` | Played when a button is clicked. Empty = silent. |
| `theme.gui.error-sound` | `minecraft:entity.villager.no` | Played when an action in a menu fails. Empty = silent. |
| `theme.gui.sound-volume` | `0.5` | Volume of the menu sounds (0.0 to 1.0). |
| `theme.gui.click-debounce-millis` | `150` | Minimum time between two handled clicks of one player. |

## `modules.yml`

One boolean per module, for example:

```yaml
homes: true
discord: false
```

A module that is switched off registers no commands, listeners, tasks or permissions. Modules that depend on a
disabled module stay off and say so in the log. New modules added by an update are appended with their default.
`/atrium modules` shows the state of every module.

## `modules/<module>.yml`

Each module has its own file with a comment on every option. Some modules keep additional files in a folder of the
same name, for example `modules/customtext/` (text pages such as `motd.yml` and `rules.yml`), `modules/dialogs/` and
`modules/recipes/`. Module-specific documentation:

- SourbyCraft integration: [SOURBYCRAFT.md](SOURBYCRAFT.md)
- Commands and permissions of every module: [COMMANDS.md](COMMANDS.md), [PERMISSIONS.md](PERMISSIONS.md)

## Lang files

- Location: `lang/<locale>/<module>.yml`, for example `lang/en_US/homes.yml`. `core.yml` holds shared texts and the
  default `prefix`.
- Shipped locales: `en_US` and `id_ID`. To add a locale, create `lang/<locale>/` (for example `lang/de_DE/`) with
  the files you want to translate; missing keys fall back to `default-locale`.
- Texts are [MiniMessage](https://docs.advntr.dev/minimessage/format.html). An empty string disables a message.
- Placeholders in angle brackets (`<player>`, `<amount>`, ...) are filled per message. They win over a theme tag of
  the same name.
- Text typed by players (chat, names, sign or book text, command arguments) is always inserted literally and never
  parsed as MiniMessage or PlaceholderAPI.
- `/editlocale` edits lang files in game; `/atrium reload` reloads all of them.

Typical line (`lang/en_US/homes.yml`):

```yaml
set: "<prefix><success>Home <highlight><home></highlight> set at <highlight><at_x> <at_y> <at_z></highlight>."
```
