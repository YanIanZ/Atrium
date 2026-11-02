# Economy

Provides `EconomyService`, `/balance [player]` (`/bal`), `/pay <player> <amount>`,
`/eco give|take|set <player> <amount>`, `/eco reset <player> [amount]`,
`/eco log <player>` (latest ten entries), `/eco groups`, and `/baltop [page]`.
Reset restores the configured starting balance; its optional compatibility amount is
validated but ignored. Amount suffixes: `k`, `m`, `b` (case insensitive). No exponent,
grouping, currency symbols, negative input, NaN or infinity in command amounts.

Balance, pay (including `atrium.pay.others`) and baltop default to everyone. Balance inspection needs
`atrium.balance.others`. Administration uses `atrium.eco.*` or the individual action
permission plus `atrium.eco.others` for other accounts; these default to OP.
Offline names resolve through Atrium's known-player records. An unknown name such as
Notch on a fresh smoke server is deliberately rejected; no guessed offline UUIDs.
Payments above the configured threshold require the same payment again within 30 seconds.

All config options have generated comments. Restart after configuration changes.
Scale is 0–10; rounding applies once at the boundary. Maximum amount is checked before
and after rounding. Negative operation amounts are rejected, but withdrawals/transfers
may reach a configured negative minimum balance. Disabling the maximum balance policy
still respects the DECIMAL(30,10) storage limit. Reducing scale with fractional persisted
data fails reads instead of silently rounding away money; migrate data deliberately first.
Display grouping is locale independent and never used as a storage representation.

## Storage and concurrency

Each mutation, including account creation and its opening ledger entry, uses one
`Database.transaction`. Transfers acquire creation/row locks in UUID string order,
check both balances, and update both accounts with their ledger entries. A rejected
transfer rolls back account creation too. SQL compare-and-set updates check row counts.
SQLite stores canonical decimal TEXT and uses BigDecimal arithmetic plus an exact TEXT
comparison; SQLite numeric arithmetic would lose precision at large balances. MySQL uses
DECIMAL and `SELECT ... FOR UPDATE`. Accounts use idempotent insertion; generated values
are validated before insertion. SQLite's core pool already serializes connections.

A module-local asynchronous queue orders storage operations through post-commit cache
publication. A tick thread never waits on it and prevents an older callback from
replacing a newer balance. This favors correctness over maximum parallel throughput.
The online cache is warmed before login and removed at quit, once queued writes drained.
`balance()` and component loads never create an account: a missing account reads as its
configured starting balance.
Creation occurs explicitly before login/at join, on a mutation, or via Vault account creation.
`economy:account` is an immutable load-time user-component projection. Its saver is a
no-op so UserManager cannot overwrite the authoritative ledger with an old snapshot.
Use `EconomyService` for current balances and every mutation.

Leaderboard scans and exact-decimal sorting happen on database I/O futures, on startup
and every configured interval. Commands only page an immutable cached snapshot; failed
refreshes retain the previous snapshot. This scans all accounts and uses O(accounts)
memory. It is eventually consistent, not updated immediately after every payment.

Consumers charging for an action must await a successful `withdraw` **before** running
the action, then schedule entity/world effects on the owning scheduler. Do not implement
charges as a balance read followed by set, or execute the command before charging.

## World groups (separate money per group of worlds)

Off by default (`groups.enabled: false`): every world shares one balance and every command, API call
and Vault call behaves exactly as before. Configuration in `economy.yml`:

```yaml
groups:
  enabled: true
  default-group: default        # used for every unlisted world and for offline players
  allow-cross-group-pay: false  # refuse /pay (and cheques) between different groups
  world-groups:
    survival: [world, world_nether, world_the_end]
    resource: ["resource_*", "mine?"]   # globs: * and ?; an exact name wins over a glob
```

Storage: balances are keyed by (uuid, group) since schema v3. The migration copies every existing balance
into the default group, whose storage key is always `default` whatever `default-group` is called, so enabling
groups later or renaming the default group never moves money. Ledger rows and cheques record their group.
A group removed from the configuration keeps its rows; `/eco groups` lists such stored groups, the configured
groups with their worlds and account counts, and the group of every loaded world. Listing an existing main
world in a new group makes the money players had there (in the default group) unavailable in that world, so
prefer putting new worlds into new groups.

Commands use the sender's current group (console: the default group). A target that is online uses its own
current group; an offline target uses the sender's group. With `atrium.eco.group` (OP) any of `/balance`,
`/pay`, `/eco` and `/baltop` take `g:<group>` or `--group <group>` (for `/pay` it applies to both sides),
e.g. `/eco give Steve 100 g:survival`, `/baltop g:resource 2`, `/eco log Steve g:survival` (without it the log
shows every group). `/pay` to an online player in another group is refused unless `allow-cross-group-pay`; an
offline receiver is paid in the payer's group. A cheque pays into the redeemer's current group and, unless
cross-group payments are allowed, only in the group it was written in. `/baltop` shows one snapshot per group.

