# SourbyCraft support

Module `sourbycraft` (`plugins/Atrium/modules/sourbycraft.yml`). SourbyCraft is the server itself, not a
plugin, so `paper-plugin.yml` has no `SourbyCraft` dependency: the module detects the `dev.iyanz.sourbycraft.api.*`
classes at runtime and, per feature, whether the service is registered. On plain Paper/Folia it logs
`SourbyCraft API not found (plain Paper/Folia): the sourbycraft module stays idle.` and does nothing else.

Compile-only API: the official GitHub branch `26.2` (commit in `compat/sourbyapi-26.2/README.md`), copied read-only
by `compat/sourbyapi-26.2/sync.sh` and compiled as the subproject `:compat:sourbyapi-26.2`. No mavenLocal jar is needed.

## Compatibility: official API vs newer local builds

| Capability | Official `26.2` (Build 50, GitHub) | Newer local SourbyCraft build (extended API) |
|---|---|---|
| Profile-scoped homes / back / kits | Yes. `capture` always writes an envelope (an explicit empty one too), and Atrium records which profile its in-memory data belongs to (`ProfileOwnership`). Because the server never calls `applyMissing`, Atrium reconciles itself before every capture, on join, world change, teleport and before every command: data of another profile is replaced by empty data, never mixed. | Same, plus `applyMissing()` / `required()` are used natively. |
| `%atrium_profile%`, `/atrium profile` | Profile id and type | Plus profile name, member role (`%atrium_profile_role%`) and virtual id |
| Purse / economy mode report | Vault provider check only | Plus `ProfilePurse.backend()` |
| Profile readiness on join | Active-profile lookup after join | `awaitProfileReady` |
| AWF worlds, async unload, region metrics | Yes | Yes |

Detection is by class/record-component presence (`ExtendedApi`), never by version strings. The extended code paths
live in isolated classes, so the official server never loads a missing type.

| Feature | SourbyCraft service | Atrium side |
|---|---|---|
| MultiProfile | `AuroraProfiles` (enabled) | one `ProfileStateAdapter` `atrium:profile`, `ActiveProfileService`, `/atrium profile`, `%atrium_profile%` `%atrium_profile_type%` `%atrium_profile_role%` |
| AWF worlds | `AuroraWorlds` | `WorldResolver` (load on demand) used by `StoredTeleportService` (homes, warps, /back) |
| Async unload | `WorldUnloadAsyncEvent` | re-fired as Atrium `WorldUnloadingEvent` |
| Region metrics | `SourbyMetrics` | `RegionMetricsService`, one extra `/lag` line |

## MultiProfile: profile-scoped data

- `profile-scoped` (default `homes`, `back`, `kits`) lists the Atrium data that follows the active profile. Providers
  implement `api/profile/ProfileScopedData` and register through `ModuleContext#profileScoped`; component-backed data
  uses `core/profile/ComponentScopedData` + a `ComponentCodec` (`ModuleContext#codec`,
  `UserManager#exportComponent/importComponent`).
  - `homes` = `homes:homes`, `kits` = `kits:usage` (cooldown timestamps and use counts), `back` = the `/back` history
    only (the `/tptoggle` flag in the same component stays account-wide).
  - Account-wide by design: nick, mail, moderation, playtime, chat settings, particles, economy. Warps are global
    objects (no per-player owned-warp counter exists), so nothing of warps is profile-scoped.
- All entries go into one envelope (`ATPF`, versioned per entry) under adapter id `atrium:profile`, at most 2 MiB
  (larger data fails the capture). Capture/apply run on the player's entity thread inside SourbyCraft's task and only
  touch in-memory components. `apply` replaces the in-memory value first, then saves it (failure: logged, stays dirty,
  retried by the save loop). Atrium's database therefore always holds the **active** profile's data.
- Absent state = empty (`applyMissing`), so a new profile never inherits homes/back/kit usage.
  `profile-missing-state: KEEP` keeps the live data once (migration of an existing server only).
- `profile-adapter-required: true` (default): a failed capture/restore, a corrupt envelope or a newer stored version
  aborts the switch/login and SourbyCraft rolls back; versions are checked before anything is changed.
- Limitation: an offline edit of profile-scoped data (for example `/delhome player:home` while the player is offline)
  changes the database, but the player's profile snapshot from their last quit is applied again at their next login.

## Economy

Atrium's economy is never profile-keyed. Verified against `SSB-AuroraProfiles/VaultBalance`: with
`economy.backend: auto` (no Essentials) or `vault`, the purse selects the registered Vault provider; when that is
Atrium (`VaultBridge`, name `Atrium`), the purse's required `economy:1` adapter reads `getBalance` and writes the delta
with `depositPlayer`/`withdrawPlayer` on every switch, so the account balance in Atrium is swapped per profile by the
purse. Keying Atrium by profile as well would manage the same money twice. Notes:

- The purse identity is `vault@Atrium:<VaultBridge class>`; renaming the plugin or class makes stored labelled
  balances refuse to restore (SourbyCraft's backend-mismatch protection).
- Purse writes run on the player's tick thread; Atrium's Vault bridge waits for the database write (up to
  `economy.vault-timeout-millis`, with a WARN). Keep the database fast or use `economy.backend: native`.
- Startup check (`economy-startup-check`): 5 s after enable the module logs the effective mode, for example
  `Economy with profiles: profile purse -> Vault -> Atrium: ...`; `/atrium profile` shows it too.

## Worlds

- `StoredTeleportService` (teleport module): stored destination not loaded → `WorldResolver` (SourbyCraft: AWF world
  loaded through `AuroraWorlds.load`, shared per world, `worlds-load-timeout-seconds`, `worlds-on-demand-deny`).
  Failures give a message per `WorldResolver.Status`: `NOT_FOUND`, `NOT_ALLOWED`, `BUSY`
  (`WorldOperationBusyException`), `TIMEOUT`, `FAILED`. Without SourbyCraft only loaded worlds resolve.
- Unload: the teleport module cancels warmups into a world on Bukkit `WorldUnloadEvent` and on `WorldUnloadingEvent`
  (fired from SourbyCraft's `WorldUnloadAsyncEvent`). No other module listened to `WorldUnloadEvent` before this
  change; stored locations (homes, warps, /back, spawns) keep their world name and resolve again later.

## Region threading

Atrium is `folia-supported: true` and uses only region/entity/global schedulers, so it needs no Aurora Compatibility
Bridge. SourbyCraft's AWF MultiProfile is experimental and off by default (`aurora.awf.multiprofile.enabled`).
