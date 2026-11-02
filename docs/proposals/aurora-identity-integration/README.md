# Atrium × SourbyCraft Aurora Identity — Integration RFC

**Status: PROPOSED / docs only.** No new Atrium module, command, permission, GUI or backend integration is implemented by this RFC. Companion: [SourbyCraft Aurora Identity + Region Fabric v3](https://github.com/YanIanZ/SourbyCraft/tree/26.2/docs/proposals/aurora-identity-territory-v3) (link points to proposed post-merge location; see companion PR while pending) and [Leviathan](https://github.com/YanIanZ/Leviathan).

## Scope

Atrium is the **player/staff experience and plugin integration consumer**; SourbyCraft `AuroraIdentityService` + `AuroraProfiles` (proposed/existing respectively) remain authoritative for account/profile identity, transactional switching and ownership. Do not introduce a second conflicting profile registry in Atrium.

## Existing baseline

- 59 Atrium modules and a shared themed menu framework are documented in [README](../../../README.md). `dialogs` supports Paper Dialog API; `nick`, `skins`, `moderation`, `economy`, `sourbycraft`, `proxy` already exist.
- [SOURBYCRAFT.md](../../SOURBYCRAFT.md) says homes/back/kits are profile-scoped; nickname, mail, moderation, playtime, chat settings, particles and economy are account-wide, and the current economy purse may swap Atrium's Vault balance. Offline edits to some profile data risk being overwritten.
- Real-player profile switching on SourbyCraft and third-party integrations are not yet fully qualified; do not claim production readiness based on boot tests.
- Fall back on plain Paper/Folia: Atrium works without SourbyCraft; do not load missing AIE classes at startup.

## Design documents

1. [CHARACTER-UX.md](CHARACTER-UX.md) — native Java dialogs, first join, profile selector and localization.
2. [PROFILE-DATA-AND-COMPAT.md](PROFILE-DATA-AND-COMPAT.md) — profile data scopes, required adapters and compatibility matrix.
3. [STAFF-IDENTITY-AND-SECURITY.md](STAFF-IDENTITY-AND-SECURITY.md) — trusted UUID moderation, inspector, permissions, audit and privacy.
4. [OPERATIONS-AND-ACCEPTANCE.md](OPERATIONS-AND-ACCEPTANCE.md) — migration, staged rollout and owner testing.

## Non-negotiable rules

- Player UUID from authenticated session never changes. Character names are presentations; ID-based operations use immutable UUIDs.
- UI must not report create/switch success until network authoritative storage confirms it.
- Critical plugin state must not silently leak between characters or be overwritten by stale callbacks.
- Atrium does not implement cross-JVM world ticks or state authority; it uses SourbyAPI capability negotiation.
- Folia entity/world mutations are scheduled on owning threads; all databases and RPC stay async/bounded.
- Existing Atrium users with no AIE enabled retain current behavior. Defaults off until implementation and qualification.

## Dependencies and planned milestone

Identity registry + required MultiProfile transaction safety first; Atrium onboarding second; distributed profile/Leviathan handoff third. No new command name/permission/config key in this RFC should be advertised as shipped.
