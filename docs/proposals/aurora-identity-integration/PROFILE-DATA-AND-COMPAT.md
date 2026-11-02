# Atrium Data Scoping and Plugin Compatibility Plan

**Status:** migration design. Current behavior remains as documented in [SOURBYCRAFT.md](../../SOURBYCRAFT.md); nothing here changes existing data or Atrium's Vault behavior.

## Required scoping table

| Area | Current baseline (read current docs) | Proposed policy |
|---|---|---|
| Account authentication, moderation | account-wide | remain account UUID; optional character sanctions distinct |
| Nick | account-wide | split cosmetic nick vs canonical immutable-ID-keyed character name |
| Homes, /back, kits usage | profile-scoped | retain, require versioned durable apply and offline edit CAS |
| Economy / Vault | account balance swapped through purse when enabled | exactly **one** purse authority; avoid twice-scoping same amount |
| Mail, messaging, chat settings | account-wide | owner decision: mail account-wide vs character, explicitly documented |
| Playtime, ranks, jobs, quests | account-wide/other modules | explicit profile contract for progression or mark unsupported |
| Skins, particles, effects | account-wide | profile preferences where safe |
| Tablist, chat, scoreboard | display presentation | read active immutable identity context for every update |
| Inventory tools/staff view | account/player-centric | show account + active profile, audit offline profile actions |
| Warps, spawn, shared worlds | global objects | retain world permission; per-profile counters only if added |

Do not claim that changing GameProfile changes databases keyed on `Player#getUniqueId()`. Implement capability levels:
- L0 account-wide legacy; no implicit profile separation.
- L1 profile adapter, bounded capture/apply with required/best-effort designation.
- L2 native profile-aware storage using stable ID.
- L3 distributed-safe event and fenced async callback; required for ARF.
- UNSUPPORTED: block specific mutable game-mode features instead of silently sharing state.

## Adapter semantics and current gap

Current `atrium:profile` envelope is stored under SourbyCraft `ProfileStateAdapter`; current documentation says a 2 MiB ceiling. The server-side adapter contract allows failures to be skipped and partial state may persist. Atrium's `profile-adapter-required` aims to fail closed. Before upgrade, inspect actual code and reconcile these semantics; never assume current behavior is safe merely because a setting is present.

New provider descriptor proposal: namespace ID, schema version, data scope, required criticality, max size, load/save threading, migration handler. Snapshot capture, apply, offline write and rollback must obey a single revision fence and transaction ID. Required failures abort switch or enter recovery. Best-effort failures are logged but cannot erase previously unknown versioned state.

## Economy policy

One authoritative source: if `ProfilePurse` stores Vault balances and swaps them on character switch, Atrium's ledger must not also independently partition and re-credit the same money. For future native profile economy, implement atomic debit/credit with operation IDs, idempotency and transaction logs; reconcile with Vault compatibility provider before activating. Reject NaN/infinity/negative and large amounts according to existing Vault guard policy.

## Offline edits

Known limitation: offline /delhome may be overwritten by old profile snapshot applied at next join. Fix via revision check and authoritative profile-specific data store (or coordinated snapshot journal), not by special casing one command. An offline edit needs profile UUID + expected revision and must invalidate any older cache/snapshot. Failure must report conflict, not success.

## Third-party matrix

For each plugin record: version, L0–L3/UNSUPPORTED tier, whether it caches names, uses account UUID or name storage, owner-thread safety, economy/provider interaction, offline behavior, Node handoff capability, test case and source SHA.

Priority: LuckPerms, Vault/Atrium Vault, TAB, PlaceholderAPI, ProtocolLib, PacketEvents, EssentialsX/CMI migration, SkyBlock/OneBlock plugins, WorldGuard, FAWE, Geyser/Floodgate, Citizens/NPCs. Mark unknown/untested explicitly, never "fully compatible" by default.

## Compatibility on plain Paper/Folia

When `AuroraIdentityService` is absent, all new identity UX remains off and legacy Atrium behavior persists. Class loading of optional Sourby API types must be isolated (reflective capability discovery or optional compile-only adapter module as current pattern), not a hard plugin dependency. Never require redis/Leviathan for non-Sourby servers.

## Security and performance

Entity component access only on entity scheduler; database and Redis calls asynchronous and bounded. Deny stale async completion using captured `profileUuid`, `identityRevision` and `sessionEpoch`. Cache only immutable snapshots and invalidate by version. No permanent account UUID aliases keyed by mutable character names.
