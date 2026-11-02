# Atrium Aurora Identity — Rollout and Owner Testing

**RFC only.** Target baseline: Atrium `main`; companion SourbyCraft code under `26.2` remains unqualified for distributed MultiProfile. Do not enable new features from this document in production until corresponding implementation merged and owner test complete.

## Deployment sequence

1. Confirm matched SourbyAPI interface version/capabilities, Atrium `sourbycraft` module can load and source server ready state.
2. Snapshot Atrium SQLite/MySQL/MariaDB and config; export current nick, mail, homes/back/kits, economy and moderation records, record hashes.
3. Review account UUID and profile UUID mapping; require qualified AIE registry and name reservations.
4. Deploy identity inspector in read-only/admin-only mode. Inspect permissions and audit for privacy.
5. Enable first-join dialogs on a staging server with synthetic players. Explicitly preserve old players' vanilla state and nick settings.
6. Enable new profile-scoped providers only after required adapter failures, offline edits, economy source of truth and recovery tested.
7. Canary a small group in one server, then multiple nodes only after Leviathan handoff and SourbyCraft distributed owner gate passes.
8. Release feature flag off by default; disable UI/creation before attempting any rollback, preserve all durable data.

## Expected operator notices

- `IDENTITY_API_MISSING` -> stay in existing Atrium behavior; do not wipe profiles.
- `REGISTRY_UNAVAILABLE` -> fail closed on creation/rename/switch.
- `PROFILE_RECOVERY_REQUIRED` -> guided waiting/retry/staff path with no gameplay.
- `ADAPTER_REQUIRED_FAILED` -> switch blocked; show stable error and correlation ID.
- `PRESENTATION_UNQUALIFIED` -> can disable character GameProfile presentation while retaining authoritative identity, with deliberate behavior documented.
- `PLUGIN_UNSUPPORTED` -> warn or deny features in multi-profile worlds.
- `OFFLINE_WRITE_CONFLICT` -> versioned edit was rejected; no false "saved".

All codes are *proposals*, not existing exceptions.

## Owner test table

| ID | Scenario | Expected |
|---|---|---|
| ATR-01 | fresh online-mode Java client, zero profiles | native onboarding, restricted world until commit |
| ATR-02 | duplicate name and double click | one durable profile, friendly retry |
| ATR-03 | create with Redis failure | no false success / no duplicate |
| ATR-04 | Paper/Folia without SourbyCraft | legacy modules unaffected |
| ATR-05 | A->B->A character switch | all required Atrium state remains isolated |
| ATR-06 | Vault/Atrium balance and economic transfer | no twice-applied delta |
| ATR-07 | offline /delhome then login | edit persists or explicit revision conflict |
| ATR-08 | client signed chat + TAB + skins after swap | no security or lookup regression |
| ATR-09 | staff /whois vs unprivileged | correct account ID only to authorized staff |
| ATR-10 | account ban then switch | no bypass |
| ATR-11 | async plugin task finishes after switch | stale mutation rejected |
| ATR-12 | client disconnect during dialog/switch | deterministic recovery |
| ATR-13 | unsupported third-party plugin | explicit policy, not silent leakage |
| ATR-14 | Geyser/Floodgate (if offered) | Bedrock parity or clearly unsupported mode |

Tests belong to owner acceptance for each exact candidate SHA. No result is PASS in this RFC.

## Observability

Counters for onboarding blocked, validation errors, name collisions, profile transition failures, required adapter failures, cache staleness, offline write conflicts, staff lookup, network handoff and per-module scoped data latency. Log correlation ID and stable IDs only; do not log password, token, private account link graph publicly.

## Docs update after implementation

Only once real code exists: amend `docs/CONFIGURATION.md`, generated `COMMANDS.md`/`PERMISSIONS.md`, `docs/SOURBYCRAFT.md` and `docs/ROADMAP.md` with verified defaults, available API and tests. Do not add phantom keys to shipped reference docs now.
