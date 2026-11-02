# Economy

Provides `EconomyService`, `/balance [player]` (`/bal`), `/pay <player> <amount>`,
`/eco give|take|set <player> <amount>`, `/eco reset <player> [amount]`,
`/eco log <player>` (latest ten entries), and `/baltop [page]`.
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
publication. It never waits on a game thread and prevents an older callback from
replacing a newer balance. This favors correctness over maximum parallel throughput.
The online cache is populated at join/enable and removed at quit. `balance()` and component
loads never create an account: a missing account reads as its configured starting balance.
Creation occurs explicitly at join, on a mutation, or via Vault account creation.
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

## Vault compatibility

Vault is optional and compile-only; the existing plugin descriptor already grants its
optional classpath. A separately loaded provider registers with ServicesManager and is
unregistered on module disable. World overloads share the global account; banks are not
supported. OfflinePlayer overloads use UUIDs directly, including unnamed offline players.
Legacy string overloads use Atrium's known-player lookup.

Vault requires synchronous methods. Cached online balance reads avoid database waits;
uncached/offline reads and writes wait up to `vault-timeout-millis`. Every actual wait
logs a warning with the caller thread, including all tick-thread callers. These calls
can stall a Folia region or global tick; integrations should prefer EconomyService futures.
A failed write is never returned as SUCCESS. A timed-out or interrupted write can still
commit after Vault returns FAILURE: its outcome is **unknown**, and automatic retries can
duplicate a charge. A bounded synchronous wait cannot guarantee cancellation of a JDBC
commit. Offline read failures throw rather than fabricate a successful zero balance.
Vault's external double interface also limits precision; Atrium's internal API stays exact.

## Validation

Normal verification:

```sh
./gradlew :modules:economy:build
scripts/new-server.sh economy 25631 # only once
./gradlew build && scripts/smoke.sh run/economy "atrium modules" "eco give Notch 100" "balance Notch" "baltop"
```

JUnit covers parsing, rounding, display, SQLite reads without writes, simultaneous
withdrawals, exact large transfers, rollback on ledger failure, debt and receiver limits,
rejected account creation, and cache lifecycle. MySQL concurrency and live Vault behavior
need integration testing with those services installed.