`EconomyService`: methods without a group use the **context group**: the group of the world the player is
in while online, otherwise the default group (offline players are always the default group). This is what
placeholders (`cachedBalance`/`balance`) show. `transfer(from, to, ...)` moves money inside the payer's
context group (both sides), so money never crosses groups implicitly. Additive overloads take an explicit
group: `balance/cachedBalance/deposit/withdraw/set(uuid, group, ...)`, `transfer(from, to, group, ...)`, plus
`groupsEnabled()`, `groups()`, `defaultGroup()`, `groupOfWorld(world)`, `currentGroup(uuid)` and
`EconomyAccounts.hasAccount(uuid, group)`. An unknown group fails reads with `IllegalArgumentException` and
writes with `NO_ACCOUNT`.

## Vault compatibility

Vault is optional and compile-only; the existing plugin descriptor already grants its
optional classpath. A separately loaded provider registers with ServicesManager and is
unregistered on module disable. World overloads (`getBalance(player, world)`, `withdrawPlayer(player, world,
amount)`, ...) use the group of that world; the world-less ones use the context group (current group of an
online player, else the default group). With groups disabled all of them share the one account. Banks are not
supported. OfflinePlayer overloads use UUIDs directly, including unnamed offline players.
Legacy string overloads use Atrium's known-player lookup.

Vault requires synchronous methods. The balances of an online player (every group, in one query, since the
world is not known before login) are warmed in `AsyncPlayerPreLoginEvent` (blocking there is expected, as the core loads user data on
that thread) and kept cached until quit; the cache is evicted after the queued writes
drain so a late commit cannot repopulate it. A read for an online player therefore always
answers from the cache and never waits, even on a foreign plugin thread such as a
scoreboard plugin's own thread.

A read that is not cached follows a thread-aware policy: a regular plugin thread may wait
up to `vault-timeout-millis`, while a server tick thread (global or region) never waits —
it answers from a recent value if one exists, otherwise it loads the balance in the
background, answers `0` and logs a rate-limited warning. String overloads consult the
online player list first, so they avoid storage entirely for online names. Writes keep
their transactional semantics, and the cache is published only from the committed result.
A write on a tick thread (for example a profile switch through GuardedEconomy) waits at
most `vault-timeout-millis` for the commit: that value is the worst-case tick stall.
A failed write is never returned as SUCCESS. A timed-out or interrupted write can still
commit after Vault returns FAILURE: its outcome is **unknown**, and automatic retries can
duplicate a charge. A bounded synchronous wait cannot guarantee cancellation of a JDBC
commit. A read that could not wait reports `0`, which is the same value the guarded Vault
boundary already returned for a failed or timed-out read, so keep bulk conversions and
other exact reads on EconomyService futures. Vault's external double interface also limits
precision; Atrium's internal API stays exact.

## Vault v2 (VaultUnlocked) provider

When the Vault v2 API (`net.milkbowl.vault2`, shipped by Atrium Vault 2.20+ or VaultUnlocked) is on the class path,
the module also registers a native v2 economy provider (`VaultUnlockedBridge`, priority Normal) next to the v1 one,
and unregisters it on module disable.

- Amounts are exact `BigDecimal`s: null or negative amounts and amounts with more decimal places than `scale` are
  refused (FAILURE), never rounded. Accounts are UUIDs; only player accounts exist (`createAccount(..., false)`,
  shared accounts, rename and delete return `false`).
- Currency: one, id `default`; the configured `currency-singular`/`currency-plural` names are accepted as aliases
  (case-insensitive). Another currency fails writes and reads as `0`. `hasMultiCurrencySupport()` is `false`.
- World groups: the v2 `world` parameter selects the group of that world (exact world names, then globs) exactly like
  the v1 world overloads; a name that maps to no world but is a configured group name (or the default group's name)
  selects that group, so v2 callers can address `survival` directly. Overloads without a world use the context group
  (current group of an online player, otherwise the default group). With groups disabled everything is one balance.
  `transfer` keeps both sides in one group (the world's group, or the payer's context group).
- Reads follow the same non-blocking policy as v1 (`VaultReads`): online balances from the cache; a tick thread never
  waits. Writes wait at most `vault-timeout-millis`; a timeout is a FAILURE with an unknown outcome (do not retry
  blindly). `set` and `transfer` are single ledger transactions.
- The v2 `pluginName` is recorded in the ledger reason as `vault2:<operation>:<plugin>` (letters, digits, `_-.`,
  at most 64 characters). v1 writes keep `vault:deposit` / `vault:withdraw`.
- `getUUIDNameMap()` lists online accounts only (no storage scan on the caller's thread); `async()` is empty.

## Validation

Normal verification:

```sh
./gradlew :modules:economy:build
scripts/new-server.sh economy 25631 # only once
./gradlew build && scripts/smoke.sh run/economy "atrium modules" "eco give Notch 100" "balance Notch" "baltop"
```

JUnit covers parsing, rounding, display, SQLite reads without writes, simultaneous
withdrawals, exact large transfers, rollback on ledger failure, debt and receiver limits,
rejected account creation, and cache lifecycle; for world groups: resolution (exact names, globs, default,
renamed default), the `g:` selector, per-group isolation and ledger, the v3 migration keeping old balances,
Vault world mapping, cross-group pay refusal, the per-group pre-login warm-up, and the disabled mode. MySQL concurrency and live Vault behavior
need integration testing with those services installed.
